package ru.octomem.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;
import static ru.octomem.storage.SegmentedItemTableTest.*;

/** Barrier-controlled schedules; timeouts are deadlock bounds, never scheduling sleeps. */
@Timeout(30)
class SegmentedItemTableConcurrencyTest {
    @Test
    void concurrentSegmentsReserveAndReleaseAgainstOneGlobalCounter() throws Exception {
        try (var allocator = allocator();
             var table = new SegmentedItemTable(8, 2, 100000,
                     key -> ByteBuffer.wrap(key).getInt())) {
            CyclicBarrier barrier = new CyclicBarrier(8);
            ExecutorService workers = Executors.newFixedThreadPool(8);
            List<Future<?>> results = new ArrayList<>();
            try {
                for (int segment = 0; segment < 8; segment++) {
                    int id = segment;
                    results.add(workers.submit(() -> {
                        for (int iteration = 0; iteration < 250; iteration++) {
                            StoredItem candidate = item(allocator, key(id), iteration);
                            cross(barrier);
                            assertTrue(table.put(key(id), candidate));
                            cross(barrier);
                            assertEquals(2880, table.metadataUsedBytes());
                            cross(barrier);
                            assertTrue(table.remove(key(id)));
                            cross(barrier);
                            assertEquals(2368, table.metadataUsedBytes());
                            cross(barrier);
                        }
                    }));
                }
                for (Future<?> result : results) result.get(15, TimeUnit.SECONDS);
                assertEquals(0, table.size());
                assertEquals(0, allocator.usedMemory());
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void differentSegmentCompletesWhileFirstSegmentIsInsideAllocation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var allocator = allocator();
             var table = new SegmentedItemTable(2, 2, 4096,
                     key -> ByteBuffer.wrap(key).getInt(), () -> {
                         if (first.compareAndSet(true, false)) {
                             entered.countDown();
                             await(release);
                         }
                     })) {
            StoredItem even = item(allocator, key(0), 10);
            StoredItem odd = item(allocator, key(1), 20);
            Future<Boolean> blocked = workers.submit(() -> table.put(key(0), even));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Future<Boolean> independent = workers.submit(() -> {
                    assertTrue(table.put(key(1), odd));
                    assertValue(table, key(1), 20);
                    return table.remove(key(1));
                });
                assertTrue(independent.get(5, TimeUnit.SECONDS));
                assertFalse(blocked.isDone());
                assertTrue(odd.isRetired());
            } finally {
                release.countDown();
            }
            assertTrue(blocked.get(5, TimeUnit.SECONDS));
            assertValue(table, key(0), 10);
            table.close();
            assertEquals(0, allocator.usedMemory());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSegmentsCannotSpendAnInFlightMetadataReservationTwice() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var allocator = allocator();
             var table = new SegmentedItemTable(2, 2, 752,
                     key -> ByteBuffer.wrap(key).getInt(), () -> {
                         hooks.incrementAndGet();
                         reserved.countDown();
                         await(release);
                     })) {
            StoredItem even = item(allocator, key(0), 10);
            StoredItem odd = item(allocator, key(1), 20);
            try {
                Future<Boolean> first = workers.submit(() -> table.put(key(0), even));
                try {
                    assertTrue(reserved.await(5, TimeUnit.SECONDS));
                    assertEquals(752, table.metadataUsedBytes());
                    Future<Boolean> second = workers.submit(() -> table.put(key(1), odd));
                    assertFalse(second.get(5, TimeUnit.SECONDS));
                    assertEquals(1, hooks.get());
                    assertFalse(odd.isRetired());
                    assertNull(table.get(key(1)));
                } finally { release.countDown(); }
                assertTrue(first.get(5, TimeUnit.SECONDS));
                assertEquals(1, table.size());
                assertEquals(752, table.metadataUsedBytes());
            } finally {
                release.countDown();
                table.close();
                even.retire();
                odd.retire();
            }
            assertEquals(0, allocator.usedMemory());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void operationsPausedBeforeSegmentLockRecheckClose() throws Exception {
        for (int operation = 0; operation < 3; operation++) {
            CountDownLatch hashing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean armed = new AtomicBoolean();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try (var allocator = allocator();
                 var table = new SegmentedItemTable(2, 2, 4096, key -> {
                     if (armed.get()) {
                         hashing.countDown();
                         await(release);
                     }
                     return ByteBuffer.wrap(key).getInt();
                 })) {
                StoredItem old = item(allocator, key(1), 10);
                StoredItem caller = item(allocator, key(1), 20);
                assertTrue(table.put(key(1), old));
                int selected = operation;
                try (ItemLease lease = table.get(key(1))) {
                    armed.set(true);
                    Future<?> raced = worker.submit(() -> assertThrows(IllegalStateException.class, () -> {
                        switch (selected) {
                            case 0 -> table.get(key(1));
                            case 1 -> table.remove(key(1));
                            default -> table.put(key(1), caller);
                        }
                    }));
                    try {
                        assertTrue(hashing.await(5, TimeUnit.SECONDS));
                        table.close();
                        assertTrue(table.isClosed());
                        assertEquals(0, table.metadataUsedBytes());
                        assertTrue(old.isRetired());
                        assertFalse(caller.isRetired());
                        assertItem(lease.item(), key(1), 10);
                    } finally { release.countDown(); }
                    raced.get(5, TimeUnit.SECONDS);
                } finally {
                    caller.retire();
                    release.countDown();
                }
                assertEquals(0, allocator.usedMemory());
            } finally {
                release.countDown();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentReplacementAndReadersObserveWholeNativeVersions() throws Exception {
        try (var allocator = allocator(); var table = new SegmentedItemTable(4, 2, 100000)) {
            assertTrue(table.put(key(1), item(allocator, key(1), 0)));
            CyclicBarrier start = new CyclicBarrier(5);
            ExecutorService workers = Executors.newFixedThreadPool(5);
            List<Future<?>> results = new ArrayList<>();
            try {
                results.add(workers.submit(() -> {
                    cross(start);
                    for (int i = 1; i <= 500; i++) {
                        assertTrue(table.put(key(1), item(allocator, key(1), i)));
                    }
                }));
                for (int reader = 0; reader < 4; reader++) {
                    results.add(workers.submit(() -> {
                        cross(start);
                        for (int i = 0; i < 500; i++) {
                            try (ItemLease lease = table.get(key(1))) {
                                assertNotNull(lease);
                                Item view = lease.item();
                                int version = view.flags();
                                assertTrue(version >= 0 && version <= 500);
                                assertItem(view, key(1), version);
                            }
                        }
                    }));
                }
                for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
                assertValue(table, key(1), 500);
                assertEquals(1, table.size());
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
            table.close();
            assertEquals(0, allocator.usedMemory());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void cross(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
