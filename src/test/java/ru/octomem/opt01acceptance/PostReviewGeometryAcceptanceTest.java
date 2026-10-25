package ru.octomem.opt01acceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.StoreResult;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry.ClassState;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.SlabHandle;
import ru.octomem.storage.StoredItem;

/**
 * Independent R1-R3 regression oracles from the post-OPT architect checkpoint
 * and task_655547050089, not from production charge/selection helpers.
 * Geometry charge is align8(24+4*N); table base is 128+8*S+S*(256+8*B),
 * and existing queues cost 512+128*N+S*(192+24*N). Logical release is not RSS.
 * Fixtures implement the public allocator contract; none mutate production by reflection.
 */
@Timeout(30)
class PostReviewGeometryAcceptanceTest {
    private static final long MEMORY = 1L << 20;
    private static final long BASE = 408; // S=1, B=2, independently substituted.

    @Test
    void r1InvalidGeometryFailsClosedWithoutTakingAllocatorOwnership() {
        int[][] invalid = {{128, 64, 256}, {0}, {-8}, {64, 64}, {64, 0}, {128, 64}};
        for (int[] sizes : invalid) {
            try (FfmSlabAllocator nativeAllocator = nativeAllocator();
                 SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
                Geometry allocator = new Geometry(nativeAllocator, table, sizes);
                assertThrows(IllegalArgumentException.class, () -> engine(allocator, table),
                        Arrays.toString(sizes));
                assertReleased(table);
                assertAllocatorUsable(nativeAllocator);
            }
        }
    }

    @Test
    void r1InvalidCountsAndMismatchedClassIdentifiersFailClosed() {
        for (int count : new int[] {-1, 0}) {
            try (FfmSlabAllocator nativeAllocator = nativeAllocator();
                 SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
                Geometry allocator = new Geometry(nativeAllocator, table, 64);
                allocator.count = count;
                assertThrows(IllegalArgumentException.class, () -> engine(allocator, table));
                assertEquals(0, allocator.reads);
                assertReleased(table);
                assertAllocatorUsable(nativeAllocator);
            }
        }
        try (FfmSlabAllocator nativeAllocator = nativeAllocator();
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            Geometry allocator = new Geometry(nativeAllocator, table, 64, 128);
            allocator.wrongId = true;
            assertThrows(IllegalArgumentException.class, () -> engine(allocator, table));
            assertReleased(table);
            assertAllocatorUsable(nativeAllocator);
        }
    }

    @Test
    void r1ChangingCountDuringCaptureFailsClosed() {
        for (int changeAfter : new int[] {1, 2}) {
          try (FfmSlabAllocator nativeAllocator = nativeAllocator();
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            Geometry allocator = new Geometry(nativeAllocator, table, 64, 128);
            allocator.changeCountAfter = changeAfter;
            assertThrows(IllegalArgumentException.class, () -> engine(allocator, table));
            assertReleased(table);
            assertAllocatorUsable(nativeAllocator);
          }
        }
    }

    @Test
    void r1ValidSnapshotSupportsExpiryAcrossDelayedFlushBoundary() {
        for (long advance : new long[] {4, 6}) {
            try (FfmSlabAllocator allocator = nativeAllocator();
                 SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
                AtomicLong now = new AtomicLong(Opt01Fixtures.NOW);
                ru.octomem.util.Clock clock = new ru.octomem.util.Clock() {
                    @Override public long currentTimeSeconds() { return now.get(); }
                    @Override public long nanoTime() { return 0; }
                };
                DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, clock,
                        Opt01Fixtures.registry(MEMORY),
                        new DefaultCacheEngine.Settings(MEMORY, 256, "noeviction", 3600));
                try {
                    engine.start();
                    assertEquals(StoreResult.STORED, engine.set(new byte[] {'k'}, new byte[] {1}, 0, 3));
                    engine.flushAll(5);
                    now.addAndGet(advance);
                    assertEquals(1, engine.reclaimExpired(10), "expired item removed before or after delayed flush");
                    assertEquals(0, table.size());
                    try (var result = engine.get(new byte[] {'k'})) { assertNull(result.item()); }
                } finally { engine.stop(); }
                assertReleased(table);
                assertAllocatorUsable(allocator);
            }
        }
    }

    @Test
    void r3GeometryCountSnapshotAndNullStatsFailuresReleaseAcceptedTable() {
        for (int mode = 0; mode < 5; mode++) {
            try (FfmSlabAllocator nativeAllocator = nativeAllocator();
                 SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
                Geometry allocator = new Geometry(nativeAllocator, table, 64, 128);
                allocator.failureMode = mode + 1;
                Throwable failure = assertThrows(Throwable.class, () -> engine(allocator, table));
                if (mode == 2) assertInstanceOf(IllegalArgumentException.class, failure);
                else assertSame(mode == 4 ? allocator.error : allocator.failure, failure,
                        "original failure must propagate");
                assertReleased(table);
                assertAllocatorUsable(nativeAllocator);
            }
        }
    }

    @Test
    void r2ExactMinimalBudgetAccountsGeometryWhileAliveAndReleasesOnStop() {
        for (int[] sizes : new int[][] {{64}, {64, 128}, {64, 128, 256}}) {
            long expected = BASE + queues(sizes.length) + geometryCharge(sizes.length);
            try (FfmSlabAllocator nativeAllocator = nativeAllocator();
                 SegmentedItemTable table = new SegmentedItemTable(1, 2, expected)) {
                Geometry allocator = new Geometry(nativeAllocator, table, sizes);
                DefaultCacheEngine engine = engine(allocator, table);
                try {
                    assertEquals(expected, table.metadataUsedBytes(), "charge exactly once before start");
                    assertEquals(geometryCharge(sizes.length), table.classGeometryMetadataUsedBytes());
                    assertEquals(sizes.length, allocator.reads);
                    assertTrue(allocator.minimumObservedCharge >= BASE + geometryCharge(sizes.length),
                            "reservation precedes snapshot population");
                    engine.start();
                    assertEquals(expected, table.metadataUsedBytes(), "fixed charge remains while alive");
                } finally {
                    engine.stop();
                }
                assertReleased(table);
                assertAllocatorUsable(nativeAllocator);
            }
        }
    }

    @Test
    void r2OneByteShortCannotExposePartiallyConfiguredEngine() {
        for (long limit : new long[] {BASE, BASE + 31, BASE + queues(1) + 31}) {
            try (FfmSlabAllocator nativeAllocator = nativeAllocator();
                 SegmentedItemTable table = new SegmentedItemTable(1, 2, limit)) {
                Geometry allocator = new Geometry(nativeAllocator, table, 64);
                assertThrows(IllegalArgumentException.class, () -> engine(allocator, table));
                if (limit < BASE + 32) assertEquals(0, allocator.reads,
                        "denied snapshot reservation must not query class stats");
                assertReleased(table);
                assertAllocatorUsable(nativeAllocator);
            }
        }
    }

    @Test
    void r2HugeCountUsesLongChargeAndDeniesBeforeArrayAllocation() {
        // 24 + 4*Integer.MAX_VALUE rounds to 8,589,934,616, not an int-sized charge.
        assertEquals(8_589_934_616L, geometryCharge(Integer.MAX_VALUE));
        assertEquals(8_589_934_616L, SegmentedItemTable.classGeometryMetadataBytes(Integer.MAX_VALUE));
        try (FfmSlabAllocator nativeAllocator = nativeAllocator();
             SegmentedItemTable table = new SegmentedItemTable(1, 2,
                     BASE + 8_589_934_616L - 1)) {
            Geometry allocator = new Geometry(nativeAllocator, table, 64);
            allocator.count = Integer.MAX_VALUE;
            assertThrows(IllegalArgumentException.class, () -> engine(allocator, table),
                    "must deny checked logical charge, not attempt multi-gigabyte array/OOME");
            assertEquals(0, allocator.reads);
            assertReleased(table);
            assertAllocatorUsable(nativeAllocator);
        }
    }

    @Test
    void r2DefaultFortyFourClassesChargeTwoHundredBytes() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(MEMORY, 4096, 1_048_576);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            assertEquals(44, allocator.classCount());
            DefaultCacheEngine engine = engine(allocator, table);
            try {
                assertEquals(BASE + queues(44) + 200, table.metadataUsedBytes());
                assertEquals(200, table.classGeometryMetadataUsedBytes());
            } finally {
                engine.stop();
            }
            assertReleased(table);
            assertAllocatorUsable(allocator);
        }
    }

    @Test
    void r3AlreadyInitializedRegistryFailureClosesOwnedTableNotAllocator() {
        try (FfmSlabAllocator allocator = nativeAllocator();
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            AtomicStatsRegistry registry = Opt01Fixtures.registry(MEMORY);
            registry.initializeClasses(1, ignored -> new ClassState(0, 0, 0), new AtomicLong(), 64);
            assertThrows(IllegalArgumentException.class,
                    () -> Opt01Fixtures.engine(allocator, table, registry, MEMORY, 256, "noeviction"));
            assertReleased(table);
            assertAllocatorUsable(allocator);
        }
    }

    @Test
    void r3NullPreconditionsPreserveCallerTableAndAllocator() {
        for (int absent = 0; absent < 5; absent++) {
            final int missing = absent;
            try (FfmSlabAllocator allocator = nativeAllocator();
                 SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
                assertThrows(NullPointerException.class, () -> new DefaultCacheEngine(
                        missing == 0 ? null : allocator, missing == 1 ? null : table,
                        missing == 2 ? null : Opt01Fixtures.fixedClock(),
                        missing == 3 ? null : Opt01Fixtures.registry(MEMORY),
                        missing == 4 ? null : new DefaultCacheEngine.Settings(MEMORY, 256, "noeviction", 3600)));
                assertFalse(table.isClosed());
                assertEquals(BASE, table.metadataUsedBytes());
                assertTrue(table.initializeClassQueues(1), "caller can still configure rejected table");
                assertAllocatorUsable(allocator);
            }
        }
    }

    @Test
    void r3ClosedAndNonemptyPreconditionsDoNotAcquireCallerResources() {
        try (FfmSlabAllocator allocator = nativeAllocator();
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            byte[] key = Opt01Fixtures.bytes("kept");
            StoredItem item = StoredItem.create(allocator, key, new byte[] {42}, 0, 0, 1, 256);
            assertNotNull(item);
            assertTrue(table.put(key, item));
            long before = table.metadataUsedBytes();
            assertThrows(IllegalArgumentException.class, () -> engine(allocator, table));
            assertFalse(table.isClosed());
            assertFalse(item.isRetired());
            assertEquals(1, table.size());
            assertEquals(before, table.metadataUsedBytes());
            try (var lease = table.get(key)) { assertNotNull(lease); }
            assertAllocatorUsable(allocator);
            table.close();
            assertThrows(IllegalArgumentException.class, () -> engine(allocator, table));
            assertReleased(table);
            assertAllocatorUsable(allocator);
        }
    }

    @Test
    void r1ValidCustomGeometryUsesStableIndexedClassesAndNeverRereads() {
        try (FfmSlabAllocator nativeAllocator = nativeAllocator();
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            // Fixed sparse custom geometry, no assumptions about built-in growth.
            Geometry allocator = new Geometry(nativeAllocator, table, 64, 128, 256);
            DefaultCacheEngine engine = engine(allocator, table);
            try {
                long[] requests = {1, 64, 65, 96, 128, 129, 256, 257};
                int[] expected = {0, 0, 1, 1, 1, 2, 2, -1};
                for (int i = 0; i < requests.length; i++)
                    assertEquals(expected[i], Opt01Fixtures.classFor(engine, requests[i]));
                engine.start();
                assertEquals(StoreResult.STORED, engine.set(new byte[] {'k'}, new byte[] {1}, 0, 0));
                assertTrue(engine.delete(new byte[] {'k'}));
                assertEquals(3, allocator.reads, "commands must not resample stable geometry");
            } finally { engine.stop(); }
        }
    }

    @Test
    void r1MemoryAllocatorMethodSetIsUnchanged() {
        Set<String> actual = realMethods(MemoryAllocator.class);
        assertEquals(Set.of("trim[]:long", "allocate[int]:SlabHandle",
                "free[interface ru.octomem.storage.SlabHandle]:void", "totalMemory[]:long",
                "usedMemory[]:long", "classCount[]:int", "classStats[int]:SlabClassStats"), actual);
        assertEquals(Set.of("unexpected[]:void"), realMethods(ExtraApi.class),
                "a non-synthetic extra method cannot be hidden by instrumentation filtering");
    }

    private static Set<String> realMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(method -> method.getName() + Arrays.toString(method.getParameterTypes())
                        + ":" + method.getReturnType().getSimpleName()).collect(Collectors.toSet());
    }

    private interface ExtraApi { void unexpected(); }

    @Test
    void r2ChargeApiMatchesIndependentAlignmentAndRejectsNonpositiveCounts() {
        for (int count : new int[] {1, 2, 3, 44, 45, 536_870_912, Integer.MAX_VALUE})
            assertEquals(geometryCharge(count), SegmentedItemTable.classGeometryMetadataBytes(count));
        assertEquals(32, SegmentedItemTable.classGeometryMetadataBytes(1));
        assertEquals(200, SegmentedItemTable.classGeometryMetadataBytes(44));
        assertThrows(IllegalArgumentException.class, () -> SegmentedItemTable.classGeometryMetadataBytes(0));
        assertThrows(IllegalArgumentException.class, () -> SegmentedItemTable.classGeometryMetadataBytes(-1));
        try (SegmentedItemTable table = new SegmentedItemTable(1, 2, BASE + 32)) {
            assertEquals(0, table.classGeometryMetadataUsedBytes());
            assertTrue(table.reserveClassGeometry(1));
            assertEquals(32, table.classGeometryMetadataUsedBytes());
            assertEquals(BASE + 32, table.metadataUsedBytes());
            table.close();
            assertReleased(table);
        }
        try (SegmentedItemTable table = new SegmentedItemTable(1, 2, BASE + 31)) {
            assertFalse(table.reserveClassGeometry(1));
            assertEquals(0, table.classGeometryMetadataUsedBytes());
            assertEquals(BASE, table.metadataUsedBytes(), "denied reservation has no partial charge");
        }
    }

    @Test
    void r2DirectReservationGuardsPreservePriorCallerState() {
        try (SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            assertThrows(IllegalArgumentException.class, () -> table.reserveClassGeometry(0));
            assertEquals(BASE, table.metadataUsedBytes());
            assertTrue(table.reserveClassGeometry(1));
            assertThrows(IllegalStateException.class, () -> table.reserveClassGeometry(1));
            assertEquals(BASE + 32, table.metadataUsedBytes());
            assertEquals(32, table.classGeometryMetadataUsedBytes());
            assertFalse(table.isClosed());
        }
        try (SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            assertTrue(table.initializeClassQueues(1));
            assertThrows(IllegalStateException.class, () -> table.reserveClassGeometry(1));
            assertEquals(BASE + queues(1), table.metadataUsedBytes());
            assertEquals(0, table.classGeometryMetadataUsedBytes());
            assertFalse(table.isClosed());
        }
        try (FfmSlabAllocator allocator = nativeAllocator();
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            byte[] key = {'k'};
            StoredItem item = StoredItem.create(allocator, key, new byte[] {1}, 0, 0, 1, 256);
            assertNotNull(item);
            assertTrue(table.put(key, item));
            long before = table.metadataUsedBytes();
            assertThrows(IllegalStateException.class, () -> table.reserveClassGeometry(1));
            assertEquals(before, table.metadataUsedBytes());
            assertEquals(0, table.classGeometryMetadataUsedBytes());
            assertEquals(1, table.size());
            assertFalse(item.isRetired());
            assertFalse(table.isClosed());
        }
        try (SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            table.close();
            assertThrows(IllegalStateException.class, () -> table.reserveClassGeometry(1));
            assertReleased(table);
        }
    }

    private static long geometryCharge(int classes) { return ((24L + 4L * classes + 7) / 8) * 8; }
    private static long queues(int classes) { return 512L + 128L * classes + 192L + 24L * classes; }
    private static FfmSlabAllocator nativeAllocator() { return new FfmSlabAllocator(MEMORY, 4096, 256); }
    private static DefaultCacheEngine engine(MemoryAllocator allocator, SegmentedItemTable table) {
        return Opt01Fixtures.engine(allocator, table, MEMORY, 256, "noeviction");
    }
    private static void assertReleased(SegmentedItemTable table) {
        assertTrue(table.isClosed(), "accepted table must fail closed");
        assertEquals(0, table.metadataUsedBytes(), "all logical reservations released");
        assertEquals(0, table.classGeometryMetadataUsedBytes(), "snapshot reservation released");
        assertThrows(IllegalStateException.class, () -> table.get(new byte[] {'k'}));
    }
    private static void assertAllocatorUsable(FfmSlabAllocator allocator) {
        long before = allocator.usedMemory();
        SlabHandle handle = allocator.allocate(64);
        assertNotNull(handle, "caller-owned allocator remains usable");
        allocator.free(handle);
        assertEquals(before, allocator.usedMemory());
    }

    /** Public-boundary fixture; malformed snapshots deliberately violate R1. */
    private static final class Geometry implements MemoryAllocator {
        private final FfmSlabAllocator delegate;
        private final SegmentedItemTable table;
        private final int[] sizes;
        private int count;
        private int reads;
        private int failureMode;
        private boolean wrongId;
        private int changeCountAfter = Integer.MAX_VALUE;
        private int countReads;
        private long minimumObservedCharge = Long.MAX_VALUE;
        private final RuntimeException failure = new IllegalStateException("fixture geometry failure");
        private final Error error = new AssertionError("fixture geometry error");
        Geometry(FfmSlabAllocator delegate, SegmentedItemTable table, int... sizes) {
            this.delegate = delegate;
            this.table = table;
            this.sizes = sizes.clone();
            this.count = sizes.length;
        }
        @Override public int classCount() {
            if (failureMode == 1) throw failure;
            if (countReads++ >= changeCountAfter) return count + 1;
            return count;
        }
        @Override public SlabClassStats classStats(int id) {
            reads++;
            minimumObservedCharge = Math.min(minimumObservedCharge, table.metadataUsedBytes());
            if (failureMode == 2 && id == 1) throw failure;
            if (failureMode == 3) return null;
            if (failureMode == 5) throw error;
            if (failureMode == 4) return new SlabClassStats() {
                @Override public int classId() { return id; }
                @Override public int chunkSize() { throw failure; }
                @Override public long totalPages() { return 0; }
                @Override public long totalChunks() { return 0; }
                @Override public long usedChunks() { return 0; }
                @Override public long freeChunks() { return 0; }
            };
            return new GeometryStats(wrongId ? id + 1 : id, sizes[id]);
        }
        @Override public SlabHandle allocate(int size) { return delegate.allocate(size); }
        @Override public void free(SlabHandle handle) { delegate.free(handle); }
        @Override public long totalMemory() { return delegate.totalMemory(); }
        @Override public long usedMemory() { return delegate.usedMemory(); }
    }

    private record GeometryStats(int classId, int chunkSize) implements SlabClassStats {
        @Override public long totalPages() { return 0; }
        @Override public long totalChunks() { return 0; }
        @Override public long usedChunks() { return 0; }
        @Override public long freeChunks() { return 0; }
    }
}
