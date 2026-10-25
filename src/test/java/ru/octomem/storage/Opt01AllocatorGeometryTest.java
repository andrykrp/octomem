package ru.octomem.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Implementer unit tests for OPT-01 pinning the allocator side of the contract:
 * the engine snapshot is derived from exactly these immutable classes, and
 * allocation selects the same lower-bound class for every size and boundary.
 */
@Timeout(30)
class Opt01AllocatorGeometryTest {
    private static final int MAXIMUM = 1024;
    private static final int[] GEOMETRY =
            {64, 80, 104, 136, 176, 224, 280, 352, 440, 552, 696, 872, 1024};

    @Test
    void allocationMatchesTheIndependentLowerBoundForEverySizeAndBoundary() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(1 << 20, 4096, MAXIMUM)) {
            assertEquals(GEOMETRY.length, allocator.classCount());
            for (int i = 0; i < GEOMETRY.length; i++) {
                assertEquals(GEOMETRY[i], allocator.classStats(i).chunkSize(), "chunk size of class " + i);
                assertEquals(i, allocator.classStats(i).classId(), "class id " + i);
            }
            for (int size = 1; size <= MAXIMUM; size++) {
                SlabHandle handle = allocator.allocate(size);
                assertNotNull(handle, "size " + size);
                int id = oracle(GEOMETRY, size);
                assertEquals(id, handle.classId(), "class of size " + size);
                assertEquals(GEOMETRY[id], handle.capacity(), "capacity of size " + size);
                allocator.free(handle);
                allocator.trim();
            }
            for (int size : new int[]{MAXIMUM + 1, Integer.MAX_VALUE}) {
                assertNull(allocator.allocate(size), "over-ceiling size " + size);
            }
            for (int i = 0; i < GEOMETRY.length; i++) {
                int chunk = GEOMETRY[i];
                assertEquals(i, oracle(GEOMETRY, chunk - 1L), "lower boundary of class " + i);
                assertEquals(i, oracle(GEOMETRY, chunk), "class " + i);
                assertEquals(i + 1 < GEOMETRY.length ? i + 1 : -1, oracle(GEOMETRY, chunk + 1L),
                        "upper boundary of class " + i);
            }
            assertEquals(GEOMETRY[GEOMETRY.length - 1], allocator.classStats(GEOMETRY.length - 1).chunkSize());
            assertEquals(0, allocator.usedMemory());
            assertEquals(0, allocator.totalMemory());
        }
    }

    private static int oracle(int[] sizes, long size) {
        long aligned = (size + 7) & ~7L;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] >= aligned) return i;
        }
        return -1;
    }
}
