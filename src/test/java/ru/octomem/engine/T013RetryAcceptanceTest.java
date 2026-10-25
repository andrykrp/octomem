package ru.octomem.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.Clock;

/**
 * Independently authored F4 retry, multi-get rollback and typed-denial cases
 * exercised through the approved package-local engine seams. Expected values
 * come from the frozen literal oracle and coordinator decisions, never from
 * production-derived calculations.
 */
@Timeout(20)
class T013RetryAcceptanceTest {
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    /** Test-owned clock with independent seconds/nanos and explicit advancement. */
    static final class MutableClock implements Clock {
        volatile long seconds = 1_700_000_000L;
        @Override public long currentTimeSeconds() { return seconds; }
        @Override public long nanoTime() { return seconds * 1_000_000_000L; }
        void advance(long delta) { seconds += delta; }
    }

    /**
     * Engine fixture whose native allocator budget is independent of the engine
     * settings budget, so class-snapshot admission stays large while native
     * capacity can be made exactly full.
     */
    static final class Rig implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final AtomicStatsRegistry stats;
        final FfmSlabAllocator allocator;
        final SegmentedItemTable table;
        final DefaultCacheEngine engine;
        Rig(long allocatorMemory, int page, int maximum, long engineMemory, String policy,
                DefaultCacheEngine.EngineHooks hooks) {
            stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "independent-retry",
                    "pointer_size", "64", "threads", "1", "limit_maxbytes", Long.toString(engineMemory)));
            allocator = new FfmSlabAllocator(allocatorMemory, page, maximum);
            table = new SegmentedItemTable(1, 2, 65536);
            engine = new DefaultCacheEngine(allocator, table, clock, stats,
                    new DefaultCacheEngine.Settings(engineMemory, maximum, policy, 3600), 0, hooks,
                    () -> Executors.newSingleThreadScheduledExecutor());
            engine.start();
        }
        long counter(Counter field) { return stats.snapshot().counters().get(field); }
        long classCounter(int classId, Counter field) {
            try (var snapshot = stats.captureClasses()) { return snapshot.counter(classId, field); }
        }
        long event(int classId, ClassStatsRegistry.Event event) {
            try (var snapshot = stats.captureClasses()) { return snapshot.event(classId, event); }
        }
        @Override public void close() { try { engine.stop(); } finally { allocator.close(); } }
    }

    /** Exactly-full native allocator (4x64 + 1x128 blocks) with a large class-snapshot budget. */
    static final class RetryRig implements AutoCloseable {
        final Rig rig;
        RetryRig(DefaultCacheEngine.EngineHooks hooks, String initialValue) {
            rig = new Rig(512, 256, 200, 65536, "lru", hooks);
            assertEquals(StoreResult.STORED, rig.engine.set(b("n"), b(initialValue), 0, 60));
            assertEquals(StoreResult.STORED, rig.engine.set(b("a"), b("x"), 0, 0));
            assertEquals(StoreResult.STORED, rig.engine.set(b("b"), b("x"), 0, 0));
            assertEquals(StoreResult.STORED, rig.engine.set(b("c"), b("x"), 0, 0));
            assertEquals(StoreResult.STORED, rig.engine.set(b("Ffiller"), b("y".repeat(60)), 0, 0));
            assertEquals(4L * 64 + 104, rig.allocator.usedMemory());
        }
        DefaultCacheEngine engine() { return rig.engine; }
        MutableClock clock() { return rig.clock; }
        long counter(Counter field) { return rig.counter(field); }
        long classCounter(int classId, Counter field) { return rig.classCounter(classId, field); }
        long event(int classId, ClassStatsRegistry.Event event) { return rig.event(classId, event); }
        @Override public void close() { rig.close(); }
    }

    @Test void numericHitThenConcurrentDeleteCannotAlsoCountMiss() throws Exception {
        BoundedTestJvm.run(T013RetryAcceptanceTest.class, "runConcurrentDelete", 15);
    }

    static void runConcurrentDelete() throws Exception {
        var reached = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var once = new AtomicBoolean();
        var stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64",
                "threads", "1", "limit_maxbytes", "128"));
        Clock clock = new Clock() {
            @Override public long currentTimeSeconds() { return 1_700_000_000L; }
            @Override public long nanoTime() { return 0; }
        };
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeArithmeticRetry() {
                if (!once.compareAndSet(false, true)) return;
                reached.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS), "Retry barrier not released"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        };
        try (var allocator = new FfmSlabAllocator(128, 128, 64);
             var table = new SegmentedItemTable(1, 2, 65536)) {
            var pool = Executors.newSingleThreadExecutor();
            var engine = new DefaultCacheEngine(allocator, table, clock, stats,
                    new DefaultCacheEngine.Settings(128, 64, "lru", 3600), 0, hooks,
                    () -> Executors.newSingleThreadScheduledExecutor());
            engine.start();
            try {
                assertEquals(StoreResult.STORED, engine.set(b("n"), b("9"), 0, 0));
                assertEquals(StoreResult.STORED, engine.set(b("v"), b("x"), 0, 0));
                var operation = pool.submit(() -> engine.increment(b("n"), 1));
                try {
                    assertTrue(reached.await(5, TimeUnit.SECONDS), "Native failure must reach unlocked retry seam");
                    assertTrue(engine.delete(b("n")), "Delete must acquire table lock while retry hook is paused");
                } finally { resume.countDown(); }
                assertEquals(CounterResult.Status.NOT_FOUND, operation.get(5, TimeUnit.SECONDS).status());
                assertEquals(1, stats.snapshot().counters().get(Counter.INCR_HITS).longValue());
                assertEquals(0, stats.snapshot().counters().get(Counter.INCR_MISSES).longValue());
            } finally {
                resume.countDown();
                BoundedTestJvm.shutdownOrHalt(pool);
                engine.stop();
            }
        }
    }

    @Test void multiGetRollsBackPreparedReplacementWithOneFreeChunk() throws Exception {
        var pins = new AtomicInteger();
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeLeaseAcquire() { pins.incrementAndGet(); }
        };
        try (var rig = new Rig(32768, 32768, 64, 65536, "noeviction", hooks)) {
            for (int i = 0; i < 511; i++) {
                assertEquals(StoreResult.STORED, rig.engine.set(b("k" + i), b("x"), 0, 0));
            }
            assertEquals(511L * 64, rig.allocator.usedMemory());
            byte[] first = b("k0"), second = b("k1");
            long firstCas, secondCas;
            try (var one = rig.engine.get(first); var two = rig.engine.get(second)) {
                assertTrue(one.found() && two.found());
                firstCas = one.item().casId();
                secondCas = two.item().casId();
            }
            assertEquals(0, pins.get(), "Plain reads do not use the lease-acquire seam");
            KeySequence keys = new KeySequence() {
                @Override public int size() { return 2; }
                @Override public void forEachKey(KeyVisitor visitor) {
                    visitor.accept(first, 0, first.length);
                    visitor.accept(second, 0, second.length);
                }
            };
            assertThrows(CacheCapacityException.class, () -> rig.engine.snapshotAndTouch(keys, 120));
            assertEquals(1, pins.get(), "Exactly one prepared replacement must exist before the second copy fails");
            assertEquals(511L * 64, rig.allocator.usedMemory(),
                    "The rolled-back prepared replacement must release its native block");
            assertEquals(0, rig.engine.snapshotMetadataUsedBytes());
            assertEquals(1, rig.event(0, ClassStatsRegistry.Event.OUTOFMEMORY));
            assertEquals(511, rig.counter(Counter.TOTAL_ITEMS));
            try (var one = rig.engine.get(first); var two = rig.engine.get(second)) {
                assertTrue(one.found() && two.found());
                assertEquals(firstCas, one.item().casId(), "Rollback preserves the first original CAS");
                assertEquals(secondCas, two.item().casId(), "Rollback preserves the second original CAS");
            }
        }
    }

    @Test void retryAfterConcurrentNonnumericReplacementCountsOneHitOnly() {
        var holder = new RetryRig[1];
        var once = new AtomicBoolean();
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeArithmeticRetry() {
                if (!once.compareAndSet(false, true)) return;
                assertTrue(holder[0].engine().delete(b("a")));
                assertEquals(StoreResult.STORED, holder[0].engine().set(b("n"), b("z"), 0, 0));
            }
        };
        try (var rig = new RetryRig(hooks, "9")) {
            holder[0] = rig;
            assertEquals(CounterResult.Status.NON_NUMERIC, rig.engine().increment(b("n"), 1).status());
            assertEquals(1, rig.counter(Counter.INCR_HITS), "The numeric hit is counted once");
            assertEquals(0, rig.counter(Counter.INCR_MISSES), "A counted hit cannot later add a miss");
            assertEquals(1, rig.classCounter(0, Counter.INCR_HITS));
        }
    }

    @Test void retryAfterConcurrentClassChangingReplacementKeepsOldClassHit() {
        var holder = new RetryRig[1];
        var once = new AtomicBoolean();
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeArithmeticRetry() {
                if (!once.compareAndSet(false, true)) return;
                assertTrue(holder[0].engine().delete(b("n")), "Free the old small-class record");
                assertEquals(StoreResult.STORED, holder[0].engine().set(b("n"), b("x".repeat(60)), 0, 0));
            }
        };
        try (var rig = new RetryRig(hooks, "9")) {
            holder[0] = rig;
            assertEquals(CounterResult.Status.NON_NUMERIC, rig.engine().increment(b("n"), 1).status());
            assertEquals(1, rig.counter(Counter.INCR_HITS));
            assertEquals(0, rig.counter(Counter.INCR_MISSES));
            assertEquals(1, rig.classCounter(0, Counter.INCR_HITS),
                    "Old-class attribution is fixed at the counted hit");
            assertEquals(0, rig.classCounter(2, Counter.INCR_HITS),
                    "The class-changing retry must not reattribute the hit");
        }
    }

    @Test void retryAfterConcurrentExpiryReturnsNotFoundWithoutMiss() {
        var holder = new RetryRig[1];
        var once = new AtomicBoolean();
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeArithmeticRetry() {
                if (!once.compareAndSet(false, true)) return;
                holder[0].clock().advance(61);
            }
        };
        try (var rig = new RetryRig(hooks, "9")) {
            holder[0] = rig;
            assertEquals(CounterResult.Status.NOT_FOUND, rig.engine().increment(b("n"), 1).status());
            assertEquals(1, rig.counter(Counter.INCR_HITS));
            assertEquals(0, rig.counter(Counter.INCR_MISSES));
        }
    }

    @Test void decrementRetryAfterConcurrentDeleteCountsOneDecrHit() {
        var holder = new RetryRig[1];
        var once = new AtomicBoolean();
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeArithmeticRetry() {
                if (!once.compareAndSet(false, true)) return;
                assertTrue(holder[0].engine().delete(b("n")));
            }
        };
        try (var rig = new RetryRig(hooks, "10")) {
            holder[0] = rig;
            assertEquals(CounterResult.Status.NOT_FOUND, rig.engine().decrement(b("n"), 1).status());
            assertEquals(1, rig.counter(Counter.DECR_HITS));
            assertEquals(0, rig.counter(Counter.DECR_MISSES));
        }
    }

    @Test void throwingRetryHookCleansReservationAndPropagates() {
        var holder = new RetryRig[1];
        var once = new AtomicBoolean();
        var failure = new AtomicReference<RuntimeException>();
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeArithmeticRetry() {
                if (!once.compareAndSet(false, true)) return;
                failure.set(new IllegalStateException("injected-retry-hook"));
                throw failure.get();
            }
        };
        try (var rig = new RetryRig(hooks, "9")) {
            holder[0] = rig;
            long nativeBefore = rig.rig.allocator.usedMemory();
            long serviceBefore = rig.engine().serviceMetadataUsedBytes();
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> rig.engine().increment(b("n"), 1));
            assertSame(failure.get(), thrown, "The hook failure must propagate unchanged");
            assertEquals(1, rig.counter(Counter.INCR_HITS), "The numeric hit was already counted");
            assertEquals(nativeBefore, rig.rig.allocator.usedMemory(), "No native block may leak from the aborted retry");
            assertEquals(serviceBefore, rig.engine().serviceMetadataUsedBytes(), "No service charge may leak");
        }
    }

    /** Deterministic scheduled-executor double that never schedules real work. */
    private static java.util.concurrent.ScheduledExecutorService schedulerProxy(
            Runnable[] captured, RuntimeException scheduleFailure, RuntimeException shutdownFailure) {
        return (java.util.concurrent.ScheduledExecutorService) java.lang.reflect.Proxy.newProxyInstance(
                T013RetryAcceptanceTest.class.getClassLoader(),
                new Class<?>[]{java.util.concurrent.ScheduledExecutorService.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "scheduleWithFixedDelay":
                            captured[0] = (Runnable) args[0];
                            if (scheduleFailure != null) throw scheduleFailure;
                            return null;
                        case "shutdownNow":
                            if (shutdownFailure != null) throw shutdownFailure;
                            return java.util.Collections.emptyList();
                        case "toString":
                            return "scheduler-proxy";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    private static AtomicStatsRegistry registryFor(String version, long limit) {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", version, "pointer_size", "64",
                "threads", "1", "limit_maxbytes", Long.toString(limit)));
    }

    private static AtomicStatsRegistry registryFor(String version, long limit, ru.octomem.stats.SafeLogger logger) {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", version, "pointer_size", "64",
                "threads", "1", "limit_maxbytes", Long.toString(limit)), logger);
    }

    @Test void readResultWrapFailureReleasesThePinnedLeaseAndPropagates() throws Exception {
        var failure = new AtomicReference<RuntimeException>();
        DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
            @Override public void beforeResultWrap() {
                failure.set(new IllegalStateException("injected-result-wrap"));
                throw failure.get();
            }
        };
        try (var rig = new Rig(32768, 32768, 64, 65536, "noeviction", hooks)) {
            long nativeEmpty = rig.allocator.usedMemory();
            long serviceEmpty = rig.engine.serviceMetadataUsedBytes();
            assertEquals(StoreResult.STORED, rig.engine.set(b("k"), b("x"), 0, 0));
            assertTrue(rig.allocator.usedMemory() > nativeEmpty);
            assertTrue(rig.engine.serviceMetadataUsedBytes() > serviceEmpty);
            RuntimeException hitFailure = assertThrows(RuntimeException.class, () -> rig.engine.get(b("k")));
            assertSame(failure.get(), hitFailure, "The wrap failure must propagate unchanged on a hit");
            RuntimeException missFailure = assertThrows(RuntimeException.class, () -> rig.engine.get(b("absent")));
            assertSame(failure.get(), missFailure, "The wrap failure must propagate unchanged on a miss");
            assertTrue(rig.engine.delete(b("k")), "Delete removes the mapping even when a lease is pinned");
            assertEquals(1, rig.counter(Counter.DELETE_HITS));
            assertEquals(nativeEmpty, rig.allocator.usedMemory(),
                    "Failed wrap must release the pin: retired native block is freed before allocator close");
            assertEquals(serviceEmpty, rig.engine.serviceMetadataUsedBytes(),
                    "Retired version must release its service charge before fixture teardown");
        }
    }

    @Test void expiryFailureWithoutClassStatsIsSuppressed() throws Exception {
        var captured = new Runnable[1];
        var armed = new AtomicBoolean();
        Clock clock = new Clock() {
            @Override public long currentTimeSeconds() {
                if (armed.get()) { throw new IllegalStateException("injected-expiry"); }
                return 1_700_000_000L;
            }
            @Override public long nanoTime() { return 0; }
        };
        var plain = new ru.octomem.stats.StatsRegistry() {
            @Override public void increment(Counter counter, long delta) { }
            @Override public void setGauge(ru.octomem.stats.Gauge gauge, long value) { }
            @Override public ru.octomem.stats.Snapshot snapshot() {
                return new ru.octomem.stats.Snapshot() {
                    @Override public java.util.Map<Counter, Long> counters() { return java.util.Map.of(); }
                    @Override public java.util.Map<ru.octomem.stats.Gauge, Long> gauges() { return java.util.Map.of(); }
                    @Override public java.util.Map<String, String> constants() { return java.util.Map.of(); }
                };
            }
            @Override public void reset() { }
        };
        try (var allocator = new FfmSlabAllocator(65536, 4096, 64);
             var table = new SegmentedItemTable(1, 2, 65536)) {
            var engine = new DefaultCacheEngine(allocator, table, clock, plain,
                    new DefaultCacheEngine.Settings(65536, 64, "lru", 3600), 0,
                    new DefaultCacheEngine.EngineHooks() { },
                    () -> schedulerProxy(captured, null, null));
            engine.start();
            try {
                assertNotNull(captured[0]);
                armed.set(true);
                captured[0].run();
            } finally {
                armed.set(false);
                engine.stop();
            }
        }
    }

    @Test void startRollbackSuppressesCleanupFailureAndStopsTheEngine() throws Exception {
        var scheduleFailure = new RuntimeException("injected-schedule-failure");
        var shutdownFailure = new RuntimeException("injected-shutdown-failure");
        Clock clock = new MutableClock();
        try (var allocator = new FfmSlabAllocator(65536, 4096, 64);
             var table = new SegmentedItemTable(1, 2, 65536)) {
            var engine = new DefaultCacheEngine(allocator, table, clock, registryFor("rollback", 65536),
                    new DefaultCacheEngine.Settings(65536, 64, "lru", 3600), 0,
                    new DefaultCacheEngine.EngineHooks() { },
                    () -> schedulerProxy(new Runnable[1], scheduleFailure, shutdownFailure));
            RuntimeException thrown = assertThrows(RuntimeException.class, engine::start);
            assertSame(scheduleFailure, thrown, "The original startup failure propagates");
            assertEquals(1, thrown.getSuppressed().length, "The cleanup failure is retained");
            assertSame(shutdownFailure, thrown.getSuppressed()[0]);
            engine.stop();
        }
    }

    @Test void expiryFailureIsLoggedOnlyWhileRunning() throws Exception {
        var captured = new Runnable[1];
        var armed = new AtomicBoolean();
        var logged = new AtomicInteger();
        var clockFailures = new AtomicInteger();
        var lastLevel = new AtomicReference<ru.octomem.stats.SafeLogger.Level>();
        var logger = new ru.octomem.stats.SafeLogger("debug", (level, message) -> {
            logged.incrementAndGet();
            lastLevel.set(level);
        });
        Clock clock = new Clock() {
            @Override public long currentTimeSeconds() {
                if (armed.get()) {
                    clockFailures.incrementAndGet();
                    throw new IllegalStateException("injected-clock");
                }
                return 1_700_000_000L;
            }
            @Override public long nanoTime() { return 0; }
        };
        try (var allocator = new FfmSlabAllocator(65536, 4096, 64);
             var table = new SegmentedItemTable(1, 2, 65536)) {
            var engine = new DefaultCacheEngine(allocator, table, clock, registryFor("expiry", 65536, logger),
                    new DefaultCacheEngine.Settings(65536, 64, "lru", 3600), 0,
                    new DefaultCacheEngine.EngineHooks() { },
                    () -> schedulerProxy(captured, null, null));
            engine.start();
            try {
            assertNotNull(captured[0], "The periodic maintenance task was installed");
            armed.set(true);
            assertDoesNotThrow(() -> captured[0].run(),
                    "A failing maintenance pass must be contained, never escape the scheduler task");
            assertEquals(1, logged.get(),
                    "A running maintenance failure is reported exactly once (the EXPIRY_FAILURE event)");
            assertEquals(1, clockFailures.get(), "The running fault was actually injected");
            assertEquals(ru.octomem.stats.SafeLogger.Level.ERROR, lastLevel.get(),
                    "A maintenance failure is an error-severity event");
            armed.set(false);
            assertEquals(StoreResult.STORED, engine.set(b("k"), b("x"), 0, 0),
                    "A contained maintenance failure must not stop or corrupt the engine");
            engine.stop();
            armed.set(true);
            assertDoesNotThrow(() -> captured[0].run(),
                    "A maintenance pass after stop must also be contained");
            assertTrue(armed.get(), "Post-stop failure source remains armed");
            assertEquals(1, clockFailures.get(), "Stopped maintenance rejects work before consulting the armed clock");
            assertEquals(1, logged.get(),
                    "A stopped engine must not re-report the earlier maintenance failure");
            } finally {
                armed.set(false);
                engine.stop();
            }
        }
    }
}
