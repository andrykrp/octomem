package ru.octomem.statsacceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.CacheCapacityException;
import ru.octomem.engine.CounterResult;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.StoreResult;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.stats.Counter;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.StoredItem;

/**
 * Independently authored T-013 acceptance evidence that exercises the
 * documented engine/storage lifecycle on a plain (non class-stats) registry and
 * the typed denial, eviction and reclaim routes. Every expected value comes from
 * the frozen oracle: an item with no TTL is never reclaimed, an expired item is
 * reclaimed as {@code expired_unfetched}, a native-allocating refusal is
 * reported as out-of-memory without publishing, and a service refusal is
 * distinct from a native denial.
 */
@Timeout(60)
class CoverageClosureEngineAcceptanceTest {
    private static byte[] b(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static final class Rig implements AutoCloseable {
        final CoverageStubs.MutableClock clock = new CoverageStubs.MutableClock();
        final CoverageStubs.PlainStats stats = new CoverageStubs.PlainStats();
        final CoverageStubs.StubAllocator allocator;
        final SegmentedItemTable table;
        final DefaultCacheEngine engine;
        Rig(int[] sizes, long memoryLimit, int itemMaxSize, String policy) {
            allocator = new CoverageStubs.StubAllocator(sizes);
            table = new SegmentedItemTable(1, 2, 1 << 20);
            engine = new DefaultCacheEngine(allocator, table, clock, stats,
                    new DefaultCacheEngine.Settings(memoryLimit, itemMaxSize, policy, 3600));
            engine.start();
        }
        long counter(Counter field) { return stats.counters.getOrDefault(field, 0L); }
        @Override public void close() { try { engine.stop(); } finally { table.close(); } }
    }

    @Test void plainRegistryReadStoreDeleteAndTouchPathsAreHonest() throws Exception {
        try (var rig = new Rig(new int[]{64, 128, 256}, 65536, 256, "lru")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("alpha"), b("1"), 7, 0));
            assertEquals(StoreResult.STORED, rig.engine.set(b("beta"), b("2"), 0, 1));
            try (var hit = rig.engine.get(b("alpha"))) {
                assertTrue(hit.found(), "A stored item is a retrieval hit");
                assertEquals(7, hit.item().flags(), "Flags are preserved literally");
            }
            assertEquals(1L, rig.counter(Counter.GET_HITS));
            rig.clock.advance(5);
            try (var expired = rig.engine.get(b("beta"))) {
                assertFalse(expired.found(), "An item past its TTL is a miss");
            }
            assertEquals(1L, rig.counter(Counter.GET_MISSES));
            assertEquals(CounterResult.Status.NOT_FOUND, rig.engine.increment(b("ghost"), 1).status());
            assertEquals(1L, rig.counter(Counter.INCR_MISSES));
            assertTrue(rig.engine.delete(b("alpha")), "Deleting a live key reports success");
            assertFalse(rig.engine.delete(b("alpha")), "Deleting a missing key reports absence");
            assertEquals(1L, rig.counter(Counter.DELETE_HITS));
            assertEquals(1L, rig.counter(Counter.DELETE_MISSES));
            assertFalse(rig.engine.touch(b("beta"), 60), "Touch of an expired item is a miss");
            assertEquals(1L, rig.counter(Counter.CMD_TOUCH));
            assertEquals(1L, rig.counter(Counter.TOUCH_MISSES));
            assertEquals(StoreResult.STORED, rig.engine.set(b("gamma"), b("x"), 0, 0));
            assertEquals(StoreResult.STORED, rig.engine.set(b("gamma"), b("y"), 0, 0));
            assertEquals(4L, rig.counter(Counter.TOTAL_ITEMS),
                    "Every publication counts total_items, including replacements");
        }
    }

    @Test void plainRegistryNativeDenialEvictsLiveAndReclaimsExpired() {
        try (var rig = new Rig(new int[]{64}, 4096, 64, "lru")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("e"), b("x"), 0, 1));
            assertEquals(StoreResult.STORED, rig.engine.set(b("l"), b("y"), 0, 0));
            try (var fetched = rig.engine.get(b("l"))) {
                assertTrue(fetched.found(), "Mark the second victim as fetched");
            }
            rig.clock.advance(5);
            rig.allocator.denyAfter = rig.allocator.allocations;
            assertEquals(StoreResult.OUT_OF_MEMORY, rig.engine.set(b("n"), b("z"), 0, 0),
                    "A native refusal is reported as out of memory");
            assertEquals(1L, rig.counter(Counter.RECLAIMED), "The expired item is reclaimed");
            assertEquals(1L, rig.counter(Counter.EVICTIONS), "The live LRU victim is evicted");
            assertEquals(1L, rig.counter(Counter.EXPIRED_UNFETCHED),
                    "The never-fetched expired item is counted expired_unfetched");
            assertEquals(0L, rig.counter(Counter.EVICTED_UNFETCHED),
                    "A fetched eviction victim is not counted evicted_unfetched");
            try (var kept = rig.engine.get(b("n"))) {
                assertFalse(kept.found(), "The refused store published nothing");
            }
        }
    }

    @Test void plainRegistryNoevictionRefusesLiveButReclaimsExpired() {
        try (var rig = new Rig(new int[]{64}, 4096, 64, "noeviction")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("live"), b("x"), 0, 0));
            assertEquals(StoreResult.STORED, rig.engine.set(b("dead"), b("y"), 0, 1));
            rig.clock.advance(5);
            rig.allocator.denyAll = true;
            assertEquals(StoreResult.OUT_OF_MEMORY, rig.engine.set(b("n"), b("z"), 0, 0));
            assertEquals(0L, rig.counter(Counter.EVICTIONS),
                    "noeviction never evicts a live victim");
            assertEquals(1L, rig.counter(Counter.RECLAIMED),
                    "noeviction still reclaims an expired victim");
            try (var kept = rig.engine.get(b("live"))) {
                assertTrue(kept.found(), "Live data survives a noeviction refusal");
            }
        }
    }

    @Test void plainRegistryCrawlerReclaimRoutesAndFlushGeneration() {
        try (var rig = new Rig(new int[]{64}, 4096, 64, "noeviction")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("a"), b("x"), 0, 1));
            assertEquals(StoreResult.STORED, rig.engine.set(b("c"), b("y"), 0, 1));
            rig.clock.advance(5);
            assertEquals(2, rig.engine.reclaimExpired(64), "Both expired mappings are reclaimed");
            assertEquals(2L, rig.counter(Counter.EXPIRED_UNFETCHED),
                    "Crawler reclaim of unfetched expired items is counted");

            assertEquals(StoreResult.STORED, rig.engine.set(b("old"), b("z"), 0, 1));
            rig.engine.flushAll(0);
            rig.clock.advance(5);
            assertEquals(1, rig.engine.reclaimExpired(64),
                    "A flushed generation is invisible and reclaimed by the crawler");
            try (var gone = rig.engine.get(b("old"))) {
                assertFalse(gone.found());
            }

            assertEquals(StoreResult.STORED, rig.engine.set(b("d"), b("q"), 0, 1));
            rig.clock.advance(5);
            rig.engine.flushAll(30);
            assertEquals(1, rig.engine.reclaimExpired(64),
                    "An expired item is reclaimed while a delayed flush is pending");
            rig.clock.advance(31);
            rig.engine.flushAll(0);
        }
    }

    @Test void plainRegistryServiceBudgetRefusalIsNotNativeDenial() {
        try (var rig = new Rig(new int[]{64}, 1024, 64, "lru")) {
            // The derived service ceiling is (memoryLimit/64)*512 = 16 charges.
            for (int i = 0; i < 16; i++) {
                assertEquals(StoreResult.STORED, rig.engine.set(b("k" + i), b("1"), 0, 0));
            }
            assertEquals(StoreResult.OUT_OF_MEMORY, rig.engine.set(b("k16"), b("1"), 0, 0),
                    "A full service budget refuses admission");
            assertEquals(CounterResult.Status.OUT_OF_MEMORY, rig.engine.increment(b("k0"), 1).status(),
                    "A service-refused arithmetic update reports out of memory");
            assertThrows(CacheCapacityException.class, () -> rig.engine.getAndTouch(b("k0"), 60),
                    "A service-refused touch reports capacity");
            assertThrows(CacheCapacityException.class,
                    () -> rig.engine.snapshotAndTouch(new CoverageStubs.Slices(b("k0")), 60),
                    "A service-refused snapshot touch reports capacity");
            try (var preserved = rig.engine.get(b("k0"))) {
                assertTrue(preserved.found(), "Refused mutations preserve the original");
            }
        }
    }

    @Test void plainRegistryAppendCombinedFailureAndOversize() {
        try (var rig = new Rig(new int[]{64, 128, 256}, 8192, 128, "lru")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("k"), b("x"), 0, 0));
            rig.allocator.denyAfter = rig.allocator.allocations + 1;
            rig.allocator.trimPositive = true;
            assertEquals(StoreResult.NOT_STORED, rig.engine.append(b("k"), b("y")),
                    "A refused combined allocation keeps the original and reports NOT_STORED");
            try (var original = rig.engine.get(b("k"))) {
                assertTrue(original.found(), "Append refusal preserves the original value");
            }
            rig.allocator.denyAfter = Integer.MAX_VALUE;
            rig.allocator.trimPositive = false;
            assertEquals(StoreResult.STORED, rig.engine.set(b("big"), b("z".repeat(80)), 0, 0));
            assertEquals(StoreResult.NOT_STORED, rig.engine.append(b("big"), b("z".repeat(80))),
                    "An admitted fragment whose combined record exceeds the limit is NOT_STORED");
        }
    }

    @Test void plainRegistryAdmitGuardsAndUnknownClassPath() {
        try (var rig = new Rig(new int[]{64}, 128, 128, "noeviction")) {
            assertEquals(StoreResult.INVALID, rig.engine.admit(
                    StorageAdmission.Mode.SET, b("k"), 0, 0, 0, -1).status(),
                    "A negative value length is invalid");
            StorageAdmission.Result admitted = rig.engine.admit(
                    StorageAdmission.Mode.SET, b("k"), 0, 0, 0, 1);
            assertEquals(StoreResult.STORED, admitted.status());
            assertThrows(IllegalArgumentException.class, () -> rig.engine.storePrepared(
                    () -> { }, StorageAdmission.Mode.SET, b("k"), b("v"), 0, 0, 0),
                    "A foreign token is not an engine-owned admission");
            assertThrows(IllegalArgumentException.class, () -> rig.engine.storePrepared(
                    admitted.token(), StorageAdmission.Mode.ADD, b("k"), b("v"), 0, 0, 0),
                    "A mismatched operation leaves the token caller-owned");
            assertEquals(StoreResult.STORED, rig.engine.storePrepared(
                    admitted.token(), StorageAdmission.Mode.SET, b("k"), b("v"), 0, 0, 0));

            // A record whose aligned size exceeds every allocator class but fits
            // the configured item maximum has no class and is refused natively.
            assertEquals(StoreResult.OUT_OF_MEMORY, rig.engine.set(b("q"), b("m".repeat(60)), 0, 0),
                    "A record with no size class cannot be admitted");
        }
    }

    @Test void plainRegistryRetryExhaustionStopsAtRetryBound() {
        try (var rig = new Rig(new int[]{64}, 65536, 64, "noeviction")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("k"), b("1"), 0, 0));
            assertEquals(StoreResult.STORED, rig.engine.set(b("a"), b("x"), 0, 0));
            rig.allocator.denyAll = true;
            rig.allocator.trimPositive = true;
            assertEquals(StoreResult.OUT_OF_MEMORY, rig.engine.set(b("fresh"), b("v"), 0, 0));
            assertEquals(CounterResult.Status.OUT_OF_MEMORY, rig.engine.increment(b("k"), 1).status());
            assertThrows(CacheCapacityException.class, () -> rig.engine.touch(b("k"), 5));
            assertThrows(CacheCapacityException.class,
                    () -> rig.engine.snapshotAndTouch(new CoverageStubs.Slices(b("k")), 5));
        }
    }

    @Test void storedItemReserveValidationAndNumericMarkerGuards() {
        try (var allocator = new CoverageStubs.StubAllocator(64, 128, 256)) {
            var budget = new StoredItem.ServiceBudget(1 << 20);
            assertThrows(IllegalArgumentException.class, () -> StoredItem.reserve(budget, allocator, 0, 1, 1000));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.reserve(budget, allocator, 251, 1, 1000));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.reserve(budget, allocator, 1, -1, 1000));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.reserve(budget, allocator, 1, 500, 64));

            var exhausted = new StoredItem.ServiceBudget(0);
            var denied = StoredItem.reserve(exhausted, allocator, 1, 1, 64);
            assertNull(denied.reservation(), "An exhausted service budget refuses before charging");
            assertFalse(denied.nativeDenied(), "A service refusal is not a native denial");

            StoredItem item = StoredItem.create(allocator, b("k"), b("12345"), 0, 0, 1, 256);
            assertNotNull(item);
            assertThrows(IllegalStateException.class, () -> item.initializeNumericLength(-1));
            assertThrows(IllegalStateException.class, () -> item.initializeNumericLength(6));
            try (var lease = item.pin()) {
                assertNotNull(lease);
                assertThrows(IllegalStateException.class, () -> item.initializeNumericLength(0));
            }
            item.initializeNumericLength(5);
            assertEquals(5, item.numericLength());
            item.retire();

            StoredItem retired = StoredItem.create(allocator, b("k"), b("1"), 0, 0, 2, 256);
            assertNotNull(retired);
            retired.retire();
            assertThrows(IllegalStateException.class, () -> retired.initializeNumericLength(0));

            SegmentedItemTable table = new SegmentedItemTable(1, 2, 1 << 20);
            try {
                assertTrue(table.initializeClassQueues(3));
                StoredItem published = StoredItem.create(allocator, b("p"), b("1"), 0, 0, 3, 256);
                assertNotNull(published);
                published.initializeVisibility(1, 1);
                assertTrue(table.put(b("p"), published), "Publishing transfers the record");
                assertThrows(IllegalStateException.class, () -> published.initializeNumericLength(0),
                        "A published record is no longer exclusively owned");
                assertTrue(published.keyEquals(b("p"), 0, 1));
            } finally {
                table.close();
            }
        }
    }

    @Test void storedItemLegacyCreateGuardsAndAllocationDenial() {
        try (var allocator = new CoverageStubs.StubAllocator(64, 128, 256)) {
            assertThrows(IllegalArgumentException.class,
                    () -> StoredItem.create(allocator, b("k"), b("v"), 0, -1L, 1, 256),
                    "A negative legacy deadline is rejected");
            var scratch = ByteBuffer.allocate(256);
            assertNull(StoredItem.create(allocator, b("k"), 0, 1, 400,
                    (offset, dst) -> { while (dst.hasRemaining()) { dst.put((byte) 'v'); } },
                    scratch, 0, false, 0, 1, 64),
                    "A record beyond the item maximum is refused without allocating");

            allocator.denyAll = true;
            assertNull(StoredItem.create(allocator, b("k"), 0, 1, 1,
                    (offset, dst) -> { while (dst.hasRemaining()) { dst.put((byte) 'v'); } },
                    scratch, 0, false, 0, 1, 64),
                    "A denied native allocation returns no record");
            assertNull(StoredItem.create(allocator, b("k"), b("v"), 0, 0, 1, 64),
                    "A denied legacy allocation returns no record");
        }
    }

    @Test void reservationFinishRejectsIncompleteNativeWrites() {
        try (var allocator = new CoverageStubs.StubAllocator(64, 128, 256)) {
            var budget = new StoredItem.ServiceBudget(1 << 20);
            var scratch = ByteBuffer.allocate(256);
            for (int failingCall : new int[]{1, 2, 3}) {
                var admission = StoredItem.reserve(budget, allocator, 2, 4, 64);
                assertNotNull(admission.reservation());
                allocator.lastHandle.failWriteCall = failingCall;
                assertThrows(IllegalStateException.class, () -> admission.reservation().finish(
                        b("ab"), 0, 2,
                        (offset, dst) -> { while (dst.hasRemaining()) { dst.put((byte) 'v'); } },
                        scratch, 0, false, 0, 1),
                        "Incomplete native write " + failingCall + " must fail closed");
                admission.reservation().close();
            }
        }
    }

    @Test void segmentedTableEpochAllowanceAndKeyGuards() {
        SegmentedItemTable table = new SegmentedItemTable(2, 2, 1 << 20);
        assertThrows(IllegalArgumentException.class, () -> table.reserveEpochAllowance(-1));
        assertTrue(table.initializeClassQueues(2));
        assertThrows(IllegalStateException.class, () -> table.reserveEpochAllowance(0),
                "A configured table cannot accept a transient epoch");
        assertThrows(IllegalArgumentException.class, () -> table.segmentId(new byte[0], 0, 0));
        assertThrows(IllegalArgumentException.class, () -> table.segmentId(new byte[251], 0, 251));
        assertTrue((table.segmentId(b("k"), 0, 1) & ~1) == 0);
        table.close();
        var state = table.classState(0);
        assertEquals(0, state.number(), "A closed table reports empty class state");

        try (var allocator = new CoverageStubs.StubAllocator(64)) {
            SegmentedItemTable mapped = new SegmentedItemTable(1, 2, 1 << 20);
            assertTrue(mapped.initializeClassQueues(1));
            StoredItem item = StoredItem.create(allocator, b("k"), b("v"), 0, 0, 1, 64);
            assertNotNull(item);
            item.initializeVisibility(1, 1);
            assertTrue(mapped.put(b("k"), item));
            assertThrows(IllegalStateException.class, () -> mapped.reserveEpochAllowance(0),
                    "A table holding a mapping cannot accept a transient epoch");
            mapped.close();
        }
    }

    @Test void plainRegistrySnapshotRemovesExpiredInvisibleMapping() throws Exception {
        try (var rig = new Rig(new int[]{64}, 65536, 64, "noeviction")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("k"), b("v"), 0, 1));
            rig.clock.advance(5);
            var snapshot = rig.engine.snapshot(new CoverageStubs.Slices(b("k")));
            try {
                assertFalse(snapshot.values().hasNext(), "An expired mapping is not a hit");
            } finally {
                snapshot.close();
            }
            assertEquals(1L, rig.counter(Counter.GET_MISSES));
            assertFalse(rig.engine.delete(b("k")),
                    "The crawler-free capture path unlinks the invisible mapping");
        }
    }

    @Test void plainRegistryPrependCombinedFailurePreservesOriginal() {
        try (var rig = new Rig(new int[]{64, 128, 256}, 8192, 128, "lru")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("k"), b("x"), 0, 0));
            rig.allocator.denyAfter = rig.allocator.allocations + 1;
            rig.allocator.trimPositive = true;
            assertEquals(StoreResult.NOT_STORED, rig.engine.prepend(b("k"), b("y")),
                    "A refused combined prepend allocation reports NOT_STORED");
            rig.allocator.denyAfter = Integer.MAX_VALUE;
            rig.allocator.trimPositive = false;
            try (var original = rig.engine.get(b("k"))) {
                assertTrue(original.found(), "Prepend refusal preserves the original value");
            }
        }
    }

    @Test void counterTrailingPaddingAndBackwardClockAge() {
        try (var rig = new Rig(new int[]{64, 128}, 65536, 128, "lru")) {
            assertEquals(StoreResult.STORED, rig.engine.set(b("n"), b("1000"), 0, 0));
            assertEquals(CounterResult.Status.VALUE, rig.engine.decrement(b("n"), 999).status());
            assertEquals(CounterResult.Status.VALUE, rig.engine.increment(b("n"), 1).status());

            rig.clock.set(Long.MIN_VALUE);
            assertEquals(StoreResult.STORED, rig.engine.set(b("old"), b("x"), 0, 0));
            rig.clock.set(10);
            assertEquals(StoreResult.STORED, rig.engine.set(b("new"), b("y"), 0, 0));
            rig.allocator.denyAll = true;
            assertEquals(StoreResult.OUT_OF_MEMORY, rig.engine.set(b("boom"), b("z"), 0, 0));
        }
    }
}
