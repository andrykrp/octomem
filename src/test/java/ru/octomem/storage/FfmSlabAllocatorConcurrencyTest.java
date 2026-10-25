package ru.octomem.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class FfmSlabAllocatorConcurrencyTest {
    @Test
    void concurrentChurnAndTrimPreserveEachLiveBlockAndAccounting() throws Exception {
        try (var allocator = new FfmSlabAllocator(4096, 256, 64);
             var pool = Executors.newFixedThreadPool(5)) {
            List<Callable<Void>> actions = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                byte pattern = (byte) (worker + 1);
                actions.add(() -> {
                    byte[] data = new byte[64];
                    Arrays.fill(data, pattern);
                    for (int i = 0; i < 500; i++) {
                        SlabHandle handle = allocator.allocate(64);
                        assertNotNull(handle);
                        assertEquals(64, handle.write(0, ByteBuffer.wrap(data)));
                        ByteBuffer result = ByteBuffer.allocate(64);
                        assertEquals(64, handle.read(0, result));
                        assertArrayEquals(data, result.array());
                        allocator.free(handle);
                    }
                    return null;
                });
            }
            actions.add(() -> { for (int i = 0; i < 500; i++) allocator.trim(); return null; });
            together(pool, actions);
            assertEquals(0, allocator.usedMemory());
            allocator.trim();
            assertEquals(0, allocator.totalMemory());
        }
    }

    @Test
    void readRacingFreeTrimCloseIsWholeReadOrFreedFailureNeverPartialUseAfterFree() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int trial = 0; trial < 200; trial++) {
                try (var allocator = new FfmSlabAllocator(256, 256, 64)) {
                    SlabHandle handle = allocator.allocate(64);
                    assertNotNull(handle);
                    byte[] bytes = new byte[64];
                    Arrays.fill(bytes, (byte) 73);
                    handle.write(0, ByteBuffer.wrap(bytes));
                    ByteBuffer result = ByteBuffer.allocate(64);
                    List<String> outcomes = together(pool, List.of(
                            () -> {
                                try {
                                    assertEquals(64, handle.read(0, result));
                                    assertArrayEquals(bytes, result.array());
                                    return "read";
                                } catch (IllegalStateException freed) {
                                    assertEquals(0, result.position());
                                    assertArrayEquals(new byte[64], result.array());
                                    return "freed";
                                }
                            },
                            () -> { allocator.free(handle); return "free"; },
                            () -> { allocator.trim(); return "trim"; },
                            () -> { allocator.close(); return "close"; }));
                    assertTrue(outcomes.getFirst().equals("read") || outcomes.getFirst().equals("freed"));
                    assertEquals(0, allocator.usedMemory());
                    assertEquals(0, allocator.totalMemory());
                    assertTrue(allocator.isClosed());
                }
            }
        }
    }

    @Test
    void simultaneousFreeHasExactlyOneWinnerAndNoDoubleAccounting() throws Exception {
        try (var allocator = new FfmSlabAllocator(128, 128, 64);
             var pool = Executors.newFixedThreadPool(2)) {
            for (int trial = 0; trial < 200; trial++) {
                SlabHandle handle = allocator.allocate(64);
                assertNotNull(handle);
                Callable<Boolean> free = () -> {
                    try { allocator.free(handle); return true; }
                    catch (IllegalArgumentException alreadyFreed) { return false; }
                };
                List<Boolean> outcomes = together(pool, List.of(free, free));
                assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
                assertEquals(0, allocator.usedMemory());
            }
        }
    }

    private static <T> List<T> together(ExecutorService pool, List<Callable<T>> actions) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(actions.size());
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> action : actions) futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return action.call();
            }));
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
    }
}
