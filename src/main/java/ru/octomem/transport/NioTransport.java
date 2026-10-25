package ru.octomem.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import ru.octomem.protocol.ChunkSink;
import ru.octomem.protocol.Command;
import ru.octomem.protocol.CommandType;
import ru.octomem.protocol.ProtocolDecoder;
import ru.octomem.protocol.ProtocolEncoder;
import ru.octomem.protocol.ProtocolException;
import ru.octomem.protocol.Response;
import ru.octomem.protocol.ResponseWriter;
import ru.octomem.protocol.WriteStatus;
import ru.octomem.stats.Counter;
import ru.octomem.stats.Gauge;
import ru.octomem.stats.StatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.stats.SafeLogger;
import ru.octomem.util.Clock;
import ru.octomem.util.SystemClock;

/**
 * Single-acceptor, fixed-loop TCP transport with bounded, connection-owned lanes.
 * Handlers, decoders and writers run exclusively on the connection's event loop.
 * A command is drained before the next is dispatched, a conservative inflight
 * bound of one. Large request bodies may be completely received before any reply;
 * no alternating client read/write assumption is imposed on a command's body.
 * Control admission reserves a fixed 10816 logical bytes per connection before
 * lane allocations and handler creation: 1088 lane, 512 protocol-bridge, 1024
 * normal-encoder writer and 8192 dispatcher. The lane sub-allowance is a
 * payload-and-minimal-wrapper model: the two 512-byte lane buffers and their
 * ByteBuffer wrappers measure 1168 bytes retained compressed / 1200 uncompressed,
 * which remains well inside the aggregate 10816 admission charge, so the
 * per-item model is not contradicted by measurement. The reservation is held
 * even while idle until connection cleanup, so the single active command's
 * writer needs no admission after a response starts.
 * Writer headroom covers its scratch, wrapper and transient defensive key copy;
 * dispatcher headroom covers its response wrapper, mutation key copy and bounded
 * lazy statistics. Engine snapshots retain their separate snapshot admission.
 * No burst pool, whole-response buffer or per-client thread is created.
 *
 * <p>Injected handlers must be nonblocking and obey codec and resource contracts.
 * Shutdown signals owner loops; it never closes a writer on a foreign thread.
 * Joins have a shared five-second deadline, so a misbehaving injected callback
 * cannot make close unbounded. Such a callback can delay its own cleanup until it
 * returns; daemon threads are not a substitute for that required contract.
 */
public final class NioTransport implements AutoCloseable {
    private static final int LANE_BYTES = 512;
    private static final long CONTROL_CHARGE = 1088L + 512 + 1024 + 8192;
    private static final int TURN_STEPS = 64;
    private static final int TURN_BYTES = 16 * 1024;
    private static final Clock JOIN_CLOCK = new SystemClock();
    private final SafeLogger logger;
    private final TransportSettings settings;
    private final ConnectionFactory factory;
    private final StatsRegistry stats;
    private final Clock clock;
    private final TransportOps ops;
    private final SharedBudget lineBudget;
    private final SharedBudget controlBudget;
    private final long lineCharge;
    private final long perConnectionLineLimit;
    private final Object lifecycle = new Object();
    private final Object admission = new Object();
    private final List<EventLoop> loops = new ArrayList<>();
    private final AtomicLong bodyUsed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final long bodyLimit;
    private final long controlCharge;
    private final javax.net.ssl.SSLContext tlsContext;
    private final java.util.concurrent.ThreadPoolExecutor tlsTasks;
    private final java.nio.file.Path unixPath;
    private volatile boolean stopping;
    // Protected by lifecycle; the first stop request determines its outcome.
    private boolean failed;
    private boolean started;
    private volatile InetSocketAddress boundAddress;
    private ServerSocketChannel listener;
    private Selector acceptSelector;
    private Thread acceptor;
    private int connections;
    private int structures;

    /**
     * Constructs an unstarted transport; no sockets, threads or large buffers are allocated.
     * @param settings immutable validated internal settings
     * @param factory per-connection handler factory
     * @param stats shared thread-safe registry, not owned by this transport
     * @param clock monotonic clock for idle timeout checks, not shutdown join deadlines
     */
    public NioTransport(TransportSettings settings, ConnectionFactory factory, StatsRegistry stats, Clock clock) {
        this(settings, factory, stats, clock, new TransportOps());
    }

    /** Creates an optional TLS transport; all TLS buffers are pre-admitted per connection. */
    public NioTransport(TransportSettings settings,ConnectionFactory factory,StatsRegistry stats,Clock clock,javax.net.ssl.SSLContext tlsContext){
        this(settings,factory,stats,clock,new TransportOps(),null,null,tlsContext);
    }
    /** Optional UNIX endpoint uses the same owner loops and refuses existing paths. */
    public NioTransport(TransportSettings settings,ConnectionFactory factory,StatsRegistry stats,Clock clock,javax.net.ssl.SSLContext tlsContext,java.nio.file.Path unixPath){
        this(settings,factory,stats,clock,new TransportOps(),null,null,tlsContext,unixPath);
    }

    NioTransport(TransportSettings settings, ConnectionFactory factory, StatsRegistry stats, Clock clock,
            TransportOps ops) {
        this(settings, factory, stats, clock, ops, null);
    }

    /** Trusted test seam: null derives production limits; supplied budgets exercise real admission denial. */
    NioTransport(TransportSettings settings, ConnectionFactory factory, StatsRegistry stats, Clock clock,
            TransportOps ops, SharedBudget lineBudget) {
        this(settings, factory, stats, clock, ops, lineBudget, null);
    }

    /** Trusted test seam: supplied budgets are validated by SharedBudget. External
     * seed reservations remain caller-owned; cleanup releases only this transport's
     * admitted charges. Null controlBudget derives the production ceiling of
     * 10816 logical bytes per configured connection, including writer/dispatcher headroom.
     */
    NioTransport(TransportSettings settings, ConnectionFactory factory, StatsRegistry stats, Clock clock,
             TransportOps ops, SharedBudget lineBudget, SharedBudget controlBudget) {
        this(settings,factory,stats,clock,ops,lineBudget,controlBudget,null);
    }
    NioTransport(TransportSettings settings,ConnectionFactory factory,StatsRegistry stats,Clock clock,
            TransportOps ops,SharedBudget lineBudget,SharedBudget controlBudget,javax.net.ssl.SSLContext tlsContext){
        this(settings,factory,stats,clock,ops,lineBudget,controlBudget,tlsContext,null);
    }
    private NioTransport(TransportSettings settings,ConnectionFactory factory,StatsRegistry stats,Clock clock,
            TransportOps ops,SharedBudget lineBudget,SharedBudget controlBudget,javax.net.ssl.SSLContext tlsContext,java.nio.file.Path unixPath){
        this.settings = Objects.requireNonNull(settings, "settings");
        this.factory = Objects.requireNonNull(factory, "factory");
        if(factory.additionalControlBytes()<0)throw new IllegalArgumentException("Negative control allowance");
        this.tlsContext=tlsContext;
        this.unixPath=unixPath;
        tlsTasks=tlsContext==null?null:new java.util.concurrent.ThreadPoolExecutor(0,Math.min(4,settings.workers()),30,java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(Math.min(settings.maxConnections(),1024)),r->{Thread t=new Thread(r,"octomem-tls-task");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        controlCharge=Math.addExact(Math.addExact(CONTROL_CHARGE,factory.additionalControlBytes()),tlsContext==null?0:TlsChannel.CHARGE);
        this.stats = Objects.requireNonNull(stats, "stats");
        logger = stats instanceof ClassStatsRegistry capability ? capability.logger() : new SafeLogger("info");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ops = Objects.requireNonNull(ops, "ops");
        this.controlBudget = controlBudget == null
                ? new SharedBudget(Math.multiplyExact(controlCharge, (long) settings.maxConnections()), 0)
                : controlBudget;
        lineCharge = 2L * settings.maxLineBytes() + 256;
        perConnectionLineLimit = SharedBudget.saturatedMultiply(settings.maxInflightCommands(), lineCharge);
        this.lineBudget = lineBudget == null
                ? new SharedBudget(SharedBudget.saturatedMultiply(settings.maxConnections(), perConnectionLineLimit), 0)
                : lineBudget;
        bodyLimit = Math.max((long) settings.itemMaxSize() + 32,
                Math.min(settings.memoryLimit(), 64L * 1024 * 1024));
    }

    /**
     * Binds synchronously and starts the fixed owner threads. Restart is unsupported.
     * @throws IOException if binding or selector initialization fails
     * @throws IllegalStateException if already started or closed
     */
    public void start() throws IOException {
        try {
            synchronized (lifecycle) {
            if (started || stopping) { throw new IllegalStateException("Transport already started or closed"); }
            try {
                started = true;
                if(unixPath!=null && (!unixPath.isAbsolute() || java.nio.file.Files.exists(unixPath,java.nio.file.LinkOption.NOFOLLOW_LINKS)))throw new IOException("UNIX endpoint unavailable");
                listener = unixPath==null?ServerSocketChannel.open():ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
                listener.configureBlocking(false);
                listener.bind(unixPath==null?settings.address():java.net.UnixDomainSocketAddress.of(unixPath));
                acceptSelector = ops.openSelector();
                listener.register(acceptSelector, SelectionKey.OP_ACCEPT);
                for (int i = 0; i < settings.workers(); i++) { loops.add(new EventLoop(i)); }
                acceptor = new Thread(this::acceptConnections, "octomem-acceptor");
                acceptor.setDaemon(true);
                if(unixPath==null)boundAddress = (InetSocketAddress) listener.getLocalAddress();
                for (EventLoop loop : loops) {
                    if (stopping) { throw new IOException("Transport closed during startup"); }
                    ops.startThread(loop.thread);
                }
                if (stopping) { throw new IOException("Transport closed during startup"); }
                ops.startThread(acceptor);
            } catch (IOException | RuntimeException | Error failure) {
                stopping = true;
                closeQuietly(listener);
                closeQuietly(acceptSelector);
                for (EventLoop loop : loops) {
                    loop.stop();
                    if (loop.thread.getState() == Thread.State.NEW) { closeQuietly(loop.selector); }
                }
                throw failure;
            }
            }
        } catch (IOException | RuntimeException | Error failure) {
            // Join started owners outside lifecycle: their failure paths may call close.
            if (stopping) { close(); }
            throw failure;
        }
    }

    /** Returns the bound local endpoint, including the selected ephemeral test port.
     * @return address retained after successful startup, including after close
     * @throws IllegalStateException if binding has not succeeded
     */
    public InetSocketAddress localAddress() {
        InetSocketAddress address = boundAddress;
        if (address == null) { throw new IllegalStateException("Transport is not bound"); }
        return address;
    }

    /** Returns capacity/shutdown policy admission refusals, excluding setup failures.
     * Not yet exposed through protocol stats.
     * @return saturating nonnegative lifetime rejection count
     */
    public long rejectedConnections() { return rejected.get(); }

    /** Returns the globally reserved body payload and overhead, not actual heap or RSS.
     * @return nonnegative current body reservation bytes
     */
    public long usedBodyBytes() { return bodyUsed.get(); }

    /** Returns globally reserved line/descriptor bytes, not actual heap or RSS.
     * @return nonnegative live logical reservation bytes
     */
    public long usedLineBytes() { return lineBudget.usedBytes(); }

    /** Returns aggregate lane, bridge, writer and dispatcher logical reservations.
     * Each admitted connection holds 10816 bytes, including idle headroom;
     * externally seeded test-budget reservations remain included and caller-owned.
     * Independent of native, line, body and engine-snapshot admission;
     * not a measurement of heap or RSS.
     * @return nonnegative current control reservation
     */
    public long usedControlBytes() { return controlBudget.usedBytes(); }

    private void acceptConnections() {
        int nextLoop = 0;
        try {
            while (!stopping) {
                ops.select(acceptSelector, 250);
                acceptSelector.selectedKeys().clear();
                for (int n = 0; n < TURN_STEPS && !stopping; n++) {
                    SocketChannel socket = listener.accept();
                    if (socket == null) { break; }
                    if (!reserveConnection()) {
                        reject(socket);
                        continue;
                    }
                    boolean transferred = false;
                    try {
                        socket.configureBlocking(false);
                        if(unixPath==null)socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        ops.beforeHandoff(socket);
                        Pending pending = new Pending(socket, ByteBuffer.allocate(LANE_BYTES), ByteBuffer.allocate(LANE_BYTES));
                        EventLoop loop = loops.get(nextLoop);
                        nextLoop = (nextLoop + 1) % loops.size();
                        transferred = loop.submit(pending);
                    } catch (IOException | RuntimeException failure) {
                        // Failure before handoff owns only this unregistered socket.
                    } finally {
                        if (!transferred) { closeQuietly(socket); releaseConnection(); }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            fail();
        } finally {
            closeQuietly(listener);
            closeQuietly(acceptSelector);
        }
    }

    private boolean reserveConnection() {
        synchronized (admission) {
            if (stopping || connections == settings.maxConnections()) { return false; }
            if (!controlBudget.reserve(controlCharge)) { return false; }
            connections++;
            publishConnections();
            return true;
        }
    }

    private void releaseConnection() {
        synchronized (admission) {
            connections--;
            controlBudget.release(controlCharge);
            publishConnections();
        }
    }

    private void publishConnections() {
        // Serialize gauge publication as well as the count to prevent stale loop updates.
        stats.setGauge(Gauge.CURR_CONNECTIONS, connections);
        stats.setGauge(Gauge.CONNECTION_STRUCTURES, structures);
    }

    private void reject(SocketChannel socket) {
        logger.log(SafeLogger.Event.CAPACITY);
        rejected.updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1);
        closeQuietly(socket);
    }

    private void fail() {
        synchronized (lifecycle) {
            if (stopping) return;
            failed = true;
            stopping = true;
            lifecycle.notifyAll();
            if(tlsTasks!=null)tlsTasks.shutdown();
        }
        // Owned threads signal cancellation only; an external owner drains them.
        close();
        logger.log(SafeLogger.Event.TRANSPORT_FAILURE);
    }

    /**
     * Waits for an explicit close or autonomous transport failure, without joining.
     * The external server owner must then close and await actual termination before
     * freeing engine resources. The first stop request wins: failures induced by
     * explicit shutdown do not change a normal stop into a failure.
     * @return true when an autonomous owner failure requested shutdown
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if invoked by an owned transport thread
     */
    public boolean awaitStopRequested() throws InterruptedException {
        synchronized (lifecycle) {
            if (Thread.currentThread() == acceptor) throw new IllegalStateException("Cannot wait on own transport");
            for (EventLoop loop : loops) {
                if (Thread.currentThread() == loop.thread) throw new IllegalStateException("Cannot wait on own transport");
            }
            while (!stopping) lifecycle.wait();
            return failed;
        }
    }

    /**
     * Idempotently stops admission and signals cancellation, joining for at most five seconds.
     * Calls from owned threads only signal shutdown, avoiding self/cross-loop join deadlocks.
     * Interruption is preserved. Shared handler factories, stats and clocks are not closed.
     */
    @Override public void close() {
        List<Thread> threads = new ArrayList<>();
        boolean ownThread = false;
        synchronized (lifecycle) {
            stopping = true;
            if(tlsTasks!=null)tlsTasks.shutdown();
            lifecycle.notifyAll();
            closeQuietly(listener);
            if (acceptSelector != null) { acceptSelector.wakeup(); }
            if (acceptor != null) {
                threads.add(acceptor);
                ownThread = Thread.currentThread() == acceptor;
                if (acceptor.getState() == Thread.State.NEW) { closeQuietly(acceptSelector); }
            } else { closeQuietly(acceptSelector); }
            for (EventLoop loop : loops) {
                loop.stop();
                threads.add(loop.thread);
                ownThread |= Thread.currentThread() == loop.thread;
                if (loop.thread.getState() == Thread.State.NEW) { closeQuietly(loop.selector); }
            }
        }
        if (ownThread) { return; }
        long deadline = JOIN_CLOCK.nanoTime() + 5_000_000_000L;
        for (Thread thread : threads) {
            long remaining = deadline - JOIN_CLOCK.nanoTime();
            if (remaining <= 0) { break; }
            try { ops.join(thread, Math.max(1, remaining / 1_000_000)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
    }

    /**
     * Waits for all owned threads to terminate after close has been requested.
     * Unlike close's bounded join, this is the external composition lifetime
     * barrier before the engine or allocator may be stopped. Never starts NEW
     * threads left by failed startup.
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws IllegalStateException if called by an owned thread or before close
     */
    public void awaitTermination() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        synchronized (lifecycle) {
            if (!stopping) throw new IllegalStateException("Close must be requested first");
            if (acceptor != null) threads.add(acceptor);
            for (EventLoop loop : loops) threads.add(loop.thread);
            for (Thread thread : threads) {
                if (thread == Thread.currentThread()) throw new IllegalStateException("Cannot join own transport thread");
            }
        }
        for (Thread thread : threads) thread.join();
        if(tlsTasks!=null)while(!tlsTasks.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS)){/* owned tasks retain admission until completion */}
    }
    /** Propagates an owned auxiliary transport failure to the shared lifecycle. */
    public void failAuxiliary(){fail();}
    /** Builds one UDP worker sharing this transport's ingress and line accounting. */
    public UdpTransport datagramTransport(InetSocketAddress address){
        return new UdpTransport(address,factory,settings.itemMaxSize(),this::failAuxiliary,
                ()->new ConnectionResources(bodyUsed,bodyLimit,settings.itemMaxSize(),lineBudget,lineCharge,perConnectionLineLimit),new UdpTransport.Ops());
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) { return; }
        try { resource.close(); } catch (Exception ignored) { /* Continue cleanup of independent resources. */ }
    }

    private record Pending(SocketChannel socket, ByteBuffer receive, ByteBuffer transmit) { }

    private final class EventLoop implements Runnable {
        final Selector selector = ops.openSelector();
        final Thread thread;
        final ArrayDeque<Pending> pending = new ArrayDeque<>();
        final ArrayDeque<Connection> ready = new ArrayDeque<>();
        final java.util.concurrent.ConcurrentLinkedQueue<Connection> tlsReady=new java.util.concurrent.ConcurrentLinkedQueue<>();
        boolean accepting = true;

        EventLoop(int index) throws IOException {
            thread = new Thread(this, "octomem-event-" + index);
            thread.setDaemon(true);
        }

        synchronized boolean submit(Pending connection) {
            if (!accepting || stopping) { return false; }
            pending.addLast(connection);
            selector.wakeup();
            return true;
        }

        synchronized void stop() { accepting = false; selector.wakeup(); }
        synchronized void tlsCompleted(Connection connection){if(accepting && !connection.closed){tlsReady.add(connection);selector.wakeup();}}
        synchronized Pending poll() { return pending.pollFirst(); }

        void schedule(Connection connection) {
            if (!connection.queued && !connection.closed) {
                connection.queued = true;
                ready.addLast(connection);
            }
        }

        @Override public void run() {
            try {
                long lastIdleScan = clock.nanoTime();
                while (!stopping) {
                    Connection awakened;while((awakened=tlsReady.poll())!=null)schedule(awakened);
                    for (int n = 0; n < TURN_STEPS; n++) {
                        Pending accepted = poll();
                        if (accepted == null) { break; }
                        Connection connection;
                        try { connection = new Connection(this, accepted); }
                        catch (RuntimeException | Error failure) {
                            // Dequeue retains raw ownership until construction succeeds.
                            closeQuietly(accepted.socket());
                            releaseConnection();
                            throw failure;
                        }
                        try { connection.register(); }
                        catch (Exception failure) { logger.log(SafeLogger.Event.TRANSPORT_FAILURE); connection.close(); }
                    }
                    ops.select(selector, ready.isEmpty() ? 100 : 0);
                    Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
                    while (selected.hasNext()) {
                        SelectionKey key = selected.next();
                        selected.remove();
                        if (key.isValid()) { schedule((Connection) key.attachment()); }
                    }
                    int turns = ready.size();
                    for (int n = 0; n < turns && !stopping; n++) {
                        Connection connection = ready.removeFirst();
                        connection.queued = false;
                        if (!connection.closed) {
                            try { connection.pump(); }
                            catch (Exception failure) { logger.log(SafeLogger.Event.TRANSPORT_FAILURE); connection.close(); }
                        }
                    }
                    long now = clock.nanoTime();
                    if (settings.idleTimeoutSeconds() != 0 && now - lastIdleScan >= 100_000_000L) {
                        lastIdleScan = now;
                        for (SelectionKey key : selector.keys()) {
                            Connection connection = (Connection) key.attachment();
                            if (now - connection.lastActivity >= settings.idleTimeoutSeconds() * 1_000_000_000L) {
                                connection.close();
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException failure) {
                fail();
            } finally {
                stop();
                Pending accepted;
                while ((accepted = poll()) != null) { closeQuietly(accepted.socket()); releaseConnection(); }
                for (SelectionKey key : selector.keys()) { ((Connection) key.attachment()).close(); }
                ready.clear();
                tlsReady.clear();
                closeQuietly(selector);
            }
        }
    }

    private final class Connection implements ChunkSink {
        final EventLoop loop;
        final SocketChannel socket;
        final ByteBuffer receive;
        final ByteBuffer transmit;
        final ConnectionResources resources = new ConnectionResources(bodyUsed, bodyLimit, settings.itemMaxSize(),
                lineBudget, lineCharge, perConnectionLineLimit);
        final int outputCapacity = Math.min(LANE_BYTES, Math.min(settings.pendingWriteBytes(), settings.maxChunkBytes()));
        ConnectionHandler handler;
        ProtocolDecoder decoder;
        ProtocolEncoder encoder;
        ResponseWriter writer;
        TlsChannel tls;
        SelectionKey key;
        boolean queued;
        boolean closed;
        boolean activeCommand;
        boolean closeAfterWrite;
        long lastActivity = clock.nanoTime();

        Connection(EventLoop loop, Pending accepted) {
            this.loop = loop;
            socket = accepted.socket();
            receive = accepted.receive();
            transmit = accepted.transmit();
            receive.limit(0);
            transmit.limit(outputCapacity);
            synchronized (admission) { structures++; publishConnections(); }
        }

        void register() throws Exception {
            if(tlsContext!=null)tls=ops.tls(tlsContext,tlsTasks,()->loop.tlsCompleted(this));
            handler = Objects.requireNonNull(factory.create(resources), "handler");
            decoder = Objects.requireNonNull(handler.decoder(), "decoder");
            encoder = Objects.requireNonNull(handler.encoder(), "encoder");
            key = socket.register(loop.selector, SelectionKey.OP_READ, this);
            stats.increment(Counter.TOTAL_CONNECTIONS, 1);
        }

        void pump() throws Exception {
            int bytes = 0;
            // Owner-confined: ready drain excludes closed entries; every close below returns immediately.
            for (int step = 0; step < TURN_STEPS && bytes < TURN_BYTES && !stopping; step++) {
                if(tls!=null){
                    if(!tls.flush(socket)){interests(SelectionKey.OP_WRITE);return;}
                    if(!tls.ready() && !tls.handshake(socket)){interests(tls.interests());if(tls.runnable())loop.schedule(this);return;}
                }
                if (transmit.position() != 0) {
                    transmit.flip();
                    int written = tls==null?ops.write(socket, transmit):tls.write(socket,transmit);
                    transmit.compact();
                    transmit.limit(outputCapacity);
                    if (written != 0) { stats.increment(Counter.BYTES_WRITTEN, written); lastActivity = clock.nanoTime(); }
                    bytes += written;
                    if (transmit.position() != 0) { interests(tls==null || tls.ready()?SelectionKey.OP_WRITE:tls.interests());if(tls!=null && tls.runnable())loop.schedule(this);return; }
                    if(tls!=null && !tls.flush(socket)){interests(SelectionKey.OP_WRITE);return;}
                }
                if (writer != null) {
                    WriteStatus status = Objects.requireNonNull(writer.writeMore(this), "write status");
                    if (status == WriteStatus.COMPLETE) {
                        ResponseWriter completed = writer;
                        writer = null;
                        completed.close();
                    } else if (transmit.position() == 0) {
                        throw new IllegalStateException("Writer made no progress with empty control lane");
                    }
                    continue;
                }
                if (activeCommand) {
                    activeCommand = false;
                    releaseCommand();
                    if (closeAfterWrite) {if(tls!=null && !tls.finish(socket)){interests(tls.interests());return;}close(); return;}
                }
                if(closeAfterWrite){if(tls!=null && !tls.finish(socket)){interests(tls.interests());return;}close();return;}
                if (resources.lineSlots() == 0 && !resources.acquireLine()) {
                    // Refuse before additional socket reads/body admission, without waiting/spin.
                    close();
                    return;
                }
                Command command;
                try { command = decoder.nextCommand(); }
                catch (ProtocolException failure) {
                    activeCommand = true;
                    Response response = handler.protocolError(failure);
                    closeAfterWrite = failure.closeConnection() || handler.closeAfterResponse();
                    if (response == null) { close(); return; }
                    encode(response);
                    continue;
                }
                if (command != null) {
                    activeCommand = true;
                    if (command.type() == CommandType.QUIT) {
                        if(tls==null){close();return;}
                        closeAfterWrite=true;continue;
                    }
                    Response response = handler.handle(command);
                    closeAfterWrite=handler.closeAfterResponse();
                    if (response != null) { encode(response); }
                    continue;
                }
                if (receive.hasRemaining()) {
                    int before = receive.position();
                    decoder.append(receive);
                    if (receive.position() != before) { continue; }
                }
                receive.compact();
                if (!receive.hasRemaining()) { throw new IllegalStateException("Decoder stalled with full receive lane"); }
                int read = tls==null?ops.read(socket, receive):tls.read(socket,receive);
                receive.flip();
                if (read < 0) { close(); return; }
                if (read == 0) { interests(tls==null?SelectionKey.OP_READ:tls.interests());if(tls!=null && tls.runnable())loop.schedule(this);return; }
                stats.increment(Counter.BYTES_READ, read);
                bytes += read;
                lastActivity = clock.nanoTime();
            }
            // Reaching the yield implies no close path was taken; shutdown can still arrive concurrently.
            if (!stopping) {
                stats.increment(Counter.CONN_YIELDS, 1);
                interests(0);
                loop.schedule(this);
            }
        }

        void encode(Response response) {
            try { writer = Objects.requireNonNull(encoder.encode(response), "writer"); }
            catch (RuntimeException | Error failure) { response.close(); throw failure; }
        }

        void releaseCommand() {
            // activeCommand follows successful admission; handlers cannot mutate the transport-owned slot.
            resources.releaseLine();
        }

        void interests(int operations) {
            // Registered owner-only key; close paths return before here. Cancellation exceptions reach pump cleanup.
            key.interestOps(operations);
        }

        @Override public int remainingCapacity() { return transmit.remaining(); }

        @Override public void accept(ByteBuffer chunk) {
            if (chunk.remaining() > remainingCapacity()) { throw new IllegalArgumentException("Chunk exceeds lane capacity"); }
            transmit.put(chunk);
        }

        void close() {
            if (closed) { return; }
            closed = true;
            if (key != null) { key.cancel(); }
            closeQuietly(socket);
            if (writer != null) {
                try { writer.close(); } catch (RuntimeException ignored) { /* Still release handler and admission. */ }
                writer = null;
            }
            closeQuietly(handler);
            resources.close();
            synchronized (admission) { structures--; publishConnections(); }
            if(tls!=null)tls.close(NioTransport.this::releaseConnection);else releaseConnection();
        }
    }
}
