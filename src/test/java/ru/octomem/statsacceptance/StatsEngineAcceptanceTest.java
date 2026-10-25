package ru.octomem.statsacceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.CounterResult;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.StoreResult;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.stats.Gauge;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;

/** Deterministic FFM/engine cases for accounting routes not controllable through socket scheduling. */
@Timeout(20)
class StatsEngineAcceptanceTest extends StatsWireFixture {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    /** Owns actual FFM pages and one segment, without background time progression. */
    private static final class Fixture implements AutoCloseable {
        final StatsWireFixture.MutableClock clock = new StatsWireFixture.MutableClock();
        final AtomicStatsRegistry stats = registry();
        final FfmSlabAllocator allocator;
        final DefaultCacheEngine engine;
        Fixture(long memory, int page, int maximum, String policy) {
            allocator = new FfmSlabAllocator(memory, page, maximum);
            engine = new DefaultCacheEngine(allocator, new SegmentedItemTable(1, 2, 1_048_576), clock, stats,
                    new DefaultCacheEngine.Settings(memory, maximum, policy, 3600));
            engine.start();
        }
        void set(String key, String value, long ttl) {
            assertEquals(StoreResult.STORED, engine.set(bytes(key), bytes(value), 0, ttl));
        }
        long counter(Counter field) { return stats.snapshot().counters().get(field); }
        long gauge(Gauge field) { return stats.snapshot().gauges().get(field); }
        @Override public void close() { try { engine.stop(); } finally { allocator.close(); } }
    }

    private static AtomicStatsRegistry registry() {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", "independent-test", "pointer_size", "64",
                "threads", "1", "limit_maxbytes", "1048576"));
    }

    @Test void a10PinnedRetiredRecordIsNotLinkedRequestedBytes() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("a", "x", 0);
            try (var old = f.engine.get(bytes("a"))) {
                long cas = old.item().casId();
                f.set("a", "y".repeat(100), 0);
                assertEquals(1, f.gauge(Gauge.CURR_ITEMS));
                assertEquals(136, f.gauge(Gauge.BYTES));
                assertEquals(2, f.counter(Counter.TOTAL_ITEMS));
                assertTrue(f.allocator.usedMemory() >= 64 + 136);
                ByteBuffer target = ByteBuffer.allocate(1);
                assertEquals(1, old.item().readValue(0, target));
                assertEquals((byte) 'x', target.get(0));
                assertEquals(cas, old.item().casId());
                assertTrue(f.engine.delete(bytes("a")));
                assertEquals(0, f.gauge(Gauge.BYTES));
                assertTrue(f.allocator.usedMemory() >= 64);
            }
            assertEquals(0, f.allocator.usedMemory());
        }
    }

    @Test void a08RetainedNumericReaderForcesLogicalReplacement() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("n", "10", 0);
            try (var old = f.engine.get(bytes("n"))) {
                assertEquals(CounterResult.Status.VALUE, f.engine.increment(bytes("n"), 1).status());
                assertEquals(2, f.counter(Counter.TOTAL_ITEMS));
                assertEquals(1, f.counter(Counter.INCR_HITS));
                ByteBuffer target = ByteBuffer.allocate(2);
                old.item().readValue(0, target);
                assertArrayEquals(bytes("10"), target.array());
            }
            assertEquals(CounterResult.Status.VALUE, f.engine.increment(bytes("n"), 1).status());
            assertEquals(2, f.counter(Counter.TOTAL_ITEMS), "Unpinned same-capacity logical update is not another stored item");
        }
    }

    @Test void a08NumericHitPrecedesFailedGrowthAndPreservesOriginal() throws Exception {
        try (var f = new Fixture(64, 64, 64, "noeviction")) {
            f.set("n", "9", 0);
            assertEquals(CounterResult.Status.OUT_OF_MEMORY, f.engine.increment(bytes("n"), 1).status());
            assertEquals(1, f.counter(Counter.INCR_HITS));
            assertEquals(1, f.counter(Counter.TOTAL_ITEMS));
            assertEquals(40, f.gauge(Gauge.BYTES));
            try (var old = f.engine.get(bytes("n"))) {
                ByteBuffer target = ByteBuffer.allocate(1);
                old.item().readValue(0, target);
                assertEquals((byte) '9', target.get(0));
            }
        }
    }

    @Test void a13CrawlerCountsUnfetchedExpiryButNotFlushedUnfetched() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("a", "x", 2);
            f.set("b", "x", 2);
            try (var fetched = f.engine.get(bytes("b"))) { assertTrue(fetched.found()); }
            f.clock.advance(3);
            assertEquals(2, f.engine.reclaimExpired(32));
            assertEquals(0, f.counter(Counter.RECLAIMED));
            assertEquals(1, f.counter(Counter.EXPIRED_UNFETCHED));
            try (var snapshot = f.stats.captureClasses()) {
                assertEquals(2, snapshot.event(0, ClassStatsRegistry.Event.CRAWLER_RECLAIMED));
            }
            f.set("c", "x", 0);
            f.engine.flushAll(0);
            assertEquals(1, f.engine.reclaimExpired(32));
            assertEquals(1, f.counter(Counter.EXPIRED_UNFETCHED));
            try (var snapshot = f.stats.captureClasses()) {
                assertEquals(3, snapshot.event(0, ClassStatsRegistry.Event.CRAWLER_RECLAIMED));
            }
        }
    }

    @Test void a13ProductSweepEligibilityIncludesExactDeadline() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("a", "x", 2);
            f.clock.advance(2);
            assertEquals(1, f.engine.reclaimExpired(32), "Coordinator msg_fb654251ac43 preserves product <= deadline");
            assertEquals(0, f.gauge(Gauge.CURR_ITEMS));
            assertEquals(1, f.counter(Counter.EXPIRED_UNFETCHED));
        }
    }

    @Test void a15ResetDoesNotForgetFetchedMarkerBeforeSweep() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("a", "x", 2);
            try (var fetched = f.engine.get(bytes("a"))) { assertTrue(fetched.found()); }
            f.stats.reset();
            f.clock.advance(3);
            assertEquals(1, f.engine.reclaimExpired(32));
            assertEquals(0, f.counter(Counter.EXPIRED_UNFETCHED));
            assertEquals(0, f.counter(Counter.TOTAL_ITEMS));
            try (var captured = f.stats.captureClasses()) {
                assertEquals(1, captured.event(0, ClassStatsRegistry.Event.CRAWLER_RECLAIMED));
            }
        }
    }

    @Test void a12EvictedAgeIsLastEventSampleNotElapsedTime() throws Exception {
        try (var f = new Fixture(16384, 16384, 64, "lru")) {
            f.set("a", "x", 100);
            f.clock.advance(2);
            for (int i = 0; i < 255; i++) f.set("b" + i, "x", 0);
            f.clock.advance(5);
            f.set("c", "x", 0);
            assertEquals(1, f.counter(Counter.EVICTIONS));
            assertEquals(1, f.counter(Counter.EVICTED_UNFETCHED));
            try (var old = f.stats.captureClasses()) {
                assertEquals(1, old.event(0, ClassStatsRegistry.Event.EVICTED_NONZERO));
                assertEquals(7, old.event(0, ClassStatsRegistry.Event.EVICTED_TIME));
            }
            f.clock.advance(20);
            try (var later = f.stats.captureClasses()) {
                assertEquals(7, later.event(0, ClassStatsRegistry.Event.EVICTED_TIME));
            }
        }
    }

    @Test void a13PressureTailExpiryIsReclaimedNotEviction() throws Exception {
        try (var f = new Fixture(128, 128, 64, "lru")) {
            f.set("a", "x", 2);
            f.set("b", "x", 0);
            f.clock.advance(3);
            f.set("c", "x", 0);
            assertEquals(1, f.counter(Counter.RECLAIMED));
            assertEquals(1, f.counter(Counter.EXPIRED_UNFETCHED));
            assertEquals(0, f.counter(Counter.EVICTIONS));
        }
    }

    @Test void a14NoevictionAdmissionFailurePreservesPinnedDataAndCountsOom() throws Exception {
        try (var f = new Fixture(16384, 16384, 64, "noeviction")) {
            for (int i = 0; i < 256; i++) f.set("k" + i, "x", 0);
            try (var pinned = f.engine.get(bytes("k0"))) {
                assertTrue(pinned.found());
                assertEquals(StoreResult.OUT_OF_MEMORY, f.engine.set(bytes("new"), bytes("x"), 0, 0));
                assertEquals(256, f.counter(Counter.CMD_SET), "Failed native admission is not an admitted storage command");
                assertEquals(256, f.counter(Counter.TOTAL_ITEMS));
                assertEquals(0, f.counter(Counter.EVICTIONS));
                try (var snapshot = f.stats.captureClasses()) {
                    assertEquals(1, snapshot.event(0, ClassStatsRegistry.Event.OUTOFMEMORY));
                    assertEquals(256, snapshot.state(0).number());
                }
                ByteBuffer target = ByteBuffer.allocate(1);
                pinned.item().readValue(0, target);
                assertEquals((byte) 'x', target.get(0));
            }
        }
    }

    @Test void a18OldEpochSnapshotSurvivesResetAndReleasesSharedAdmissionOnce() {
        AtomicStatsRegistry stats = registry();
        AtomicLong used = new AtomicLong();
        stats.initializeClasses(2, id -> new ClassStatsRegistry.ClassState(1, 40, 100), used, 4096);
        stats.incrementClass(0, Counter.GET_HITS, 7);
        stats.incrementClass(1, Counter.GET_HITS, 11);
        var old = stats.captureClasses();
        assertEquals(768, used.get(), "Approved 512+128*classCount admission");
        stats.reset();
        stats.incrementClass(0, Counter.GET_HITS, 2);
        assertEquals(7, old.counter(0, Counter.GET_HITS));
        assertEquals(11, old.counter(1, Counter.GET_HITS));
        try (var current = stats.captureClasses()) {
            assertEquals(2, current.counter(0, Counter.GET_HITS));
            assertEquals(0, current.counter(1, Counter.GET_HITS));
            assertEquals(40, current.state(0).requestedBytes());
        }
        old.close();
        old.close();
        assertEquals(0, used.get());
    }

    @Test void a21InsufficientClassSnapshotBudgetDoesNotAllocateOrEraseCounters() {
        AtomicStatsRegistry stats = registry();
        AtomicLong used = new AtomicLong();
        stats.initializeClasses(2, id -> new ClassStatsRegistry.ClassState(0, 0, 0), used, 767);
        stats.incrementClass(0, Counter.CMD_SET, 3);
        assertThrows(IllegalStateException.class, stats::captureClasses);
        assertEquals(0, used.get());
        assertEquals(3, stats.snapshot().counters().get(Counter.CMD_SET).longValue());
    }

    @Test void a15EveryGeneralCounterAndClassEventResetsButDetachedImageSurvives() {
        AtomicStatsRegistry stats = registry();
        AtomicLong used = new AtomicLong();
        stats.initializeClasses(1, id -> new ClassStatsRegistry.ClassState(2, 80, 100), used, 4096);
        for (Counter counter : Counter.values()) stats.increment(counter, 9);
        for (Gauge gauge : Gauge.values()) stats.setGauge(gauge, 17);
        for (Counter counter : new Counter[]{Counter.CMD_SET, Counter.GET_HITS, Counter.DELETE_HITS,
                Counter.INCR_HITS, Counter.DECR_HITS, Counter.CAS_HITS, Counter.CAS_BADVAL, Counter.TOUCH_HITS}) {
            stats.incrementClass(0, counter, 3);
        }
        stats.recordRemoval(0, ClassStatsRegistry.Removal.EVICTION, true, true, 23);
        stats.recordRemoval(0, ClassStatsRegistry.Removal.TAIL, true, true, 0);
        stats.recordRemoval(0, ClassStatsRegistry.Removal.CRAWLER, true, true, 0);
        stats.outOfMemory(0);
        var generalBefore = stats.snapshot();
        try (var old = stats.captureClasses()) {
            for (ClassStatsRegistry.Event event : ClassStatsRegistry.Event.values()) assertTrue(old.event(0, event) > 0, event.name());
            stats.reset();
            stats.reset();
            var after = stats.snapshot();
            for (Counter counter : Counter.values()) assertEquals(0, after.counters().get(counter).longValue(), counter.name());
            for (Gauge gauge : Gauge.values()) assertEquals(17, after.gauges().get(gauge).longValue(), gauge.name());
            assertEquals(generalBefore.constants(), after.constants());
            try (var current = stats.captureClasses()) {
                for (ClassStatsRegistry.Event event : ClassStatsRegistry.Event.values()) {
                    assertEquals(0, current.event(0, event), event.name());
                    assertTrue(old.event(0, event) > 0, "Detached old event changed: " + event);
                }
                assertEquals(new ClassStatsRegistry.ClassState(2, 80, 100), current.state(0));
                assertEquals(23, old.event(0, ClassStatsRegistry.Event.EVICTED_TIME));
            }
        }
        assertEquals(0, used.get());
    }

    @Test void a21BoundaryCounterValuesRemainPositiveAndSaturate() {
        AtomicStatsRegistry stats = registry();
        stats.initializeClasses(1, id -> new ClassStatsRegistry.ClassState(0, 0, 0), new AtomicLong(), 4096);
        stats.incrementClass(0, Counter.CMD_SET, Long.MAX_VALUE - 1);
        stats.incrementClass(0, Counter.CMD_SET, 3);
        assertEquals(Long.MAX_VALUE, stats.snapshot().counters().get(Counter.CMD_SET).longValue());
        try (var snapshot = stats.captureClasses()) {
            assertEquals(Long.MAX_VALUE, snapshot.counter(0, Counter.CMD_SET));
        }
    }

    @Test void a06TokenIssuerAndHeaderMismatchCannotPublishOrStealOwnership() {
        try (var owner = new Fixture(1_048_576, 4096, 1024, "lru");
             var other = new Fixture(1_048_576, 4096, 1024, "lru")) {
            var admitted = owner.engine.admit(StorageAdmission.Mode.SET, bytes("a"), 7, 10, 0, 1);
            assertEquals(StoreResult.STORED, admitted.status());
            assertNotNull(admitted.token());
            assertEquals(64, owner.allocator.usedMemory());
            assertEquals(0, owner.gauge(Gauge.CURR_ITEMS));
            assertEquals(1, owner.counter(Counter.CMD_SET));
            try (var token = admitted.token()) {
                assertThrows(IllegalArgumentException.class, () -> other.engine.storePrepared(token, StorageAdmission.Mode.SET,
                        bytes("a"), bytes("x"), 7, 10, 0));
                assertThrows(IllegalArgumentException.class, () -> owner.engine.storePrepared(token, StorageAdmission.Mode.SET,
                        bytes("b"), bytes("x"), 7, 10, 0));
                assertThrows(IllegalArgumentException.class, () -> owner.engine.storePrepared(token, StorageAdmission.Mode.ADD,
                        bytes("a"), bytes("x"), 7, 10, 0));
                assertThrows(IllegalArgumentException.class, () -> owner.engine.storePrepared(token, StorageAdmission.Mode.SET,
                        bytes("a"), bytes("xx"), 7, 10, 0));
                assertThrows(IllegalArgumentException.class, () -> owner.engine.storePrepared(token, StorageAdmission.Mode.SET,
                        bytes("a"), bytes("x"), 8, 10, 0));
                assertThrows(IllegalArgumentException.class, () -> owner.engine.storePrepared(token, StorageAdmission.Mode.SET,
                        bytes("a"), bytes("x"), 7, 11, 0));
                assertEquals(64, owner.allocator.usedMemory(), "Mismatch must leave token owned by original caller");
                assertEquals(0, other.allocator.usedMemory());
                assertEquals(0, other.gauge(Gauge.CURR_ITEMS));
            }
            admitted.token().close();
            assertEquals(0, owner.allocator.usedMemory());
            assertEquals(0, owner.engine.serviceMetadataUsedBytes());
            assertEquals(1, owner.counter(Counter.CMD_SET));
        }
    }

    @Test void a06TokenTransfersOneNativeBlockAndCannotBeConsumedTwice() throws Exception {
        try (var f = new Fixture(64, 64, 64, "noeviction")) {
            byte[] callerKey = bytes("a");
            var admission = f.engine.admit(StorageAdmission.Mode.SET, callerKey, 0, 0, 0, 1);
            assertEquals(StoreResult.STORED, admission.status());
            callerKey[0] = 'b';
            try (var token = admission.token()) {
                assertEquals(StoreResult.STORED, f.engine.storePrepared(token, StorageAdmission.Mode.SET,
                        bytes("a"), bytes("x"), 0, 0, 0));
                assertEquals(64, f.allocator.usedMemory(), "No second same-record allocation is possible in this budget");
                assertThrows(IllegalStateException.class, () -> f.engine.storePrepared(token, StorageAdmission.Mode.SET,
                        bytes("a"), bytes("y"), 0, 0, 0));
            }
            admission.token().close();
            assertEquals(64, f.allocator.usedMemory(), "Closing transferred token must not free published record");
            assertEquals(1, f.counter(Counter.CMD_SET));
            assertEquals(1, f.counter(Counter.TOTAL_ITEMS));
            try (var actual = f.engine.get(bytes("a"))) {
                assertTrue(actual.found());
                ByteBuffer target = ByteBuffer.allocate(1);
                actual.item().readValue(0, target);
                assertEquals((byte) 'x', target.get(0));
            }
        }
    }

    @Test void a06ConditionalRejectAndCombinedAppendFailureReleaseOnlyUnpublishedToken() throws Exception {
        try (var f = new Fixture(128, 128, 64, "noeviction")) {
            f.set("a", "x", 0);
            var add = f.engine.admit(StorageAdmission.Mode.ADD, bytes("a"), 0, 0, 0, 1);
            assertEquals(StoreResult.STORED, add.status());
            try (var token = add.token()) {
                assertEquals(StoreResult.NOT_STORED, f.engine.storePrepared(token, StorageAdmission.Mode.ADD,
                        bytes("a"), bytes("y"), 0, 0, 0));
            }
            assertEquals(64, f.allocator.usedMemory());
            var append = f.engine.admit(StorageAdmission.Mode.APPEND, bytes("a"), 0, 0, 0, 1);
            assertEquals(StoreResult.STORED, append.status());
            try (var token = append.token()) {
                assertEquals(StoreResult.NOT_STORED, f.engine.storePrepared(token, StorageAdmission.Mode.APPEND,
                        bytes("a"), bytes("y"), 0, 0, 0));
            }
            assertEquals(64, f.allocator.usedMemory());
            assertEquals(3, f.counter(Counter.CMD_SET));
            assertEquals(1, f.counter(Counter.TOTAL_ITEMS));
            assertEquals(40, f.gauge(Gauge.BYTES));
            try (var actual = f.engine.get(bytes("a"))) {
                ByteBuffer target = ByteBuffer.allocate(1);
                actual.item().readValue(0, target);
                assertEquals((byte) 'x', target.get(0));
            }
        }
    }

    @Test void a18ConcurrentResetsNeverRewriteCapturedClassCounters() throws Exception {
        AtomicStatsRegistry stats = registry();
        AtomicLong used = new AtomicLong();
        stats.initializeClasses(2, id -> new ClassStatsRegistry.ClassState(0, 0, 0), used, 8192);
        stats.incrementClass(0, Counter.GET_HITS, 13);
        stats.incrementClass(1, Counter.GET_HITS, 29);
        try (var pinned = stats.captureClasses(); var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var resets = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 1000; i++) stats.reset();
                return null;
            });
            var increments = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    stats.incrementClass(0, Counter.GET_HITS, 1);
                    try (var current = stats.captureClasses()) {
                        assertEquals(13, pinned.counter(0, Counter.GET_HITS));
                        assertEquals(29, pinned.counter(1, Counter.GET_HITS));
                        assertTrue(current.counter(0, Counter.GET_HITS) >= 0);
                    }
                }
                return null;
            });
            start.countDown();
            resets.get(5, java.util.concurrent.TimeUnit.SECONDS);
            increments.get(5, java.util.concurrent.TimeUnit.SECONDS);
            stats.reset();
            stats.incrementClass(0, Counter.GET_HITS, 7);
            try (var finalImage = stats.captureClasses()) {
                assertEquals(7, finalImage.counter(0, Counter.GET_HITS));
                assertEquals(0, finalImage.counter(1, Counter.GET_HITS));
            }
            assertEquals(7, stats.snapshot().counters().get(Counter.GET_HITS).longValue());
        }
        assertEquals(0, used.get());
    }

    @Test void a13LogicalInPlaceArithmeticPreservesFetchedStateBeforeExpiry() throws Exception {
        try (var f = new Fixture(1_048_576, 4096, 1024, "lru")) {
            f.set("n", "10", 2);
            try (var fetched = f.engine.get(bytes("n"))) { assertTrue(fetched.found()); }
            assertEquals(CounterResult.Status.VALUE, f.engine.increment(bytes("n"), 1).status());
            assertEquals(1, f.counter(Counter.TOTAL_ITEMS));
            f.clock.advance(3);
            assertEquals(1, f.engine.reclaimExpired(32));
            assertEquals(0, f.counter(Counter.EXPIRED_UNFETCHED),
                    "Logical in-place numeric update cannot turn fetched data into never-fetched data");
        }
    }

    @Test void a14TouchNativeFailureIsClassOomAndPreservesOriginalMetadata() throws Exception {
        try (var f = new Fixture(16384, 16384, 64, "noeviction")) {
            for (int i = 0; i < 256; i++) f.set("k" + i, "x", 60);
            long cas;
            try (var old = f.engine.get(bytes("k0"))) { cas = old.item().casId(); }
            assertThrows(ru.octomem.engine.CacheCapacityException.class, () -> f.engine.touch(bytes("k0"), 120));
            try (var snapshot = f.stats.captureClasses()) {
                assertEquals(1, snapshot.event(0, ClassStatsRegistry.Event.OUTOFMEMORY));
            }
            try (var old = f.engine.get(bytes("k0"))) {
                assertEquals(cas, old.item().casId());
                assertEquals(1_700_000_060L, old.item().expiryTime());
            }
            assertEquals(256, f.counter(Counter.TOTAL_ITEMS));
        }
    }

    @Test void a14MultiGatNativeFailureRollsBackBothKeysAndCountsOneClassOom() throws Exception {
        try (var f = new Fixture(32768, 32768, 64, "noeviction")) {
            for (int i = 0; i < 511; i++) f.set("k" + i, "x", 60);
            byte[] a = bytes("k0"), b = bytes("k1");
            ru.octomem.engine.KeySequence keys = new ru.octomem.engine.KeySequence() {
                @Override public int size() { return 2; }
                @Override public void forEachKey(ru.octomem.engine.KeyVisitor visitor) {
                    visitor.accept(a, 0, a.length); visitor.accept(b, 0, b.length);
                }
            };
            long casA, casB;
            try (var first = f.engine.get(a); var second = f.engine.get(b)) {
                casA = first.item().casId(); casB = second.item().casId();
            }
            assertThrows(ru.octomem.engine.CacheCapacityException.class, () -> f.engine.snapshotAndTouch(keys, 120));
            try (var captured = f.stats.captureClasses()) {
                assertEquals(1, captured.event(0, ClassStatsRegistry.Event.OUTOFMEMORY));
            }
            try (var first = f.engine.get(a); var second = f.engine.get(b)) {
                assertEquals(casA, first.item().casId()); assertEquals(casB, second.item().casId());
                assertEquals(1_700_000_060L, first.item().expiryTime());
                assertEquals(1_700_000_060L, second.item().expiryTime());
            }
            assertEquals(0, f.engine.snapshotMetadataUsedBytes());
            assertEquals(511, f.counter(Counter.TOTAL_ITEMS));
            assertEquals(511L * 64, f.allocator.usedMemory(), "First prepared copy must be rolled back after second allocation fails");
        }
    }
}
