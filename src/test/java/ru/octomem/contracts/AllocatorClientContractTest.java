package ru.octomem.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SlabHandle;

/** One-block test double, not proof of actual native allocation or concurrent reservations. */
class AllocatorClientContractTest {
    @Test
    void allocationDenialAndReleaseAreDistinctFromInvalidArguments() {
        OneBlockAllocator allocator = new OneBlockAllocator();
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(0));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(-1));
        assertNull(allocator.allocate(9));
        SlabHandle handle = allocator.allocate(8);
        assertNotNull(handle);
        assertEquals(8, allocator.usedMemory());
        assertEquals(8, allocator.totalMemory());
        assertNull(allocator.allocate(1));
        assertThrows(NullPointerException.class, () -> allocator.free(null));
        allocator.free(handle);
        assertEquals(0, allocator.usedMemory());
        assertThrows(IllegalArgumentException.class, () -> allocator.free(handle));
        assertThrows(IllegalStateException.class, () -> handle.read(0, ByteBuffer.allocate(1)));
        assertNotNull(allocator.allocate(8));
    }

    @Test
    void offsetTransfersUseBufferRemainingAndDoNotWrapRanges() {
        OneBlockAllocator allocator = new OneBlockAllocator();
        SlabHandle handle = allocator.allocate(8);
        ByteBuffer source = ByteBuffer.wrap(new byte[] {9, 1, 2, 3, 9});
        source.position(1).limit(4);
        assertEquals(3, handle.write(2, source));
        assertEquals(4, source.position());
        ByteBuffer target = ByteBuffer.allocate(5);
        target.position(1).limit(4);
        assertEquals(3, handle.read(2, target));
        assertEquals(4, target.position());
        assertArrayEquals(new byte[] {0, 1, 2, 3, 0}, target.array());
        assertEquals(0, handle.read(8, ByteBuffer.allocate(2)));
        assertEquals(0, handle.write(8, ByteBuffer.allocate(2)));
        assertThrows(IndexOutOfBoundsException.class, () -> handle.read(-1, ByteBuffer.allocate(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> handle.read(Integer.MAX_VALUE, ByteBuffer.allocate(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> handle.write(Integer.MIN_VALUE, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> handle.read(0, null));
        ByteBuffer readOnly = ByteBuffer.allocate(2).asReadOnlyBuffer();
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> handle.read(2, readOnly));
        assertEquals(0, readOnly.position());
        allocator.free(handle);
    }

    @Test
    void callerFinallyReturnsAllocationOnPreparationFailure() {
        OneBlockAllocator allocator = new OneBlockAllocator();
        assertThrows(IllegalStateException.class, () -> {
            SlabHandle prepared = allocator.allocate(8);
            try {
                prepared.write(0, ByteBuffer.wrap(new byte[] {1, 2}));
                throw new IllegalStateException("simulated preparation failure before publication");
            } finally {
                allocator.free(prepared);
            }
        });
        assertEquals(0, allocator.usedMemory());
        assertNotNull(allocator.allocate(8));
    }

    private static final class OneBlockAllocator implements MemoryAllocator {
        private TestBlock live;
        public SlabHandle allocate(int size) {
            if (size <= 0) { throw new IllegalArgumentException("size"); }
            if (size > 8 || live != null) { return null; }
            live = new TestBlock();
            return live;
        }
        public void free(SlabHandle handle) {
            Objects.requireNonNull(handle);
            if (handle != live) { throw new IllegalArgumentException("not live"); }
            live.freed = true;
            live = null;
        }
        public long totalMemory() { return 8; }
        public long usedMemory() { return live == null ? 0 : 8; }
        public int classCount() { return 1; }
        public SlabClassStats classStats(int classId) { throw new UnsupportedOperationException("not used by client"); }
    }

    private static final class TestBlock implements SlabHandle {
        private final byte[] bytes = new byte[8];
        private boolean freed;
        public int classId() { return 0; }
        public int capacity() { return bytes.length; }
        private int checkedCount(int offset, ByteBuffer buffer) {
            if (freed) { throw new IllegalStateException("freed"); }
            Objects.requireNonNull(buffer);
            Objects.checkFromIndexSize(offset, 0, bytes.length);
            return Math.min(buffer.remaining(), bytes.length - offset);
        }
        public int read(int offset, ByteBuffer dst) {
            int count = checkedCount(offset, dst);
            dst.put(bytes, offset, count);
            return count;
        }
        public int write(int offset, ByteBuffer src) {
            int count = checkedCount(offset, src);
            src.get(bytes, offset, count);
            return count;
        }
    }
}
