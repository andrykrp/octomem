package ru.octomem.storage;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.octomem.stats.SlabClassStats;

import static org.junit.jupiter.api.Assertions.*;

/** Independent literal-contract tests; oracle frozen in tests/reports/T009-table. */
class SegmentedItemTableTest {
    @Test
    void constructorValidationAndLiteralInitialCharges() {
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 0, 3, 6}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SegmentedItemTable(invalid, 2, 10000));
            assertThrows(IllegalArgumentException.class,
                    () -> new SegmentedItemTable(1, invalid, 10000));
        }
        assertThrows(IllegalArgumentException.class, () -> new SegmentedItemTable(1, 1, 10000));
        for (long invalid : new long[]{Long.MIN_VALUE, -1, 0, 407}) {
            assertThrows(IllegalArgumentException.class, () -> new SegmentedItemTable(1, 2, invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentedItemTable(1 << 30, 1 << 30, Long.MAX_VALUE));
        try (var table = new SegmentedItemTable(1, 2, 408)) {
            assertEquals(408, table.metadataUsedBytes());
            assertEquals(0, table.size());
            assertFalse(table.isClosed());
        }
        try (var table = new SegmentedItemTable(4, 8, 1440)) {
            assertEquals(1440, table.metadataUsedBytes());
        }
    }

    @Test
    void validationDoesNotTakeCallerOwnership() {
        try (var allocator = allocator(); var table = new SegmentedItemTable(1, 2, 4096)) {
            StoredItem candidate = item(allocator, key(1), 11);
            try {
                assertThrows(NullPointerException.class, () -> table.put(null, candidate));
                assertThrows(NullPointerException.class, () -> table.put(key(1), null));
                assertThrows(NullPointerException.class, () -> table.get(null));
                assertThrows(NullPointerException.class, () -> table.remove(null));
                for (byte[] invalid : new byte[][]{new byte[0], new byte[251]}) {
                    assertThrows(IllegalArgumentException.class, () -> table.put(invalid, candidate));
                    assertThrows(IllegalArgumentException.class, () -> table.get(invalid));
                    assertThrows(IllegalArgumentException.class, () -> table.remove(invalid));
                }
                assertThrows(IllegalArgumentException.class, () -> table.put(key(2), candidate));
                assertThrows(IllegalArgumentException.class, () -> table.put(new byte[]{1}, candidate));
                assertFalse(candidate.isRetired());
                assertEquals(0, candidate.pinCount());
                assertEquals(408, table.metadataUsedBytes());
                assertEquals(0, table.size());
            } finally {
                candidate.retire();
            }
            for (int length : new int[]{1, 250}) {
                byte[] key = new byte[length];
                Arrays.fill(key, (byte) 0xff);
                StoredItem boundary = item(allocator, key, length);
                assertTrue(table.put(key, boundary));
                assertValue(table, key.clone(), length);
                assertTrue(table.remove(key));
            }
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void replacementPreservesOldLeaseAndSameOwnerIsNoOpAtExactCap() {
        try (var allocator = allocator(); var table = new SegmentedItemTable(1, 2, 472)) {
            byte[] key = key(42);
            StoredItem old = item(allocator, key, 101);
            StoredItem newer = item(allocator, key, 202);
            assertTrue(table.put(key, old));
            try (ItemLease lease = table.get(key.clone())) {
                assertNotNull(lease);
                assertEquals(1, old.pinCount());
                assertTrue(table.put(key.clone(), old));
                assertFalse(old.isRetired());
                assertEquals(1, old.pinCount());
                assertTrue(table.put(key, newer));
                assertTrue(old.isRetired());
                assertNull(old.pin());
                assertEquals(old.allocatedBytes() + newer.allocatedBytes(), allocator.usedMemory());
                assertItem(lease.item(), key, 101);
                assertValue(table, key, 202);
                assertEquals(1, table.size());
                assertEquals(472, table.metadataUsedBytes());
            }
            assertEquals(newer.allocatedBytes(), allocator.usedMemory());
            assertTrue(table.remove(key));
            assertFalse(table.remove(key));
            assertTrue(newer.isRetired());
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void closeRetiresAllButIndependentViewsKeepTheirOwnLease() {
        try (var allocator = allocator()) {
            var table = new SegmentedItemTable(4, 4, 4096);
            StoredItem first = item(allocator, key(1), 10);
            StoredItem second = item(allocator, key(2), 20);
            assertTrue(table.put(key(1), first));
            assertTrue(table.put(key(2), second));
            try (ItemLease a = table.get(key(1)); ItemLease b = table.get(key(1));
                 ItemLease c = table.get(key(2))) {
                Item aView = a.item();
                assertTrue(table.remove(key(1)));
                assertItem(aView, key(1), 10);
                table.close();
                table.close();
                assertTrue(first.isRetired());
                assertTrue(second.isRetired());
                assertTrue(table.isClosed());
                assertEquals(0, table.size());
                assertEquals(0, table.metadataUsedBytes());
                assertItem(b.item(), key(1), 10);
                assertItem(c.item(), key(2), 20);
                a.close();
                assertThrows(IllegalStateException.class, aView::valueLength);
                assertItem(b.item(), key(1), 10);
                StoredItem caller = item(allocator, key(3), 30);
                try {
                    assertThrows(IllegalStateException.class, () -> table.put(key(3), caller));
                    assertFalse(caller.isRetired());
                } finally {
                    caller.retire();
                }
                assertThrows(IllegalStateException.class, () -> table.get(key(1)));
                assertThrows(IllegalStateException.class, () -> table.remove(key(1)));
            } finally {
                table.close();
            }
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void collisionChainsRemoveEveryPositionAndSurviveMultipleResizes() {
        for (int removed : new int[]{0, 3, 5}) {
            try (var allocator = allocator();
                 var table = new SegmentedItemTable(1, 16, 10000, ignored -> -1L)) {
                for (int i = 0; i < 6; i++) {
                    assertTrue(table.put(key(i), item(allocator, key(i), i)));
                }
                assertTrue(table.remove(key(removed)));
                for (int i = 0; i < 6; i++) {
                    if (i == removed) assertNull(table.get(key(i)));
                    else assertValue(table, key(i), i);
                }
                assertFalse(table.remove(key(100)));
            }
        }
        try (var allocator = allocator();
             var table = new SegmentedItemTable(4, 2, 100000, ignored -> Long.MIN_VALUE)) {
            for (int i = 0; i < 150; i++) {
                byte[] supplied = key(i);
                assertTrue(table.put(supplied, item(allocator, supplied, i)));
                Arrays.fill(supplied, (byte) 77);
            }
            assertEquals(150, table.size());
            for (int i = 0; i < 150; i++) assertValue(table, key(i), i);
            for (int i = 149; i >= 0; i--) assertTrue(table.remove(key(i)));
            assertEquals(0, table.size());
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void literalCapAndTransientPeakRefusalPreserveOwnershipAndMapping() {
        for (long cap : new long[]{408, 471}) {
            try (var allocator = allocator(); var table = new SegmentedItemTable(1, 2, cap)) {
                StoredItem candidate = item(allocator, key(1), 10);
                try {
                    assertFalse(table.put(key(1), candidate));
                    assertFalse(candidate.isRetired());
                    assertEquals(408, table.metadataUsedBytes());
                    assertNull(table.get(key(1)));
                } finally { candidate.retire(); }
            }
        }
        // Two entries with four buckets cost 552, but the old array makes peak 632.
        for (long cap : new long[]{552, 631, 632}) {
            try (var allocator = allocator(); var table = new SegmentedItemTable(1, 2, cap)) {
                StoredItem first = item(allocator, key(1), 10);
                StoredItem candidate = item(allocator, key(2), 20);
                assertTrue(table.put(key(1), first));
                boolean accepted = table.put(key(2), candidate);
                try {
                    assertEquals(cap == 632, accepted);
                    assertEquals(accepted ? 552 : 472, table.metadataUsedBytes());
                    assertEquals(accepted ? 2 : 1, table.size());
                    assertFalse(first.isRetired());
                    assertFalse(candidate.isRetired());
                    assertValue(table, key(1), 10);
                    if (accepted) assertValue(table, key(2), 20);
                    else assertNull(table.get(key(2)));
                } finally { if (!accepted) candidate.retire(); }
            }
        }
    }

    @Test
    void allocationFailuresRollbackEachPreparationPointAndPermitRetry() {
        for (boolean growth : new boolean[]{false, true}) {
            for (boolean error : new boolean[]{false, true}) {
                for (int point = 1; point <= (growth ? 2 : 1); point++) {
                    AtomicInteger calls = new AtomicInteger();
                    AtomicInteger failAt = new AtomicInteger(Integer.MAX_VALUE);
                    RuntimeException runtime = new IllegalStateException("injected entry failure");
                    OutOfMemoryError oom = new OutOfMemoryError("injected metadata failure");
                    try (var allocator = allocator();
                         var table = new SegmentedItemTable(1, 2, 10000, ignored -> 0L, () -> {
                             if (calls.incrementAndGet() == failAt.get()) {
                                 if (error) throw oom;
                                 throw runtime;
                             }
                         })) {
                        if (growth) assertTrue(table.put(key(1), item(allocator, key(1), 10)));
                        calls.set(0);
                        failAt.set(point);
                        StoredItem candidate = item(allocator, key(2), 20);
                        try {
                            Throwable failure = assertThrows(Throwable.class,
                                    () -> table.put(key(2), candidate));
                            assertSame(error ? oom : runtime, failure);
                            assertEquals(growth ? 472 : 408, table.metadataUsedBytes());
                            assertEquals(growth ? 1 : 0, table.size());
                            assertNull(table.get(key(2)));
                            assertFalse(candidate.isRetired());
                            if (growth) assertValue(table, key(1), 10);
                            failAt.set(Integer.MAX_VALUE);
                            assertTrue(table.put(key(2), candidate));
                            assertValue(table, key(2), 20);
                        } finally {
                            table.close();
                            candidate.retire();
                        }
                        assertEquals(0, allocator.usedMemory());
                    }
                }
            }
        }
    }

    @Test
    void replacementAndNoOpNeverCallAllocationHook() {
        AtomicBooleanHook hook = new AtomicBooleanHook();
        try (var allocator = allocator();
             var table = new SegmentedItemTable(1, 2, 472, ignored -> 0L, hook)) {
            StoredItem first = item(allocator, key(1), 10);
            StoredItem replacement = item(allocator, key(1), 20);
            assertTrue(table.put(key(1), first));
            hook.fail = true;
            assertTrue(table.put(key(1), first));
            assertFalse(first.isRetired());
            assertTrue(table.put(key(1), replacement));
            assertTrue(first.isRetired());
            assertValue(table, key(1), 20);
        }
    }

    private static final class AtomicBooleanHook implements SegmentedItemTable.AllocationHook {
        private boolean fail;

        @Override
        public void beforeAllocation() {
            if (fail) throw new AssertionError("replacement allocated metadata");
        }
    }

    @Test
    void retiredInputIsRejectedWithoutReplacingLiveMapping() {
        try (var allocator = allocator(); var table = new SegmentedItemTable(1, 2, 4096)) {
            StoredItem live = item(allocator, key(1), 10);
            StoredItem retired = item(allocator, key(1), 20);
            assertTrue(table.put(key(1), live));
            retired.retire();
            assertThrows(IllegalStateException.class, () -> table.put(key(1), retired));
            assertValue(table, key(1), 10);
            assertFalse(live.isRetired());
            assertEquals(472, table.metadataUsedBytes());
        }
    }

    @Test
    void closeContinuesAfterFreeThenThrowAndRethrowsFirstFailure() {
        for (boolean error : new boolean[]{false, true}) {
            RuntimeException runtime = new IllegalStateException("after actual free");
            OutOfMemoryError oom = new OutOfMemoryError("after actual free");
            AtomicInteger frees = new AtomicInteger();
            try (var nativeAllocator = allocator()) {
                MemoryAllocator throwing = new MemoryAllocator() {
                    @Override public SlabHandle allocate(int size) { return nativeAllocator.allocate(size); }
                    @Override public void free(SlabHandle handle) {
                        nativeAllocator.free(handle);
                        int call = frees.incrementAndGet();
                        if (call == 1) {
                            if (error) throw oom;
                            throw runtime;
                        }
                        throw new IllegalArgumentException("later cleanup failure");
                    }
                    @Override public long totalMemory() { return nativeAllocator.totalMemory(); }
                    @Override public long usedMemory() { return nativeAllocator.usedMemory(); }
                    @Override public int classCount() { return nativeAllocator.classCount(); }
                    @Override public SlabClassStats classStats(int id) { return nativeAllocator.classStats(id); }
                };
                var table = new SegmentedItemTable(2, 2, 4096);
                StoredItem[] items = new StoredItem[3];
                try {
                    for (int i = 0; i < items.length; i++) {
                        items[i] = StoredItem.create(throwing, key(i), new byte[]{1}, 0, 0, 0, 512);
                        assertNotNull(items[i]);
                        assertTrue(table.put(key(i), items[i]));
                    }
                    Throwable failure = assertThrows(Throwable.class, table::close);
                    assertSame(error ? oom : runtime, failure);
                    assertEquals(3, frees.get());
                    assertEquals(0, nativeAllocator.usedMemory());
                    assertTrue(table.isClosed());
                    assertEquals(0, table.metadataUsedBytes());
                    assertEquals(0, table.size());
                    for (StoredItem item : items) assertTrue(item.isRetired());
                    assertDoesNotThrow(table::close);
                    assertEquals(3, frees.get());
                } finally { table.close(); }
            }
        }
    }

    @Test
    void seededOperationsMatchIndependentByteKeyMap() {
        for (boolean collisions : new boolean[]{false, true}) {
            try (var allocator = allocator();
                 var table = collisions ? new SegmentedItemTable(8, 2, 100000, ignored -> 7L)
                         : new SegmentedItemTable(8, 2, 100000)) {
                Map<ByteBuffer, Integer> expected = new HashMap<>();
                Random random = new Random(0x7095EEDL);
                for (int step = 0; step < 2500; step++) {
                    byte[] key = key(random.nextInt(80));
                    ByteBuffer independentKey = ByteBuffer.wrap(key.clone()).asReadOnlyBuffer();
                    switch (random.nextInt(3)) {
                        case 0 -> {
                            int version = random.nextInt();
                            assertTrue(table.put(key, item(allocator, key, version)), "step " + step);
                            expected.put(independentKey, version);
                        }
                        case 1 -> assertEquals(expected.remove(independentKey) != null, table.remove(key),
                                "step " + step);
                        default -> {
                            Integer version = expected.get(independentKey);
                            if (version == null) assertNull(table.get(key), "step " + step);
                            else assertValue(table, key, version);
                        }
                    }
                    assertEquals(expected.size(), table.size(), "step " + step);
                    assertTrue(table.metadataUsedBytes() <= 100000);
                }
                for (int i = 0; i < 80; i++) {
                    Integer version = expected.get(ByteBuffer.wrap(key(i)));
                    if (version == null) assertNull(table.get(key(i)));
                    else assertValue(table, key(i), version);
                }
                table.close();
                assertEquals(0, allocator.usedMemory());
            }
        }
    }

    static FfmSlabAllocator allocator() {
        return new FfmSlabAllocator(4 * 1024 * 1024, 4096, 512);
    }

    static byte[] key(int key) {
        return ByteBuffer.allocate(4).putInt(key).array();
    }

    static StoredItem item(FfmSlabAllocator allocator, byte[] key, int version) {
        StoredItem result = StoredItem.create(allocator, key,
                ByteBuffer.allocate(4).putInt(version).array(), version, 0, version, 512);
        assertNotNull(result, "fixture native allocation");
        return result;
    }

    static void assertValue(SegmentedItemTable table, byte[] key, int version) {
        try (ItemLease lease = table.get(key)) {
            assertNotNull(lease);
            assertItem(lease.item(), key, version);
        }
    }

    static void assertItem(Item item, byte[] key, int version) {
        assertArrayEquals(key, item.key());
        assertEquals(version, item.flags());
        assertEquals((long) version, item.casId());
        assertEquals(0, item.expiryTime());
        assertEquals(4, item.valueLength());
        ByteBuffer value = ByteBuffer.allocate(4);
        assertEquals(4, item.readValue(0, value));
        assertEquals(version, value.flip().getInt());
    }
}
