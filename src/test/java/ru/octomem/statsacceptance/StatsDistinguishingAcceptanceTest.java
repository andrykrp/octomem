package ru.octomem.statsacceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.CounterResult;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.KeySequence;
import ru.octomem.engine.KeyVisitor;
import ru.octomem.engine.StoreResult;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.stats.Gauge;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.StoredItem;

/**
 * Independently authored distinguishing cases for denied-preparation typing,
 * fetched-state contrast, version lexical negatives and concurrent observer vs
 * reset. Expected values come from the frozen oracle and coordinator decisions.
 */
@Timeout(25)
class StatsDistinguishingAcceptanceTest extends StatsWireFixture {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static AtomicStatsRegistry registry() {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", "independent-distinguishing",
                "pointer_size", "64", "threads", "1", "limit_maxbytes", "1048576"));
    }

    /** Engine fixture whose native budget is independent of the settings budget. */
    private static final class Fixture implements AutoCloseable {
        final StatsWireFixture.MutableClock clock = new StatsWireFixture.MutableClock();
        final AtomicStatsRegistry stats = registry();
        final FfmSlabAllocator allocator;
        final DefaultCacheEngine engine;
        Fixture(long memory, int page, int maximum, String policy) {
            this(memory, page, maximum, memory, policy);
        }
        Fixture(long allocatorMemory, int page, int maximum, long engineMemory, String policy) {
            allocator = new FfmSlabAllocator(allocatorMemory, page, maximum);
            engine = new DefaultCacheEngine(allocator, new SegmentedItemTable(1, 2, 1_048_576), clock, stats,
                    new DefaultCacheEngine.Settings(engineMemory, maximum, policy, 3600));
            engine.start();
        }
        void set(String key, String value, long ttl) {
            assertEquals(StoreResult.STORED, engine.set(bytes(key), bytes(value), 0, ttl));
        }
        void set(int flags, String key, String value, long ttl) {
            assertEquals(StoreResult.STORED, engine.set(bytes(key), bytes(value), flags, ttl));
        }
        long counter(Counter field) { return stats.snapshot().counters().get(field); }
        long gauge(Gauge field) { return stats.snapshot().gauges().get(field); }
        long event(int classId, ClassStatsRegistry.Event event) {
            try (var snapshot = stats.captureClasses()) { return snapshot.event(classId, event); }
        }
        @Override public void close() { try { engine.stop(); } finally { allocator.close(); } }
    }

    @Test void a05GetAndTouchNativeDenialIsClassOomAndPreservesMetadata() throws Exception {
        try (var f = new Fixture(16384, 16384, 64, "noeviction")) {
            for (int i = 0; i < 256; i++) f.set("k" + i, "x", 60);
            long cas;
            try (var old = f.engine.get(bytes("k0"))) { cas = old.item().casId(); }
            assertThrows(ru.octomem.engine.CacheCapacityException.class, () -> f.engine.getAndTouch(bytes("k0"), 120));
            assertEquals(1, f.event(0, ClassStatsRegistry.Event.OUTOFMEMORY));
            try (var old = f.engine.get(bytes("k0"))) {
                assertEquals(cas, old.item().casId(), "Native denial preserves CAS");
                assertEquals(1_700_000_060L, old.item().expiryTime(), "Native denial preserves the original TTL");
            }
            assertEquals(256, f.counter(Counter.TOTAL_ITEMS));
            assertEquals(1, f.counter(Counter.CMD_TOUCH));
            assertEquals(0, f.counter(Counter.TOUCH_HITS));
        }
    }

    @Test void a06SnapshotBudgetDenialIsNotClassOomAndPreservesData() throws Exception {
        try (var f = new Fixture(65536, 4096, 64, "lru")) {
            String[] names = {"a", "b", "c", "d", "e", "f", "g", "h"};
            for (String name : names) f.set(name, "x", 60);
            long[] cas = new long[names.length];
            for (int i = 0; i < names.length; i++) {
                try (var old = f.engine.get(bytes(names[i]))) { cas[i] = old.item().casId(); }
            }
            KeySequence keys = new KeySequence() {
                @Override public int size() { return names.length; }
                @Override public void forEachKey(KeyVisitor visitor) {
                    for (String name : names) { byte[] key = bytes(name); visitor.accept(key, 0, key.length); }
                }
            };
            long cmdGetBefore = f.counter(Counter.CMD_GET);
            long cmdTouchBefore = f.counter(Counter.CMD_TOUCH);
            assertThrows(ru.octomem.engine.CacheCapacityException.class, () -> f.engine.snapshotAndTouch(keys, 120));
            assertThrows(ru.octomem.engine.CacheCapacityException.class, () -> f.engine.snapshot(keys));
            assertEquals(cmdTouchBefore, f.counter(Counter.CMD_TOUCH),
                    "Snapshot admission precedes the touch command count");
            assertEquals(cmdGetBefore, f.counter(Counter.CMD_GET),
                    "Snapshot admission precedes the get command count");
            assertEquals(0, f.event(0, ClassStatsRegistry.Event.OUTOFMEMORY),
                    "Snapshot-budget denial is not a class native out-of-memory event");
            assertEquals(0, f.engine.snapshotMetadataUsedBytes());
            for (int i = 0; i < names.length; i++) {
                try (var old = f.engine.get(bytes(names[i]))) {
                    assertTrue(old.found());
                    assertEquals(cas[i], old.item().casId(), "Denied snapshot preserves CAS");
                    assertEquals(1_700_000_060L, old.item().expiryTime(), "Denied snapshot preserves TTL");
                }
            }
        }
    }

    @Test void a14ServiceReservationDenialIsTypedWithoutNativeLeak() {
        try (var allocator = new FfmSlabAllocator(1024, 1024, 64)) {
            var exhausted = new StoredItem.ServiceBudget(0);
            var serviceDenied = StoredItem.reserve(exhausted, allocator, 1, 1, 64);
            assertNull(serviceDenied.reservation());
            assertFalse(serviceDenied.nativeDenied(), "Service denial must not be reported as native denial");
            assertEquals(0, allocator.usedMemory(), "Service denial must roll back the native block");
        }
        try (var tight = new FfmSlabAllocator(64, 64, 64)) {
            var budget = new StoredItem.ServiceBudget(4096);
            try (var held = StoredItem.reserve(budget, tight, 1, 1, 64).reservation()) {
                assertNotNull(held);
                var nativeDenied = StoredItem.reserve(budget, tight, 1, 1, 64);
                assertNull(nativeDenied.reservation());
                assertTrue(nativeDenied.nativeDenied(), "Native exhaustion is typed as native denial");
            }
            assertEquals(0, tight.usedMemory());
        }
    }

    @Test void a08GrowthArithmeticReplacementStartsUnfetched() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("g", "9", 2);
            try (var fetched = f.engine.get(bytes("g"))) { assertTrue(fetched.found()); }
            assertEquals(CounterResult.Status.VALUE, f.engine.increment(bytes("g"), 1_000_000).status());
            assertEquals(2, f.counter(Counter.TOTAL_ITEMS), "Genuine growth publishes one replacement");
            f.clock.advance(3);
            assertEquals(1, f.engine.reclaimExpired(32));
            assertEquals(1, f.counter(Counter.EXPIRED_UNFETCHED),
                    "A growth replacement starts unfetched even when the old version was fetched");
        }
    }

    @Test void a08PinnedArithmeticReplacementStartsUnfetched() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("n", "10", 2);
            try (var held = f.engine.get(bytes("n"))) {
                assertTrue(held.found());
                assertEquals(CounterResult.Status.VALUE, f.engine.increment(bytes("n"), 1).status());
                assertEquals(2, f.counter(Counter.TOTAL_ITEMS), "A retained reader forces a logical replacement");
            }
            f.clock.advance(3);
            assertEquals(1, f.engine.reclaimExpired(32));
            assertEquals(1, f.counter(Counter.EXPIRED_UNFETCHED),
                    "A pinned replacement starts unfetched even when the old version was fetched");
        }
    }

    @Test void a08FittingUnpinnedArithmeticPreservesFetchedState() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("p", "10", 2);
            try (var fetched = f.engine.get(bytes("p"))) { assertTrue(fetched.found()); }
            assertEquals(CounterResult.Status.VALUE, f.engine.increment(bytes("p"), 1).status());
            assertEquals(1, f.counter(Counter.TOTAL_ITEMS), "Logical in-place update keeps one item");
            f.clock.advance(3);
            assertEquals(1, f.engine.reclaimExpired(32));
            assertEquals(0, f.counter(Counter.EXPIRED_UNFETCHED),
                    "A fitting unpinned update preserves the fetched marker");
        }
    }

    @Test void a12FetchedVictimIsNotEvictedUnfetched() throws Exception {
        try (var f = new Fixture(16384, 16384, 64, "lru")) {
            f.set("a", "x", 100);
            try (var fetched = f.engine.get(bytes("a"))) { assertTrue(fetched.found()); }
            f.clock.advance(2);
            for (int i = 0; i < 255; i++) f.set("b" + i, "x", 0);
            f.clock.advance(5);
            f.set("c", "x", 0);
            assertEquals(1, f.counter(Counter.EVICTIONS));
            assertEquals(0, f.counter(Counter.EVICTED_UNFETCHED),
                    "A previously fetched victim is not counted evicted_unfetched");
            assertEquals(1, f.event(0, ClassStatsRegistry.Event.EVICTED_NONZERO));
        }
    }

    @Test void a01VersionLeadingTrailingAndBlankRejectedButEmbeddedManifestSpaceAccepted() {
        var base = new HashMap<>(Map.of("pid", "1", "version", "x", "pointer_size", "64",
                "threads", "1", "limit_maxbytes", "128"));
        for (String invalid : new String[]{" ", "  1.0.0", "1.0.0 ", "1.0.0\t", "\t1.0.0", "\n1.0.0"}) {
            base.put("version", invalid);
            assertThrows(IllegalArgumentException.class, () -> new AtomicStatsRegistry(base),
                    "Version must reject boundary whitespace: <" + invalid.replace("\n", "\\n").replace("\t", "\\t") + ">");
        }
        base.put("version", "1.0.0 (octomem)");
        assertEquals("1.0.0 (octomem)", new AtomicStatsRegistry(base).snapshot().constants().get("version"),
                "The honest manifest version with embedded spaces must remain accepted");
    }

    @Test void a18ConcurrentResourceUsageObserverAndResetStaySafe() throws Exception {
        ru.octomem.engine.BoundedTestJvm.run(StatsDistinguishingAcceptanceTest.class, "runResourceObservers", 20);
    }

    static void runResourceObservers() throws Exception {
        var fixture = new StatsDistinguishingAcceptanceTest();
        fixture.evidence = java.nio.file.Files.createTempFile(
                java.nio.file.Path.of("target", "child-test-results"), "resource-observers-", ".wire.txt");
        try {
            fixture.exerciseResourceObservers();
        } finally {
            fixture.stop();
        }
    }

    private void exerciseResourceObservers() throws Exception {
        start();
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var pool = Executors.newFixedThreadPool(3);
        List<Future<?>> observers = new ArrayList<>();
        try {
        for (int i = 0; i < 3; i++) {
            observers.add(pool.submit(() -> {
                while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        var usage = server.resourceUsage();
                        assertTrue(usage.nativeUsedBytes() >= 0 && usage.nativeTotalBytes() >= 0
                                && usage.serviceBytes() >= 0 && usage.bodyBytes() >= 0);
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                        return;
                    }
                }
            }));
        }
            for (int i = 0; i < 250; i++) {
                wire.expect("stats reset\r\n", "RESET\r\n");
                wire.expect("set a 0 0 1\r\nx\r\n", "STORED\r\n");
                wire.expect("get a\r\n", "VALUE a 0 1\r\nx\r\nEND\r\n");
            }
        } finally {
            stop.set(true);
            ru.octomem.engine.BoundedTestJvm.shutdownOrHalt(pool);
            for (Future<?> observer : observers) observer.get(5, TimeUnit.SECONDS);
        }
        assertNull(failure.get(), "Concurrent resourceUsage observations must not fail during reset");
        assertTrue(server.resourceUsage().nativeUsedBytes() >= 0);
    }

    @Test void a11MultiplePagesPerClassZeroAndNonZeroFlagsAndPageTrim() throws Exception {
        try (var f = new Fixture(1024, 256, 64, 65536, "lru")) {
            for (int i = 0; i < 5; i++) f.set("k" + i, "x", 0);
            var two = f.allocator.classStats(0);
            assertEquals(2, two.totalPages(), "A fifth class block must allocate a second physical page");
            assertEquals(8, two.totalChunks());
            assertEquals(5, two.usedChunks());
            assertEquals(3, two.freeChunks());
            assertEquals(512, f.allocator.totalMemory());
            f.set(7, "f0", "abcd", 0);
            f.set(0, "f1", "abcd", 0);
            assertEquals(7 * 64, f.allocator.usedMemory());
            assertEquals(7 * 40, f.gauge(Gauge.BYTES), "Flags do not change requested record bytes");
            for (int i = 0; i < 4; i++) assertTrue(f.engine.delete(bytes("k" + i)));
            assertEquals(256, f.allocator.trim(), "A fully emptied page must be released");
            assertEquals(256, f.allocator.totalMemory());
            var one = f.allocator.classStats(0);
            assertEquals(1, one.totalPages());
            assertEquals(4, one.totalChunks());
            assertEquals(3, one.usedChunks());
            assertEquals(1, one.freeChunks());
            assertEquals(3 * 40, f.gauge(Gauge.BYTES));
        }
    }

    @Test void a06StorageAdmissionResultRejectsInvalidOwnership() {
        StorageAdmission.Token token = () -> { };
        assertEquals(StoreResult.STORED, new StorageAdmission.Result(StoreResult.STORED, token).status());
        assertThrows(IllegalArgumentException.class, () -> new StorageAdmission.Result(StoreResult.STORED, null));
        assertThrows(IllegalArgumentException.class, () -> new StorageAdmission.Result(StoreResult.NOT_STORED, token));
        assertThrows(IllegalArgumentException.class, () -> new StorageAdmission.Result(null, null));
        token.close();
    }

    @Test void a14ReservationFinishAndReleaseFailClosed() {
        try (var allocator = new FfmSlabAllocator(1024, 1024, 64)) {
            var budget = new StoredItem.ServiceBudget(4096);
            var admission = StoredItem.reserve(budget, allocator, 2, 4, 64);
            assertNotNull(admission.reservation());
            var reservation = admission.reservation();
            var scratch = ByteBuffer.allocate(256);
            assertThrows(IllegalStateException.class, () -> reservation.finish(new byte[]{'a'},
                    (offset, dst) -> { while (dst.hasRemaining()) dst.put((byte) 'v'); }, scratch, 0, false, 0, 1),
                    "Key length mismatch must fail before any native write");
            assertThrows(IllegalStateException.class, () -> reservation.finish(new byte[]{'a', 'b'},
                    (offset, dst) -> { dst.put((byte) 'v'); }, scratch, 0, false, 0, 1),
                    "Incomplete source fill must fail closed");
            StoredItem item = reservation.finish(new byte[]{'a', 'b'},
                    (offset, dst) -> { while (dst.hasRemaining()) dst.put((byte) 'v'); }, scratch, 0, false, 0, 1);
            assertNotNull(item);
            assertEquals(4, item.valueLength());
            reservation.close();
            reservation.close();
            assertThrows(IllegalStateException.class, () -> reservation.finish(new byte[]{'a', 'b'},
                    (offset, dst) -> { while (dst.hasRemaining()) dst.put((byte) 'v'); }, scratch, 0, false, 0, 1),
                    "A transferred reservation owns no block and must fail closed");
            item.retire();
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test void a15ClosedClassSnapshotRejectsFurtherReads() {
        AtomicStatsRegistry stats = registry();
        stats.initializeClasses(1, id -> new ClassStatsRegistry.ClassState(1, 40, 100),
                new java.util.concurrent.atomic.AtomicLong(), 4096);
        var snapshot = stats.captureClasses();
        snapshot.close();
        snapshot.close();
        assertThrows(IllegalStateException.class, () -> snapshot.counter(0, Counter.GET_HITS));
        assertThrows(IllegalStateException.class, () -> snapshot.event(0, ClassStatsRegistry.Event.OUTOFMEMORY));
        assertThrows(IllegalStateException.class, () -> snapshot.state(0));
    }

    @Test void a14ReservationFinishHandlesEmptyAndMultiChunkValues() {
        try (var allocator = new FfmSlabAllocator(16384, 4096, 2048)) {
            var budget = new StoredItem.ServiceBudget(65536);
            var scratch = ByteBuffer.allocate(256);
            var emptyAdmission = StoredItem.reserve(budget, allocator, 1, 0, 2048);
            StoredItem empty = emptyAdmission.reservation().finish(new byte[]{'e'},
                    (offset, dst) -> fail("Empty value must never invoke the source"), scratch, 0, false, 0, 1);
            assertEquals(0, empty.valueLength());
            empty.retire();
            var bigAdmission = StoredItem.reserve(budget, allocator, 2, 300, 2048);
            var reservation = bigAdmission.reservation();
            StoredItem big = reservation.finish(new byte[]{'b', 'g'},
                    (offset, dst) -> { int at = offset; while (dst.hasRemaining()) dst.put((byte) (at++ % 251)); },
                    scratch, 0, false, 0, 2);
            assertEquals(300, big.valueLength());
            ByteBuffer check = ByteBuffer.allocate(300);
            assertEquals(300, big.copyValue(0, check));
            assertEquals(0, check.get(0));
            assertEquals((byte) 250, check.get(250));
            assertEquals(0, check.get(251));
            assertEquals((byte) 48, check.get(299));
            big.retire();
            assertEquals(0, allocator.usedMemory());
        }
    }
}
