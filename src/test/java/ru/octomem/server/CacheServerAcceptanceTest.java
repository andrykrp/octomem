package ru.octomem.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.config.ConfigLoader;
import ru.octomem.cli.CacheServer;
import ru.octomem.util.Clock;

/** Independent literal-wire acceptance of the actual server composition. */
@Timeout(20)
class CacheServerAcceptanceTest {
    private CacheServer server;
    private Socket socket;
    private final TestClock clock = new TestClock();
    private Path transcript;

    @BeforeEach void evidence(TestInfo info) throws IOException {
        Path root = Path.of("tests", "reports", "server", "wire");
        Files.createDirectories(root);
        transcript = root.resolve(info.getTestMethod().orElseThrow().getName() + ".txt");
        Files.writeString(transcript, "CASE " + info.getDisplayName() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void record(String kind, byte[] data) throws IOException {
        Files.writeString(transcript, kind + " " + java.util.HexFormat.of().formatHex(data) + "\n",
                StandardOpenOption.APPEND);
    }

    @AfterEach void close() throws Exception {
        try { if (socket != null) socket.close(); }
        finally { if (server != null) { server.close(); server.close(); } }
    }

    private void start(String... overrides) throws Exception {
        Map<String, String> config = new HashMap<>(Map.of("transport.workers", "2",
                "memory.limit", "4194304", "item.maxSize", "65536", "connections.max", "8"));
        for (int i = 0; i < overrides.length; i += 2) config.put(overrides[i], overrides[i + 1]);
        server = new CacheServer(ConfigLoader.load(null, config), new InetSocketAddress("127.0.0.1", 0), clock);
        server.start();
        assertTrue(server.localAddress().getPort() > 0);
        assertTrue(server.localAddress().getAddress().isLoopbackAddress());
        socket = new Socket();
        socket.connect(server.localAddress(), 2000);
        socket.setSoTimeout(3000);
    }

    private void send(String input) throws IOException {
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
        record("INPUT", bytes);
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private void expect(String input, String output) throws IOException {
        send(input);
        byte[] expected = output.getBytes(StandardCharsets.ISO_8859_1);
        record("EXPECTED", expected);
        byte[] actual = socket.getInputStream().readNBytes(expected.length);
        record("ACTUAL", actual);
        assertArrayEquals(expected, actual, "Input: " + input);
    }

    private String line() throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (result.size() < 8192) {
            int b = socket.getInputStream().read();
            assertNotEquals(-1, b, "Unexpected EOF");
            result.write(b);
            if (b == '\n') {
                record("ACTUAL-LINE", result.toByteArray());
                return result.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new AssertionError("Unbounded response line");
    }

    @Test void firstSetGetDeleteRawWireMilestone() throws Exception {
        start();
        expect("set first 17 0 3\r\none\r\nget first\r\ndelete first\r\nget first\r\n",
                "STORED\r\nVALUE first 17 3\r\none\r\nEND\r\nDELETED\r\nEND\r\n");
    }

    @Test void storesAndConditionalFailuresPreserveFlags() throws Exception {
        start();
        expect("replace k 0 0 1\r\nx\r\nappend k 0 0 1\r\nx\r\nprepend k 0 0 1\r\nx\r\n",
                "NOT_STORED\r\nNOT_STORED\r\nNOT_STORED\r\n");
        expect("add k 4294967295 0 1\r\nb\r\nadd k 0 0 1\r\nx\r\n"
                + "append k 5 -1 1\r\nc\r\nprepend k 6 -1 1\r\na\r\nget k\r\n",
                "STORED\r\nNOT_STORED\r\nSTORED\r\nSTORED\r\nVALUE k 4294967295 3\r\nabc\r\nEND\r\n");
        expect("replace k 3 0 0\r\n\r\nget k\r\ndelete k 999\r\ndelete k\r\n",
                "STORED\r\nVALUE k 3 0\r\n\r\nEND\r\nDELETED\r\nNOT_FOUND\r\n");
    }

    @Test void casUsesOpaqueUnsignedTokenAndDetectsStaleVersion() throws Exception {
        start();
        expect("set k 4 0 1\r\na\r\n", "STORED\r\n");
        send("gets k\r\n");
        String header = line();
        assertTrue(header.matches("VALUE k 4 1 [0-9]+\r\n"), header);
        String token = header.substring("VALUE k 4 1 ".length()).strip();
        Long.parseUnsignedLong(token);
        assertEquals("a\r\n", line());
        assertEquals("END\r\n", line());
        expect("cas k 8 0 1 " + token + "\r\nb\r\ncas k 9 0 1 " + token + "\r\nc\r\n"
                + "cas missing 0 0 1 18446744073709551615\r\nx\r\nget k\r\n",
                "STORED\r\nEXISTS\r\nNOT_FOUND\r\nVALUE k 8 1\r\nb\r\nEND\r\n");
    }

    @Test void countersWrapUnsignedAndClampDecrement() throws Exception {
        start();
        expect("set n 0 0 20\r\n18446744073709551615\r\nincr n 1\r\ndecr n 1\r\n"
                + "incr n 9223372036854775808\r\ndecr n 18446744073709551615\r\n"
                + "incr absent 1\r\ndecr absent 1\r\nset text 0 0 1\r\nx\r\nincr text 1\r\ndecr text 1\r\n",
                "STORED\r\n0\r\n0\r\n9223372036854775808\r\n0\r\nNOT_FOUND\r\nNOT_FOUND\r\nSTORED\r\n"
                + "CLIENT_ERROR cannot increment or decrement non-numeric value\r\n"
                + "CLIENT_ERROR cannot increment or decrement non-numeric value\r\n");
    }

    @Test void relativeAbsoluteAndNegativeExpiryAreClockDriven() throws Exception {
        start();
        expect("set r 0 2 1\r\nx\r\nset a 0 1700000002 1\r\ny\r\nset z 0 -1 1\r\nz\r\nget z r a\r\n",
                "STORED\r\nSTORED\r\nSTORED\r\nVALUE r 0 1\r\nx\r\nVALUE a 0 1\r\ny\r\nEND\r\n");
        clock.seconds += 2;
        expect("get r a\r\n", "END\r\n");
    }

    @Test void appendPrependDoNotResetExpiry() throws Exception {
        start();
        expect("set k 7 2 1\r\nb\r\nappend k 0 0 1\r\nc\r\nprepend k 0 0 1\r\na\r\n",
                "STORED\r\nSTORED\r\nSTORED\r\n");
        clock.seconds += 2;
        expect("get k\r\n", "END\r\n");
    }

    @Test void touchGatGatsAndDuplicateKeys() throws Exception {
        start();
        expect("set k 7 1 1\r\nx\r\nset noreply 8 0 1\r\ny\r\ntouch k 10\r\ntouch missing 3\r\n",
                "STORED\r\nSTORED\r\nTOUCHED\r\nNOT_FOUND\r\n");
        clock.seconds += 2;
        expect("gat 20 k noreply k\r\n", "VALUE k 7 1\r\nx\r\nVALUE noreply 8 1\r\ny\r\nEND\r\n");
        send("gats 30 k k\r\n");
        assertTrue(line().matches("VALUE k 7 1 [0-9]+\r\n"));
        assertEquals("x\r\n", line());
        assertEquals("END\r\n", line());
        expect("touch k -1\r\nget k\r\n", "TOUCHED\r\nEND\r\n");
    }

    @Test void flushImmediateAndDelayedPreserveLaterEntries() throws Exception {
        start();
        expect("set k 0 0 1\r\nx\r\nflush_all\r\nget k\r\n", "STORED\r\nOK\r\nEND\r\n");
        clock.seconds++;
        expect("set k 0 0 1\r\nx\r\nflush_all 2\r\nget k\r\n",
                "STORED\r\nOK\r\nVALUE k 0 1\r\nx\r\nEND\r\n");
        clock.seconds += 3;
        expect("set later 0 0 1\r\ny\r\nget k later\r\n", "STORED\r\nVALUE later 0 1\r\ny\r\nEND\r\n");
    }

    @Test void noreplySuccessAndFailuresStayOrdered() throws Exception {
        start();
        expect("set n 0 0 1 noreply\r\n1\r\nadd n 0 0 1 noreply\r\nx\r\n"
                + "replace missing 0 0 1 noreply\r\nx\r\nappend missing 0 0 1 noreply\r\nx\r\n"
                + "prepend missing 0 0 1 noreply\r\nx\r\ncas missing 0 0 1 1 noreply\r\nx\r\n"
                + "incr n 4 noreply\r\ndecr n 1 noreply\r\ntouch n 0 noreply\r\n"
                + "delete missing noreply\r\nverbosity 2 noreply\r\nget n\r\n",
                "VALUE n 0 1\r\n4\r\nEND\r\n");
        expect("delete n noreply\r\nflush_all noreply\r\nget n\r\n", "END\r\n");
    }

    @Test void fragmentBinaryBodyAndPipelineThroughTwoByteChunks() throws Exception {
        start("protocol.maxChunkBytes", "2", "connections.pendingWriteBytes", "2", "connections.maxInflightCommands", "1");
        String input = "set bin 12 0 5\r\na\u0000\r\nb\r\nget bin bin\r\nversion\r\nverbosity 1\r\n";
        for (byte b : input.getBytes(StandardCharsets.ISO_8859_1)) socket.getOutputStream().write(b);
        String expected = "STORED\r\nVALUE bin 12 5\r\na\u0000\r\nb\r\nEND\r\nVERSION 1.0.0 (octomem)\r\nOK\r\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.ISO_8859_1), socket.getInputStream().readNBytes(expected.length()));
    }

    @Test void oversizedBodyIsDrainedIncludingNoreply() throws Exception {
        start("item.maxSize", "128");
        String body = "x".repeat(129);
        expect("set big 0 0 129\r\n" + body + "\r\nget big\r\n",
                "SERVER_ERROR object too large for cache\r\nEND\r\n");
        expect("set big 0 0 129 noreply\r\n" + body + "\r\nversion\r\n", "VERSION 1.0.0 (octomem)\r\n");
    }

    @Test void lfEmptyAndUnsupportedCommandsHaveLiteralResponses() throws Exception {
        start();
        expect("\r\nunknown\r\nversion\n", "ERROR\r\nERROR\r\nVERSION 1.0.0 (octomem)\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"set k 4294967296 0 1\r\nx\r\n", "incr k 18446744073709551616\r\n",
            "cas k 0 0 1 18446744073709551616\r\nx\r\n", "touch k\r\n", "get\r\n"})
    void malformedArgumentsAreRejected(String command) throws Exception {
        start();
        expect(command, command.startsWith("incr ") ? "CLIENT_ERROR invalid numeric delta argument\r\n"
                : "CLIENT_ERROR bad command line format\r\n");
    }

    @Test void keyLength250IsAcceptedAnd251Rejected() throws Exception {
        start();
        String key = "k".repeat(250);
        expect("set " + key + " 0 0 1\r\nx\r\nget " + key + "\r\n",
                "STORED\r\nVALUE " + key + " 0 1\r\nx\r\nEND\r\n");
        expect("get " + key + "k\r\n", "CLIENT_ERROR bad command line format\r\n");
    }

    @Test void snapshotAdmissionFailureHasNoPartialRetrievalAndVersionStillProgresses() throws Exception {
        start("memory.limit", "128", "item.maxSize", "128", "protocol.maxChunkBytes", "2");
        expect("get absent\r\nversion\r\n", "SERVER_ERROR out of memory storing object\r\nVERSION 1.0.0 (octomem)\r\n");
    }

    @Test void expiryThresholdDistinguishesThirtyDaysFromAbsoluteEpoch() throws Exception {
        start();
        expect("set relative 0 2592000 1\r\nx\r\nset absolute 0 2592001 1\r\ny\r\nget relative absolute\r\n",
                "STORED\r\nSTORED\r\nVALUE relative 0 1\r\nx\r\nEND\r\n");
        clock.seconds += 2_592_000;
        expect("get relative\r\n", "END\r\n");
    }

    @Test void idleConnectionChargeRejectionAndShutdownRelease() throws Exception {
        start("connections.max", "1");
        expect("version\r\n", "VERSION 1.0.0 (octomem)\r\n");
        assertEquals(10816 + 32768, server.resourceUsage().controlBytes(), "Base transport plus ADR-020 negotiated protocol allowance");
        assertEquals(1, server.resourceUsage().currentConnections());
        try (Socket rejected = new Socket()) {
            rejected.connect(server.localAddress(), 2000);
            rejected.setSoTimeout(3000);
            assertEquals(-1, rejected.getInputStream().read(), "Over-limit connection must be rejected");
        }
        assertEquals(10816 + 32768, server.resourceUsage().controlBytes(), "Rejected connection must not retain allocation");
        assertEquals(1, server.resourceUsage().currentConnections());
        assertEquals(1, server.resourceUsage().rejectedConnections());
        expect("version\r\n", "VERSION 1.0.0 (octomem)\r\n");
        server.close();
        var resources = server.resourceUsage();
        assertAll(() -> assertEquals(0, resources.controlBytes()),
                () -> assertEquals(0, resources.lineBytes()), () -> assertEquals(0, resources.bodyBytes()),
                () -> assertEquals(0, resources.nativeTotalBytes()), () -> assertEquals(0, resources.nativeUsedBytes()),
                () -> assertEquals(0, resources.snapshotBytes()), () -> assertEquals(0, resources.serviceBytes()),
                () -> assertEquals(0, resources.currentConnections()));
    }

    @Test void unfinishedBodyAndStoredNativeRecordAreReleasedAtShutdown() throws Exception {
        start();
        expect("set existing 0 0 1\r\nx\r\n", "STORED\r\n");
        assertTrue(server.resourceUsage().nativeUsedBytes() > 0);
        send("set incomplete 0 0 100\r\nx");
        server.close();
        var resources = server.resourceUsage();
        assertAll(() -> assertEquals(0, resources.controlBytes()),
                () -> assertEquals(0, resources.lineBytes()), () -> assertEquals(0, resources.bodyBytes()),
                () -> assertEquals(0, resources.nativeTotalBytes()), () -> assertEquals(0, resources.nativeUsedBytes()),
                () -> assertEquals(0, resources.snapshotBytes()), () -> assertEquals(0, resources.serviceBytes()));
    }

    @Test void statsItemsReportsRealMandatoryClassFields() throws Exception {
        start();
        assertTrue(stats("stats items").isEmpty());
        expect("set a 0 0 1\r\nx\r\n", "STORED\r\n");
        Map<String, String> values = stats("stats items");
        String prefix = values.keySet().stream().filter(k -> k.endsWith(":number"))
                .findFirst().orElseThrow().replace("number", "");
        assertEquals("1", values.get(prefix + "number"));
        for (String field : new String[]{"age", "evicted", "evicted_nonzero", "evicted_time",
                "outofmemory", "tailrepairs", "reclaimed", "expired_unfetched", "evicted_unfetched", "crawler_reclaimed"}) {
            assertNotNull(values.get(prefix + field), field);
            assertTrue(values.get(prefix + field).matches("[0-9]+"), field);
        }
        expect("version\r\n", "VERSION 1.0.0 (octomem)\r\n");
    }

    private Map<String, String> stats(String command) throws IOException {
        send(command + "\r\n");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < 4096; i++) {
            String row = line();
            if (row.equals("END\r\n")) return values;
            assertTrue(row.startsWith("STAT ") && row.endsWith("\r\n"), row);
            String[] parts = row.substring(5, row.length() - 2).split(" ", 2);
            assertEquals(2, parts.length, row);
            assertNull(values.put(parts[0], parts[1]), "Duplicate stats field: " + parts[0]);
        }
        throw new AssertionError("Unbounded statistics response");
    }

    @Test void availableGeneralStatsTrackRealActionsAndResetCounters() throws Exception {
        start();
        expect("set statkey 0 0 1\r\nx\r\nget statkey absent\r\n",
                "STORED\r\nVALUE statkey 0 1\r\nx\r\nEND\r\n");
        Map<String, String> current = stats("stats");
        assertAll(() -> assertEquals("1", current.get("cmd_set")),
                // Memcached 1.6.45 proto_parser.c:652-735 counts each requested key.
                () -> assertEquals("2", current.get("cmd_get")),
                () -> assertEquals("1", current.get("get_hits")),
                () -> assertEquals("1", current.get("get_misses")),
                () -> assertEquals("4194304", current.get("limit_maxbytes")),
                () -> assertEquals("1", current.get("curr_connections")));
        expect("stats reset\r\n", "RESET\r\n");
        Map<String, String> reset = stats("stats");
        assertEquals("0", reset.get("cmd_get"));
        assertEquals("0", reset.get("cmd_set"));
        expect("get statkey\r\n", "VALUE statkey 0 1\r\nx\r\nEND\r\n");
    }

    @Test void commandLineLimitHasExactBoundary() throws Exception {
        start("protocol.maxLineBytes", "16");
        expect("get " + "k".repeat(12) + "\r\n", "END\r\n");
        expect("get " + "k".repeat(13) + "\r\n", "CLIENT_ERROR bad command line format\r\n");
    }

    @Test void largePayloadStreamsAcrossSmallPendingWindow() throws Exception {
        start("protocol.maxChunkBytes", "17", "connections.pendingWriteBytes", "19");
        String body = "0123456789abcdef".repeat(2048);
        expect("set large 2 0 " + body.length() + "\r\n" + body + "\r\nget large large\r\ndelete large\r\n",
                "STORED\r\nVALUE large 2 " + body.length() + "\r\n" + body + "\r\nEND\r\nDELETED\r\n");
    }

    @Test void closeUnblocksAwaitTerminationAndReleasesBoundPort() throws Exception {
        start();
        InetSocketAddress address = server.localAddress();
        java.util.concurrent.FutureTask<Void> awaited = new java.util.concurrent.FutureTask<>(() -> {
            server.awaitTermination();
            return null;
        });
        Thread waiter = Thread.ofPlatform().daemon().start(awaited);
        try {
            server.close();
            awaited.get(3, java.util.concurrent.TimeUnit.SECONDS);
            waiter.join(3000);
            assertFalse(waiter.isAlive());
            try (java.net.ServerSocket rebound = new java.net.ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(address);
            }
        } finally {
            waiter.interrupt();
            waiter.join(3000);
        }
    }

    @Test void availableSlabCountersDescribeActualAllocatorWithoutClaimingFullProfile() throws Exception {
        start();
        expect("set slabkey 0 0 1\r\nx\r\n", "STORED\r\n");
        Map<String, String> values = stats("stats slabs");
        assertFalse(values.isEmpty());
        long occupiedBytes = 0;
        long usedChunks = 0;
        for (String field : values.keySet()) {
            if (!field.endsWith(":chunk_size")) continue;
            String prefix = field.substring(0, field.indexOf(':') + 1);
            long chunk = Long.parseLong(values.get(field));
            long used = Long.parseLong(values.get(prefix + "used_chunks"));
            long free = Long.parseLong(values.get(prefix + "free_chunks"));
            long total = Long.parseLong(values.get(prefix + "total_chunks"));
            assertTrue(chunk > 0);
            assertEquals(total, used + free);
            occupiedBytes += used * chunk;
            usedChunks += used;
        }
        assertEquals(1, usedChunks);
        assertEquals(server.resourceUsage().nativeUsedBytes(), occupiedBytes);
        expect("delete slabkey\r\n", "DELETED\r\n");
        assertEquals(0, server.resourceUsage().nativeUsedBytes());
    }

    @Test void quitClosesWithoutExecutingTrailingCommand() throws Exception {
        start();
        send("quit\r\nset forbidden 0 0 1\r\nx\r\n");
        assertEquals(-1, socket.getInputStream().read());
        socket.close();
        socket = new Socket();
        socket.connect(server.localAddress(), 2000);
        socket.setSoTimeout(3000);
        expect("get forbidden\r\n", "END\r\n");
    }

    @Test void malformedBodyCannotExecuteTrailingStorage() throws Exception {
        start();
        send("set broken 0 0 1\r\nx!!set forbidden 0 0 1\r\ny\r\n");
        byte[] result = socket.getInputStream().readNBytes(100);
        String text = new String(result, StandardCharsets.ISO_8859_1);
        assertTrue(text.isEmpty() || text.equals("CLIENT_ERROR bad data chunk\r\n"), text);
        socket.close();
        socket = new Socket();
        socket.connect(server.localAddress(), 2000);
        socket.setSoTimeout(3000);
        expect("get broken forbidden\r\n", "END\r\n");
    }

    private static final class TestClock implements Clock {
        volatile long seconds = 1_700_000_000L;
        @Override public long currentTimeSeconds() { return seconds; }
        @Override public long nanoTime() { return seconds * 1_000_000_000L; }
    }
}
