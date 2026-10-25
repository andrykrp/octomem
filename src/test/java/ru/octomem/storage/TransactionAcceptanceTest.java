package ru.octomem.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Independently authored additive transaction contract tests; coordinator-approved scope. */
@Timeout(20)
class TransactionAcceptanceTest {
    @Test
    void promotionRejectsForeignThreadRevokedViewAndUnmappedOwners() throws Exception {
        try(FfmSlabAllocator allocator=new FfmSlabAllocator(65536,4096,4096);
            SegmentedItemTable table=new SegmentedItemTable(1,2,65536);
            var pool=Executors.newSingleThreadExecutor()) {
            table.initializeClassQueues(allocator.classCount());
            StoredItem a=item(allocator,"a","a"), b=item(allocator,"b","b"), unpublished=item(allocator,"u","u");
            table.put(b("a"),a); table.put(b("b"),b);
            AtomicReference<SegmentedItemTable.Transaction> escaped=new AtomicReference<>();
            try {
                table.transaction(0,tx -> {
                    escaped.set(tx);
                    pool.submit(() -> assertThrows(IllegalStateException.class,() -> tx.promote(a))).get(5,TimeUnit.SECONDS);
                    assertThrows(IllegalArgumentException.class,() -> tx.promote(unpublished));
                    tx.scan(0,2,current -> {
                        StoredItem other=current==a?b:a;
                        assertThrows(IllegalStateException.class,() -> tx.selectForRemoval(other));
                        return false;
                    });
                    assertFalse(tx.removeSelected());
                    StoredItem replacement=item(allocator,"a","new");
                    tx.prepareReplacement(b("a"),0,1,replacement); tx.commit();
                    assertThrows(IllegalArgumentException.class,() -> tx.promote(a));
                    assertSame(replacement,tx.find(b("a"),0,1));
                    assertSame(b,tx.find(b("b"),0,1));
                    return null;
                });
                assertThrows(IllegalStateException.class,() -> escaped.get().promote(b));
                assertEquals(2,table.size());
            } finally { unpublished.retire(); }
        }
    }
    @Test
    void serviceBudgetActualCasContentionRespectsSmallLimit() throws Exception {
        var barrier=new java.util.concurrent.CyclicBarrier(4);
        ThreadLocal<Boolean> first=ThreadLocal.withInitial(() -> true);
        StoredItem.ServiceBudget budget=new StoredItem.ServiceBudget(1024,() -> {
            if(first.get()) {
                first.set(false);
                try { barrier.await(5,TimeUnit.SECONDS); }
                catch(Exception error) { throw new IllegalStateException(error); }
            }
        });
        try(FfmSlabAllocator allocator=new FfmSlabAllocator(65536,4096,4096);
            var pool=Executors.newFixedThreadPool(4)) {
            java.util.List<java.util.concurrent.Future<StoredItem>> futures=new java.util.ArrayList<>();
            for(int i=0;i<4;i++) futures.add(pool.submit(() -> StoredItem.create(budget,allocator,b("a"),0,1,1,
                (offset,dst) -> dst.put((byte)1),ByteBuffer.allocate(32),0,false,0,0,4096)));
            java.util.List<StoredItem> admitted=new java.util.ArrayList<>();
            for(var future:futures) { StoredItem result=future.get(10,TimeUnit.SECONDS); if(result!=null) admitted.add(result); }
            assertEquals(2,admitted.size());
            assertEquals(1024,budget.usedBytes());
            admitted.forEach(StoredItem::retire);
            assertEquals(0,budget.usedBytes());
            assertEquals(0,allocator.usedMemory());
        }
    }
    @Test
    void serviceBudgetDenialAndFailureReleaseWithoutNativeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new StoredItem.ServiceBudget(-1));
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536,4096,4096)) {
            var none = new StoredItem.ServiceBudget(511);
            var one = new StoredItem.ServiceBudget(512);
            StoredItem.ValueSource source = (offset,dst) -> { while(dst.hasRemaining()) dst.put((byte)1); };
            assertNull(StoredItem.create(none,allocator,b("a"),0,1,1,source,ByteBuffer.allocate(32),0,false,0,0,4096));
            assertEquals(0,allocator.totalMemory());
            assertNull(StoredItem.create(one,allocator,b("a"),0,1,4096,source,ByteBuffer.allocate(32),0,false,0,0,4096));
            assertEquals(0,one.usedBytes());
            StoredItem item = StoredItem.create(one,allocator,b("a"),0,1,1,source,ByteBuffer.allocate(32),0,true,-1,0,4096);
            assertNotNull(item);
            assertEquals(512,one.usedBytes());
            assertNull(StoredItem.create(one,allocator,b("b"),0,1,1,source,ByteBuffer.allocate(32),0,false,0,0,4096));
            item.retire();
            assertEquals(0,one.usedBytes());
        }
    }

    @Test
    void defaultExpiryPresenceKeepsLegacyItemCompatibility() {
        for (long expiry : new long[]{0,1,-1}) {
            Item legacy = new Item() {
                public byte[] key() { return b("k"); }
                public int flags() { return 0; }
                public long expiryTime() { return expiry; }
                public long casId() { return 0; }
                public int valueLength() { return 0; }
                public int readValue(long offset,ByteBuffer dst) { return 0; }
                public boolean isExpired(long now) { return hasExpiry() && expiry<=now; }
                public boolean isActive() { return false; }
                public void markActive() { }
            };
            assertEquals(expiry != 0,legacy.hasExpiry());
        }
    }
    static byte[] b(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    static StoredItem item(FfmSlabAllocator allocator, String key, String value) {
        return java.util.Objects.requireNonNull(StoredItem.create(allocator, b(key), b(value), 7, 100, 42, 4096));
    }

    @Test
    void batchCommitAndCallbackFailurePreserveOwnership() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 65536, key -> 0)) {
            StoredItem a = item(allocator, "a", "A0");
            StoredItem b = item(allocator, "b", "B0");
            assertTrue(table.put(b("a"), a));
            assertTrue(table.put(b("b"), b));
            long before = allocator.usedMemory();
            StoredItem rollbackA = item(allocator, "a", "A1");
            StoredItem rollbackB = item(allocator, "b", "B1");
            RuntimeException expected = new RuntimeException("injected callback failure");
            assertSame(expected, assertThrows(RuntimeException.class, () -> table.transactionAll(tx -> {
                tx.prepareReplacement(b("a"), 0, 1, rollbackA);
                tx.prepareReplacement(b("b"), 0, 1, rollbackB);
                assertSame(a, tx.find(b("a"), 0, 1));
                assertSame(b, tx.find(b("b"), 0, 1));
                throw expected;
            })));
            assertTrue(rollbackA.isRetired());
            assertTrue(rollbackB.isRetired());
            assertEquals(before, allocator.usedMemory());
            try (ItemLease pinA = table.get(b("a")); ItemLease pinB = table.get(b("b"))) {
                StoredItem nextA = item(allocator, "a", "A2");
                StoredItem nextB = item(allocator, "b", "B2");
                table.transaction(new int[]{0}, 1, tx -> {
                    tx.prepareReplacement(b("a"), 0, 1, nextA);
                    tx.prepareReplacement(b("b"), 0, 1, nextB);
                    assertThrows(IllegalStateException.class, () -> tx.put(b("a"), nextA));
                    assertThrows(IllegalStateException.class, () -> tx.remove(b("a"), 0, 1));
                    tx.commit();
                    tx.commit();
                    assertSame(nextA, tx.find(b("a"), 0, 1));
                    assertSame(nextB, tx.find(b("b"), 0, 1));
                    return null;
                });
                assertTrue(a.isRetired());
                assertTrue(b.isRetired());
                assertEquals("A0", text(pinA.item()));
                assertEquals("B0", text(pinB.item()));
            }
            table.transaction(0, tx -> {
                assertFalse(tx.remove(b("missing"), 0, 7));
                assertTrue(tx.remove(b("a"), 0, 1));
                assertTrue(tx.remove(b("b"), 0, 1));
                return null;
            });
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void invalidPreparationDoesNotTransferOwnershipAndUncommittedWorkRollsBack() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 65536)) {
            StoredItem old = item(allocator, "a", "old");
            assertTrue(table.put(b("a"), old));
            StoredItem replacement = item(allocator, "a", "replacement");
            StoredItem wrongKey = item(allocator, "b", "wrong");
            StoredItem retired = item(allocator, "a", "retired");
            retired.retire();
            try {
                table.transaction(0, tx -> {
                    assertThrows(IllegalArgumentException.class, () -> tx.prepareReplacement(b("missing"), 0, 7, replacement));
                    assertThrows(IllegalArgumentException.class, () -> tx.prepareReplacement(b("a"), 0, 1, old));
                    assertThrows(IllegalArgumentException.class, () -> tx.prepareReplacement(b("a"), 0, 1, retired));
                    assertThrows(IllegalArgumentException.class, () -> tx.prepareReplacement(b("a"), 0, 1, wrongKey));
                    tx.prepareReplacement(b("a"), 0, 1, replacement);
                    assertThrows(IllegalArgumentException.class, () -> tx.prepareReplacement(b("a"), 0, 1, replacement));
                    return null;
                });
                assertTrue(replacement.isRetired());
                assertFalse(wrongKey.isRetired());
                table.transactionAll(tx -> { assertSame(old, tx.find(b("a"), 0, 1)); return null; });
            } finally { wrongKey.retire(); }
        }
    }

    @Test
    void transactionScopeOrderingThreadConfinementAndRevocation() throws Exception {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(4, 2, 65536, key -> key[0] & 3);
             var executor = Executors.newSingleThreadExecutor()) {
            assertThrows(IllegalArgumentException.class, () -> table.transaction(new int[]{1, 0}, 2, tx -> null));
            assertThrows(IllegalArgumentException.class, () -> table.transaction(new int[]{0, 0}, 2, tx -> null));
            assertThrows(IllegalArgumentException.class, () -> table.transaction(new int[]{-1}, 1, tx -> null));
            assertThrows(IllegalArgumentException.class, () -> table.transaction(new int[]{4}, 1, tx -> null));
            assertThrows(IndexOutOfBoundsException.class, () -> table.transaction(new int[]{0}, 2, tx -> null));
            assertThrows(IndexOutOfBoundsException.class, () -> table.transaction(-1, tx -> null));
            assertThrows(IndexOutOfBoundsException.class, () -> table.transaction(4, tx -> null));
            AtomicReference<SegmentedItemTable.Transaction> escaped = new AtomicReference<>();
            table.transaction(new int[]{0, 2}, 2, tx -> {
                escaped.set(tx);
                assertNull(tx.find(new byte[]{4}, 0, 1));
                assertNull(tx.find(new byte[]{2}, 0, 1));
                assertThrows(IllegalArgumentException.class, () -> tx.find(new byte[]{1}, 0, 1));
                executor.submit(() -> {
                    assertThrows(IllegalStateException.class, () -> tx.find(new byte[]{4}, 0, 1));
                    assertThrows(IllegalStateException.class, tx::commit);
                    assertThrows(IllegalStateException.class, tx::removeSelected);
                    assertThrows(IllegalStateException.class, () -> tx.selectForRemoval(null));
                }).get(5, TimeUnit.SECONDS);
                return null;
            });
            var revoked = escaped.get();
            assertThrows(IllegalStateException.class, () -> revoked.find(new byte[]{4}, 0, 1));
            assertThrows(IllegalStateException.class, revoked::commit);
            assertThrows(IllegalStateException.class, revoked::removeSelected);
            assertThrows(IllegalStateException.class, () -> revoked.selectForRemoval(null));
            table.transaction(0, tx -> {
                assertThrows(IllegalArgumentException.class, () -> tx.scan(1, 1, item -> false));
                assertThrows(IllegalArgumentException.class, () -> tx.scan(0, -1, item -> false));
                assertEquals(0, tx.scan(0, 0, item -> { fail(); return false; }));
                assertFalse(tx.removeSelected());
                assertThrows(IllegalStateException.class, () -> tx.selectForRemoval(null));
                return null;
            });
            table.close();
            assertThrows(IllegalStateException.class, () -> table.transaction(0, tx -> null));
            assertThrows(IllegalStateException.class, () -> table.transaction(new int[]{0}, 1, tx -> null));
            assertThrows(IllegalStateException.class, () -> table.transactionAll(tx -> null));
        }
    }

    @Test
    void collidingRingScanRotatesAndSelectedRemovalDoesNotCorruptChain() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 65536, key -> 0)) {
            for (String name : new String[]{"a", "b", "c"}) assertTrue(table.put(b(name), item(allocator, name, name)));
            java.util.Set<StoredItem> visited = new java.util.HashSet<>();
            for (int i = 0; i < 3; i++) table.transaction(0, tx -> {
                assertEquals(1, tx.scan(0, 1, value -> { assertTrue(visited.add(value)); return false; }));
                return null;
            });
            assertEquals(3, visited.size());
            table.transaction(0, tx -> {
                assertEquals(3, tx.scan(0, 10, value -> {
                    if (value.keyEquals(b("b"), 0, 1)) tx.selectForRemoval(value);
                    return false;
                }));
                assertTrue(tx.removeSelected());
                assertFalse(tx.removeSelected());
                assertNull(tx.find(b("b"), 0, 1));
                assertNotNull(tx.find(b("a"), 0, 1));
                assertNotNull(tx.find(b("c"), 0, 1));
                assertEquals(2, tx.scan(0, 10, value -> { tx.selectForRemoval(value); return true; }));
                assertFalse(tx.removeSelected());
                return null;
            });
            assertEquals(0, table.size());
            assertEquals(0, allocator.usedMemory());
        }
    }

    static String text(Item item) {
        ByteBuffer bytes = ByteBuffer.allocate(item.valueLength());
        assertEquals(bytes.remaining(), item.readValue(0, bytes));
        return new String(bytes.array(), StandardCharsets.US_ASCII);
    }

    @Test
    void streamingRecordValidationSizeAndSourceFailureDoNotLeak() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536, 4096, 4096)) {
            StoredItem.ValueSource fill = (offset, dst) -> { while (dst.hasRemaining()) dst.put((byte) 'x'); };
            ByteBuffer scratch = ByteBuffer.allocate(32);
            assertThrows(IllegalArgumentException.class, () -> StoredItem.create(allocator, b("a"), 0, 0, 1, fill, scratch, 0, 0, 0, 4096));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.create(allocator, new byte[251], 0, 251, 1, fill, scratch, 0, 0, 0, 4096));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.create(allocator, b("a"), 0, 1, -1, fill, scratch, 0, 0, 0, 4096));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.create(allocator, b("a"), 0, 1, 1, fill, scratch, 0, -1, 0, 4096));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.create(allocator, b("a"), 0, 1, 1, fill, scratch, 0, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.create(allocator, b("a"), 0, 1, 1, fill, ByteBuffer.allocate(31), 0, 0, 0, 4096));
            assertThrows(IllegalArgumentException.class, () -> StoredItem.create(allocator, b("a"), 0, 1, 1, fill, scratch.asReadOnlyBuffer(), 0, 0, 0, 4096));
            assertNull(StoredItem.create(allocator, b("a"), 0, 1, 4096, fill, scratch, 0, 0, 0, 4096));
            assertNull(StoredItem.create(allocator, b("a"), 0, 1, Integer.MAX_VALUE, fill, scratch, 0, 0, 0, Integer.MAX_VALUE));
            assertThrows(IllegalStateException.class, () -> StoredItem.create(allocator, b("a"), 0, 1, 1, (o,d) -> {}, scratch, 0, 0, 0, 4096));
            assertEquals(0, allocator.usedMemory());
            assertEquals(0, scratch.position());
            RuntimeException failure = new RuntimeException("source");
            assertSame(failure, assertThrows(RuntimeException.class, () -> StoredItem.create(allocator, b("a"), 0, 1, 1, (o,d) -> { throw failure; }, scratch, 0, 0, 0, 4096)));
            assertEquals(0, allocator.usedMemory());
            byte[] key = new byte[250];
            java.util.Arrays.fill(key, (byte) 'k');
            StoredItem created = StoredItem.create(allocator, key, 0, key.length, 75, fill, scratch, -1, 0, -1, 4096);
            assertNotNull(created);
            try {
                assertEquals(75, created.copyValue(0, ByteBuffer.allocate(100)));
                assertEquals(0, created.copyValue(75, ByteBuffer.allocate(1)));
                assertThrows(IndexOutOfBoundsException.class, () -> created.copyValue(-1, scratch));
                assertThrows(IndexOutOfBoundsException.class, () -> created.copyValue(76, scratch));
                created.initializeVisibility(123, 7);
                assertEquals(123, created.createdAt());
                assertEquals(7, created.generation());
                assertThrows(IllegalStateException.class, () -> created.initializeVisibility(124, 8));
            } finally { created.retire(); }
            assertThrows(IllegalStateException.class, () -> created.copyValue(0, scratch));
            StoredItem pinned = item(allocator, "pin", "v");
            try (ItemLease lease = pinned.pin()) {
                assertThrows(IllegalStateException.class, () -> pinned.initializeVisibility(1, 1));
            } finally { pinned.retire(); }
            StoredItem zero = item(allocator, "zero", "v");
            assertThrows(IllegalStateException.class, () -> zero.initializeVisibility(1, 0));
            zero.retire();
            assertThrows(IllegalStateException.class, () -> zero.initializeVisibility(1, 1));
        }
    }

    @Test
    void streamingShortWritesAtHeaderKeyAndValueReleaseRealFfmBlock() {
        for (int failedWrite = 1; failedWrite <= 3; failedWrite++) {
            try (FfmSlabAllocator delegate = new FfmSlabAllocator(65536, 4096, 4096)) {
                int position = failedWrite;
                MemoryAllocator wrapper = new MemoryAllocator() {
                    SlabHandle raw;
                    SlabHandle wrapped;
                    int writes;
                    public SlabHandle allocate(int size) {
                        raw = delegate.allocate(size);
                        wrapped = new SlabHandle() {
                            public int classId() { return raw.classId(); }
                            public int capacity() { return raw.capacity(); }
                            public int read(int offset, ByteBuffer target) { return raw.read(offset, target); }
                            public int write(int offset, ByteBuffer source) {
                                if (++writes == position) return 0;
                                return raw.write(offset, source);
                            }
                        };
                        return wrapped;
                    }
                    public void free(SlabHandle handle) { assertSame(wrapped, handle); delegate.free(raw); }
                    public long totalMemory() { return delegate.totalMemory(); }
                    public long usedMemory() { return delegate.usedMemory(); }
                    public int classCount() { return delegate.classCount(); }
                    public ru.octomem.stats.SlabClassStats classStats(int id) { return delegate.classStats(id); }
                };
                assertThrows(IllegalStateException.class, () -> StoredItem.create(wrapper, b("a"), 0, 1, 1,
                    (offset, dst) -> dst.put((byte) 'v'), ByteBuffer.allocate(32), 0, 0, 0, 4096));
                assertEquals(0, delegate.usedMemory());
            }
        }
    }
}
