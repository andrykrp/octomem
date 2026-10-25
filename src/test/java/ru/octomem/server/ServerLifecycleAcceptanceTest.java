package ru.octomem.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.cli.CacheServer;
import ru.octomem.cli.Main;
import ru.octomem.config.ConfigLoader;

/** No-network CLI seam tests complement, not replace, real server/process tests. */
@Timeout(15)
class ServerLifecycleAcceptanceTest {
    @Test void administrativeModesNeverConstructServer() {
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
            for (String[] args : List.of(new String[]{"--help"}, new String[]{"--version"},
                    new String[]{"--check-config"}, new String[]{"--listen.port=0"})) {
                int code = Main.run(args, sink, sink, config -> { throw new AssertionError("Unexpected construction"); });
                assertEquals(args[0].equals("--listen.port=0") ? 2 : 0, code);
            }
        }
    }

    @Test void startupFailureClosesLifecycleAndDoesNotLeakCause() {
        List<String> calls = new ArrayList<>();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            assertEquals(1, Main.run(new String[0], stream, stream, config -> new Main.ServerLifecycle() {
                public void start() throws IOException { calls.add("start"); throw new IOException("SECRET_TEST_CAUSE"); }
                public void awaitTermination() { fail("Must not wait after startup failure"); }
                public void close() { calls.add("close"); }
            }));
        }
        assertEquals(List.of("start", "close"), calls);
        assertFalse(diagnostics.toString(StandardCharsets.UTF_8).contains("SECRET_TEST_CAUSE"));
    }

    @Test void interruptionClosesLifecycleAndRestoresInterruptFlag() {
        List<String> calls = new ArrayList<>();
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
            assertEquals(1, Main.run(new String[]{"--log.level=error"}, sink, sink, config -> new Main.ServerLifecycle() {
                public void start() { calls.add("start"); }
                public void awaitTermination() throws InterruptedException { calls.add("await"); throw new InterruptedException(); }
                public void close() { calls.add("close"); }
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(List.of("start", "await", "close"), calls);
    }

    @Test void actualBindFailureReleasesEverythingAndCloseIsIdempotent() throws Exception {
        var config = ConfigLoader.load(null, Map.of("transport.workers", "2", "memory.limit", "4194304", "item.maxSize", "65536"));
        try (ServerSocket reserved = new ServerSocket()) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0));
            CacheServer server = new CacheServer(config, new InetSocketAddress("127.0.0.1", reserved.getLocalPort()));
            try {
                assertThrows(IOException.class, server::start);
                server.awaitTermination();
                assertEquals(0, server.resourceUsage().nativeTotalBytes());
                assertEquals(0, server.resourceUsage().controlBytes());
                assertThrows(IllegalStateException.class, server::start);
            } finally { server.close(); server.close(); }
        }
    }

    @Test void closeBeforeStartAndRepeatedStartRespectLifecycle() throws Exception {
        var config = ConfigLoader.load(null, Map.of("transport.workers", "1"));
        CacheServer unstarted = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0));
        unstarted.close();
        unstarted.close();
        assertThrows(IllegalStateException.class, unstarted::start);
        try (CacheServer running = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0))) {
            running.start();
            var address = running.localAddress();
            running.start();
            assertEquals(address, running.localAddress());
            Thread.currentThread().interrupt();
            try { running.close(); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
        }
    }

    @Test void configuredLogLevelsComposeWithoutBinding() throws Exception {
        for (String level : List.of("debug", "error", "warn")) {
            var config = ConfigLoader.load(null, Map.of("transport.workers", "1", "log.level", level));
            try (CacheServer server = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0))) {
                assertEquals(0, server.resourceUsage().controlBytes());
            }
        }
    }

    @Test void constructorFailureAfterCoreCreationPropagatesWithoutBinding() throws Exception {
        var config = ConfigLoader.load(null, Map.of("transport.workers", "1"));
        var clock = new ru.octomem.util.Clock() {
            public long currentTimeSeconds() { return 1700000000; }
            public long nanoTime() { throw new IllegalStateException("Injected clock construction failure"); }
        };
        assertThrows(IllegalStateException.class,
                () -> new CacheServer(config, new InetSocketAddress("127.0.0.1", 0), clock));
    }

    @Test void transportTerminationRequiresCloseAndCannotJoinOwnedThread() throws Exception {
        var settings = new ru.octomem.transport.TransportSettings(new InetSocketAddress("127.0.0.1", 0),
                1, 1, 512, 1, 0, 8192, 512, 4096, 128);
        var stats = new ru.octomem.stats.AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64", "threads", "1", "limit_maxbytes", "4096"));
        var reference = new java.util.concurrent.atomic.AtomicReference<ru.octomem.transport.NioTransport>();
        var result = new java.util.concurrent.CompletableFuture<Throwable>();
        ru.octomem.transport.ConnectionFactory factory = resources -> {
            var transport = reference.get();
            transport.close();
            try { transport.awaitTermination(); result.complete(null); }
            catch (Throwable failure) { result.complete(failure); }
            throw new IllegalStateException("Test closes at admission");
        };
        try (var transport = new ru.octomem.transport.NioTransport(settings, factory, stats, new ru.octomem.util.SystemClock())) {
            reference.set(transport);
            assertThrows(IllegalStateException.class, transport::awaitTermination);
            transport.start();
            try (var socket = new java.net.Socket()) {
                socket.connect(transport.localAddress(), 2000);
                assertInstanceOf(IllegalStateException.class, result.get(3, java.util.concurrent.TimeUnit.SECONDS));
            }
            transport.awaitTermination();
        }
    }

    /**
     * Budget for awaiting the injected autonomous failure. The path completes in well under a
     * second normally; the bound is deliberately generous for a slow 2-CPU acceptance stand
     * while staying below the class-level {@link Timeout} guard, and it is twice
     * {@code NioTransport.close()}'s own bounded 5 s shutdown join. A genuine hang therefore
     * still fails loudly with a diagnostic rather than passing after a longer pause.
     */
    private static final long AUTONOMOUS_FAILURE_BUDGET_MILLIS = 10_000;

    @Test void autonomousEventLoopFailureMakesMainReturnFailureAfterNativeCleanup() throws Exception {
        // Sticky, not single-shot: the product deliberately isolates a per-connection callback
        // failure (the event loop catches it, logs and closes only that connection), so a flag
        // consumed by such an isolated occurrence must not lose the signal. The event loop's own
        // top-level guard calls the clock every iteration, so a sticky arm is guaranteed to
        // surface as an autonomous transport failure wherever the first injected call lands.
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var system = new ru.octomem.util.SystemClock();
        var clock = new ru.octomem.util.Clock() {
            public long currentTimeSeconds() { return system.currentTimeSeconds(); }
            public long nanoTime() {
                if (armed.get() && Thread.currentThread().getName().startsWith("octomem-event-"))
                    throw new IllegalStateException("SECRET_AUTONOMOUS_FAILURE");
                return system.nanoTime();
            }
        };
        var created = new java.util.concurrent.CompletableFuture<CacheServer>();
        var ready = new java.util.concurrent.CountDownLatch(1);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        var task = new java.util.concurrent.FutureTask<Integer>(() -> {
            try (PrintStream output = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
                return Main.run(new String[]{"--transport.workers=1", "--memory.limit=4m", "--item.maxSize=64k"}, output, output, config -> {
                    CacheServer server = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0), clock);
                    created.complete(server);
                    return new Main.ServerLifecycle() {
                        public void start() throws IOException { server.start(); ready.countDown(); }
                        public void awaitTermination() throws InterruptedException { server.awaitTermination(); }
                        public void close() { server.close(); }
                    };
                });
            }
        });
        Thread main = Thread.ofPlatform().daemon().start(task);
        CacheServer server = created.get(3, java.util.concurrent.TimeUnit.SECONDS);
        try {
            assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS));
            try (var socket = new java.net.Socket()) {
                socket.connect(server.localAddress(), 2000); socket.setSoTimeout(3000);
                socket.getOutputStream().write("set held 0 0 1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
                assertArrayEquals("STORED\r\n".getBytes(StandardCharsets.US_ASCII), socket.getInputStream().readNBytes(8));
                assertTrue(server.resourceUsage().nativeUsedBytes() > 0);
                armed.set(true);
                assertEquals(1, awaitMainRun(task,
                        "the event loop has not surfaced the injected failure, or Main.run is still "
                        + "inside shutdown (transport await/close) and has not released the engine, "
                        + "allocator and control reservations"));
                assertEquals(0, server.resourceUsage().nativeTotalBytes());
                assertEquals(0, server.resourceUsage().controlBytes());
                assertFalse(diagnostics.toString(StandardCharsets.UTF_8).contains("SECRET_AUTONOMOUS_FAILURE"));
            }
        } finally { server.close(); main.interrupt(); main.join(3000); }
    }

    /** Waits on the actual completion condition (Main.run returned) instead of a single fixed
     * instant, failing with the unfinished work named explicitly when the documented budget
     * expires. No operation is retried, so a real hang is reported and never masked.
     * @param task the Main.run invocation under test
     * @param unfinished description of what would remain unfinished on timeout
     * @return the Main.run exit code
     * @throws Exception if reading the completed task fails
     */
    private static int awaitMainRun(java.util.concurrent.FutureTask<Integer> task, String unfinished)
            throws Exception {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(AUTONOMOUS_FAILURE_BUDGET_MILLIS);
        for (;;) {
            if (task.isDone()) { return task.get(); }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                fail("Main.run did not return within " + AUTONOMOUS_FAILURE_BUDGET_MILLIS + " ms: " + unfinished);
            }
            try { return task.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS); }
            catch (java.util.concurrent.TimeoutException stillRunning) { /* re-check the condition */ }
        }
    }

    @Test void connectionConstructionFailureReturnsAdmissionAndClosesAcceptedSocket() throws Exception {
        var fired = new java.util.concurrent.atomic.AtomicBoolean();
        var system = new ru.octomem.util.SystemClock();
        var clock = new ru.octomem.util.Clock() {
            public long currentTimeSeconds() { return system.currentTimeSeconds(); }
            public long nanoTime() {
                boolean constructing = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals("ru.octomem.transport.NioTransport$Connection")
                                && frame.getMethodName().equals("<init>")));
                if (constructing && fired.compareAndSet(false, true)) throw new IllegalStateException("INJECTED_CONSTRUCTION_FAILURE");
                return system.nanoTime();
            }
        };
        var config = ConfigLoader.load(null, Map.of("transport.workers", "1", "memory.limit", "4194304", "item.maxSize", "65536"));
        try (CacheServer server = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0), clock)) {
            server.start();
            var task = new java.util.concurrent.FutureTask<Void>(() -> { server.awaitTermination(); return null; });
            Thread waiter = Thread.ofPlatform().daemon().start(task);
            try (var socket = new java.net.Socket()) {
                socket.connect(server.localAddress(), 2000); socket.setSoTimeout(3000);
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> task.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(fired.get());
                var usage = server.resourceUsage();
                assertAll(() -> assertEquals(0, usage.controlBytes()),
                        () -> assertEquals(0, usage.currentConnections()),
                        () -> assertEquals(0, usage.nativeTotalBytes()),
                        () -> assertEquals(-1, socket.getInputStream().read()));
            } finally { server.close(); waiter.interrupt(); waiter.join(3000); }
        }
    }

    @Test void concurrentExternalCloseAndStartCannotLeaveListenerOrResources() throws Exception {
        var config = ConfigLoader.load(null, Map.of("transport.workers", "1", "memory.limit", "4194304", "item.maxSize", "65536"));
        CacheServer server = new CacheServer(config, new InetSocketAddress("127.0.0.1", 0));
        var go = new java.util.concurrent.CountDownLatch(1);
        var start = new java.util.concurrent.FutureTask<Void>(() -> {
            go.await();
            try { server.start(); } catch (IllegalStateException closedFirst) { }
            return null;
        });
        var close = new java.util.concurrent.FutureTask<Void>(() -> { go.await(); server.close(); return null; });
        Thread starter = Thread.ofPlatform().daemon().start(start);
        Thread closer = Thread.ofPlatform().daemon().start(close);
        try {
            go.countDown(); start.get(5, java.util.concurrent.TimeUnit.SECONDS); close.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, server::start);
            assertEquals(0, server.resourceUsage().controlBytes());
            assertEquals(0, server.resourceUsage().nativeTotalBytes());
        } finally { go.countDown(); server.close(); starter.join(3000); closer.join(3000); }
    }
}
