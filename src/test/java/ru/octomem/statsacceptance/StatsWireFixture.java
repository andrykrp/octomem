package ru.octomem.statsacceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.engine.StoreResult;
import ru.octomem.transport.ConnectionFactory;
import ru.octomem.transport.NioTransport;
import ru.octomem.transport.TextConnectionFactory;
import ru.octomem.util.Clock;

/** Independent bounded wire harness; expected values never come from production snapshots. */
abstract class StatsWireFixture {
    static final Set<String> GENERAL = Set.of(("pid uptime time version pointer_size threads curr_connections "
            + "total_connections connection_structures conn_yields cmd_get cmd_set cmd_flush cmd_touch "
            + "get_hits get_misses delete_hits delete_misses incr_hits incr_misses decr_hits decr_misses "
            + "cas_hits cas_misses cas_badval touch_hits touch_misses curr_items total_items bytes reclaimed "
            + "expired_unfetched evicted_unfetched limit_maxbytes evictions bytes_read bytes_written").split(" "));
    static final Set<String> ITEMS = Set.of(("number age evicted evicted_nonzero evicted_time outofmemory "
            + "tailrepairs reclaimed expired_unfetched evicted_unfetched crawler_reclaimed").split(" "));
    static final Set<String> SLABS = Set.of(("chunk_size chunks_per_page total_pages total_chunks used_chunks "
            + "free_chunks free_chunks_end get_hits cmd_set delete_hits incr_hits decr_hits cas_hits cas_badval mem_requested").split(" "));
    static final Set<String> RESET_GENERAL = Set.of(("total_connections conn_yields cmd_get cmd_set cmd_flush cmd_touch "
            + "get_hits get_misses delete_hits delete_misses incr_hits incr_misses decr_hits decr_misses cas_hits "
            + "cas_misses cas_badval touch_hits touch_misses total_items reclaimed expired_unfetched evicted_unfetched evictions").split(" "));
    static final String EVIDENCE_DIR_PROPERTY = "octomem.t013.wireEvidenceDir";
    static final String DEFAULT_EVIDENCE_DIR = "target/t013-wire-evidence";
    /**
     * Frozen oracle inputs, read from the test classpath so a clean release source tree needs no
     * repository reports. Each resource identity and digest is recorded in the per-case preamble.
     */
    static final List<String> ORACLE_RESOURCES = List.of(
            "/t013/compatibility-manifest.json", "/t013/contract.md", "/t013/coordinator-decisions.md");
    final MutableClock clock = new MutableClock();
    CacheServer server;
    Wire wire;
    private final List<Wire> clients = new ArrayList<>();
    Path evidence;

    @BeforeEach void prepareEvidence(TestInfo info) throws Exception {
        assertEquals(25, Runtime.version().feature(), "Acceptance requires JDK 25");
        Path dir = Path.of(System.getProperty(EVIDENCE_DIR_PROPERTY, DEFAULT_EVIDENCE_DIR));
        Files.createDirectories(dir);
        String caseId = info.getTestMethod().orElseThrow().getName() + "-"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(info.getDisplayName().getBytes(StandardCharsets.UTF_8)), 0, 6);
        evidence = dir.resolve(caseId + ".wire.txt");
        Files.writeString(evidence, "CASE " + info.getDisplayName() + "\nJDK " + Runtime.version()
                + "\nOS " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "\nMAX_HEAP " + Runtime.getRuntime().maxMemory()
                + "\nJVM_ARGS " + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        for (String resource : ORACLE_RESOURCES) {
            byte[] content = oracle(resource);
            Files.writeString(evidence, "ORACLE classpath:" + resource + " "
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)) + "\n",
                    StandardOpenOption.APPEND);
        }
        for (String root : List.of("src/main/java", "src/test/java/ru/octomem/statsacceptance")) {
            try (var paths = Files.walk(Path.of(root))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                    Files.writeString(evidence, "SOURCE " + path + " " + HexFormat.of().formatHex(hash) + "\n",
                            StandardOpenOption.APPEND);
                }
            }
        }
    }

    /** Reads a frozen oracle input from the test classpath; never from repository report paths. */
    private static byte[] oracle(String resource) throws IOException {
        try (InputStream input = StatsWireFixture.class.getResourceAsStream(resource)) {
            assertNotNull(input, "Missing classpath oracle resource " + resource);
            return input.readAllBytes();
        }
    }

    void start(String... overrides) throws Exception {
        Map<String, String> config = new HashMap<>(Map.of("transport.workers", "2", "connections.max", "16",
                "memory.limit", "8388608", "item.maxSize", "1048576", "expiry.reclaimIntervalSeconds", "3600"));
        for (int i = 0; i < overrides.length; i += 2) config.put(overrides[i], overrides[i + 1]);
        server = new CacheServer(ConfigLoader.load(null, config), new InetSocketAddress("127.0.0.1", 0), clock);
        server.start();
        wire = connect();
    }

    Wire connect() throws IOException {
        Wire client = new Wire(server.localAddress(), evidence, clients.size());
        clients.add(client);
        return client;
    }

    /**
     * Installs a test-only observing admission barrier before {@code server.start()}.
     *
     * <p>This is a narrow reflection seam, needed because {@link CacheServer} exposes no
     * admission injection constructor or setter. The server composes an unstarted
      * {@link NioTransport} whose negotiated factory wraps a {@link TextConnectionFactory}
      * that owns the classic {@link StorageAdmission} shared by every connection and event loop.
     * The adapter is placed into that field before the transport threads are started; the
     * subsequent {@link Thread#start()} publication makes it visible to both loops.</p>
     *
     * <p>The adapter delegates every method to the real admission unchanged and only records
     * observations after the real call returns. It therefore never short-circuits, delays or
     * replaces admission, and it is only the readiness signal, never the source of the
     * asserted statistics. A missing or retyped seam field fails loudly below, so a refactor
     * cannot silently disable the barrier.</p>
     *
     * <p>The better long-term production injection point would be a first-class
     * {@code StorageAdmission} constructor parameter (or an equivalent package-private seam)
     * on {@link CacheServer}/{@link NioTransport}, allowing tests to observe admission without
     * reflection. Production is deliberately left unchanged by this test-side correction.</p>
     *
     * @param expected number of admission completions to wait for
     * @return the installed observer
     * @throws ReflectiveOperationException declared for the field reads; a missing or
     *         wrong-typed seam field raises {@link AssertionError} instead
     */
    AdmissionObserver observeAdmissions(int expected) throws ReflectiveOperationException {
        Field transportField = seamField(CacheServer.class, "transport", NioTransport.class);
        Field factoryField = seamField(NioTransport.class, "factory", ConnectionFactory.class);
        Field admissionField = seamField(TextConnectionFactory.class, "admission", StorageAdmission.class);
        NioTransport transport = (NioTransport) transportField.get(server);
        ConnectionFactory negotiated = (ConnectionFactory) factoryField.get(transport);
        Field classicField = seamField(ru.octomem.transport.MemcachedConnectionFactory.class,
                "classic", TextConnectionFactory.class);
        TextConnectionFactory factory = (TextConnectionFactory) classicField.get(negotiated);
        StorageAdmission real = (StorageAdmission) admissionField.get(factory);
        assertNotNull(real, "CacheServer admission is null; the readiness barrier cannot be installed");
        AdmissionObserver observer = new AdmissionObserver(real, expected);
        admissionField.set(factory, observer);
        return observer;
    }

    /**
     * Resolves one reflection seam field, failing loudly with the exact owner, field and type
     * so a production refactor cannot silently disable the admission barrier.
     */
    private static Field seamField(Class<?> owner, String name, Class<?> required) throws NoSuchFieldException {
        Field field;
        try {
            field = owner.getDeclaredField(name);
        } catch (NoSuchFieldException missing) {
            throw new AssertionError("Admission barrier seam field " + owner.getName() + "." + name
                    + " is missing; refusing to run with a silently disabled readiness barrier", missing);
        }
        if (field.getType() != required) {
            throw new AssertionError("Admission barrier seam field " + owner.getName() + "." + name
                    + " has type " + field.getType().getName() + ", expected " + required.getName());
        }
        field.setAccessible(true);
        return field;
    }

    /**
     * Test-only observing wrapper. Every method delegates to the real admission first and
     * publishes observation only after the real call returns; the returned {@link Result}
     * (including its token) is the production value, unmodified.
     */
    static final class AdmissionObserver implements StorageAdmission {
        private final StorageAdmission delegate;
        private final CountDownLatch completed;
        private final CountDownLatch denied = new CountDownLatch(1);
        private final List<String> keys = new CopyOnWriteArrayList<>();
        private final List<Result> results = new CopyOnWriteArrayList<>();

        AdmissionObserver(StorageAdmission delegate, int expected) {
            this.delegate = delegate;
            completed = new CountDownLatch(expected);
        }

        @Override public Result admit(Mode mode, byte[] key, int flags, long exptime, long cas, int valueLength) {
            Result result = delegate.admit(mode, key, flags, exptime, cas, valueLength);
            keys.add(new String(key, StandardCharsets.US_ASCII));
            results.add(result);
            completed.countDown();
            return result;
        }

        void awaitAdmissions() throws InterruptedException {
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Native admission calls did not all return");
        }

        void assertHeld(Set<String> expectedKeys) {
            assertEquals(expectedKeys.size(), keys.size(), "Lost or duplicated admission");
            assertEquals(expectedKeys, Set.copyOf(keys), "Unexpected or duplicate admission key");
            assertEquals(expectedKeys.size(), results.size(), "Missing admission result");
            Set<Token> tokens = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Result result : results) {
                assertEquals(StoreResult.STORED, result.status(), "Native admission was denied");
                assertNotNull(result.token(), "Native admission returned no token");
                assertTrue(tokens.add(result.token()), "Native admission reused a live token");
            }
        }

        void awaitDenial() throws InterruptedException {
            assertTrue(denied.await(3, TimeUnit.SECONDS), "Ingress capacity rejection did not return");
        }

        @Override public StoreResult storePrepared(Token token, Mode mode, byte[] key, byte[] value,
                int flags, long exptime, long cas) {
            return delegate.storePrepared(token, mode, key, value, flags, exptime, cas);
        }

        @Override public void malformedFlush() { delegate.malformedFlush(); }

        @Override public void capacityDenied() {
            delegate.capacityDenied();
            denied.countDown();
        }
    }

    @AfterEach void stop() throws Exception {
        try {
            for (Wire client : clients) client.close();
        } finally {
            if (server != null) server.close();
        }
    }

    static long number(Map<String, String> values, String field) {
        String value = values.get(field);
        assertNotNull(value, "Missing mandatory field " + field);
        assertTrue(value.matches("[0-9]+"), field + " not unsigned decimal: " + value);
        return Long.parseLong(value);
    }

    static void count(Map<String, String> values, String field, long expected) {
        assertEquals(expected, number(values, field), field);
    }

    static Map<Integer, Map<String, String>> classes(Map<String, String> values, boolean items) {
        Map<Integer, Map<String, String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String[] parts = key.split(":");
            if (items) {
                assertEquals(3, parts.length, key);
                assertEquals("items", parts[0], key);
            } else if (parts.length == 1) {
                return;
            } else {
                assertEquals(2, parts.length, key);
            }
            int id = Integer.parseInt(parts[items ? 1 : 0]);
            assertTrue(id > 0, "Wire classes are allocator IDs + 1");
            result.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put(parts[items ? 2 : 1], value);
        });
        return result;
    }

    static int classFor(Map<Integer, Map<String, String>> slabs, long requested) {
        return slabs.entrySet().stream().filter(e -> number(e.getValue(), "chunk_size") >= requested)
                .min(java.util.Comparator.comparingLong(e -> number(e.getValue(), "chunk_size")))
                .orElseThrow().getKey();
    }

    /** Test-owned clock, with distinct monotonic and epoch values. */
    static final class MutableClock implements Clock {
        volatile long seconds = 1_700_000_000L;
        volatile long nanos = 1_000_000_000L;
        @Override public long currentTimeSeconds() { return seconds; }
        @Override public long nanoTime() { return nanos; }
        void advance(long delta) { seconds += delta; nanos += delta * 1_000_000_000L; }
    }

    /** Bounded raw socket client with byte-exact hex evidence and byte accounting. */
    static final class Wire implements AutoCloseable {
        final Socket socket = new Socket();
        final Path evidence;
        final int id;
        long sent;
        long received;
        Wire(InetSocketAddress address, Path evidence, int id) throws IOException {
            this.evidence = evidence;
            this.id = id;
            socket.connect(address, 2000);
            socket.setSoTimeout(3000);
        }
        void record(String direction, byte[] bytes) throws IOException {
            synchronized (StatsWireFixture.class) {
                Files.writeString(evidence, id + " " + direction + " " + HexFormat.of().formatHex(bytes) + "\n", StandardOpenOption.APPEND);
            }
        }
        void send(String text) throws IOException {
            byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
            record("SEND", data);
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
            sent += data.length;
        }
        String line() throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            for (int i = 0; i < 8192; i++) {
                int value = socket.getInputStream().read();
                assertNotEquals(-1, value, "Unexpected EOF");
                result.write(value);
                received++;
                if (value == '\n') {
                    byte[] bytes = result.toByteArray();
                    record("RECV", bytes);
                    String text = result.toString(StandardCharsets.ISO_8859_1);
                    assertTrue(text.endsWith("\r\n"), "Non-CRLF response");
                    return text;
                }
            }
            throw new AssertionError("Unbounded response line");
        }
        void expect(String request, String response) throws IOException {
            send(request);
            byte[] expected = response.getBytes(StandardCharsets.ISO_8859_1);
            byte[] actual = socket.getInputStream().readNBytes(expected.length);
            received += actual.length;
            record("EXPECTED", expected);
            record("RECV", actual);
            assertArrayEquals(expected, actual);
        }
        Map<String, String> stats(String command) throws IOException {
            send(command + "\r\n");
            Map<String, String> result = new LinkedHashMap<>();
            for (int i = 0; i < 8192; i++) {
                String row = line();
                if (row.equals("END\r\n")) return result;
                assertTrue(row.startsWith("STAT "), row);
                String[] fields = row.substring(5, row.length() - 2).split(" ", 2);
                assertEquals(2, fields.length, row);
                assertNull(result.put(fields[0], fields[1]), "Duplicate field " + fields[0]);
            }
            throw new AssertionError("Unbounded statistics response");
        }
        String cas(String key, String value) throws IOException {
            send("gets " + key + "\r\n");
            String[] header = line().strip().split(" ");
            assertEquals(5, header.length);
            assertEquals("VALUE", header[0]);
            assertEquals(key, header[1]);
            assertEquals(value.length(), Integer.parseInt(header[3]));
            assertEquals(value + "\r\n", line());
            assertEquals("END\r\n", line());
            Long.parseUnsignedLong(header[4]);
            return header[4];
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
