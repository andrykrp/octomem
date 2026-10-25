package ru.octomem.storage;

import org.junit.jupiter.api.Test;
import ru.octomem.eviction.LruSelection;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static ru.octomem.storage.TransactionAcceptanceTest.*;

/** Independent class queue admission, membership, rotation and unsigned activity oracle. */
class ClassQueueAcceptanceTest {
    @Test
    void leastRecentRemovalHonorsBoundedEligibilityAndClass() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536,4096,4096);
             SegmentedItemTable table = new SegmentedItemTable(1,2,65536)) {
            table.initializeClassQueues(allocator.classCount());
            StoredItem a = item(allocator,"a","a");
            StoredItem b = item(allocator,"b","b");
            table.put(b("a"),a); table.put(b("b"),b);
            table.transaction(0,tx -> {
                assertThrows(IllegalArgumentException.class, () -> tx.removeLeastRecent(0,a.classId(),0,x -> true));
                assertThrows(IndexOutOfBoundsException.class, () -> tx.removeLeastRecent(0,-1,1,x -> true));
                List<StoredItem> seen = new ArrayList<>();
                assertFalse(tx.removeLeastRecent(0,a.classId(),1,x -> { seen.add(x); return false; }));
                assertEquals(List.of(a),seen);
                assertFalse(tx.removeLeastRecent(0,a.classId(),10,x -> false));
                assertTrue(tx.removeLeastRecent(0,a.classId(),10,x -> x == b));
                assertTrue(tx.removeLeastRecent(0,a.classId(),10,x -> true));
                assertFalse(tx.removeLeastRecent(0,a.classId(),10,x -> true));
                return null;
            });
        }
    }
    @Test
    void classQueueAdmissionIsChargedAtomicAndSingleUse() {
        try (SegmentedItemTable table = new SegmentedItemTable(1, 2, 408)) {
            assertFalse(table.initializeClassQueues(1));
            assertEquals(408, table.metadataUsedBytes());
            assertThrows(IllegalArgumentException.class, () -> table.initializeClassQueues(0));
            assertThrows(IllegalArgumentException.class, () -> table.initializeClassQueues(-1));
        }
        try (SegmentedItemTable table = new SegmentedItemTable(2, 2, 65536)) {
            long before = table.metadataUsedBytes();
            assertTrue(table.initializeClassQueues(3));
            assertEquals(before + 2 * (192 + 24 * 3) + 512 + 128 * 3, table.metadataUsedBytes());
            assertThrows(IllegalStateException.class, () -> table.initializeClassQueues(3));
            table.close();
            assertThrows(IllegalStateException.class, () -> table.initializeClassQueues(3));
            assertEquals(0, table.metadataUsedBytes());
        }
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 65536)) {
            assertTrue(table.put(b("a"), item(allocator, "a", "a")));
            assertThrows(IllegalStateException.class, () -> table.initializeClassQueues(allocator.classCount()));
        }
    }

    @Test
    void classRotationReplacementRemovalAndPinsRemainIndependent() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 65536)) {
            assertTrue(table.initializeClassQueues(allocator.classCount()));
            StoredItem a = item(allocator, "a", "small-a");
            StoredItem b = item(allocator, "b", "small-b");
            StoredItem large = item(allocator, "large", "l".repeat(3000));
            assertEquals(a.classId(), b.classId());
            assertNotEquals(a.classId(), large.classId());
            assertTrue(table.put(b("a"), a));
            assertTrue(table.put(b("b"), b));
            assertTrue(table.put(b("large"), large));
            List<StoredItem> visited = new ArrayList<>();
            for (int i = 0; i < 2; i++) table.transaction(0, tx -> {
                assertEquals(1, tx.scanClass(0, a.classId(), 1, value -> { visited.add(value); return false; }));
                return null;
            });
            assertEquals(2, visited.size());
            assertEquals(java.util.Set.of(a, b), new java.util.HashSet<>(visited));
            try (ItemLease pinned = table.get(b("a"))) {
                StoredItem changedClass = item(allocator, "a", "n".repeat(3000));
                table.transactionAll(tx -> {
                    tx.prepareReplacement(b("a"), 0, 1, changedClass);
                    tx.commit();
                    List<StoredItem> small = new ArrayList<>();
                    assertEquals(1, tx.scanClass(0, b.classId(), 20, value -> { small.add(value); return false; }));
                    assertEquals(List.of(b), small);
                    assertEquals(2, tx.scanClass(0, large.classId(), 20, value -> false));
                    assertEquals(0, tx.scanClass(0, large.classId(), 0, value -> { fail(); return false; }));
                    assertThrows(IllegalArgumentException.class, () -> tx.scanClass(0, 0, -1, value -> false));
                    assertThrows(IndexOutOfBoundsException.class, () -> tx.scanClass(0, -1, 1, value -> false));
                    assertEquals(2, tx.scanClass(0, large.classId(), 20, value -> true));
                    assertEquals(0, tx.scanClass(0, large.classId(), 20, value -> false));
                    return null;
                });
                assertEquals("small-a", text(pinned.item()));
                assertTrue(table.remove(b("b")));
                assertEquals(0, table.size());
            }
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void lruSelectionUsesUnsignedOrderAndDoesNotReplaceOnTieOrNewer() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096)) {
            StoredItem old = item(allocator, "old", "a");
            StoredItem newer = item(allocator, "new", "b");
            StoredItem unsigned = item(allocator, "unsigned", "c");
            try {
                old.recordActivity(3, false);
                newer.recordActivity(9, false);
                unsigned.recordActivity(-1, false);
                LruSelection selection = new LruSelection();
                assertNull(selection.candidate());
                selection.consider(newer);
                selection.consider(unsigned);
                assertSame(newer, selection.candidate());
                selection.consider(old);
                assertSame(old, selection.candidate());
                newer.recordActivity(3, true);
                selection.consider(newer);
                assertSame(old, selection.candidate());
                assertTrue(newer.wasFetched());
            } finally { old.retire(); newer.retire(); unsigned.retire(); }
        }
    }
}
