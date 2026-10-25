package ru.octomem.storage;

import org.junit.jupiter.api.Test;
import ru.octomem.stats.SlabClassStats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StoredItemTest {
    static final byte[] KEY = {0, (byte) 255, 13};
    static final byte[] VALUE = {1, 2, 3, 4, 5};

    static StoredItem create(MemoryAllocator allocator) {
        StoredItem stored = StoredItem.create(allocator, KEY.clone(), VALUE.clone(), -1, 123, -1L, 1024);
        assertNotNull(stored);
        return stored;
    }

    @Test
    void nativeRecordLayoutAndClassRoundingAreIndependentOfItemLimit() {
        try (var allocator = new TrackingAllocator()) {
            byte[] key = KEY.clone();
            byte[] value = VALUE.clone();
            StoredItem stored = StoredItem.create(allocator, key, value, 0x89abcdef, Long.MAX_VALUE, Long.MIN_VALUE, 40);
            assertNotNull(stored);
            assertEquals(1, allocator.allocations);
            assertEquals(40, allocator.requested);
            assertEquals(64, stored.allocatedBytes());
            assertEquals(64, allocator.usedMemory());
            ByteBuffer raw = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(40, allocator.raw.read(0, raw));
            raw.flip();
            assertEquals(0x89abcdef, raw.getInt());
            assertEquals(3, raw.getInt());
            assertEquals(5, raw.getInt());
            assertEquals(0, raw.getInt());
            assertEquals(Long.MAX_VALUE, raw.getLong());
            assertEquals(Long.MIN_VALUE, raw.getLong());
            byte[] payload = new byte[8];
            raw.get(payload);
            assertArrayEquals(new byte[]{0, (byte) 255, 13, 1, 2, 3, 4, 5}, payload);
            Arrays.fill(key, (byte) 9);
            Arrays.fill(value, (byte) 9);
            try (ItemLease lease = stored.pin()) {
                assertArrayEquals(KEY, lease.item().key());
                ByteBuffer copy = ByteBuffer.allocate(5);
                assertEquals(5, lease.item().readValue(0, copy));
                assertArrayEquals(VALUE, copy.array());
                assertEquals(0x89abcdef, lease.item().flags());
                assertEquals(Long.MIN_VALUE, lease.item().casId());
            }
            stored.retire();
            assertEquals(1, allocator.frees);
            assertEquals(64, stored.allocatedBytes());
        }
    }

    @Test
    void invalidArgumentsAndAlignedLimitDenialDoNotAllocate() {
        try (var a = new TrackingAllocator()) {
            assertThrows(NullPointerException.class, () -> StoredItem.create(null, KEY, VALUE, 0, 0, 0, 1024));
            assertThrows(NullPointerException.class, () -> StoredItem.create(a, null, VALUE, 0, 0, 0, 1024));
            assertThrows(NullPointerException.class, () -> StoredItem.create(a, KEY, null, 0, 0, 0, 1024));
            for (int length : new int[]{0, 251})
                assertThrows(IllegalArgumentException.class, () -> StoredItem.create(a, new byte[length], VALUE, 0, 0, 0, 1024));
            for (long expiry : new long[]{-1, Long.MIN_VALUE})
                assertThrows(IllegalArgumentException.class, () -> StoredItem.create(a, KEY, VALUE, 0, expiry, 0, 1024));
            for (int maximum : new int[]{0, -1, Integer.MIN_VALUE})
                assertThrows(IllegalArgumentException.class, () -> StoredItem.create(a, KEY, VALUE, 0, 0, 0, maximum));
            for (int maximum : new int[]{1, 32, 33, 39})
                assertNull(StoredItem.create(a, new byte[]{1}, new byte[0], 0, 0, 0, maximum));
            assertEquals(0, a.allocations);
            assertEquals(0, a.usedMemory());
            for (int length : new int[]{1, 250}) {
                StoredItem item = StoredItem.create(a, new byte[length], new byte[0], 0, 0, 0, 1024);
                assertNotNull(item);
                item.retire();
            }
        }
    }

    @Test
    void capacityDenialPreservesExistingRecord() {
        try (var a = new FfmSlabAllocator(64, 64, 1024)) {
            StoredItem old = create(a);
            assertNull(StoredItem.create(a, KEY, VALUE, 0, 0, 0, 1024));
            assertEquals(64, a.usedMemory());
            try (ItemLease lease = old.pin()) { assertArrayEquals(KEY, lease.item().key()); }
            assertFalse(old.isRetired());
            old.retire();
            assertEquals(0, a.usedMemory());
        }
    }

    @Test
    void eachLeaseViewDiesWithItsOwnLeaseAndRetirementWaitsForLastPin() {
        try (var a = new TrackingAllocator()) {
            StoredItem stored = create(a);
            ItemLease first = stored.pin();
            ItemLease second = stored.pin();
            assertNotNull(first);
            assertNotNull(second);
            Item dead = first.item();
            assertNotSame(dead, second.item());
            assertEquals(2, stored.pinCount());
            first.close();
            first.close();
            assertThrows(IllegalStateException.class, first::item);
            assertClosed(dead);
            assertEquals(1, stored.pinCount());
            assertEquals(0, a.frees);
            stored.retire();
            stored.retire();
            assertTrue(stored.isRetired());
            assertNull(stored.pin());
            assertEquals(0, a.frees);
            assertArrayEquals(KEY, second.item().key());
            assertTrue(stored.keyEquals(KEY, 0, KEY.length));
            second.close();
            second.close();
            assertEquals(1, a.frees);
            assertEquals(0, stored.pinCount());
            assertEquals(0, a.usedMemory());
            assertThrows(IllegalStateException.class, () -> stored.keyEquals(KEY, 0, KEY.length));
            assertClosed(dead);
            stored.retire();
            assertEquals(1, a.frees);
        }
    }

    static void assertClosed(Item item) {
        assertThrows(IllegalStateException.class, item::key);
        assertThrows(IllegalStateException.class, item::flags);
        assertThrows(IllegalStateException.class, item::expiryTime);
        assertThrows(IllegalStateException.class, item::casId);
        assertThrows(IllegalStateException.class, item::valueLength);
        assertThrows(IllegalStateException.class, () -> item.readValue(0, ByteBuffer.allocate(0)));
        assertThrows(IllegalStateException.class, () -> item.isExpired(Long.MAX_VALUE));
        assertThrows(IllegalStateException.class, item::isActive);
        assertThrows(IllegalStateException.class, item::markActive);
    }

    @Test
    void readOffsetsDestinationBoundsAndNoSharedCursor() {
        try (var a = new TrackingAllocator()) {
            StoredItem stored = create(a);
            try (ItemLease one = stored.pin(); ItemLease two = stored.pin()) {
                Item item = one.item();
                ByteBuffer dst = ByteBuffer.allocate(10);
                Arrays.fill(dst.array(), (byte) 99);
                dst.position(2).limit(5);
                assertEquals(3, item.readValue(1, dst));
                assertEquals(5, dst.position());
                assertEquals(5, dst.limit());
                assertArrayEquals(new byte[]{99, 99, 2, 3, 4, 99, 99, 99, 99, 99}, dst.array());
                ByteBuffer independent = ByteBuffer.allocateDirect(7);
                assertEquals(5, two.item().readValue(0, independent));
                independent.flip();
                for (byte b : VALUE) assertEquals(b, independent.get());
                assertEquals(0, item.readValue(5, ByteBuffer.allocate(3)));
                assertEquals(0, item.readValue(0, ByteBuffer.allocate(0)));
                assertThrows(NullPointerException.class, () -> item.readValue(0, null));
                for (long offset : new long[]{-1, 6, Integer.MAX_VALUE, 1L << 32, Long.MAX_VALUE, Long.MIN_VALUE})
                    assertThrows(IndexOutOfBoundsException.class, () -> item.readValue(offset, ByteBuffer.allocate(0)));
                for (long offset : new long[]{0, 5}) {
                    assertThrows(ReadOnlyBufferException.class, () -> item.readValue(offset, ByteBuffer.allocate(0).asReadOnlyBuffer()));
                    assertThrows(ReadOnlyBufferException.class, () -> item.readValue(offset, ByteBuffer.allocate(5).asReadOnlyBuffer()));
                }
                byte[] copy = item.key();
                copy[0] = 87;
                assertArrayEquals(KEY, item.key());
            }
            stored.retire();
        }
    }

    @Test
    void ttlActivityAndUnsignedBitPatternsRemainIndependent() {
        try (var a = new TrackingAllocator()) {
            for (long expiry : new long[]{0, 1, Long.MAX_VALUE}) {
                StoredItem stored = StoredItem.create(a, KEY, new byte[0], Integer.MIN_VALUE, expiry, -1L, 1024);
                assertNotNull(stored);
                try (ItemLease one = stored.pin(); ItemLease two = stored.pin()) {
                    Item item = one.item();
                    assertFalse(item.isActive());
                    assertFalse(item.isExpired(-1));
                    assertEquals(expiry != 0, item.isExpired(expiry));
                    assertEquals(expiry != 0, item.isExpired(Long.MAX_VALUE));
                    if (expiry > 0) assertFalse(item.isExpired(expiry - 1));
                    item.markActive();
                    item.markActive();
                    assertTrue(two.item().isActive());
                    assertEquals(Integer.MIN_VALUE, item.flags());
                    assertEquals(-1L, item.casId());
                    assertEquals(expiry, item.expiryTime());
                    assertEquals(0, item.valueLength());
                    assertEquals(0, item.readValue(0, ByteBuffer.allocate(2)));
                }
                stored.retire();
            }
        }
    }

    @Test
    void keyComparisonUsesExactSliceAndRejectsInvalidBounds() {
        try (var a = new TrackingAllocator()) {
            StoredItem stored = create(a);
            assertTrue(stored.keyEquals(new byte[]{7, 0, (byte) 255, 13, 8}, 1, 3));
            assertFalse(stored.keyEquals(new byte[]{0, (byte) 255, 12}, 0, 3));
            assertFalse(stored.keyEquals(KEY, 0, 2));
            assertFalse(stored.keyEquals(KEY, 3, 0));
            assertThrows(NullPointerException.class, () -> stored.keyEquals(null, 0, 0));
            for (int[] range : new int[][]{{-1, 1}, {0, -1}, {1, 3}, {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}})
                assertThrows(IndexOutOfBoundsException.class, () -> stored.keyEquals(KEY, range[0], range[1]));
            stored.retire();
        }
    }

    @Test
    void allocatorShutdownDoesNotInvalidateOutstandingItemViews() {
        try (var a = new TrackingAllocator()) {
            StoredItem stored = create(a);
            try (ItemLease lease = stored.pin()) {
                a.close();
                stored.retire();
                assertArrayEquals(KEY, lease.item().key());
                assertEquals(5, lease.item().readValue(0, ByteBuffer.allocate(5)));
                assertEquals(64, a.usedMemory());
            }
            assertEquals(0, a.usedMemory());
            assertEquals(0, a.totalMemory());
            assertEquals(1, a.frees);
        }
    }

    @Test
    void recoverablePreparationFailuresFreeExactlyOnce() {
        for (int failure : new int[]{1, 2}) {
            try (var a = new TrackingAllocator()) {
                a.failure = failure;
                assertThrows(PreparationFailure.class, () -> create(a));
                assertEquals(1, a.allocations);
                assertEquals(1, a.frees);
                assertEquals(0, a.usedMemory());
                a.failure = 0;
                StoredItem stored = create(a);
                stored.retire();
                assertEquals(2, a.frees);
            }
        }
    }

    @Test
    void seededImmutableRecordsAndPinModel() {
        Random random = new Random(0x5709cafeL);
        try (var a = new TrackingAllocator()) {
            for (int iteration = 0; iteration < 200; iteration++) {
                byte[] key = new byte[1 + random.nextInt(250)];
                byte[] value = new byte[random.nextInt(400)];
                random.nextBytes(key);
                random.nextBytes(value);
                int flags = random.nextInt();
                long cas = random.nextLong();
                long expiry = random.nextLong() & Long.MAX_VALUE;
                int aligned = (32 + key.length + value.length + 7) & ~7;
                StoredItem stored = StoredItem.create(a, key, value, flags, expiry, cas, aligned);
                assertNotNull(stored);
                assertEquals(aligned, a.requested);
                var pins = new java.util.ArrayList<ItemLease>();
                for (int step = 0; step < 20; step++) {
                    if (pins.isEmpty() || random.nextBoolean()) pins.add(stored.pin());
                    else pins.removeLast().close();
                    assertEquals(pins.size(), stored.pinCount());
                    assertEquals(stored.allocatedBytes(), a.usedMemory());
                }
                stored.retire();
                assertNull(stored.pin());
                for (ItemLease pin : pins) {
                    Item item = pin.item();
                    assertArrayEquals(key, item.key());
                    assertEquals(flags, item.flags());
                    assertEquals(cas, item.casId());
                    assertEquals(expiry, item.expiryTime());
                    assertTrue(stored.keyEquals(key, 0, key.length));
                    int offset = random.nextInt(value.length + 1);
                    ByteBuffer dst = ByteBuffer.allocate(value.length - offset);
                    assertEquals(dst.capacity(), item.readValue(offset, dst));
                    assertArrayEquals(Arrays.copyOfRange(value, offset, value.length), dst.array());
                    pin.close();
                    assertClosed(item);
                }
                assertEquals(0, a.usedMemory());
            }
        }
    }

    @Test
    void incompleteWritesAndCleanupFailuresNeverPublish() {
        for (int write = 1; write <= 3; write++) {
            try (var a = new TrackingAllocator()) {
                a.shortWrite = write;
                assertThrows(IllegalStateException.class, () -> create(a));
                assertEquals(1, a.frees);
                assertEquals(0, a.usedMemory());
            }
        }
        for (boolean sameFailure : new boolean[]{false, true}) {
            try (var a = new TrackingAllocator()) {
                a.writeFailure = new PreparationFailure();
                a.freeFailure = sameFailure ? a.writeFailure : new PreparationFailure();
                RuntimeException thrown = assertThrows(PreparationFailure.class, () -> create(a));
                assertSame(a.writeFailure, thrown);
                assertEquals(sameFailure ? 0 : 1, thrown.getSuppressed().length);
                if (!sameFailure) assertSame(a.freeFailure, thrown.getSuppressed()[0]);
                assertEquals(1, a.frees);
                assertEquals(0, a.usedMemory());
            }
        }
    }

    @Test
    void readFailurePreservesDestinationLimitAndLeaseLiveness() {
        try (var a = new TrackingAllocator()) {
            StoredItem stored = create(a);
            try (ItemLease lease = stored.pin()) {
                a.shortRead = true;
                assertThrows(IllegalStateException.class, () -> stored.keyEquals(KEY, 0, KEY.length));
                assertThrows(IllegalStateException.class, () -> lease.item().key());
                a.shortRead = false;
                a.readFailure = new PreparationFailure();
                ByteBuffer dst = ByteBuffer.allocate(20).position(3).limit(17);
                assertThrows(PreparationFailure.class, () -> lease.item().readValue(1, dst));
                assertEquals(17, dst.limit());
                assertEquals(3, dst.position());
                a.readFailure = null;
                assertArrayEquals(KEY, lease.item().key());
                assertEquals(4, lease.item().readValue(1, dst));
                assertEquals(17, dst.limit());
            }
            stored.retire();
        }
    }

    @Test
    void pinOverflowIsRejectedWithoutChangingOwnership() throws Exception {
        try (var a = new TrackingAllocator()) {
            StoredItem stored = create(a);
            // Bounded white-box fault injection: billions of real leases would violate test budgets.
            var pins = StoredItem.class.getDeclaredField("pins");
            pins.setAccessible(true);
            pins.setInt(stored, Integer.MAX_VALUE);
            try {
                assertThrows(IllegalStateException.class, stored::pin);
                assertEquals(Integer.MAX_VALUE, stored.pinCount());
                assertFalse(stored.isRetired());
                assertEquals(0, a.frees);
            } finally {
                pins.setInt(stored, 0);
                stored.retire();
            }
            assertEquals(1, a.frees);
        }
    }

    @Test
    void keyComparisonWipesAllScratchBytesOnMatchMismatchAndReadFailures() throws Exception {
        var scratchField = StoredItem.class.getDeclaredField("keyScratch");
        scratchField.setAccessible(true);
        for (int length : new int[]{3, 17, 250}) {
            try (var a = new TrackingAllocator()) {
                byte[] key = new byte[length];
                Arrays.fill(key, (byte) 0x73);
                StoredItem stored = StoredItem.create(a, key, VALUE, -1, 123, -1L, 1024);
                assertNotNull(stored);
                try (ItemLease lease = stored.pin()) {
                    ByteBuffer scratch = ((ByteBuffer) scratchField.get(stored)).duplicate();
                    assertEquals(8, scratch.capacity());
                    for (int scenario = 0; scenario < 4; scenario++) {
                        // Poison unused bytes too: clearing only the last short chunk is insufficient.
                        scratch.clear();
                        for (int i = 0; i < 8; i++) scratch.put(i, (byte) 0x5a);
                        a.readFaultAfterCopy = scenario >= 2 ? scenario - 1 : 0;
                        if (scenario == 0) assertTrue(stored.keyEquals(key, 0, length));
                        if (scenario == 1) {
                            byte[] mismatch = key.clone();
                            mismatch[length - 1] ^= 1;
                            assertFalse(stored.keyEquals(mismatch, 0, length));
                        }
                        if (scenario == 2)
                            assertThrows(IllegalStateException.class, () -> stored.keyEquals(key, 0, length));
                        if (scenario == 3)
                            assertThrows(PreparationFailure.class, () -> stored.keyEquals(key, 0, length));
                        for (int i = 0; i < 8; i++)
                            assertEquals(0, scratch.get(i), "scratch byte " + i + ", scenario " + scenario);
                        a.readFaultAfterCopy = 0;
                        assertArrayEquals(key, lease.item().key());
                        ByteBuffer value = ByteBuffer.allocate(VALUE.length);
                        assertEquals(VALUE.length, lease.item().readValue(0, value));
                        assertArrayEquals(VALUE, value.array());
                        assertEquals(1, stored.pinCount());
                        assertFalse(stored.isRetired());
                        assertEquals(stored.allocatedBytes(), a.usedMemory());
                        assertEquals(1, a.allocations);
                        assertEquals(0, a.frees);
                    }
                } finally {
                    a.readFaultAfterCopy = 0;
                    stored.retire();
                }
                assertEquals(0, stored.pinCount());
                assertEquals(0, a.usedMemory());
                assertEquals(1, a.frees);
            }
        }
    }

    static final class PreparationFailure extends RuntimeException { }

    static final class TrackingAllocator implements MemoryAllocator, AutoCloseable {
        final FfmSlabAllocator delegate = new FfmSlabAllocator(16384, 1024, 1024);
        SlabHandle raw;
        SlabHandle wrapped;
        int requested;
        int allocations;
        int frees;
        int failure;
        int shortWrite;
        int writes;
        boolean shortRead;
        int readFaultAfterCopy;
        RuntimeException writeFailure;
        RuntimeException readFailure;
        RuntimeException freeFailure;

        public synchronized SlabHandle allocate(int size) {
            allocations++;
            requested = size;
            raw = delegate.allocate(size);
            if (raw == null) return null;
            SlabHandle underlying = raw;
            wrapped = new SlabHandle() {
                public int classId() { return underlying.classId(); }
                public int capacity() {
                    if (failure == 2) throw new PreparationFailure();
                    return underlying.capacity();
                }
                public int read(int offset, ByteBuffer dst) {
                    if (readFailure != null) throw readFailure;
                    if (shortRead) return 0;
                    if (readFaultAfterCopy != 0) {
                        int copied = underlying.read(offset, dst);
                        if (readFaultAfterCopy == 2) throw new PreparationFailure();
                        return copied - 1;
                    }
                    return underlying.read(offset, dst);
                }
                public int write(int offset, ByteBuffer src) {
                    writes++;
                    if (writeFailure != null) throw writeFailure;
                    if (shortWrite == writes) return 0;
                    if (failure == 1) {
                        underlying.write(offset, src);
                        throw new PreparationFailure();
                    }
                    return underlying.write(offset, src);
                }
            };
            return wrapped;
        }

        public synchronized void free(SlabHandle handle) {
            assertSame(wrapped, handle);
            frees++;
            delegate.free(raw);
            if (freeFailure != null) throw freeFailure;
        }
        public long totalMemory() { return delegate.totalMemory(); }
        public long usedMemory() { return delegate.usedMemory(); }
        public int classCount() { return delegate.classCount(); }
        public SlabClassStats classStats(int id) { return delegate.classStats(id); }
        public void close() { delegate.close(); }
    }
}
