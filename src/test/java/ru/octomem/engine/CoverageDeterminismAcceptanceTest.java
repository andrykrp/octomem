package ru.octomem.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.Item;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.Clock;

/**
 * Empirical bounded contention stress for the admitted command-metadata budget.
 *
 * <p>The snapshot budget loop in {@code DefaultCacheEngine.capture} reads the
 * shared counter, tests the limit, then optimistically {@code compareAndSet}s it.
 * Barrier-released bursts encourage contention but do not control scheduling
 * between the read and CAS. Admission or denial does not prove a lost CAS;
 * retry-edge coverage is an empirical observation in the separate coverage report.
 *
 * <p>Preemption can cause a lost CAS even on one CPU; two reported processors
 * neither enforce CPU affinity nor guarantee simultaneous execution. The legacy
 * class/file name is retained for test selectors, not a determinism claim.
 *
 * <p>No production seam, exclusion, threshold change or profile change is used.
 * The workload has fixed round, burst and worker bounds and a hard parent-process
 * deadline, validates ordered keys, absence of duplicates and exact value bytes, and returns
 * the engine to a quiescent, fully released state with no leaked metadata.
 *
 * @since 1.0
 */
@Timeout(180)
class CoverageDeterminismAcceptanceTest {

    private static final long NOW = 1_700_000_000L;
    private static final long MEMORY_LIMIT = 64L * 1024L;
    private static final int ITEM_MAX = 256;
    private static final int KEY_COUNT = 3;
    private static final long CHARGE = 512L + 512L * KEY_COUNT;
    private static final long SLOTS = (MEMORY_LIMIT / 16L) / CHARGE;
    private static final int WORKERS = 8;
    private static final int ROUNDS = 500;
    private static final int BURST = 200;

    private static final Clock CLOCK = new Clock() {
        @Override public long currentTimeSeconds() { return NOW; }
        @Override public long nanoTime() { return 0; }
    };

    private static final byte[] KEY_ONE = bytes("determinism-one");
    private static final byte[] KEY_TWO = bytes("determinism-two");
    private static final byte[] KEY_THREE = bytes("determinism-three");
    private static final List<String> EXPECTED_KEYS = List.of(
            "determinism-one", "determinism-two", "determinism-three");

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static String text(byte[] value) { return new String(value, StandardCharsets.US_ASCII); }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "coverage-determinism-churn");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static KeySequence fixedKeys(byte[]... keys) {
        return new KeySequence() {
            @Override public int size() { return keys.length; }
            @Override public void forEachKey(KeyVisitor visitor) {
                for (byte[] key : keys) visitor.accept(key, 0, key.length);
            }
        };
    }

    private static DefaultCacheEngine newEngine(FfmSlabAllocator allocator, SegmentedItemTable table) {
        AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of(
                "pid", "1", "version", "coverage-determinism", "pointer_size", "64",
                "threads", "1", "limit_maxbytes", Long.toString(MEMORY_LIMIT)));
        DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, CLOCK, stats,
                new DefaultCacheEngine.Settings(MEMORY_LIMIT, ITEM_MAX, "noeviction", 3600));
        engine.start();
        return engine;
    }

    private static void storeFixture(DefaultCacheEngine engine) {
        assertEquals(StoreResult.STORED, engine.set(KEY_ONE, bytes("1"), 0, 0));
        assertEquals(StoreResult.STORED, engine.set(KEY_TWO, bytes("2"), 0, 0));
        assertEquals(StoreResult.STORED, engine.set(KEY_THREE, bytes("3"), 0, 0));
    }

    private static void assertContents(RetrievalSnapshot snapshot) {
        Iterator<Item> cursor = snapshot.values();
        for (int i = 0; i < EXPECTED_KEYS.size(); i++) {
            assertTrue(cursor.hasNext(), "Snapshot omitted a key");
            Item item = cursor.next();
            assertEquals(EXPECTED_KEYS.get(i), text(item.key()), "First-occurrence key order");
            ByteBuffer value = ByteBuffer.allocate(item.valueLength());
            assertEquals(1, item.readValue(0, value), "Exact value length");
            assertArrayEquals(bytes(Integer.toString(i + 1)), value.array(), "Exact stored value");
        }
        assertFalse(cursor.hasNext(), "Snapshot must not contain extra or duplicate keys");
    }

    /**
     * The metadata budget admits exactly two charge slots. With many threads
     * released together, callers may lose an optimistic CAS race. This is not a
     * universal schedule proof. Every admitted snapshot must preserve content
     * and order, and the budget must drain regardless of observed retry coverage.
     */
    @Test
    void empiricalSnapshotBudgetContentionKeepsOrderedValuesAndDrainsBudget() throws Exception {
        BoundedTestJvm.run(CoverageDeterminismAcceptanceTest.class, "runContention", 170);
    }

    static void runContention() throws Exception {
        assertEquals(2L, SLOTS, "This acceptance depends on a two-slot metadata budget");

        try (FfmSlabAllocator allocator = new FfmSlabAllocator(1 << 20, 4096, ITEM_MAX);
             SegmentedItemTable table = new SegmentedItemTable(4, 2, 1 << 20)) {
            DefaultCacheEngine engine = newEngine(allocator, table);
            ExecutorService pool = Executors.newFixedThreadPool(WORKERS, daemonFactory());
            CountDownLatch finished = new CountDownLatch(WORKERS);
            CyclicBarrier barrier = new CyclicBarrier(WORKERS);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicLong admitted = new AtomicLong();
            AtomicLong denied = new AtomicLong();
            AtomicLong attempts = new AtomicLong();
            KeySequence keys = fixedKeys(KEY_ONE, KEY_TWO, KEY_THREE);
            try {
                storeFixture(engine);
                for (int worker = 0; worker < WORKERS; worker++) {
                    pool.execute(() -> {
                        try {
                            for (int round = 0; round < ROUNDS; round++) {
                                barrier.await(30, TimeUnit.SECONDS);
                                for (int step = 0; step < BURST; step++) {
                                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                                    attempts.incrementAndGet();
                                    RetrievalSnapshot snapshot = null;
                                    try {
                                        snapshot = engine.snapshot(keys);
                                    } catch (CacheCapacityException saturated) {
                                        denied.incrementAndGet();
                                        continue;
                                    }
                                    try {
                                        assertContents(snapshot);
                                        admitted.incrementAndGet();
                                    } finally {
                                        snapshot.close();
                                    }
                                }
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                assertTrue(finished.await(150, TimeUnit.SECONDS), "Snapshot churn did not finish");
                assertNull(failure.get(), () -> "Concurrent snapshot failed: " + failure.get());
                assertEquals(attempts.get(), admitted.get() + denied.get(),
                        "Every attempt is either admitted or a typed denial");
                assertTrue(admitted.get() > 0, "The budget must admit some snapshots");
                assertTrue(denied.get() > 0, "The two-slot budget must deny some snapshots");
                System.out.println("EMPIRICAL_CONTENTION workers=" + WORKERS + " rounds=" + ROUNDS
                        + " burst=" + BURST + " attempts=" + attempts + " admitted=" + admitted
                        + " denied=" + denied + "; no assertion of a particular CAS schedule");
                assertEquals(0L, engine.snapshotMetadataUsedBytes(),
                        "Admitted snapshot metadata must fully drain after the churn");
                try (GetResult one = engine.get(KEY_ONE);
                     GetResult two = engine.get(KEY_TWO);
                     GetResult three = engine.get(KEY_THREE)) {
                    assertTrue(one.found() && two.found() && three.found(),
                            "Stored values survive the metadata churn");
                }
            } finally {
                BoundedTestJvm.shutdownOrHalt(pool);
                engine.stop();
            }
        }
    }

    /**
     * Touch snapshots take the same budget loop and publish replacements; a
     * single-threaded call must still return exactly the stored keys, advance no
     * value content and release every charge, so the retry path is never
     * satisfied by weakening the touch contract.
     */
    @Test
    void snapshotAndTouchCarriesTheSameBudgetAndContentContract() throws Exception {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(1 << 20, 4096, ITEM_MAX);
             SegmentedItemTable table = new SegmentedItemTable(4, 2, 1 << 20)) {
            DefaultCacheEngine engine = newEngine(allocator, table);
            try {
                storeFixture(engine);
                KeySequence keys = fixedKeys(KEY_ONE, KEY_TWO, KEY_ONE, KEY_THREE);
                try (RetrievalSnapshot snapshot = engine.snapshotAndTouch(keys, 60)) {
                    assertEquals(NOW, snapshot.capturedClock());
                    assertContents(snapshot);
                }
                assertEquals(0L, engine.snapshotMetadataUsedBytes(),
                        "A closed touch snapshot releases its metadata charge");
                try (GetResult touched = engine.get(KEY_ONE)) {
                    assertTrue(touched.found(), "The touched key remains present");
                    ByteBuffer value = ByteBuffer.allocate(touched.item().valueLength());
                    assertEquals(touched.item().valueLength(), touched.item().readValue(0, value));
                    assertEquals("1", text(value.array()), "Touch preserves the value content");
                }
            } finally {
                engine.stop();
            }
        }
    }
}
