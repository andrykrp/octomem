package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.protocol.*;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.Gauge;
import ru.octomem.util.SystemClock;

/** Real text decoder, bridge and NIO; only application responses and encoding are fixtures. */
@Timeout(30)
class TextConnectionSocketTest {
    static final String VERSION = "VERSION test (octomem)\r\n";

    @Test void pipelinedVersionsReleasePreviousBorrowBeforeNextDispatch() throws Exception {
        try (Fixture f = new Fixture("normal", 2, 1024); Socket s = f.connect()) {
            send(s, "version\r\nversion\r\n");
            expect(s, VERSION + VERSION);
            await(() -> f.responses.size() == 2 && f.responses.get(1).closed.get() == 1);
            f.assertReleased(2, 2);
        }
    }

    @Test void completeZeroNullAndNoreplyStoresKeepPipelineAndBorrowAlive() throws Exception {
        for (String mode : List.of("normal", "zero", "null", "noreply")) {
            try (Fixture f = new Fixture(mode, 2, 1024); Socket s = f.connect()) {
                send(s, "set borrowed 7 0 3" + (mode.equals("noreply") ? " noreply" : "")
                        + "\r\nabc\r\nversion\r\n");
                expect(s, (mode.equals("normal") ? "STORED\r\n" : "") + VERSION);
                await(() -> f.commands.size() == 2 && f.responses.get(f.responses.size() - 1).closed.get() == 1);
                f.assertReleased(mode.equals("null") ? 1 : 2,
                        mode.equals("null") || mode.equals("noreply") ? 1 : 2);
            }
        }
    }

    @Test void oversizedNoreplyDrainsBodyContainingCommandTextBeforeVersion() throws Exception {
        try (Fixture f = new Fixture("normal", 2, 64); Socket s = f.connect()) {
            String body = "version\r\n".repeat(8);
            send(s, "set k 0 0 " + body.length() + " noreply\r\n" + body + "\r\nversion\r\n");
            expect(s, VERSION);
            await(() -> f.responses.size() == 1 && f.responses.getFirst().closed.get() == 1);
            assertEquals(CommandType.VERSION, f.types.getFirst());
            f.assertReleased(1, 1);
        }
    }

    @Test void realGlobalBodyDenialDrainsNoreplyAndDoesNotReleaseOtherOwner() throws Exception {
        try (Fixture f = new Fixture("normal", 3, 1024); Socket held = f.connect(); Socket denied = f.connect()) {
            send(held, "set held 0 0 980\r\n");
            await(() -> f.transport.usedBodyBytes() == 1012);
            send(denied, "set k 0 0 20 noreply\r\nversion\r\n12345678901\r\nversion\r\n");
            expect(denied, VERSION);
            assertEquals(1012, f.transport.usedBodyBytes());
            assertEquals(1, f.commands.size());
            held.close();
            await(() -> f.transport.usedBodyBytes() == 0);
            send(denied, "version\r\n"); expect(denied, VERSION);
        }
    }

    @Test void fatalMalformedTerminatorFlushesSafeErrorAndNeverDispatchesTail() throws Exception {
        for (String suffix : List.of("", " noreply")) {
            try (Fixture f = new Fixture("normal", 2, 1024); Socket s = f.connect()) {
                send(s, "set k 0 0 3" + suffix + "\r\nabcX\nversion\r\n");
                expect(s, "CLIENT_ERROR bad data chunk\r\n");
                assertEquals(-1, s.getInputStream().read());
                assertTrue(f.commands.isEmpty());
                await(() -> f.transport.usedControlBytes() == 0);
                assertEquals(0, f.transport.usedBodyBytes());
                assertEquals(0, f.transport.usedLineBytes());
            }
        }
    }

    @Test void fixedErrorKindsStreamThroughTwoByteLaneAndRecoverWhenPossible() throws Exception {
        try (Fixture f = new Fixture("normal", 2, 64); Socket s = f.connect()) {
            send(s, "unknown\r\nversion\r\n"); expect(s, "ERROR\r\n" + VERSION);
            send(s, "set k 0 0 40\r\n" + "x".repeat(40) + "\r\nversion\r\n");
            expect(s, "SERVER_ERROR object too large for cache\r\n" + VERSION);
        }
        try (Fixture f = new Fixture("normal", 2, 64); Socket s = f.connect()) {
            send(s, "set k invalid 0 1\r\nx\r\n");
            expect(s, "CLIENT_ERROR bad command line format\r\n");
            assertEquals(-1, s.getInputStream().read());
        }
        try (Fixture f = new Fixture("normal", 2, 1024); Socket held = f.connect(); Socket s = f.connect()) {
            send(held, "set held 0 0 980\r\n"); await(() -> f.transport.usedBodyBytes() == 1012);
            send(s, "set k 0 0 20\r\n" + "x".repeat(20) + "\r\nversion\r\n");
            expect(s, "SERVER_ERROR out of memory storing object\r\n" + VERSION);
        }
    }

    @Test void idleAdmissionChargesBeforeFactoryAndDeniedAdmissionNeverCreatesCodec() throws Exception {
        try (Fixture f = new Fixture("normal", 1, 1024); Socket idle = f.connect()) {
            await(() -> f.resources.size() == 1);
            assertEquals(List.of(10816L), f.factoryCharges);
            assertEquals(10816, f.transport.usedControlBytes());
            assertEquals(0, f.transport.usedLineBytes());
            assertEquals(0, f.transport.usedBodyBytes());
            try (Socket denied = f.connect()) { assertEquals(-1, denied.getInputStream().read()); }
            assertEquals(1, f.resources.size());
            assertEquals(10816, f.transport.usedControlBytes());
            idle.close(); await(() -> f.transport.usedControlBytes() == 0);
        }
    }

    @Test void seededControlDenialPrecedesHandoffReadAndFactoryAndRestoresAdmission() throws Exception {
        SharedBudget budget = new SharedBudget(10816, 1);
        AtomicInteger handoffs = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        TransportOps ops = new TransportOps() {
            @Override void beforeHandoff(SocketChannel socket) { handoffs.incrementAndGet(); }
            @Override int read(SocketChannel socket, ByteBuffer buffer) throws IOException {
                reads.incrementAndGet(); return super.read(socket, buffer);
            }
        };
        try (Fixture f = new Fixture("normal", 1, 1024, ops, budget)) {
            try (Socket denied = f.connect()) {
                assertEquals(-1, denied.getInputStream().read());
            }
            assertEquals(1, budget.usedBytes(), "external seed must not be released by rejection");
            assertEquals(1, f.transport.usedControlBytes());
            assertEquals(0, handoffs.get()); assertEquals(0, reads.get());
            assertTrue(f.resources.isEmpty()); assertTrue(f.factoryCharges.isEmpty());
            assertEquals(0L, f.stats.snapshot().gauges().get(Gauge.CURR_CONNECTIONS));
            assertEquals(0L, f.stats.snapshot().gauges().get(Gauge.CONNECTION_STRUCTURES));
            assertEquals(0, f.transport.usedLineBytes()); assertEquals(0, f.transport.usedBodyBytes());
            budget.release(1);
            try (Socket accepted = f.connect()) {
                await(() -> f.resources.size() == 1);
                assertEquals(List.of(10816L), f.factoryCharges);
                send(accepted, "version\r\n"); expect(accepted, VERSION);
                assertEquals(1, handoffs.get()); assertTrue(reads.get() > 0);
                assertEquals(10816, budget.usedBytes());
            }
            await(() -> budget.usedBytes() == 0);
        }
        SharedBudget callerOwned = new SharedBudget(10816, 1);
        try (Fixture f = new Fixture("normal", 1, 1024, new TransportOps(), callerOwned)) {
            f.close(); assertEquals(1, callerOwned.usedBytes());
        } finally { callerOwned.release(1); }
    }

    @Test void throwsDisconnectAndShutdownCloseDelegatesAndBudgetsOnce() throws Exception {
        for (String mode : List.of("dispatchThrow", "encodeThrow", "encodeNull", "writeThrow", "writeNull", "writerCloseThrow", "responseCloseThrow", "stall")) {
            Fixture f = new Fixture(mode, 2, 1024);
            try (Socket s = f.connect()) {
                send(s, "set borrowed 7 0 3\r\nabc\r\n");
                if (mode.equals("stall")) {
                    await(() -> f.calls.get() > 0);
                    assertEquals(35, f.transport.usedBodyBytes());
                    assertEquals(2304, f.transport.usedLineBytes());
                    f.close();
                } else if (mode.endsWith("CloseThrow")) {
                    s.getInputStream().readAllBytes();
                } else { assertEquals(-1, s.getInputStream().read()); }
                await(() -> f.transport.usedControlBytes() == 0);
                f.close(); f.close();
                assertEquals(0, f.transport.usedBodyBytes());
                assertEquals(0, f.transport.usedLineBytes());
                f.assertReleased(mode.equals("dispatchThrow") ? 0 : 1,
                        mode.startsWith("encode") || mode.equals("dispatchThrow") ? 0 : 1);
            } finally { f.close(); }
        }
        try (Fixture f = new Fixture("normal", 2, 1024); Socket s = f.connect()) {
            send(s, "set borrowed 7 0 100\r\na"); await(() -> f.transport.usedBodyBytes() == 132);
            s.close(); await(() -> f.transport.usedControlBytes() == 0);
            assertEquals(0, f.transport.usedBodyBytes()); assertEquals(0, f.transport.usedLineBytes());
        }
        try (Fixture f = new Fixture("stall", 2, 1024); Socket s = f.connect()) {
            send(s, "set borrowed 7 0 3\r\nabc\r\n"); await(() -> f.calls.get() > 0);
            s.setSoLinger(true, 0); s.close(); await(() -> f.transport.usedControlBytes() == 0);
            f.assertReleased(1, 1);
        }
    }

    static void send(Socket s, String text) throws Exception { s.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII)); }
    static void expect(Socket s, String text) throws Exception {
        assertEquals(text, new String(s.getInputStream().readNBytes(text.length()), StandardCharsets.US_ASCII));
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "condition timed out");
            Thread.sleep(5);
        }
    }

    static final class Fixture implements AutoCloseable {
        final String mode;
        final List<Command> commands = new CopyOnWriteArrayList<>();
        final List<CommandType> types = new CopyOnWriteArrayList<>();
        final List<Reply> responses = new CopyOnWriteArrayList<>();
        final List<ConnectionResources> resources = new CopyOnWriteArrayList<>();
        final List<Long> factoryCharges = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger writerCloses = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64",
                "threads", "1", "limit_maxbytes", "1024"));
        final NioTransport transport;

        Fixture(String mode, int max, int item) throws Exception {
            this(mode, max, item, new TransportOps(), null);
        }
        Fixture(String mode, int max, int item, TransportOps ops, SharedBudget controlBudget) throws Exception {
            this.mode = mode;
            TextConnectionFactory factory = new TextConnectionFactory(1024, item, command -> {
                commands.add(command); types.add(command.type());
                if (mode.equals("dispatchThrow")) throw new IllegalStateException("injected dispatcher");
                if (command.type() == CommandType.SET && mode.equals("null")) return null;
                Reply reply = new Reply(this, command); responses.add(reply); return reply;
            }, response -> {
                if (mode.equals("encodeThrow")) throw new IllegalStateException("injected encoder");
                if (mode.equals("encodeNull")) return null;
                Reply reply = responses.getLast();
                // Exercise every forwarded response accessor without needing a cache engine.
                response.kind(); response.snapshot(); response.includeCas(); response.number(); response.text(); response.statsLines();
                return new ResponseWriter() {
                    final byte[] bytes = (reply.command.type() == CommandType.VERSION ? VERSION
                            : mode.equals("zero") ? "" : "STORED\r\n").getBytes(StandardCharsets.US_ASCII);
                    int offset;
                    boolean closed;
                    public WriteStatus writeMore(ChunkSink sink) {
                        check(!closed, "callback after writer close"); reply.alive(); calls.incrementAndGet();
                        if (mode.equals("writeThrow")) throw new IllegalStateException("injected writer");
                        if (mode.equals("writeNull")) return null;
                        if (mode.equals("stall")) {
                            while (sink.remainingCapacity() > 0) sink.accept(ByteBuffer.wrap(new byte[] {'x'}));
                            return WriteStatus.IN_PROGRESS;
                        }
                        while (offset < bytes.length) {
                            int n = bytes[offset] == '\r' ? 2 : 1;
                            if (sink.remainingCapacity() < n) return WriteStatus.IN_PROGRESS;
                            check(n <= sink.remainingCapacity(), "capacity exceeded");
                            sink.accept(ByteBuffer.wrap(bytes, offset, n)); offset += n;
                        }
                        return WriteStatus.COMPLETE;
                    }
                    public void close() {
                        check(!closed, "delegate writer closed twice"); reply.alive(); closed = true;
                        writerCloses.incrementAndGet();
                        if (mode.equals("writerCloseThrow")) throw new IllegalStateException("injected writer close");
                        response.close();
                    }
                };
            });
            transport = new NioTransport(new TransportSettings(new InetSocketAddress("127.0.0.1", 0),
                    1, max, 2, 2, 0, 1024, 2, item, item), r -> {
                factoryCharges.add(transportCharge());
                TextConnectionHandler handler = factory.create(r); resources.add(r); return handler;
            }, stats, new SystemClock(), ops, null, controlBudget);
            transport.start();
        }
        long transportCharge() { return transport.usedControlBytes(); }
        Socket connect() throws Exception {
            Socket s = new Socket(); s.setSoTimeout(10000); s.setReceiveBufferSize(1024);
            s.connect(transport.localAddress(), 10000); return s;
        }
        void check(boolean ok, String message) {
            if (!ok) { AssertionError e = new AssertionError(message); failure.compareAndSet(null, e); throw e; }
        }
        void assertReleased(int replies, int writers) {
            assertNull(failure.get()); assertEquals(replies, responses.size()); assertEquals(writers, writerCloses.get());
            for (Reply reply : responses) assertEquals(1, reply.closed.get());
            for (Command command : commands) assertThrows(IllegalStateException.class, command::keys);
            for (ConnectionResources r : resources) assertEquals(0, r.usedBodyBytes());
        }
        public void close() { transport.close(); }
    }

    static final class Reply implements Response {
        final Fixture fixture;
        final Command command;
        final AtomicInteger closed = new AtomicInteger();
        Reply(Fixture fixture, Command command) { this.fixture = fixture; this.command = command; }
        void alive() {
            fixture.check(closed.get() == 0, "response used after close");
            try {
                if (command.type() == CommandType.SET) {
                    fixture.check(command.keys().size() == 1, "borrowed keys lost");
                    command.keys().forEachKey((bytes, offset, length) -> fixture.check(
                            new String(bytes, offset, length, StandardCharsets.US_ASCII).equals("borrowed"), "borrowed key changed"));
                    fixture.check(new String(command.value(), StandardCharsets.US_ASCII).equals("abc"), "body changed");
                }
            } catch (RuntimeException | Error e) { fixture.failure.compareAndSet(null, e); throw e; }
        }
        public ResponseKind kind() { alive(); return ResponseKind.VERSION; }
        public RetrievalSnapshot snapshot() { alive(); return null; }
        public boolean includeCas() { alive(); return false; }
        public long number() { alive(); return 0; }
        public String text() { alive(); return "test (octomem)"; }
        public List<String> statsLines() { alive(); return null; }
        public void close() {
            alive(); fixture.check(closed.incrementAndGet() == 1, "response closed twice");
            if (fixture.mode.equals("responseCloseThrow")) throw new IllegalStateException("injected response close");
        }
    }
}
