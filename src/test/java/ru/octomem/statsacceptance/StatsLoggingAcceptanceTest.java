package ru.octomem.statsacceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.stats.SafeLogger;

/** Safe event/level expectations independently specified by coordinator Q10. */
@Timeout(20)
class StatsLoggingAcceptanceTest extends StatsWireFixture {
    private record Entry(SafeLogger.Level level, String text) { }

    private SafeLogger capture(String level, List<Entry> entries) {
        return new SafeLogger(level, (severity, text) -> {
            entries.add(new Entry(severity, text));
            try {
                synchronized (StatsLoggingAcceptanceTest.class) {
                    Files.writeString(evidence.resolveSibling(evidence.getFileName() + ".logs.txt"),
                            severity + " " + text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
    }

    private void startLogged(String level, SafeLogger logger) throws Exception {
        var config = ConfigLoader.load(null, Map.of("transport.workers", "1", "connections.max", "8",
                "memory.limit", "1048576", "item.maxSize", "1024", "log.level", level));
        server = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0), clock, logger);
        server.start();
        wire = connect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "warn", "info", "debug"})
    void a19NamedThresholdsFilterEventsAndNeverExposeCacheContent(String threshold) throws Exception {
        List<Entry> entries = new CopyOnWriteArrayList<>();
        startLogged(threshold, capture(threshold, entries));
        wire.expect("set SECRET_KEY_T013 0 0 18\r\nSECRET_VALUE_T013!\r\n"
                + "get SECRET_KEY_T013\r\n", "STORED\r\nVALUE SECRET_KEY_T013 0 18\r\nSECRET_VALUE_T013!\r\nEND\r\n");
        wire.expect("unknown SECRET_KEY_T013\r\n", "ERROR\r\n");
        server.close();
        int maximum = SafeLogger.Level.valueOf(threshold.toUpperCase(java.util.Locale.ROOT)).ordinal();
        assertTrue(entries.stream().allMatch(e -> e.level().ordinal() <= maximum));
        if (maximum >= SafeLogger.Level.INFO.ordinal()) {
            assertTrue(entries.stream().filter(e -> e.level() == SafeLogger.Level.INFO).count() >= 3,
                    "Startup, effective configuration and shutdown must all log");
        }
        if (threshold.equals("debug")) assertTrue(entries.stream().anyMatch(e -> e.level() == SafeLogger.Level.DEBUG));
        for (Entry entry : entries) {
            assertFalse(entry.text().contains("SECRET_KEY"));
            assertFalse(entry.text().contains("SECRET_VALUE"));
            assertFalse(entry.text().contains("\r"));
            assertFalse(entry.text().contains("\n"));
            assertTrue(entry.text().codePoints().anyMatch(c -> c >= 0x400 && c <= 0x4ff), "Human event must be Russian");
        }
    }

    @Test void a19VerbosityChangesOnlyThisServerAndPreservesWireReplies() throws Exception {
        List<Entry> entries = new CopyOnWriteArrayList<>();
        SafeLogger logger = capture("error", entries);
        SafeLogger independent = new SafeLogger("warn", (level, text) -> fail("Unrelated logger was touched"));
        startLogged("error", logger);
        wire.expect("verbosity 1\r\n", "OK\r\n");
        assertEquals(1, logger.verbosity());
        wire.expect("verbosity 99\r\nversion\r\n", "OK\r\nVERSION 1.0.0 (octomem)\r\n");
        assertEquals(2, logger.verbosity());
        wire.expect("verbosity 0 noreply\r\nversion\r\n", "VERSION 1.0.0 (octomem)\r\n");
        assertEquals(0, logger.verbosity());
        independent.log(SafeLogger.Event.STARTED);
    }

    @Test void a19ErrorCategoriesAndWarningsHaveRequiredSeverity() {
        List<Entry> entries = new CopyOnWriteArrayList<>();
        SafeLogger logger = capture("debug", entries);
        logger.log(SafeLogger.Event.STARTED);
        logger.configuration(1024, 128, 2);
        logger.log(SafeLogger.Event.STOPPED);
        logger.log(SafeLogger.Event.BROAD_BIND);
        logger.log(SafeLogger.Event.CAPACITY);
        logger.log(SafeLogger.Event.START_FAILURE);
        logger.log(SafeLogger.Event.INTERNAL_FAILURE);
        logger.log(SafeLogger.Event.TRANSPORT_FAILURE);
        logger.log(SafeLogger.Event.EXPIRY_FAILURE);
        assertEquals(List.of(SafeLogger.Level.INFO, SafeLogger.Level.INFO, SafeLogger.Level.INFO,
                SafeLogger.Level.WARN, SafeLogger.Level.WARN, SafeLogger.Level.ERROR, SafeLogger.Level.ERROR,
                SafeLogger.Level.ERROR, SafeLogger.Level.ERROR), entries.stream().map(Entry::level).toList());
    }

    @Test void a19DebugSummaryCannotSuppressFirstCapacityWarning() {
        List<Entry> entries = new CopyOnWriteArrayList<>();
        SafeLogger logger = capture("debug", entries);
        logger.log(SafeLogger.Event.COMMAND);
        logger.log(SafeLogger.Event.CAPACITY);
        assertTrue(entries.stream().anyMatch(e -> e.level() == SafeLogger.Level.WARN),
                "Rate limiting debug must not consume the required first capacity warning");
        for (int i = 0; i < 100; i++) logger.log(SafeLogger.Event.CAPACITY);
        assertTrue(entries.stream().filter(e -> e.level() == SafeLogger.Level.WARN).count() < 10,
                "Repeated pressure warnings must be bounded");
    }

    @Test void a20ThrowingSinkCannotStopServerOrMutateCache() throws Exception {
        SafeLogger broken = new SafeLogger("debug", (level, text) -> {
            throw new IllegalStateException("SECRET_EXCEPTION_T013\r\nSECRET_VALUE_T013");
        });
        startLogged("debug", broken);
        wire.expect("set a 0 0 1\r\nx\r\nget a\r\nstats reset\r\nget a\r\n",
                "STORED\r\nVALUE a 0 1\r\nx\r\nEND\r\nRESET\r\nVALUE a 0 1\r\nx\r\nEND\r\n");
        assertDoesNotThrow(server::close);
    }

    @Test void a19RealStartupBindFailureEmitsSafeError() throws Exception {
        List<Entry> entries = new CopyOnWriteArrayList<>();
        var config = ConfigLoader.load(null, Map.of("transport.workers", "1", "memory.limit", "1048576",
                "item.maxSize", "1024", "log.level", "error"));
        try (var occupied = new java.net.ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            server = new CacheServer(config, new InetSocketAddress("127.0.0.1", occupied.getLocalPort()),
                    clock, capture("error", entries));
            assertThrows(java.io.IOException.class, server::start);
            assertTrue(entries.stream().anyMatch(e -> e.level() == SafeLogger.Level.ERROR));
            assertTrue(entries.stream().allMatch(e -> e.level() == SafeLogger.Level.ERROR));
            assertTrue(entries.stream().noneMatch(e -> e.text().contains("BindException") || e.text().contains("at ru.")));
        }
    }

    @Test void a19RealCapacityPressureProducesBoundedWarningWithoutMarkers() throws Exception {
        List<Entry> entries = new CopyOnWriteArrayList<>();
        var config = ConfigLoader.load(null, Map.of("transport.workers", "1", "memory.limit", "1024",
                "item.maxSize", "1024", "log.level", "debug"));
        server = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0), clock, capture("debug", entries));
        server.start();
        wire = connect();
        String secret = "SECRET_CAPACITY_VALUE_".repeat(30);
        wire.expect("set SECRET_CAPACITY_KEY 0 0 " + secret.length() + "\r\n" + secret + "\r\n", "STORED\r\n");
        for (int i = 0; i < 10; i++) {
            wire.expect("set SECRET_CAPACITY_KEY 0 0 " + secret.length() + "\r\n" + secret + "\r\n",
                    "SERVER_ERROR out of memory storing object\r\n");
            // T-018 invalidates failed SET: restore the target before the next rejection.
            wire.expect("set SECRET_CAPACITY_KEY 0 0 " + secret.length() + "\r\n" + secret + "\r\n", "STORED\r\n");
        }
        long warnings = entries.stream().filter(e -> e.level() == SafeLogger.Level.WARN).count();
        assertTrue(warnings >= 1 && warnings < 10, "At least first capacity warning, not one per rejection");
        assertTrue(entries.stream().noneMatch(e -> e.text().contains("SECRET_CAPACITY")));
    }

    @Test void a20ActualMainDefaultServerRoutesEventsToProvidedStreams() throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicInteger exit = new java.util.concurrent.atomic.AtomicInteger(-1);
        int port;
        try (var reservation = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        try (var out = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
             var err = new java.io.PrintStream(errors, true, java.nio.charset.StandardCharsets.UTF_8)) {
            Thread main = new Thread(() -> exit.set(ru.octomem.cli.Main.run(new String[]{
                    "--listen.address=127.0.0.1", "--listen.port=" + port, "--transport.workers=1",
                    "--memory.limit=1048576", "--item.maxSize=1024", "--log.level=info"}, out, err)), "t013-main-route");
            main.setDaemon(true);
            main.start();
            boolean ready = false;
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline && main.isAlive()) {
                    try (var probe = new java.net.Socket()) {
                        probe.connect(new InetSocketAddress("127.0.0.1", port), 100);
                        probe.setSoTimeout(1000);
                        probe.getOutputStream().write("version\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        assertEquals("VERSION 1.0.0 (octomem)\r\n", new String(probe.getInputStream().readNBytes(25), java.nio.charset.StandardCharsets.US_ASCII));
                        ready = true;
                        break;
                    } catch (java.net.ConnectException notReady) { Thread.sleep(10); }
                }
                assertTrue(ready, "Actual Main server did not bind");
            } finally {
                main.interrupt();
                main.join(5000);
            }
            assertFalse(main.isAlive(), "Interrupt must drain real Main server");
            assertEquals(1, exit.get(), "Interrupted Main returns documented failure exit");
            String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(evidence.resolveSibling(evidence.getFileName() + ".main.stdout.txt"), text);
            Files.writeString(evidence.resolveSibling(evidence.getFileName() + ".main.stderr.txt"), errors.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(text.lines().count() >= 3, "Real configuration/start/stop must use caller's output stream");
            assertFalse(errors.toString(java.nio.charset.StandardCharsets.UTF_8).isBlank());
        }
    }

    @Test void a19InjectedMonotonicTimeControlsEachCategoryIncludingWrap() {
        List<Entry> events = new CopyOnWriteArrayList<>();
        var nanos = new java.util.concurrent.atomic.AtomicLong(0);
        SafeLogger logger = new SafeLogger("debug", (level, text) -> events.add(new Entry(level, text)), nanos::get);
        logger.log(SafeLogger.Event.COMMAND);
        logger.log(SafeLogger.Event.CAPACITY);
        logger.log(SafeLogger.Event.CAPACITY);
        assertEquals(2, events.size(), "First event at nanoTime=0 is not suppressed; categories independent");
        nanos.set(999_999_999);
        logger.log(SafeLogger.Event.CAPACITY);
        assertEquals(2, events.size());
        nanos.set(1_000_000_000);
        logger.log(SafeLogger.Event.CAPACITY);
        assertEquals(3, events.size());
        var wrap = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 500_000_000);
        List<Entry> wrapped = new CopyOnWriteArrayList<>();
        SafeLogger nearWrap = new SafeLogger("warn", (level, text) -> wrapped.add(new Entry(level, text)), wrap::get);
        nearWrap.log(SafeLogger.Event.CAPACITY);
        wrap.addAndGet(1_000_000_000);
        nearWrap.log(SafeLogger.Event.CAPACITY);
        assertEquals(2, wrapped.size());
        SafeLogger brokenClock = new SafeLogger("debug", (level, text) -> { }, () -> { throw new IllegalStateException("SECRET_CLOCK"); });
        assertDoesNotThrow(() -> brokenClock.log(SafeLogger.Event.CAPACITY));
    }

    /**
     * Seven 8 MiB header-only reservations fill the body budget and perform seven native
     * admissions; the eighth header is refused before any eighth native admission.
     *
     * <p>The test must not infer "seven native admissions completed" from "seven heap body
     * reservations were observed". Production publishes the global body reservation
     * (ConnectionResources) before the native admission is attempted (TextProtocolDecoder) and
     * increments {@code cmd_set} only after native/service capacity is reserved
     * (DefaultCacheEngine), while NioTransport spreads connections over independent loops, and
     * {@code StatsWireFixture.send} only writes/flushes TCP. On a 2-CPU stand the seventh native
     * admission can therefore complete after an early statistics sample, yielding {@code 6}.</p>
     *
     * <p>The test now waits on a deterministic admission-complete barrier owned by the test: a
     * narrowly scoped test-only observing adapter installed into the shared {@code StorageAdmission}
     * via reflection before {@code start()}, which delegates unchanged and signals only after each
     * real admission call returns. The expectation stays exactly seven: no range, no retry, no
     * poll-until-equals. See {@link StatsWireFixture#observeAdmissions(int)} for the seam and its
     * documented better long-term production injection point.</p>
     */
    @Test void a19IngressBodyLimitWarnsBeforeAnyNativeAdmission() throws Exception {
        List<Entry> events = new CopyOnWriteArrayList<>();
        var config = ConfigLoader.load(null, Map.of("transport.workers", "2", "connections.max", "16",
                "memory.limit", "134217728", "item.maxSize", "9000000", "log.level", "warn"));
        server = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0), clock, capture("warn", events));
        AdmissionObserver admissions = observeAdmissions(7);
        server.start();
        wire = connect();
        // Seven payload+32 reservations fit 64MiB; eight do not.
        for (int i = 0; i < 7; i++) connect().send("set p" + i + " 0 0 8388608\r\n");
        admissions.awaitAdmissions();
        var expectedKeys = java.util.Set.of("p0", "p1", "p2", "p3", "p4", "p5", "p6");
        admissions.assertHeld(expectedKeys);
        assertEquals(7L * (8_388_608 + 32), server.resourceUsage().bodyBytes());
        count(wire.stats("stats"), "cmd_set", 7);
        long nativeBefore = server.resourceUsage().nativeUsedBytes();
        assertTrue(server.resourceUsage().nativeTotalBytes() < 134_217_728);
        Wire rejected = connect();
        rejected.send("set SECRET_INGRESS_KEY 0 0 8388608\r\n");
        admissions.awaitDenial();
        admissions.assertHeld(expectedKeys);
        assertTrue(events.stream().anyMatch(e -> e.level() == SafeLogger.Level.WARN));
        count(wire.stats("stats"), "cmd_set", 7);
        assertEquals(nativeBefore, server.resourceUsage().nativeUsedBytes());
        assertEquals(7L * (8_388_608 + 32), server.resourceUsage().bodyBytes());
        assertTrue(events.stream().noneMatch(e -> e.text().contains("SECRET_INGRESS")));
        server.close();
        assertEquals(0, server.resourceUsage().bodyBytes());
        assertEquals(0, server.resourceUsage().nativeUsedBytes());
        assertEquals(0, server.resourceUsage().nativeTotalBytes());
        assertEquals(0, server.resourceUsage().serviceBytes());
    }

    @Test void a19LoggerCallbacksRunOutsideGateAndBackwardClockDoesNotRefill() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong(2_000_000_000L);
        List<Entry> events = new CopyOnWriteArrayList<>();
        var holder = new java.util.concurrent.atomic.AtomicReference<SafeLogger>();
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var completedInsideCallback = new java.util.concurrent.atomic.AtomicBoolean();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "same-category-logger-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            SafeLogger logger = new SafeLogger("debug", (level, text) -> {
                events.add(new Entry(level, text));
                if (entered.compareAndSet(false, true)) {
                    try {
                        // Even a suppressed same-category call must acquire the CAPACITY gate.
                        pool.submit(() -> holder.get().log(SafeLogger.Event.CAPACITY))
                                .get(2, java.util.concurrent.TimeUnit.SECONDS);
                        completedInsideCallback.set(true);
                    } catch (Throwable failure) { callbackFailure.set(failure); }
                }
            }, time::get);
            holder.set(logger);
            logger.log(SafeLogger.Event.CAPACITY);
            // SafeLogger swallows sink exceptions, so the test thread owns all failure assertions.
            assertNull(callbackFailure.get(), "Sink callback held the same-category rate-limit gate");
            assertTrue(completedInsideCallback.get(), "Same-category other-thread call completed before sink returned");
            assertEquals(1, events.size(), "Same-time capacity probe is suppressed, not emitted recursively");
            time.set(1_000_000_000L);
            logger.log(SafeLogger.Event.CAPACITY);
            assertEquals(1, events.stream().filter(e -> e.level() == SafeLogger.Level.WARN).count());
            time.set(3_000_000_000L);
            logger.log(SafeLogger.Event.CAPACITY);
            assertEquals(2, events.stream().filter(e -> e.level() == SafeLogger.Level.WARN).count());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Logger probe worker must terminate");
        }
    }

    /** Entry point for an isolated classpath mutation experiment, without wire/server setup. */
    static void runLoggerGateProbe() throws Exception {
        new StatsLoggingAcceptanceTest().a19LoggerCallbacksRunOutsideGateAndBackwardClockDoesNotRefill();
    }

    @Test void a20RawControlByteMarkersNeverEscapeAndInjectedSinkSuppressesStderr() throws Exception {
        List<Entry> entries = new CopyOnWriteArrayList<>();
        SafeLogger logger = capture("debug", entries);
        var captured = new java.io.ByteArrayOutputStream();
        var original = System.err;
        String marker = "SECRET_MARKER_T013";
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            startLogged("debug", logger);
            String value = "\u0001" + marker + "\u007f";
            wire.expect("set K 0 0 " + value.length() + "\r\n" + value + "\r\n", "STORED\r\n");
            wire.expect("get K\r\n", "VALUE K 0 " + value.length() + "\r\n" + value + "\r\nEND\r\n");
            wire.expect("unknowncmd " + marker + "\r\n", "ERROR\r\n");
            server.close();
        } finally {
            System.setErr(original);
        }
        String stderr = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(evidence.resolveSibling(evidence.getFileName() + ".sink-bypass.txt"),
                "STDERR_BYTES=" + captured.size() + "\n" + stderr,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        for (Entry entry : entries) {
            assertFalse(entry.text().contains(marker), "Log leaked the raw marker");
            assertFalse(entry.text().contains("\u0001") || entry.text().contains("\u007f"), "Log leaked control bytes");
            assertFalse(entry.text().contains("\r") || entry.text().contains("\n"), "Log leaked line injection");
        }
        assertTrue(entries.stream().anyMatch(e -> e.level() == SafeLogger.Level.INFO),
                "Startup/config/stop still flow through the injected sink");
        assertFalse(stderr.contains(marker), "Actual server must not route raw content to process stderr");
        assertFalse(stderr.contains("\u0001") || stderr.contains("\u007f"), "Control bytes reached process stderr");
        assertFalse(stderr.contains("Сервер запущен"), "Injected per-server sink must suppress the default stderr logger");
    }

}
