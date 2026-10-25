package ru.octomem.server;

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
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.util.Clock;

import static org.junit.jupiter.api.Assertions.*;

/** Independent literal TCP regressions, with immutable per-invocation transcripts. */
@Timeout(20)
class CompatibilityRecheckWireTest {
    private static final long NOW = 1_700_000_000L;
    private static final String VERSION = "VERSION 1.0.0 (octomem)\r\n";
    private static final String STORAGE_OOM = "SERVER_ERROR out of memory storing object\r\n";
    private final TestClock clock = new TestClock();
    private CacheServer server;
    private Socket socket;
    private Path transcript;

    @BeforeEach
    void evidence(TestInfo info) throws IOException {
        Path root = Path.of(System.getProperty("recheck.evidence",
                "tests/reports/compatibility-recheck-20261025"), "wire");
        Files.createDirectories(root);
        transcript = root.resolve(info.getTestMethod().orElseThrow().getName() + "-" + UUID.randomUUID() + ".txt");
        Files.writeString(transcript, "CASE " + info.getDisplayName() + "\n", StandardOpenOption.CREATE_NEW);
    }

    @AfterEach
    void close() throws Exception {
        try { if (socket != null) socket.close(); }
        finally {
            if (server != null) {
                server.close();
                var usage = server.resourceUsage();
                assertAll(() -> assertEquals(0, usage.nativeUsedBytes()),
                        () -> assertEquals(0, usage.nativeTotalBytes()),
                        () -> assertEquals(0, usage.bodyBytes()),
                        () -> assertEquals(0, usage.snapshotBytes()),
                        () -> assertEquals(0, usage.serviceBytes()),
                        () -> assertEquals(0, usage.controlBytes()),
                        () -> assertEquals(0, usage.lineBytes()));
            }
        }
    }

    static Stream<Arguments> ttlCases() {
        return Stream.of("touch", "gat", "gats").flatMap(op -> Stream.of(0L, 30L, NOW + 30, -1L)
                .map(ttl -> Arguments.of(op, ttl)));
    }

    @ParameterizedTest(name = "{0} ttl={1} keeps original token")
    @MethodSource("ttlCases")
    void ttlOnlyCasRoundTrip(String operation, long ttl) throws Exception {
        start();
        expect("set k 7 5 3\r\nold\r\n", "STORED\r\n");
        String token = gets("k", "old", 7);
        String returned = token;
        if (operation.equals("touch")) expect("touch k " + ttl + "\r\n", "TOUCHED\r\n");
        else {
            send(operation + " " + ttl + " k\r\n");
            returned = retrieval("k", "old", 7, operation.equals("gats"));
        }
        if (ttl < 0) {
            expect("get k\r\ncas k 0 0 3 " + token + "\r\nnew\r\nversion\r\n", "END\r\nNOT_FOUND\r\n" + VERSION);
            if (operation.equals("gats")) assertEquals(token, returned);
        } else {
            String after = gets("k", "old", 7);
            send("cas k 9 0 3 " + token + "\r\nnew\r\nversion\r\n");
            String cas = line();
            assertEquals(VERSION, line());
            String fromUpdate = returned;
            assertAll(() -> assertEquals(token, after, "gets after TTL-only update"),
                    () -> { if (operation.equals("gats")) assertEquals(token, fromUpdate); },
                    () -> assertEquals("STORED\r\n", cas, "CAS from before touch must succeed"));
            assertNotEquals(token, gets("k", "new", 9));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gat", "gats"})
    void noreplyIsAnOrdinaryRetrievalKey(String operation) throws Exception {
        start();
        expect("set noreply 7 0 3\r\nold\r\n", "STORED\r\n");
        String token = gets("noreply", "old", 7);
        send(operation + " 0 noreply\r\nversion\r\n");
        String returned = retrieval("noreply", "old", 7, operation.equals("gats"));
        assertEquals(VERSION, line());
        if (operation.equals("gats")) assertEquals(token, returned);
    }

    @Test
    void touchNoreplyPreservesCasAndASecondConnectionWriteInvalidatesIt() throws Exception {
        start();
        expect("set k 7 0 3\r\nold\r\n", "STORED\r\n");
        String token = gets("k", "old", 7);
        expect("touch k 30 noreply\r\ntouch absent 0 noreply\r\nversion\r\n", VERSION);
        String touched = gets("k", "old", 7);
        try (Socket writer = new Socket()) {
            writer.connect(server.localAddress(), 2000);
            writer.setSoTimeout(3000);
            writer.getOutputStream().write(bytes("set k 9 0 3\r\nnew\r\n"));
            assertArrayEquals(bytes("STORED\r\n"), writer.getInputStream().readNBytes(8));
        }
        expect("cas k 0 0 3 " + token + "\r\nbad\r\nget k\r\n", "EXISTS\r\nVALUE k 9 3\r\nnew\r\nEND\r\n");
        assertEquals(token, touched);
    }

    static Stream<Arguments> numericErrors() {
        Stream.Builder<Arguments> result = Stream.builder();
        for (String bad : new String[]{"x", "18446744073709551616", "-1"}) {
            for (String op : new String[]{"incr", "decr"}) {
                for (boolean quiet : new boolean[]{false, true}) result.add(Arguments.of(op + " k " + bad,
                        "CLIENT_ERROR invalid numeric delta argument\r\n", quiet));
            }
        }
        for (String bad : new String[]{"x", "2147483648", "-2147483649"}) {
            for (String op : new String[]{"touch", "flush_all", "gat", "gats"}) {
                String command = op.equals("touch") ? "touch k " + bad : op.equals("flush_all")
                        ? "flush_all " + bad : op + " " + bad + " k";
                result.add(Arguments.of(command, "CLIENT_ERROR invalid exptime argument\r\n", false));
                if (op.equals("touch") || op.equals("flush_all")) {
                    result.add(Arguments.of(command, "CLIENT_ERROR invalid exptime argument\r\n", true));
                }
            }
        }
        return result.build();
    }

    @ParameterizedTest(name = "{0} noreply={2}")
    @MethodSource("numericErrors")
    void commandSpecificNumericErrorsAndNoreply(String command, String error, boolean quiet) throws Exception {
        start();
        expect("set k 7 0 1\r\n9\r\n", "STORED\r\n");
        String token = gets("k", "9", 7);
        expect(command + (quiet ? " noreply" : "") + "\r\nversion\r\n", (quiet ? "" : error) + VERSION);
        assertEquals(token, gets("k", "9", 7));
    }

    static Stream<Arguments> oversizedStores() {
        return Stream.of("set", "add", "replace", "append", "prepend", "cas")
                .flatMap(op -> Stream.of(false, true).map(quiet -> Arguments.of(op, quiet)));
    }

    @ParameterizedTest
    @MethodSource("oversizedStores")
    void oversizeInvalidatesOnlySetAndDrainsBody(String operation, boolean quiet) throws Exception {
        start("item.maxSize", "128");
        expect("set k 7 0 3\r\nold\r\n", "STORED\r\n");
        String token = gets("k", "old", 7);
        // Embedded command bytes must be drained as data, never executed.
        String body = "delete k\r\n" + "x".repeat(120);
        String command = operation + " k 0 0 " + body.length() + (operation.equals("cas") ? " " + token : "")
                + (quiet ? " noreply" : "") + "\r\n" + body + "\r\n";
        expect(command + "version\r\n", (quiet ? "" : "SERVER_ERROR object too large for cache\r\n") + VERSION);
        if (operation.equals("set")) expect("get k\r\nversion\r\n", "END\r\n" + VERSION);
        else assertEquals(token, gets("k", "old", 7));
    }

    static Stream<Arguments> pressureOperations() {
        return Stream.of("set", "replace", "append", "prepend", "cas", "incr", "decr")
                .flatMap(op -> Stream.of(false, true).map(quiet -> Arguments.of(op, quiet)));
    }

    @ParameterizedTest
    @MethodSource("pressureOperations")
    void realNativePressureHasOperationSpecificErrors(String operation, boolean quiet) throws Exception {
        // Only the target owns the entire native page; LRU cannot evict the target
        // to prepare its immutable replacement. No unsupported config or mock is used.
        start("memory.limit", "32768", "item.maxSize", "32768");
        String original = "0".repeat(31999) + "9";
        expect("set k 7 0 32000\r\n" + original + "\r\n", "STORED\r\n");
        String token = gets("k", original, 7);
        assertEquals(32768, server.resourceUsage().nativeTotalBytes());
        assertTrue(server.resourceUsage().nativeUsedBytes() >= 31744, "native, not just metadata exhaustion");
        boolean counter = operation.equals("incr") || operation.equals("decr");
        String command;
        if (counter) command = operation + " k 1" + (quiet ? " noreply" : "") + "\r\n";
        else {
            String body = operation.equals("append") || operation.equals("prepend") ? "1" : original;
            command = operation + " k 0 0 " + body.length() + (operation.equals("cas") ? " " + token : "")
                    + (quiet ? " noreply" : "") + "\r\n" + body + "\r\n";
        }
        expect(command + "version\r\n", (quiet ? "" : counter ? "SERVER_ERROR out of memory\r\n" : STORAGE_OOM) + VERSION);
        if (operation.equals("set")) expect("get k\r\nversion\r\n", "END\r\n" + VERSION);
        else assertEquals(token, gets("k", original, 7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x 0", "0 x", "4294967296 0", "0 -", "0 +",
            "0 9223372036854775808", "0 -9223372036854775809", "0 999999999999999999999"})
    void invalidSetHeaderRetainsOldValue(String fields) throws Exception {
        start();
        expect("set k 7 0 3\r\nold\r\n", "STORED\r\n");
        String token = gets("k", "old", 7);
        send("set k " + fields + " 3\r\nnew\r\n");
        assertEquals("CLIENT_ERROR bad command line format\r\n", line());
        // Malformed framing may close this connection; independent connection checks state.
        socket.close();
        connect();
        assertEquals(token, gets("k", "old", 7));
    }

    @Test
    void badBodyRetainsOldValue() throws Exception {
        start();
        expect("set k 7 0 3\r\nold\r\n", "STORED\r\n");
        String token = gets("k", "old", 7);
        send("set k 0 0 3\r\nnewXX");
        assertEquals("CLIENT_ERROR bad data chunk\r\n", line());
        socket.close();
        connect();
        assertEquals(token, gets("k", "old", 7));
    }

    static Stream<Arguments> combinedFailures() {
        return Stream.of("append", "prepend").flatMap(op -> Stream.of(false, true)
                .flatMap(size -> Stream.of(false, true).map(quiet -> Arguments.of(op, size, quiet))));
    }

    @ParameterizedTest
    @MethodSource("combinedFailures")
    void admittedFragmentCombinedFailureIsNotStored(String operation, boolean oversized, boolean quiet) throws Exception {
        start("memory.limit", "65536", "item.maxSize", oversized ? "32768" : "65536");
        String original = "o".repeat(30000);
        expect("set k 7 30 30000\r\n" + original + "\r\n", "STORED\r\n");
        String token = gets("k", original, 7);
        expect(operation + " k 0 0 30000" + (quiet ? " noreply" : "") + "\r\n" + original
                + "\r\nversion\r\n", (quiet ? "" : "NOT_STORED\r\n") + VERSION);
        assertEquals(token, gets("k", original, 7));
    }

    @ParameterizedTest
    @ValueSource(longs = {-2147483648L, 2147483647L})
    void signed32ExptimeEndpointsAreAccepted(long ttl) throws Exception {
        start();
        expect("set k 7 0 3\r\nold\r\ntouch k " + ttl + "\r\nversion\r\n", "STORED\r\nTOUCHED\r\n" + VERSION);
        if (ttl < 0) expect("get k\r\n", "END\r\n");
        else gets("k", "old", 7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"incr k x wrong", "decr k x noreply extra", "touch k +",
            "touch k 9223372036854775808", "touch k -9223372036854775809",
            "flush_all x wrong", "flush_all x noreply extra"})
    void malformedNumericSuffixNeverSuppressesItsSpecificError(String command) throws Exception {
        start();
        String error = command.startsWith("incr") || command.startsWith("decr")
                ? "CLIENT_ERROR invalid numeric delta argument\r\n" : "CLIENT_ERROR invalid exptime argument\r\n";
        expect(command + "\r\nversion\r\n", error + VERSION);
    }

    static Stream<Arguments> lexicalErrors() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String value : new String[]{"+0", "+1", "000000000000"}) {
            for (String op : new String[]{"touch", "gat", "gats", "flush_all"}) {
                String command = op.equals("touch") ? "touch k " + value
                        : op + " " + value + (op.startsWith("ga") ? " k" : "");
                cases.add(Arguments.of(command, false, "invalid exptime argument"));
                if (op.equals("touch") || op.equals("flush_all")) cases.add(Arguments.of(command, true, "invalid exptime argument"));
            }
        }
        for (String op : new String[]{"incr", "decr"}) {
            for (boolean quiet : new boolean[]{false, true}) cases.add(Arguments.of(op + " k " + "0".repeat(23), quiet, "invalid numeric delta argument"));
        }
        return cases.build();
    }

    @ParameterizedTest
    @MethodSource("lexicalErrors")
    void referenceLexicalLimitsRejectWithoutMutation(String command, boolean quiet, String error) throws Exception {
        start();
        expect("set k 7 2 1\r\n9\r\n", "STORED\r\n");
        String token = gets("k", "9", 7);
        expect(command + (quiet ? " noreply" : "") + "\r\nversion\r\n",
                (quiet ? "" : "CLIENT_ERROR " + error + "\r\n") + VERSION);
        assertEquals(token, gets("k", "9", 7));
        clock.seconds += 2;
        expect("get k\r\n", "END\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"touch", "gat", "gats", "flush_all"})
    void bareMinusExpiryUsesReferenceZeroQuirk(String op) throws Exception {
        start();
        expect("set k 7 2 1\r\n9\r\n", "STORED\r\n");
        String token = gets("k", "9", 7);
        if (op.equals("touch")) expect("touch k -\r\nversion\r\n", "TOUCHED\r\n" + VERSION);
        else if (op.equals("flush_all")) expect("flush_all -\r\nget k\r\n", "OK\r\nEND\r\n");
        else {
            send(op + " - k\r\n");
            String returned = retrieval("k", "9", 7, op.equals("gats"));
            if (op.equals("gats")) assertEquals(token, returned);
        }
        clock.seconds += 3;
        if (!op.equals("flush_all")) assertEquals(token, gets("k", "9", 7));
    }

    static Stream<Arguments> invalidCasTokens() {
        return Stream.of("0".repeat(23), "x", "18446744073709551616", "+1", "-1")
                .flatMap(token -> Stream.of(false, true).map(quiet -> Arguments.of(token, quiet)));
    }

    @ParameterizedTest
    @MethodSource("invalidCasTokens")
    void overlongCasRejectedBeforeStorage(String invalid, boolean quiet) throws Exception {
        start();
        expect("set k 7 0 1\r\n9\r\n", "STORED\r\n");
        String token = gets("k", "9", 7);
        expect("cas k 0 0 1 " + invalid + (quiet ? " noreply" : "") + "\r\nx\r\nversion\r\n",
                (quiet ? "" : "CLIENT_ERROR bad command line format\r\n") + VERSION);
        assertEquals(token, gets("k", "9", 7));
    }

    @Test
    void lexicalLengthBoundariesRemainAccepted() throws Exception {
        start();
        expect("set k 7 2 1\r\n9\r\n", "STORED\r\n");
        String token = gets("k", "9", 7);
        expect("touch k " + "0".repeat(11) + "\r\nversion\r\n", "TOUCHED\r\n" + VERSION);
        assertEquals(token, gets("k", "9", 7));
        expect("incr k " + "0".repeat(22) + "\r\ndecr k " + "0".repeat(22) + "\r\n", "9\r\n9\r\n");
        String current = gets("k", "9", 7);
        String padded = "0".repeat(22 - current.length()) + current;
        expect("cas k 8 0 1 " + padded + "\r\nx\r\nversion\r\n", "STORED\r\n" + VERSION);
        gets("k", "x", 8);
    }

    static Stream<Arguments> storageNarrowing() {
        return Stream.of(4294967296L, 2147483648L, -2147483649L, Long.MIN_VALUE, Long.MAX_VALUE)
                .flatMap(ttl -> Stream.of(false, true).map(quiet -> Arguments.of(ttl, quiet)));
    }

    @ParameterizedTest
    @MethodSource("storageNarrowing")
    void storageExpiryMatchesPinnedLp64Narrowing(long ttl, boolean quiet) throws Exception {
        start();
        expect("set k 7 " + ttl + " 1" + (quiet ? " noreply" : "") + "\r\nx\r\nversion\r\n",
                (quiet ? "" : "STORED\r\n") + VERSION);
        if ((int) ttl < 0) expect("get k\r\n", "END\r\n");
        else gets("k", "x", 7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"touch", "touch k", "touch k 0 wrong", "touch k 0 noreply extra",
            "version extra", "delete k -", "delete k 9223372036854775808"})
    void malformedCommandStructureRemainsAnExplicitError(String command) throws Exception {
        start();
        expect(command + "\r\n", "CLIENT_ERROR bad command line format\r\n");
    }

    @Test
    void overlongSingleKeyDoesNotBecomeNumericError() throws Exception {
        start();
        expect("touch " + "k".repeat(251) + " 0\r\n", "CLIENT_ERROR bad command line format\r\n");
        expect("stats bogus\r\nversion\r\n", "ERROR\r\n" + VERSION);
    }

    @Test
    void rejectedBodyDrainCannotDeleteASecondConnectionReplacement() throws Exception {
        start("item.maxSize", "128");
        expect("set k 7 0 3\r\nold\r\n", "STORED\r\n");
        Socket rejected = socket;
        send("set k 0 0 130\r\n");
        connect();
        try {
            boolean absent = false;
            long deadline = System.nanoTime() + 2_000_000_000L;
            // Retrieval responses are the phase barrier, not timing guesses.
            for (int attempts = 0; attempts < 100 && System.nanoTime() < deadline; attempts++) {
                send("get k\r\n");
                String response = line();
                if (response.equals("END\r\n")) { absent = true; break; }
                assertEquals("VALUE k 7 3\r\n", response);
                assertEquals("old\r\n", line());
                assertEquals("END\r\n", line());
            }
            assertTrue(absent, "SET must invalidate at header rejection before body drain");
            expect("set k 9 0 3\r\nnew\r\n", "STORED\r\n");
            Socket observer = socket;
            socket = rejected;
            try {
                expect("x".repeat(130) + "\r\nversion\r\n", "SERVER_ERROR object too large for cache\r\n" + VERSION);
            } finally { socket = observer; }
            gets("k", "new", 9);
        } finally { rejected.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "malformed", "disconnect"})
    void nativeRejectedUploadCannotDeleteNewWriterAcrossTwoWorkers(String ending) throws Exception {
        start("transport.workers", "2", "memory.limit", "32768", "item.maxSize", "32768");
        expect("set k 7 0 32000\r\n" + "o".repeat(32000) + "\r\n", "STORED\r\n");
        Socket rejected = socket;
        send("set k 0 0 32000\r\n");
        connect();
        try {
            boolean absent = false;
            long deadline = System.nanoTime() + 2_000_000_000L;
            for (int attempt = 0; attempt < 100 && System.nanoTime() < deadline; attempt++) {
                send("get k\r\n");
                String header = line();
                if (header.equals("END\r\n")) { absent = true; break; }
                assertEquals("VALUE k 7 32000\r\n", header);
                assertEquals("o".repeat(32000) + "\r\n", line());
                assertEquals("END\r\n", line());
            }
            assertTrue(absent, "native admission failure must invalidate before upload resumes");
            expect("set k 9 0 3\r\nnew\r\n", "STORED\r\n");
            String replacement = gets("k", "new", 9);
            Socket observer = socket;
            socket = rejected;
            try {
                if (ending.equals("disconnect")) {
                    send("partial");
                    rejected.close();
                } else if (ending.equals("complete")) {
                    expect("x".repeat(32000) + "\r\nversion\r\n", STORAGE_OOM + VERSION);
                } else {
                    send("x".repeat(32000) + "XX");
                    // Malformed rejected-body framing is unrecoverable: error then close or immediate close.
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int next;
                    while ((next = rejected.getInputStream().read()) != -1) {
                        bytes.write(next);
                        assertTrue(bytes.size() <= 128, "bounded framing failure output");
                    }
                    String reply = bytes.toString(StandardCharsets.US_ASCII);
                    assertTrue(reply.isEmpty() || reply.equals("CLIENT_ERROR bad data chunk\r\n"), reply);
                }
            } finally { socket = observer; }
            assertEquals(replacement, gets("k", "new", 9));
        } finally { rejected.close(); }
    }

    private void start(String... overrides) throws Exception {
        Map<String, String> config = new HashMap<>(Map.of("transport.workers", "1", "memory.limit", "4194304",
                "item.maxSize", "4096", "connections.max", "8", "expiry.reclaimIntervalSeconds", "86400"));
        for (int i = 0; i < overrides.length; i += 2) config.put(overrides[i], overrides[i + 1]);
        Files.writeString(transcript, "CONFIG " + config + "\n", StandardOpenOption.APPEND);
        server = new CacheServer(ConfigLoader.load(null, config), new InetSocketAddress("127.0.0.1", 0), clock);
        server.start();
        connect();
    }

    private void connect() throws IOException {
        socket = new Socket();
        socket.connect(server.localAddress(), 2000);
        socket.setSoTimeout(3000);
    }

    private String gets(String key, String value, int flags) throws IOException {
        send("gets " + key + "\r\n");
        return retrieval(key, value, flags, true);
    }

    private String retrieval(String key, String value, int flags, boolean cas) throws IOException {
        String header = line();
        String prefix = "VALUE " + key + " " + flags + " " + value.length();
        String token = "";
        if (cas) {
            assertTrue(header.startsWith(prefix + " ") && header.endsWith("\r\n"), header);
            token = header.substring(prefix.length() + 1, header.length() - 2);
            Long.parseUnsignedLong(token);
        } else assertEquals(prefix + "\r\n", header);
        assertEquals(value + "\r\n", line());
        assertEquals("END\r\n", line());
        return token;
    }

    private void send(String command) throws IOException {
        record("INPUT", command);
        socket.getOutputStream().write(bytes(command));
        socket.getOutputStream().flush();
    }

    private void expect(String command, String expected) throws IOException {
        record("EXPECTED", expected);
        send(command);
        // Read complete lines through a known sentinel, not expected-length reads that can mask extra errors.
        StringBuilder actual = new StringBuilder();
        int count = (int) expected.chars().filter(c -> c == '\n').count();
        for (int i = 0; i < count; i++) actual.append(line());
        assertEquals(expected, actual.toString(), command.substring(0, Math.min(command.length(), 80)));
    }

    private String line() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < 65536) {
            int next = socket.getInputStream().read();
            assertNotEquals(-1, next, "unexpected EOF");
            out.write(next);
            if (next == '\n') {
                String line = out.toString(StandardCharsets.US_ASCII);
                record("ACTUAL", line);
                return line;
            }
        }
        throw new AssertionError("unbounded line");
    }

    private void record(String kind, String data) throws IOException {
        Files.writeString(transcript, kind + " " + java.util.HexFormat.of().formatHex(bytes(data)) + "\n", StandardOpenOption.APPEND);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    private static final class TestClock implements Clock {
        volatile long seconds = NOW;
        public long currentTimeSeconds() { return seconds; }
        public long nanoTime() { return System.nanoTime(); }
    }
}
