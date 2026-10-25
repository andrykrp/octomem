package ru.octomem.storage;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StoredItemConcurrencyTest {
    @Test
    void readersKeepNativeBlockAliveAcrossRetirementAndAllocatorShutdown() throws Exception {
        try (var allocator = new StoredItemTest.TrackingAllocator();
             var threads = Executors.newFixedThreadPool(4)) {
            StoredItem stored = StoredItemTest.create(allocator);
            var pinned = new CountDownLatch(4);
            var retired = new CountDownLatch(1);
            var read = new CountDownLatch(4);
            var release = new CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(threads.submit(() -> {
                try (ItemLease lease = stored.pin()) {
                    assertNotNull(lease);
                    Item item = lease.item();
                    pinned.countDown();
                    assertTrue(retired.await(10, TimeUnit.SECONDS));
                    for (int n = 0; n < 100; n++) {
                        assertArrayEquals(StoredItemTest.KEY, item.key());
                        ByteBuffer dst = ByteBuffer.allocate(5);
                        assertEquals(5, item.readValue(0, dst));
                        assertArrayEquals(StoredItemTest.VALUE, dst.array());
                        item.markActive();
                    }
                    read.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return null;
            }));
            try {
                assertTrue(pinned.await(10, TimeUnit.SECONDS));
                stored.retire();
                allocator.close();
                retired.countDown();
                assertTrue(read.await(10, TimeUnit.SECONDS));
                assertNull(stored.pin());
                assertEquals(4, stored.pinCount());
                assertEquals(0, allocator.frees);
                assertEquals(64, allocator.usedMemory());
            } finally {
                retired.countDown();
                release.countDown();
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(0, stored.pinCount());
            assertEquals(1, allocator.frees);
            assertEquals(0, allocator.usedMemory());
            assertEquals(0, allocator.totalMemory());
        }
    }

    @Test
    void barrierPinRetireAndFinalCloseRaceNeverResurrectsOrDoubleFrees() throws Exception {
        try (var allocator = new StoredItemTest.TrackingAllocator();
             var threads = Executors.newFixedThreadPool(3)) {
            for (int round = 0; round < 200; round++) {
                StoredItem stored = StoredItemTest.create(allocator);
                var ready = new CountDownLatch(1);
                var start = new CyclicBarrier(3);
                var owner = threads.submit(() -> {
                    try (ItemLease lease = stored.pin()) {
                        assertNotNull(lease);
                        Item item = lease.item();
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        assertArrayEquals(StoredItemTest.KEY, item.key());
                    }
                    return null;
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                var retire = threads.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    stored.retire();
                    stored.retire();
                    return null;
                });
                var contender = threads.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    ItemLease pin = stored.pin();
                    if (pin != null) {
                        Item item = pin.item();
                        try (pin) {
                            assertArrayEquals(StoredItemTest.KEY, item.key());
                            assertEquals(5, item.readValue(0, ByteBuffer.allocate(5)));
                        }
                        StoredItemTest.assertClosed(item);
                    }
                    return null;
                });
                owner.get(10, TimeUnit.SECONDS);
                retire.get(10, TimeUnit.SECONDS);
                contender.get(10, TimeUnit.SECONDS);
                assertTrue(stored.isRetired());
                assertNull(stored.pin());
                assertEquals(0, stored.pinCount());
                assertEquals(round + 1, allocator.frees);
                assertEquals(0, allocator.usedMemory());
            }
        }
    }
}
