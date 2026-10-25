package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;
import static ru.octomem.transport.TextConnectionSocketTest.await;
import static ru.octomem.transport.TextConnectionSocketTest.send;
import static ru.octomem.transport.TextConnectionSocketTest.expect;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.protocol.*;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.*;
import ru.octomem.util.SystemClock;

/** Both production codecs and real native pinned records, with test-only dispatcher/snapshots. */
@Timeout(60)
class TextProtocolEndToEndTest {
    static final String VERSION = "VERSION test (octomem)\r\n";
    static final int LARGE = 8 * 1024 * 1024;

    @Test void binaryStoreGetsUnsignedCasAndPipelineWithTwoByteEncoder() throws Exception {
        try (Fixture f = new Fixture(2, 1024); Socket s = f.connect()) {
            byte[] body = {0, 13, 10, (byte) 255, 1, 127};
            send(s, "set binary 4294967295 0 6\r\n");
            s.getOutputStream().write(body); send(s, "\r\ngets binary missing binary\r\nget binary\r\nversion\r\n");
            expect(s, "STORED\r\nVALUE binary 4294967295 6 18446744073709551615\r\n");
            assertArrayEquals(body, s.getInputStream().readNBytes(6));
            expect(s, "\r\nEND\r\nVALUE binary 4294967295 6\r\n");
            assertArrayEquals(body, s.getInputStream().readNBytes(6)); expect(s, "\r\nEND\r\n" + VERSION);
            await(() -> f.replies.size() == 4 && f.replies.getLast().closed.get() == 1);
            f.assertCleanCommands(); assertEquals(0, f.items.get("binary").pinCount());
        }
    }

    @Test void realEncoderAdministrativeFormattingAndNoreplyAndQuit() throws Exception {
        try (Fixture f = new Fixture(2, 1024); Socket s = f.connect()) {
            send(s, "version\r\nstats\r\nstats reset\r\nincr key 1\r\nverbosity 1\r\n");
            expect(s, VERSION + "STAT curr_items 0\r\nSTAT version test (octomem)\r\nEND\r\nRESET\r\n18446744073709551615\r\nOK\r\n");
            send(s, "set silent 0 0 0 noreply\r\n\r\nset denied 0 0 0 noreply\r\n\r\nset null 0 0 0\r\n\r\nversion\r\n");
            expect(s, VERSION);
            await(() -> f.replies.getLast().closed.get() == 1 && f.transport.usedBodyBytes() == 0);
            send(s, "set denied 0 0 0\r\n\r\nunknown\r\nversion\r\n");
            expect(s, "SERVER_ERROR out of memory storing object\r\nERROR\r\n" + VERSION);
            send(s, "quit\r\nversion\r\n"); assertEquals(-1, s.getInputStream().read());
            await(() -> f.transport.usedControlBytes() == 0);
            assertEquals(0, f.transport.usedLineBytes()); f.assertCleanCommands();
        }
    }

    @Test void realEncoderDeniedAndOversizedNoreplyBodyDrainAndFatalFlush() throws Exception {
        try (Fixture f = new Fixture(2, 1024); Socket held = f.connect(); Socket s = f.connect()) {
            send(held, "set held 0 0 980\r\n"); await(() -> f.transport.usedBodyBytes() == 1012);
            send(s, "set k 0 0 20 noreply\r\n" + "x".repeat(20) + "\r\nversion\r\n"); expect(s, VERSION);
            assertEquals(1012, f.transport.usedBodyBytes());
            send(s, "set k 0 0 1100 noreply\r\n" + "x".repeat(1100) + "\r\nversion\r\n"); expect(s, VERSION);
            send(s, "set k 0 0 20 noreply\r\n" + "x".repeat(20) + "X\nversion\r\n");
            expect(s, "CLIENT_ERROR bad data chunk\r\n"); assertEquals(-1, s.getInputStream().read());
            held.close(); await(() -> f.transport.usedControlBytes() == 0);
            assertEquals(0, f.transport.usedBodyBytes()); assertEquals(0, f.transport.usedLineBytes());
        }
    }

    @Test void partialIngressCancellationAndRealEncoderRejectionReleaseBudgets() throws Exception {
        for (boolean shutdown : List.of(false, true)) {
            try (Fixture f = new Fixture(2, 1024); Socket s = f.connect()) {
                send(s, "set partial 0 0 100\r\na"); await(() -> f.transport.usedBodyBytes() == 132);
                if (shutdown) f.transport.close(); else s.close();
                await(() -> f.transport.usedControlBytes() == 0);
                assertEquals(0, f.transport.usedBodyBytes()); assertEquals(0, f.transport.usedLineBytes());
                assertTrue(f.replies.isEmpty());
            }
        }
        try (Fixture f = new Fixture(2, 1024); Socket bad = f.connect(); Socket good = f.connect()) {
            send(bad, "set invalid 0 0 3\r\nabc\r\n");
            assertEquals(-1, bad.getInputStream().read());
            send(good, "version\r\n"); expect(good, VERSION);
            await(() -> f.replies.size() == 2 && f.replies.getLast().closed.get() == 1);
            assertEquals(0, f.transport.usedBodyBytes()); f.assertCleanCommands();
        }
    }

    @Test void pausedNativeMultiKeyRetrievalResumesAndHealthyPeerMakesProgress() throws Exception {
        try (Fixture f = new Fixture(512, LARGE + 64)) {
            byte[] large = new byte[LARGE];
            for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 31);
            f.put("large", large); f.put("tail", new byte[] {0, (byte) 255});
            try (Socket slow = f.connect(); Socket healthy = f.connect()) {
                send(slow, "gets large tail\r\n");
                await(() -> !f.snapshots.isEmpty() && f.snapshots.getFirst().visited.get() == 1);
                for (int i = 0; i < 10; i++) { send(healthy, "version\r\n"); expect(healthy, VERSION); }
                Snapshot snapshot = f.snapshots.getFirst();
                assertEquals(1, snapshot.visited.get(), "must remain backpressured before tail");
                assertEquals(1, f.items.get("large").pinCount()); assertEquals(1, f.items.get("tail").pinCount());
                f.items.values().forEach(StoredItem::retire);
                assertTrue(f.allocator.usedMemory() > 0);
                expect(slow, "VALUE large 4294967295 " + LARGE + " 18446744073709551615\r\n");
                assertArrayEquals(large, slow.getInputStream().readNBytes(LARGE));
                expect(slow, "\r\nVALUE tail 4294967295 2 18446744073709551615\r\n");
                assertArrayEquals(new byte[] {0, (byte) 255}, slow.getInputStream().readNBytes(2)); expect(slow, "\r\nEND\r\n");
                await(() -> snapshot.closed.get() == 1);
                assertEquals(2, snapshot.visited.get()); assertEquals(0, f.allocator.usedMemory());
                send(slow, "version\r\n"); expect(slow, VERSION); f.assertCleanCommands();
            }
        }
    }

    @Test void disconnectAndShutdownReleaseCurrentAndUnvisitedNativePins() throws Exception {
        for (boolean shutdown : List.of(false, true)) {
            try (Fixture f = new Fixture(512, LARGE + 64); Socket slow = f.connect(); Socket healthy = f.connect()) {
                f.put("large", new byte[LARGE]); f.put("tail", new byte[] {1});
                send(slow, "get large tail\r\n");
                await(() -> !f.snapshots.isEmpty() && f.snapshots.getFirst().visited.get() == 1);
                send(healthy, "version\r\n"); expect(healthy, VERSION);
                Snapshot snapshot = f.snapshots.getFirst(); assertEquals(1, snapshot.visited.get());
                f.items.values().forEach(StoredItem::retire);
                if (shutdown) f.transport.close(); else { slow.setSoLinger(true, 0); slow.close(); }
                await(() -> snapshot.closed.get() == 1);
                assertEquals(1, snapshot.visited.get(), "unvisited tail must be released without traversal");
                assertEquals(0, f.allocator.usedMemory());
                for (StoredItem item : f.items.values()) assertEquals(0, item.pinCount());
                if (!shutdown) { send(healthy, "version\r\n"); expect(healthy, VERSION); }
                f.transport.close(); f.transport.close();
                assertEquals(0, f.transport.usedBodyBytes()); assertEquals(0, f.transport.usedLineBytes());
                assertEquals(0, f.transport.usedControlBytes()); f.assertCleanCommands();
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final FfmSlabAllocator allocator;
        final Map<String, StoredItem> items = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Command> commands = new CopyOnWriteArrayList<>();
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        final List<Snapshot> snapshots = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final NioTransport transport;
        final int itemMax;
        Fixture(int chunk, int itemMax) throws Exception {
            this.itemMax = itemMax;
            allocator = new FfmSlabAllocator(32L * 1024 * 1024, 4096, itemMax);
            TextConnectionFactory factory = new TextConnectionFactory(1024, itemMax, this::dispatch, new TextProtocolEncoder(chunk));
            transport = new NioTransport(new TransportSettings(new InetSocketAddress("127.0.0.1", 0), 1, 4,
                    chunk, 2, 0, 1024, chunk, itemMax, itemMax), factory,
                    new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64", "threads", "1", "limit_maxbytes", "1024")), new SystemClock());
            transport.start();
        }
        void put(String key, byte[] value) {
            StoredItem item = StoredItem.create(allocator, key.getBytes(StandardCharsets.US_ASCII), value, -1, 0, -1, itemMax);
            assertNotNull(item); StoredItem previous = items.put(key, item); if (previous != null) previous.retire();
        }
        Response dispatch(Command command) {
            try {
                commands.add(command);
                List<String> keys = new ArrayList<>();
                command.keys().forEachKey((b, o, n) -> keys.add(new String(b, o, n, StandardCharsets.US_ASCII)));
                ResponseKind kind;
                Snapshot snapshot = null;
                switch (command.type()) {
                    case SET -> {
                        if (keys.getFirst().equals("null")) return null;
                        if (keys.getFirst().equals("denied") || keys.getFirst().equals("invalid")) kind = ResponseKind.SERVER_ERROR;
                        else { put(keys.getFirst(), command.value()); kind = ResponseKind.STORED; }
                    }
                    case GET, GETS -> {
                        List<ItemLease> pins = new ArrayList<>();
                        for (String key : new LinkedHashSet<>(keys)) {
                            StoredItem item = items.get(key); if (item != null) pins.add(item.pin());
                        }
                        snapshot = new Snapshot(pins); snapshots.add(snapshot); kind = ResponseKind.RETRIEVAL;
                    }
                    case STATS -> kind = ResponseKind.STATS;
                    case STATS_RESET -> kind = ResponseKind.RESET;
                    case INCR -> kind = ResponseKind.NUMBER;
                    case VERBOSITY -> kind = ResponseKind.OK;
                    case QUIT -> { return null; }
                    default -> kind = ResponseKind.VERSION;
                }
                Reply reply = new Reply(this, command, kind, snapshot); replies.add(reply); return reply;
            } catch (RuntimeException | Error e) { failure.compareAndSet(null, e); throw e; }
        }
        Socket connect() throws Exception {
            Socket socket = new Socket(); socket.setReceiveBufferSize(65536); socket.setSoTimeout(20000);
            socket.connect(transport.localAddress(), 10000); return socket;
        }
        void assertCleanCommands() {
            assertNull(failure.get());
            for (Reply reply : replies) assertEquals(1, reply.closed.get());
            for (Command command : commands) assertThrows(IllegalStateException.class, command::keys);
        }
        public void close() {
            transport.close(); items.values().forEach(StoredItem::retire);
            try { assertEquals(0, allocator.usedMemory()); assertNull(failure.get()); }
            finally { allocator.close(); }
        }
    }

    static final class Reply implements Response {
        final Fixture fixture;
        final Command command;
        final ResponseKind kind;
        final Snapshot snapshot;
        final AtomicInteger closed = new AtomicInteger();
        Reply(Fixture f, Command c, ResponseKind k, Snapshot s) { fixture = f; command = c; kind = k; snapshot = s; }
        public ResponseKind kind() { return kind; }
        public RetrievalSnapshot snapshot() { return snapshot; }
        public boolean includeCas() { return command.type() == CommandType.GETS; }
        public long number() { return -1; }
        public String text() {
            if (kind != ResponseKind.SERVER_ERROR) return "test (octomem)";
            List<String> keys = new ArrayList<>();
            command.keys().forEachKey((b, o, n) -> keys.add(new String(b, o, n, StandardCharsets.US_ASCII)));
            return keys.getFirst().equals("invalid") ? "unsafe\r\ntext" : "out of memory storing object";
        }
        public List<String> statsLines() { return List.of("STAT curr_items 0", "STAT version test (octomem)"); }
        public void close() {
            if (closed.get() != 0) return;
            try { command.keys().size(); if (snapshot != null) snapshot.close(); }
            catch (RuntimeException | Error e) { fixture.failure.compareAndSet(null, e); throw e; }
            finally { closed.incrementAndGet(); }
        }
    }

    static final class Snapshot implements RetrievalSnapshot {
        final List<ItemLease> pins;
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger visited = new AtomicInteger();
        boolean obtained;
        Snapshot(List<ItemLease> pins) { this.pins = pins; }
        public long capturedClock() { return 0; }
        public Iterator<Item> values() {
            assertFalse(obtained); obtained = true;
            Iterator<ItemLease> iterator = pins.iterator();
            return new Iterator<>() {
                public boolean hasNext() { assertEquals(0, closed.get()); return iterator.hasNext(); }
                public Item next() { assertEquals(0, closed.get()); Item value = iterator.next().item(); visited.incrementAndGet(); return value; }
            };
        }
        public void close() { if (closed.get() == 0) { pins.forEach(ItemLease::close); closed.incrementAndGet(); } }
    }
}
