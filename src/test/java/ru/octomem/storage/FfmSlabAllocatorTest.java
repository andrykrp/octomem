package ru.octomem.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FfmSlabAllocatorTest {
    private static SlabHandle allocated(SlabHandle handle) {
        assertNotNull(handle);
        return handle;
    }
    @Test
    void constructorBoundsAreValidatedWithoutNativeAllocation() {
        for (long limit : new long[]{0, -1, Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new FfmSlabAllocator(limit, 256, 1024));
        for (int page : new int[]{0, -1, Integer.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new FfmSlabAllocator(1024, page, 1024));
        for (int max : new int[]{Integer.MIN_VALUE, -1, 0, 63, 2147483641, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new FfmSlabAllocator(1024, 256, max));
        try (var allocator = new FfmSlabAllocator(Long.MAX_VALUE, Integer.MAX_VALUE, 2147483640)) {
            assertEquals(0, allocator.totalMemory());
            assertEquals(0, allocator.usedMemory());
            assertEquals(AllocatorOracle.classes(2147483640).size(), allocator.classCount());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 65, 79, 80, 81, 1000, 1024})
    void exactClassSequenceBoundariesAndOriginalMaximum(int maximum) {
        List<Integer> expected = AllocatorOracle.classes(maximum);
        if (maximum == 1024) assertEquals(List.of(64, 80, 104, 136, 176, 224, 280, 352, 440, 552, 696, 872, 1024), expected);
        try (var allocator = new FfmSlabAllocator(8192, 257, maximum)) {
            assertEquals(expected.size(), allocator.classCount());
            for (int i = 0; i < expected.size(); i++) {
                var stats = allocator.classStats(i);
                assertEquals(i, stats.classId());
                assertEquals(expected.get(i).intValue(), stats.chunkSize());
                assertEquals(0, stats.totalPages());
                assertEquals(0, stats.totalChunks());
                assertEquals(0, stats.usedChunks());
                assertEquals(0, stats.freeChunks());
            }
            for (int size = 1; size <= maximum; size++) {
                SlabHandle handle = allocator.allocate(size);
                assertNotNull(handle);
                int id = AllocatorOracle.classId(expected, size);
                assertEquals(id, handle.classId());
                assertEquals(expected.get(id).intValue(), handle.capacity());
                allocator.free(handle);
                allocator.trim();
            }
            assertNull(allocator.allocate(maximum + 1));
            assertNull(allocator.allocate(Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> allocator.classStats(-1));
            assertThrows(IllegalArgumentException.class, () -> allocator.classStats(expected.size()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 63, 64, 65, 127, 128, 129, 257})
    void physicalPagesChargeExactBytesAndNeverSpendIncompleteBudget(int pageSize) {
        int physical = Math.max(pageSize, 64);
        try (var denied = new FfmSlabAllocator(physical - 1, pageSize, 64)) {
            assertNull(denied.allocate(1));
            assertEquals(0, denied.totalMemory());
            assertEquals(0, denied.usedMemory());
        }
        try (var allocator = new FfmSlabAllocator(physical * 2L - 1, pageSize, 64)) {
            List<SlabHandle> handles = new ArrayList<>();
            for (int i = 0; i < physical / 64; i++) handles.add(allocated(allocator.allocate(1)));
            assertNull(allocator.allocate(1));
            assertEquals(physical, allocator.totalMemory());
            assertEquals(64L * handles.size(), allocator.usedMemory());
            var full = allocator.classStats(0);
            assertEquals(1, full.totalPages());
            assertEquals(physical / 64, full.totalChunks());
            assertEquals(0, full.freeChunks());
            allocator.free(handles.removeLast());
            long charged = allocator.totalMemory();
            SlabHandle reused = allocated(allocator.allocate(1));
            assertEquals(charged, allocator.totalMemory());
            handles.add(reused);
            assertEquals(0, allocator.trim());
            handles.forEach(allocator::free);
            assertEquals(0, allocator.usedMemory());
            assertEquals(physical, allocator.totalMemory());
            assertEquals(physical, allocator.trim());
            assertEquals(0, allocator.trim());
            assertEquals(0, allocator.totalMemory());
            assertEquals(0, allocator.classStats(0).totalPages());
            assertEquals(0, allocator.classStats(0).totalChunks());
            assertEquals(physical / 64, full.usedChunks());
        }
    }

    @Test
    void oversizedClassPageAndMultipleClassesShareOnePhysicalBudget() {
        try (var allocator = new FfmSlabAllocator(512, 128, 352)) {
            SlabHandle large = allocated(allocator.allocate(300));
            assertEquals(352, large.capacity());
            assertEquals(352, allocator.totalMemory());
            SlabHandle small = allocated(allocator.allocate(1));
            assertEquals(480, allocator.totalMemory());
            assertNull(allocator.allocate(80));
            allocator.free(large);
            assertEquals(352, allocator.trim());
            assertEquals(128, allocator.totalMemory());
            allocator.free(small);
        }
    }

    @Test
    void closePreservesOutstandingBlocksAndReleasesOnlyEmptyPages() {
        var allocator = new FfmSlabAllocator(1024, 128, 128);
        SlabHandle first = allocated(allocator.allocate(1));
        SlabHandle second = allocated(allocator.allocate(1));
        SlabHandle otherPage = allocated(allocator.allocate(100));
        allocator.free(otherPage);
        assertFalse(allocator.isClosed());
        allocator.close();
        allocator.close();
        assertTrue(allocator.isClosed());
        assertEquals(128, allocator.totalMemory());
        assertEquals(128, allocator.usedMemory());
        assertNull(allocator.allocate(1));
        for (int size : new int[]{0, -1, Integer.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> allocator.allocate(size));
        assertEquals(64, first.capacity());
        assertEquals(0, first.classId());
        assertEquals(3, first.write(0, ByteBuffer.wrap(new byte[]{9, 8, 7})));
        ByteBuffer result = ByteBuffer.allocate(3);
        assertEquals(3, first.read(0, result));
        assertArrayEquals(new byte[]{9, 8, 7}, result.array());
        allocator.free(first);
        assertEquals(128, allocator.totalMemory());
        assertEquals(64, allocator.usedMemory());
        assertEquals(0, allocator.trim());
        allocator.free(second);
        assertEquals(0, allocator.totalMemory());
        assertEquals(0, allocator.usedMemory());
        assertEquals(0, allocator.trim());
        allocator.close();
    }

    @Test
    void nullForeignDoubleFreeAndFreedHandleAccessCannotCorruptAccounting() {
        try (var allocator = new FfmSlabAllocator(128, 128, 64);
             var foreign = new FfmSlabAllocator(128, 128, 64)) {
            SlabHandle handle = allocated(allocator.allocate(1));
            assertThrows(NullPointerException.class, () -> allocator.free(null));
            SlabHandle alien = new SlabHandle() {
                public int capacity() { throw new AssertionError("Do not interrogate foreign handles"); }
                public int classId() { throw new AssertionError("Do not interrogate foreign handles"); }
                public int read(int offset, ByteBuffer dst) { throw new AssertionError(); }
                public int write(int offset, ByteBuffer src) { throw new AssertionError(); }
            };
            assertThrows(IllegalArgumentException.class, () -> allocator.free(alien));
            assertThrows(IllegalArgumentException.class, () -> foreign.free(handle));
            assertEquals(64, allocator.usedMemory());
            allocator.free(handle);
            assertThrows(IllegalArgumentException.class, () -> allocator.free(handle));
            assertThrows(IllegalStateException.class, handle::capacity);
            assertThrows(IllegalStateException.class, handle::classId);
            assertThrows(IllegalStateException.class, () -> handle.read(0, ByteBuffer.allocate(1)));
            assertThrows(IllegalStateException.class, () -> handle.write(0, ByteBuffer.allocate(1)));
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void trimUnlinksEmptyMiddleAndTailPagesWithoutLosingLiveHead() {
        try (var allocator = new FfmSlabAllocator(192, 64, 64)) {
            SlabHandle first = allocated(allocator.allocate(64));
            SlabHandle middle = allocated(allocator.allocate(64));
            SlabHandle last = allocated(allocator.allocate(64));
            allocator.free(middle);
            assertEquals(64, allocator.trim());
            assertEquals(128, allocator.totalMemory());
            assertEquals(2, allocator.classStats(0).totalPages());
            allocator.free(first);
            assertEquals(64, allocator.trim());
            assertEquals(64, last.capacity());
            last.write(0, ByteBuffer.wrap(new byte[]{42}));
            ByteBuffer result = ByteBuffer.allocate(1);
            last.read(0, result);
            assertEquals(42, result.get(0));
            allocator.free(last);
            assertEquals(64, allocator.trim());
            assertEquals(0, allocator.totalMemory());
        }
    }

    @Test
    void transfersRespectOffsetsRemainingPositionsLimitsAndReadOnlyDestinations() {
        try (var allocator = new FfmSlabAllocator(128, 128, 64)) {
            SlabHandle handle = allocated(allocator.allocate(1));
            ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4});
            source.position(1).limit(4);
            assertEquals(2, handle.write(62, source));
            assertEquals(3, source.position());
            assertEquals(4, source.limit());
            ByteBuffer destination = ByteBuffer.allocateDirect(6);
            destination.position(2).limit(5);
            assertEquals(2, handle.read(62, destination));
            assertEquals(4, destination.position());
            assertEquals(5, destination.limit());
            assertEquals(1, destination.get(2));
            assertEquals(2, destination.get(3));
            assertEquals(0, handle.read(64, destination));
            assertEquals(0, handle.write(64, source));
            assertEquals(0, handle.read(0, ByteBuffer.allocate(0)));
            assertEquals(0, handle.write(0, ByteBuffer.allocate(0)));
            ByteBuffer readonly = ByteBuffer.allocate(4).asReadOnlyBuffer();
            assertThrows(ReadOnlyBufferException.class, () -> handle.read(0, readonly));
            assertEquals(0, readonly.position());
            assertThrows(NullPointerException.class, () -> handle.read(0, null));
            assertThrows(NullPointerException.class, () -> handle.write(0, null));
            for (int offset : new int[]{-1, 65, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                int position = source.position();
                assertThrows(IndexOutOfBoundsException.class, () -> handle.write(offset, source));
                assertThrows(IndexOutOfBoundsException.class, () -> handle.read(offset, destination));
                assertEquals(position, source.position());
            }
            assertEquals(2, handle.write(0, ByteBuffer.wrap(new byte[]{5, 6}).asReadOnlyBuffer()));
            allocator.free(handle);
        }
    }

    @Test
    void sharedPageBlocksAndReusedSlotDoNotAliasLiveNeighbors() {
        try (var allocator = new FfmSlabAllocator(256, 256, 64)) {
            List<SlabHandle> handles = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                SlabHandle handle = allocated(allocator.allocate(64));
                byte[] bytes = new byte[64];
                Arrays.fill(bytes, (byte) (i + 1));
                handle.write(0, ByteBuffer.wrap(bytes));
                handles.add(handle);
            }
            allocator.free(handles.get(1));
            handles.set(1, allocated(allocator.allocate(64)));
            byte[] replacement = new byte[64];
            Arrays.fill(replacement, (byte) 2);
            handles.get(1).write(0, ByteBuffer.wrap(replacement));
            for (int i = 0; i < 4; i++) {
                ByteBuffer read = ByteBuffer.allocate(64);
                handles.get(i).read(0, read);
                for (byte value : read.array()) assertEquals((byte) (i + 1), value);
            }
            assertEquals(256, allocator.totalMemory());
            handles.forEach(allocator::free);
        }
    }

    @Test
    void seededPageModelPredictsEveryAllocationDenialChargeAndTrim() {
        int maximum = 352;
        int pageSize = 257;
        long limit = 4096;
        List<Integer> classes = AllocatorOracle.classes(maximum);
        long[] pages = new long[classes.size()];
        long[] live = new long[classes.size()];
        List<SlabHandle> handles = new ArrayList<>();
        Random random = new Random(90017);
        try (var allocator = new FfmSlabAllocator(limit, pageSize, maximum)) {
            for (int step = 0; step < 2500; step++) {
                if (!handles.isEmpty() && random.nextInt(3) == 0) {
                    SlabHandle handle = handles.remove(random.nextInt(handles.size()));
                    live[handle.classId()]--;
                    allocator.free(handle);
                } else {
                    int size = 1 + random.nextInt(maximum);
                    int id = AllocatorOracle.classId(classes, size);
                    int physical = Math.max(pageSize, classes.get(id));
                    int slots = physical / classes.get(id);
                    long total = 0;
                    for (int i = 0; i < pages.length; i++) total += pages[i] * Math.max(pageSize, classes.get(i));
                    boolean newPage = live[id] == pages[id] * slots;
                    boolean allowed = !newPage || physical <= limit - total;
                    SlabHandle handle = allocator.allocate(size);
                    assertEquals(allowed, handle != null);
                    if (allowed) {
                        if (newPage) pages[id]++;
                        live[id]++;
                        handles.add(handle);
                        assertEquals(classes.get(id).intValue(), handle.capacity());
                    }
                }
                long used = 0;
                long physical = 0;
                for (int i = 0; i < pages.length; i++) {
                    used += live[i] * classes.get(i);
                    physical += pages[i] * Math.max(pageSize, classes.get(i));
                    var stats = allocator.classStats(i);
                    assertEquals(pages[i], stats.totalPages());
                    assertEquals(live[i], stats.usedChunks());
                    assertEquals(pages[i] * (Math.max(pageSize, classes.get(i)) / classes.get(i)), stats.totalChunks());
                    assertEquals(stats.totalChunks() - live[i], stats.freeChunks());
                }
                assertEquals(used, allocator.usedMemory());
                assertEquals(physical, allocator.totalMemory());
                assertTrue(physical <= limit);
            }
            handles.forEach(allocator::free);
            long before = allocator.totalMemory();
            assertEquals(before, allocator.trim());
            assertEquals(0, allocator.totalMemory());
            for (int cycle = 0; cycle < 200; cycle++) {
                SlabHandle handle = allocated(allocator.allocate(65));
                allocator.free(handle);
                assertEquals(257, allocator.trim());
                assertEquals(0, allocator.usedMemory());
                assertEquals(0, allocator.totalMemory());
            }
        }
    }
}
