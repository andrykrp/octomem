package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.KeySequence;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.protocol.*;
import ru.octomem.stats.*;
import ru.octomem.util.Clock;

/** Independent real TCP acceptance; the small binary test protocol is not production codec coverage. */
@Timeout(40)
class NioTransportSocketTest {
    static final int LARGE = 64 * 1024 * 1024;
    static final int BODY = 32 * 1024;

    @Test void fragmentedBodyCompletesBeforeResponseAndWriterResumesInOrder() throws Exception {
        try (Fixture f = new Fixture(2, 8, 17, 0, BODY * 2L); Socket s = f.connect()) {
            Peer peer = f.peer(0);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeByte('B');
            out.writeInt(BODY);
            out.flush();
            f.await(() -> peer.resources.usedBodyBytes() == BODY + 32L);
            assertEquals(1, peer.resources.lineSlots());
            assertEquals(2304, peer.resources.usedLineBytes());
            assertEquals(0, peer.handled.get());
            byte[] fragment = new byte[137];
            int sent = 0;
            while (sent < BODY) {
                int n = Math.min(fragment.length, BODY - sent);
                for (int i = 0; i < n; i++) fragment[i] = pattern(sent + i);
                out.write(fragment, 0, n);
                sent += n;
            }
            out.flush();
            verify(s, 4096);
            request(s, 'P', 0);
            verify(s, 17);
            f.await(() -> peer.responsesClosed.get() == 2);
            assertEquals(2, peer.encoded.get());
            assertTrue(peer.writerCalls.get() > 2);
            assertEquals(0, peer.resources.usedBodyBytes());
            assertEquals(BODY + 10L, f.stats.snapshot().counters().get(Counter.BYTES_READ));
            f.await(() -> f.stats.snapshot().counters().get(Counter.BYTES_WRITTEN) == 4113L);
            f.assertHealthy();
        }
    }

    @Test void stalledDeepPipelineIsBoundedAndHealthyPeerProgressesOnSameLoop() throws Exception {
        try (Fixture f = new Fixture(1, 8, 31, 0, 1); Socket stalled = f.connect(); Socket healthy = f.connect()) {
            Peer slow = f.peer(0);
            request(stalled, 'L', 0);
            f.await(() -> slow.writerCalls.get() > 0);
            int decodeCallsAtWriterStart = slow.decodeCalls.get();
            byte[] pipeline = new byte[5 * 1000];
            for (int i = 0; i < 1000; i++) pipeline[i * 5] = 'P';
            stalled.getOutputStream().write(pipeline);
            for (int i = 0; i < 30; i++) {
                request(healthy, 'P', 0);
                verify(healthy, 17);
            }
            assertEquals(1, slow.handled.get(), "pending first writer must stop pipeline dispatch");
            assertEquals(1, slow.encoded.get());
            assertEquals(decodeCallsAtWriterStart, slow.decodeCalls.get(), "pending writer must stop decoder polling");
            assertEquals(1, slow.resources.lineSlots());
            assertTrue(slow.produced.get() < LARGE, "reader must actually be stalled");
            assertTrue(slow.writerCalls.get() < 1_000_000, "bounded callbacks, not capacity-zero spin");
            assertTrue(slow.maxCapacity.get() <= 31);
            assertTrue(slow.maxAppend.get() <= 512);
            f.assertHealthy();
        }
    }

    @Test void actualSocketBackpressureResumesOneWriterWithoutCopyingWholeResponse() throws Exception {
        try (Fixture f = new Fixture(1, 4, 512, 0, 1);
             Socket slow = f.connect(65536); Socket barrier = f.connect()) {
            Peer peer = f.peer(0);
            request(slow, 'S', 0);
            for (int i = 0; i < 20; i++) { request(barrier, 'P', 0); verify(barrier, 17); }
            assertTrue(peer.produced.get() < 8 * 1024 * 1024, "must reach real paused-reader backpressure");
            assertEquals(1, peer.encoded.get());
            verify(slow, 8 * 1024 * 1024);
            request(slow, 'P', 0); verify(slow, 17);
            f.await(() -> peer.responsesClosed.get() == 2);
            assertEquals(2, peer.encoded.get(), "continuation never re-encodes a response");
            f.assertHealthy();
        }
    }

    @Test void disconnectAndShutdownReleaseIncompleteBodyAndPendingWriterExactlyOnce() throws Exception {
        Fixture f = new Fixture(1, 8, 31, 0, BODY * 2L);
        try (Socket partial = f.connect(); Socket stalled = f.connect()) {
            Peer p = f.peer(0);
            Peer w = f.peer(1);
            request(partial, 'B', BODY);
            request(stalled, 'L', 0);
            f.await(() -> p.resources.usedBodyBytes() == BODY + 32L && w.writerCalls.get() > 0);
            partial.close();
            f.await(() -> p.closed.get() == 1);
            f.close();
            f.close();
            assertEquals(1, w.writerClosed.get());
            assertEquals(1, w.responsesClosed.get());
            assertEquals(1, w.closed.get());
            for (Peer peer : f.peers) {
                assertEquals(0, peer.resources.usedBodyBytes());
                assertEquals(0, peer.resources.lineSlots());
                assertEquals(0, peer.resources.usedLineBytes());
            }
            assertEquals(0L, f.stats.snapshot().gauges().get(Gauge.CURR_CONNECTIONS));
            f.assertHealthy();
        } finally { f.close(); }
    }

    @Test void halfCloseDrainsCompleteResponseButAbandonsIncompleteBody() throws Exception {
        try (Fixture f = new Fixture(1, 4, 17, 0, BODY * 2L)) {
            try (Socket complete = f.connect()) {
                request(complete, 'P', 0);
                complete.shutdownOutput();
                verify(complete, 17);
                assertEquals(-1, complete.getInputStream().read());
            }
            try (Socket partial = f.connect()) {
                Peer peer = f.peer(1);
                request(partial, 'B', BODY);
                partial.getOutputStream().write(new byte[19]);
                partial.shutdownOutput();
                assertEquals(-1, partial.getInputStream().read());
                f.await(() -> peer.closed.get() == 1);
                assertEquals(0, peer.handled.get());
                assertEquals(0, peer.resources.usedBodyBytes());
            }
            f.assertHealthy();
        }
    }

    @Test void decoderRefusingFullLaneIsDisconnectedWithoutAffectingPeer() throws Exception {
        try (Fixture f = new Fixture(1, 4, 17, 0, 1); Socket bad = f.connect(); Socket good = f.connect()) {
            request(bad, 'J', 0);
            bad.getOutputStream().write(new byte[1024]);
            assertEquals(-1, bad.getInputStream().read());
            request(good, 'P', 0); verify(good, 17);
            f.await(() -> f.peers.get(0).closed.get() == 1);
            f.assertHealthy();
        }
    }

    @Test void decoderHandlerEncoderAndWriterFailuresAreConnectionLocal() throws Exception {
        for (char mode : new char[] {'D', 'H', 'E', 'W', 'Z', 'O', 'T', 'U', 'C', 'V'}) {
            try (Fixture f = new Fixture(1, 4, 31, 0, 1); Socket bad = f.connect(); Socket good = f.connect()) {
                Peer failed = f.peer(0);
                request(bad, mode, 0);
                assertEquals(-1, bad.getInputStream().read(), "failure " + mode);
                request(good, 'P', 0);
                verify(good, 17);
                f.await(() -> failed.closed.get() == 1);
                if (mode != 'D' && mode != 'H' && mode != 'V') assertEquals(1, failed.responsesClosed.get());
                if (mode != 'D' && mode != 'H' && mode != 'E' && mode != 'U' && mode != 'V') assertEquals(1, failed.writerClosed.get());
                assertEquals(0, failed.resources.lineSlots());
                f.assertHealthy();
            }
        }
    }

    @Test void admissionRejectsExcessSocketAndReopensAfterDisconnect() throws Exception {
        try (Fixture f = new Fixture(1, 1, 31, 0, 1); Socket first = f.connect()) {
            f.peer(0);
            try (Socket rejected = f.connect()) {
                assertEquals(-1, rejected.getInputStream().read());
            }
            assertEquals(1, f.peers.size());
            assertEquals(1L, f.stats.snapshot().gauges().get(Gauge.CURR_CONNECTIONS));
            assertEquals(1L, f.stats.snapshot().counters().get(Counter.TOTAL_CONNECTIONS));
            first.close();
            f.await(() -> f.stats.snapshot().gauges().get(Gauge.CURR_CONNECTIONS) == 0L);
            try (Socket replacement = f.connect()) {
                request(replacement, 'P', 0);
                verify(replacement, 17);
                assertEquals(2L, f.stats.snapshot().counters().get(Counter.TOTAL_CONNECTIONS));
            }
        }
    }

    @Test void controlledIdleClockExpiresAndZeroDisablesTimeout() throws Exception {
        for (long timeout : new long[] {0, 10}) {
            try (Fixture f = new Fixture(1, 4, 31, timeout, 1); Socket idle = f.connect(); Socket barrier = f.connect()) {
                Peer peer = f.peer(0);
                request(idle, 'P', 0);
                verify(idle, 17);
                request(barrier, 'P', 0); verify(barrier, 17);
                f.clock.nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
                request(idle, 'P', 0); verify(idle, 17);
                // Same-loop peer proves idle socket's post-write lastActivity update completed.
                request(barrier, 'P', 0); verify(barrier, 17);
                f.clock.nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
                if (timeout == 0) {
                    request(idle, 'P', 0);
                    verify(idle, 17);
                    assertEquals(0, peer.closed.get());
                } else {
                    assertEquals(-1, idle.getInputStream().read());
                    f.await(() -> peer.closed.get() == 1);
                }
            }
        }
    }

    @Test void occupiedBindPortFailsCleanly() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            TransportSettings settings = settings(1, 4, 31, 0, 1,
                    new InetSocketAddress(occupied.getInetAddress(), occupied.getLocalPort()));
            AtomicInteger factories = new AtomicInteger();
            NioTransport transport = new NioTransport(settings, r -> {
                factories.incrementAndGet(); throw new IllegalStateException("unreachable");
            }, registry(), new ManualClock());
            try {
                assertThrows(IllegalStateException.class, transport::localAddress);
                assertThrows(IOException.class, transport::start);
                assertThrows(IllegalStateException.class, transport::start);
            } finally { transport.close(); transport.close(); }
            assertEquals(0, factories.get());
        }
    }

    @Test void lifecycleRejectsRestartAndReleasesBoundPort() throws Exception {
        TransportSettings settings = settings(1, 1, 2, 0, 1, new InetSocketAddress("127.0.0.1", 0));
        NioTransport unstarted = new NioTransport(settings, r -> null, registry(), new ManualClock());
        unstarted.close(); unstarted.close();
        assertThrows(IllegalStateException.class, unstarted::start);
        InetSocketAddress bound;
        try (Fixture f = new Fixture(1, 4, 31, 0, 1)) {
            bound = f.transport.localAddress();
            assertThrows(IllegalStateException.class, f.transport::start);
        }
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.bind(bound);
            assertEquals(bound.getPort(), rebound.getLocalPort());
        }
    }

    @Test void closeFromOwnedCallbackSignalsShutdownWithoutDeadlock() throws Exception {
        try (Fixture f = new Fixture(2, 4, 31, 0, 1); Socket socket = f.connect()) {
            Peer peer = f.peer(0);
            request(socket, 'X', 0);
            assertEquals(-1, socket.getInputStream().read());
            f.close();
            assertEquals(1, peer.closed.get());
            assertFalse(peer.owner.isAlive());
            assertEquals(0L, f.transport.usedBodyBytes());
            f.assertHealthy();
        }
    }

    @Test void protocolErrorFlushesBeforeCloseAndRecoverableErrorKeepsPipeline() throws Exception {
        for (char mode : new char[] {'F', 'R'}) {
            try (Fixture f = new Fixture(1, 4, 2, 0, 1); Socket socket = f.connect()) {
                request(socket, mode, 0);
                verify(socket, 17);
                if (mode == 'F') assertEquals(-1, socket.getInputStream().read());
                else { request(socket, 'P', 0); verify(socket, 17); }
                f.assertHealthy();
            }
        }
    }

    @Test void suppressedResponseReleasesLineAndQuitClosesWithoutEncoding() throws Exception {
        try (Fixture f = new Fixture(1, 4, 2, 0, 1); Socket socket = f.connect()) {
            Peer peer = f.peer(0);
            request(socket, 'N', 0);
            request(socket, 'P', 0);
            verify(socket, 17);
            assertEquals(1, peer.encoded.get());
            request(socket, 'Q', 0);
            assertEquals(-1, socket.getInputStream().read());
            f.await(() -> peer.closed.get() == 1);
            assertEquals(0, peer.resources.lineSlots());
            f.assertHealthy();
        }
    }

    @Test void factoryFailureDoesNotKillWorker() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (Fixture f = new Fixture(1, 4, 31, 0, 1, attempts); Socket rejected = f.connect()) {
            assertEquals(-1, rejected.getInputStream().read());
            try (Socket good = f.connect()) {
                request(good, 'P', 0); verify(good, 17);
                assertEquals(2, attempts.get());
                assertEquals(1, f.peers.size());
            }
            f.assertHealthy();
        }
    }

    static TransportSettings settings(int workers, int max, int pending, long idle, long memory,
                                      InetSocketAddress address) {
        return new TransportSettings(address, workers, max, pending, 2, idle, 1024, 17,
                memory, (int) Math.min(memory, BODY));
    }

    static AtomicStatsRegistry registry() {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64",
                "threads", "1", "limit_maxbytes", "1"));
    }

    static byte pattern(long i) { return (byte) ('a' + i % 23); }

    static void request(Socket s, char op, int size) throws IOException {
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        out.writeByte(op); out.writeInt(size); out.flush();
    }

    static void verify(Socket s, int size) throws IOException {
        byte[] bytes = new byte[113];
        int offset = 0;
        while (offset < size) {
            int n = s.getInputStream().read(bytes, 0, Math.min(bytes.length, size - offset));
            assertTrue(n > 0, "premature EOF at " + offset);
            for (int i = 0; i < n; i++) assertEquals(pattern(offset + i), bytes[i]);
            offset += n;
        }
    }

    static final class ManualClock implements Clock {
        final AtomicLong nanos = new AtomicLong();
        public long nanoTime() { return nanos.get(); }
        public long currentTimeSeconds() { return TimeUnit.NANOSECONDS.toSeconds(nanos.get()); }
    }

    static final class Fixture implements AutoCloseable {
        final AtomicStatsRegistry stats = registry();
        final ManualClock clock = new ManualClock();
        final List<Peer> peers = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Object changed = new Object();
        final NioTransport transport;
        final int pending;
        Fixture(int workers, int max, int pending, long idle, long memory) throws IOException {
            this(workers, max, pending, idle, memory, null);
        }
        Fixture(int workers, int max, int pending, long idle, long memory, AtomicInteger attempts) throws IOException {
            this(workers, max, pending, idle, memory, attempts, new TransportOps());
        }
        Fixture(int workers, int max, int pending, long idle, long memory, AtomicInteger attempts, TransportOps ops) throws IOException {
            this.pending = pending;
            transport = new NioTransport(settings(workers, max, pending, idle, memory,
                    new InetSocketAddress("127.0.0.1", 0)), resources -> {
                if (attempts != null && attempts.incrementAndGet() == 1) throw new IOException("injected factory failure");
                Peer peer = new Peer(this, resources); peers.add(peer); signal(); return peer;
            }, stats, clock, ops);
            transport.start();
        }
        Socket connect() throws IOException {
            return connect(1024);
        }
        Socket connect(int receiveBuffer) throws IOException {
            Socket s = new Socket();
            try {
                s.setReceiveBufferSize(receiveBuffer); s.setSoTimeout(10000);
                s.connect(transport.localAddress(), 10000);
                return s;
            } catch (IOException ex) { s.close(); throw ex; }
        }
        Peer peer(int index) throws InterruptedException {
            await(() -> peers.size() > index); return peers.get(index);
        }
        void signal() { synchronized (changed) { changed.notifyAll(); } }
        void await(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            synchronized (changed) {
                while (!condition.getAsBoolean()) {
                    long left = deadline - System.nanoTime();
                    assertTrue(left > 0, "condition deadline expired");
                    TimeUnit.NANOSECONDS.timedWait(changed, Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)));
                }
            }
        }
        void check(boolean condition, String message) {
            if (!condition) {
                IllegalStateException ex = new IllegalStateException(message);
                failure.compareAndSet(null, ex); throw ex;
            }
        }
        void assertHealthy() { assertNull(failure.get(), () -> String.valueOf(failure.get())); }
        public void close() { transport.close(); signal(); }
    }

    static final class Peer implements ConnectionHandler, ProtocolDecoder {
        final Fixture f;
        final ConnectionResources resources;
        final Thread owner = Thread.currentThread();
        final AtomicInteger handled = new AtomicInteger(), encoded = new AtomicInteger();
        final AtomicInteger decodeCalls = new AtomicInteger();
        final AtomicInteger writerCalls = new AtomicInteger(), writerClosed = new AtomicInteger();
        final AtomicInteger responsesClosed = new AtomicInteger(), closed = new AtomicInteger();
        final AtomicInteger maxCapacity = new AtomicInteger(), maxAppend = new AtomicInteger();
        final AtomicLong produced = new AtomicLong();
        final byte[] header = new byte[5];
        int headerSize, bodySize, bodyRead;
        byte[] body;
        boolean ready;
        char op;
        Peer(Fixture f, ConnectionResources resources) { this.f = f; this.resources = resources; }
        void owned() { f.check(Thread.currentThread() == owner, "callback migrated event loop"); }
        public ProtocolDecoder decoder() { owned(); return this; }
        public void append(ByteBuffer input) {
            owned();
            f.check(resources.lineSlots() == 1, "read without line admission");
            maxAppend.accumulateAndGet(input.remaining(), Math::max);
            while (input.hasRemaining() && headerSize < 5) header[headerSize++] = input.get();
            if (headerSize != 5 || ready) return;
            op = (char) header[0];
            if (op == 'J') return;
            if (body == null && bodyRead == 0) {
                bodySize = ByteBuffer.wrap(header, 1, 4).getInt();
                if (bodySize > 0) {
                    f.check(resources.tryReserveBody(bodySize), "test body admission unexpectedly denied");
                    body = new byte[bodySize]; f.signal();
                }
            }
            while (input.hasRemaining() && bodyRead < bodySize) body[bodyRead++] = input.get();
            ready = bodyRead == bodySize;
        }
        public Command nextCommand() throws ProtocolException {
            owned(); decodeCalls.incrementAndGet();
            if (!ready) return null;
            if (op == 'D') throw new IllegalStateException("injected decoder failure");
            if (op == 'F' || op == 'R') throw new ProtocolException(ProtocolException.Kind.CLIENT_ERROR, op == 'F');
            if (op == 'V') throw new ProtocolException(ProtocolException.Kind.ERROR, true);
            return new TestCommand(op, body);
        }
        public Response protocolError(ProtocolException failure) {
            owned(); headerSize = bodyRead = bodySize = 0; ready = false;
            if (op == 'V') return null;
            return new TestResponse(this, 'P');
        }
        public Response handle(Command command) {
            owned(); handled.incrementAndGet();
            char mode = (char) command.flags();
            if (mode == 'H') throw new IllegalStateException("injected handler failure");
            if (mode == 'X') { f.transport.close(); return null; }
            if (body != null) {
                for (int i = 0; i < body.length; i++) f.check(body[i] == pattern(i), "fragment body mismatch");
                resources.releaseBody(bodySize);
            }
            body = null; bodyRead = bodySize = headerSize = 0; ready = false;
            f.signal();
            if (mode == 'N' || mode == 'Q') return null;
            return new TestResponse(this, mode);
        }
        public ProtocolEncoder encoder() {
            owned();
            return response -> {
                owned(); encoded.incrementAndGet();
                TestResponse r = (TestResponse) response;
                if (r.mode == 'E') throw new IllegalStateException("injected encoder failure");
                if (r.mode == 'U') return null;
                return new ResponseWriter() {
                    final byte[] scratch = new byte[17];
                    long offset;
                    boolean complete, isClosed;
                    public WriteStatus writeMore(ChunkSink sink) {
                        owned(); f.check(!complete && !isClosed, "writer called after completion/close");
                        writerCalls.incrementAndGet();
                        if (r.mode == 'W') throw new IllegalStateException("injected writer failure");
                        if (r.mode == 'Z') return WriteStatus.IN_PROGRESS;
                        if (r.mode == 'T') return null;
                        if (r.mode == 'O') { sink.accept(ByteBuffer.allocate(f.pending + 1)); return WriteStatus.COMPLETE; }
                        if (r.mode == 'C') { complete = true; return WriteStatus.COMPLETE; }
                        int cap = sink.remainingCapacity();
                        maxCapacity.accumulateAndGet(cap, Math::max);
                        f.check(cap >= 0 && cap <= f.pending, "sink exceeds pending-byte budget");
                        int n = (int) Math.min(Math.min(cap, scratch.length), r.length - offset);
                        if (n == 0) { f.signal(); return WriteStatus.IN_PROGRESS; }
                        for (int i = 0; i < n; i++) scratch[i] = pattern(offset + i);
                        sink.accept(ByteBuffer.wrap(scratch, 0, n));
                        // Poison borrowed bytes immediately: the transport must own its pending copy.
                        java.util.Arrays.fill(scratch, (byte) 0);
                        offset += n; produced.addAndGet(n); f.signal();
                        if (r.mode == 'I' && writerCalls.get() >= 70) f.clock.nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
                        complete = offset == r.length;
                        return complete ? WriteStatus.COMPLETE : WriteStatus.IN_PROGRESS;
                    }
                    public void close() {
                        owned(); writerClosed.incrementAndGet();
                        f.check(!isClosed, "writer close called twice"); isClosed = true; r.close(); f.signal();
                        if (r.mode == 'C' || r.mode == 'K') throw new IllegalStateException("injected writer close failure");
                    }
                };
            };
        }
        public void close() { owned(); closed.incrementAndGet(); f.signal(); }
    }

    record TestCommand(char mode, byte[] value) implements Command {
        public CommandType type() { return mode == 'Q' ? CommandType.QUIT : CommandType.VERSION; }
        public KeySequence keys() { return null; }
        public int flags() { return mode; }
        public long exptime() { return 0; }
        public long casId() { return 0; }
        public long delta() { return 0; }
        public int level() { return 0; }
        public boolean noreply() { return false; }
    }

    static final class TestResponse implements Response {
        final Peer peer;
        final char mode;
        final int length;
        boolean closed;
        TestResponse(Peer peer, char mode) {
            this.peer = peer; this.mode = mode;
            length = mode == 'L' || mode == 'K' || mode == 'I' ? LARGE : mode == 'S' ? 8 * 1024 * 1024 : mode == 'B' ? 4096 : 17;
        }
        public ResponseKind kind() { return ResponseKind.VERSION; }
        public RetrievalSnapshot snapshot() { return null; }
        public boolean includeCas() { return false; }
        public long number() { return 0; }
        public String text() { return "test"; }
        public List<String> statsLines() { return null; }
        public void close() {
            peer.owned();
            if (!closed) { closed = true; peer.responsesClosed.incrementAndGet(); peer.f.signal(); }
        }
    }
}
