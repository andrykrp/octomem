package ru.octomem.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import ru.octomem.config.ServerConfig;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.protocol.CacheCommandDispatcher;
import ru.octomem.protocol.TextProtocolEncoder;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.Gauge;
import ru.octomem.stats.SafeLogger;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.transport.NioTransport;
import ru.octomem.transport.TextConnectionFactory;
import ru.octomem.transport.MemcachedConnectionFactory;
import ru.octomem.transport.TransportSecurity;
import ru.octomem.transport.TransportSettings;
import ru.octomem.util.Clock;
import ru.octomem.util.SystemClock;

/**
 * Owns the first complete server composition without modifying public settings.
 * Lifecycle calls are serialized and must originate outside transport threads.
 * Close waits for every transport owner before stopping the engine and allocator.
 * Construction opens no sockets or native pages; start binds the listener.
 * The fixed heap metadata baseline accounts the index/class queues/geometry, the exact
 * bounded per-page free-slot metadata and the composition epoch allowance; the
 * W+3 reserved epochs plus the already-live current epoch equal the W+4
 * complete-epoch design bound.
 * @since 1.0
 */
public final class CacheServer implements Main.ServerLifecycle {
    private final FfmSlabAllocator allocator;
    private final DefaultCacheEngine engine;
    private final NioTransport transport;
    private final AtomicStatsRegistry stats;
    private final SafeLogger logger;
    private final ServerConfig config;
    private final ru.octomem.transport.UdpTransport udp;
    private final java.nio.file.Path unixPath;
    private boolean started;
    private boolean closed;

    /** Creates the production composition with a shared system clock.
     * @param config validated configuration
     */
    public CacheServer(ServerConfig config) {
        this(config, new InetSocketAddress(InetAddress.ofLiteral(config.listenAddress()), config.listenPort()), new SystemClock());
    }

    /** Creates a composition with an internal bind override for ephemeral tests.
     * @param config validated configuration, unchanged by the override
     * @param bindAddress resolved test bind address, permitting port zero
     */
    public CacheServer(ServerConfig config, InetSocketAddress bindAddress) {
        this(config, bindAddress, new SystemClock());
    }

    /** Creates a deterministic composition without new public configuration keys.
     * @param config validated configuration
     * @param bindAddress resolved internal address, permitting port zero
     * @param clock clock shared by engine, statistics and transport
     */
    public CacheServer(ServerConfig config, InetSocketAddress bindAddress, Clock clock) {
        this(config, bindAddress, clock, new SafeLogger(config.logLevel(),clock::nanoTime));
    }

    /** Creates a server with an injectable, shared safe operational logger. */
    public CacheServer(ServerConfig config, InetSocketAddress bindAddress, Clock clock, SafeLogger logger) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "logger");
        logger.setLevel(config.logLevel());
        TransportSecurity security=TransportSecurity.load(config.values(),bindAddress.getAddress().isLoopbackAddress());
        String unix=config.values().get("listen.unixSocket"),udpPort=config.values().get("listen.udpPort");
        unixPath=unix==null?null:java.nio.file.Path.of(unix);
        TransportSettings settings = new TransportSettings(bindAddress, config.transportWorkers(),
                config.connectionsMax(), config.connectionsPendingWriteBytes(), config.connectionsMaxInflightCommands(),
                config.connectionsIdleTimeoutSeconds(), config.protocolMaxLineBytes(), config.protocolMaxChunkBytes(),
                config.memoryLimit(), config.itemMaxSize());
        stats = new AtomicStatsRegistry(Map.of(
                "pid", Long.toString(ProcessHandle.current().pid()), "version", Main.VERSION + " (octomem)",
                "pointer_size", Long.toString(java.lang.foreign.ValueLayout.ADDRESS.byteSize() * 8),
                "threads", Integer.toString(config.transportWorkers()),
                "limit_maxbytes", Long.toString(config.memoryLimit())), logger);
        // Native pages normally use 1 MiB; tiny budgets still admit a fitting page.
        allocator = new FfmSlabAllocator(config.memoryLimit(), (int) Math.min(1_048_576L, config.memoryLimit()),
                Math.clamp(config.itemMaxSize(), 64, Integer.MAX_VALUE - 7));
        SegmentedItemTable table = null;
        try {
            int segments = 16;
            // Per-page free-slot stacks are real Java heap. Account their exact
            // bounded worst case (store's F-2 finding) in the fixed metadata
            // baseline so the documented heap budget is truthful. The data term
            // is at most memory.limit/16 plus a small per-page header/node term.
            long pageMetadataAllowance = allocator.maximumPageMetadataHeapBytes();
            long baseline = 128 + 8L * segments + segments * (256L + 8 * 2)
                    + segments * (192L + 24L * allocator.classCount())
                    + 512L + 128L * allocator.classCount()
                    + pageMetadataAllowance;
            // Explicit index-only budget, independent of native and snapshot pools.
            long metadataLimit = baseline + Math.min(config.memoryLimit(), 256L * 1024 * 1024);
            // Engine reserves this exact charge once before allocating its snapshot;
            // adding it here preserves the existing variable index budget.
            metadataLimit = Math.addExact(metadataLimit,
                    SegmentedItemTable.classGeometryMetadataBytes(allocator.classCount()));
            // W+3 reserved epochs plus the already-live current epoch equal the
            // W+4 complete-epoch design bound. One epoch measures 5928 bytes
            // (compressed) / 5968 (uncompressed) against a 6144-byte charge.
            long epochAllowance=Math.multiplyExact((long)config.transportWorkers()+3+(udpPort==null?0:1),
                    512L+128L*allocator.classCount());
            metadataLimit=Math.addExact(metadataLimit,epochAllowance);
            table = new SegmentedItemTable(segments, 2, metadataLimit);
            if(!table.reserveEpochAllowance(Math.addExact(epochAllowance,pageMetadataAllowance)))
                throw new IllegalStateException("Startup metadata allowance unavailable");
            engine = new DefaultCacheEngine(allocator, table, clock, stats,
                    new DefaultCacheEngine.Settings(config.memoryLimit(), config.itemMaxSize(),
                            config.evictionPolicy(), config.expiryReclaimIntervalSeconds()));
            CacheCommandDispatcher dispatcher = new CacheCommandDispatcher(engine, stats, allocator, clock,
                    Main.VERSION + " (octomem)");
            MemcachedConnectionFactory factory=new MemcachedConnectionFactory(config.protocolMaxLineBytes(),
                    config.itemMaxSize(),config.protocolMaxChunkBytes(),engine,dispatcher,Main.VERSION+" (octomem)",security);
            transport = new NioTransport(settings,factory,stats,clock,security.tls(),unixPath);
            udp=udpPort==null?null:transport.datagramTransport(new InetSocketAddress(bindAddress.getAddress(),Integer.parseInt(udpPort)));
        } catch (RuntimeException | Error failure) {
            try { if (table != null) table.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            try { allocator.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Starts engine maintenance, then the listener; rolls back on any failure.
     * @throws IOException if binding or selector startup fails
     * @throws IllegalStateException if already closed
     */
    @Override public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("Server is closed");
        if (started) return;
        try {
            engine.start();
            transport.start();
            if(udp!=null)udp.start();
            started = true;
            logger.configuration(config.memoryLimit(), config.itemMaxSize(), config.transportWorkers());
            if (unixPath==null && !transport.localAddress().getAddress().isLoopbackAddress()) logger.log(SafeLogger.Event.BROAD_BIND);
            logger.log(SafeLogger.Event.STARTED);
        } catch (IOException | RuntimeException | Error failure) {
            logger.log(SafeLogger.Event.START_FAILURE);
            try { close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Returns the actual bound address after startup.
     * @return listener address, including the assigned ephemeral port
     */
    public InetSocketAddress localAddress() { return transport.localAddress(); }

    /**
     * Weakly coherent read-only ownership counters, usable after close.
     * @param controlBytes current transport control reservations
     * @param lineBytes current line reservations
     * @param bodyBytes current body reservations
     * @param nativeTotalBytes allocated native page bytes
     * @param nativeUsedBytes occupied native block bytes
     * @param snapshotBytes admitted engine snapshot metadata
     * @param serviceBytes admitted record service metadata
     * @param currentConnections currently accepted connections
     * @param rejectedConnections cumulative admission refusals
     */
    public record ResourceUsage(long controlBytes, long lineBytes, long bodyBytes,
            long nativeTotalBytes, long nativeUsedBytes, long snapshotBytes,
            long serviceBytes, long currentConnections, long rejectedConnections) { }

    /** Samples bounded resource accounting without exposing mutable services.
     * @return weakly coherent resource observations
     */
    public ResourceUsage resourceUsage() {
        return new ResourceUsage(transport.usedControlBytes()+(udp==null?0:udp.usedControlBytes()), transport.usedLineBytes(), transport.usedBodyBytes(),
                allocator.totalMemory(), allocator.usedMemory(), engine.snapshotMetadataUsedBytes(),
                engine.serviceMetadataUsedBytes(), stats.gauge(Gauge.CURR_CONNECTIONS),
                transport.rejectedConnections());
    }

    /**
     * Waits externally for explicit shutdown or autonomous transport failure,
     * then drains all transport owners and releases engine/native resources.
     * An individual client QUIT does not stop the server. No event-loop callback
     * invokes server cleanup and no extra watcher thread is created.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException after cleanup when transport failed autonomously
     */
    @Override public void awaitTermination() throws InterruptedException {
        boolean failed = transport.awaitStopRequested();
        close();
        if (failed) throw new IllegalStateException("Server transport failed");
    }

    /**
     * Idempotently stops transport, waits for pins/cursors to be released, then
     * stops the engine and closes native pages. Interrupts do not skip cleanup;
     * the calling thread's interrupt flag is restored. External threads only.
     */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        boolean interrupted = Thread.interrupted();
        try {
            if(udp!=null)udp.close();
            transport.close();
            for (;;) {
                try { transport.awaitTermination(); break; }
                catch (InterruptedException failure) { interrupted = true; }
            }
            try { engine.stop(); }
            finally { allocator.close(); }
        } finally {
            if (started) logger.log(SafeLogger.Event.STOPPED);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
