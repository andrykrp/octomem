package ru.octomem.opt01acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.CacheCapacityException;
import ru.octomem.engine.CounterResult;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.GetResult;
import ru.octomem.engine.KeySequence;
import ru.octomem.engine.KeyVisitor;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.engine.StoreResult;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.SlabHandle;

/**
 * Independent OPT-01 acceptance for the "no per-command geometry traversal"
 * contract: the engine reads each class's chunk size exactly once, before
 * {@code start()}, and never again across a realistic command mix that includes
 * near-ceiling values, oversized denials and native reservation failures.
 *
 * <p>The allocation-freedom and lock-freedom parts of the original claim are not
 * observable through the public API (there is no public counter for heap
 * allocations or allocator-lock acquisitions performed by the private lookup),
 * so this class asserts only the observable proxy and explicitly does not claim
 * them.
 */
@Timeout(60)
class Opt01GeometryTraversalAcceptanceTest {

    @Test
    void constructionReadsEachClassOnceAndCommandMixNeverRereads() throws CacheCapacityException {
        try (FfmSlabAllocator nativeAllocator = new FfmSlabAllocator(1L << 20, 4096, 4096)) {
            RecordingAllocator allocator = new RecordingAllocator(nativeAllocator);
            int classCount = nativeAllocator.classCount();
            SegmentedItemTable table = new SegmentedItemTable(1, 2, 1L << 20);
            AtomicStatsRegistry stats = Opt01Fixtures.registry(1L << 20);
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, Opt01Fixtures.fixedClock(), stats,
                    new DefaultCacheEngine.Settings(1L << 20, 4096, "noeviction", 86_400));

            assertGeometryReadExactlyOnce(allocator, classCount, "before start()");
            int frozen = allocator.totalReads();
            assertEquals(classCount, frozen, "snapshot cost must be one read per class");

            engine.start();
            try {
                runCommandMix(engine, allocator);
            } finally {
                engine.stop();
            }

            assertEquals(frozen, allocator.totalReads(),
                    "no command may re-traverse allocator class geometry");
            assertGeometryReadExactlyOnce(allocator, classCount, "after command mix");
        }
    }

    private static void runCommandMix(DefaultCacheEngine engine, RecordingAllocator allocator)
            throws CacheCapacityException {
        // Small through near-ceiling successful stores (max block 4096, key length 1:
        // record bytes are (32 + 1 + value + 7) & ~7).
        assertEquals(StoreResult.STORED, engine.set(Opt01Fixtures.bytes("a"), Opt01Fixtures.bytes("v"), 0, 0));
        assertEquals(StoreResult.STORED, engine.set(Opt01Fixtures.bytes("b"), new byte[300], 0, 0));
        assertEquals(StoreResult.STORED, engine.set(Opt01Fixtures.bytes("c"), new byte[4000], 0, 0));
        assertEquals(StoreResult.STORED, engine.set(Opt01Fixtures.bytes("d"), new byte[4063], 0, 0));

        for (String key : new String[] {"a", "b", "c", "d"}) {
            try (GetResult result = engine.get(Opt01Fixtures.bytes(key))) {
                assertNotNull(result.item(), key);
            }
        }

        assertEquals(StoreResult.STORED, engine.set(Opt01Fixtures.bytes("num"), Opt01Fixtures.bytes("10"), 0, 0));
        assertEquals(CounterResult.Status.VALUE, engine.increment(Opt01Fixtures.bytes("num"), 5).status());
        assertEquals(CounterResult.Status.NON_NUMERIC,
                engine.increment(Opt01Fixtures.bytes("a"), 1).status());

        try (RetrievalSnapshot snapshot = engine.snapshot(keys("a", "b", "c"))) {
            assertNotNull(snapshot);
        }

        for (String key : new String[] {"a", "b", "c", "d", "num"}) {
            assertTrue(engine.delete(Opt01Fixtures.bytes(key)), key);
        }

        // Oversized request is refused before any class geometry is consulted.
        assertEquals(StoreResult.TOO_LARGE, engine.set(Opt01Fixtures.bytes("x"), new byte[4064], 0, 0));

        // Native reservation failure must not re-read geometry either.
        allocator.failAllocate = true;
        try {
            assertEquals(StoreResult.OUT_OF_MEMORY,
                    engine.set(Opt01Fixtures.bytes("fail"), Opt01Fixtures.bytes("v"), 0, 0));
        } finally {
            allocator.failAllocate = false;
        }
    }

    /** Observable boundary: the private lookup is not public API. */
    @Test
    void allocationAndLockFreedomAreNotClaimedBecauseNotPubliclyObservable() throws Exception {
        // The public MemoryAllocator surface exposes no heap-allocation counter and
        // no allocator-lock counter, and classFor is private; therefore per-lookup
        // allocation-freedom and lock-freedom cannot be observed without reflection
        // or instrumentation. We assert the observable fact instead: the command
        // mix above performs zero additional geometry reads.
        assertTrue(Modifier.isPrivate(DefaultCacheEngine.class
                .getDeclaredMethod("classFor", long.class).getModifiers()),
                "the lookup is an internal implementation detail, not public API");
    }

    private static void assertGeometryReadExactlyOnce(RecordingAllocator allocator, int classCount, String when) {
        assertEquals(classCount, allocator.totalReads(), "total geometry reads " + when);
        for (int i = 0; i < classCount; i++) {
            assertEquals(1, allocator.readsFor(i), "class " + i + " geometry read " + when);
        }
    }

    private static KeySequence keys(String... names) {
        byte[][] keyBytes = new byte[names.length][];
        for (int i = 0; i < names.length; i++) keyBytes[i] = Opt01Fixtures.bytes(names[i]);
        return new KeySequence() {
            @Override public int size() { return keyBytes.length; }
            @Override public void forEachKey(KeyVisitor visitor) {
                for (byte[] key : keyBytes) visitor.accept(key, 0, key.length);
            }
        };
    }

    /** Counts every allocator geometry read without altering the delegate's results. */
    private static final class RecordingAllocator implements MemoryAllocator {
        private final FfmSlabAllocator delegate;
        private final int[] reads;
        private int total;
        private volatile boolean failAllocate;

        RecordingAllocator(FfmSlabAllocator delegate) {
            this.delegate = delegate;
            this.reads = new int[delegate.classCount()];
        }

        int totalReads() { return total; }

        int readsFor(int classId) { return reads[classId]; }

        @Override public SlabHandle allocate(int size) {
            return failAllocate ? null : delegate.allocate(size);
        }

        @Override public void free(SlabHandle handle) { delegate.free(handle); }

        @Override public long trim() { return delegate.trim(); }

        @Override public long totalMemory() { return delegate.totalMemory(); }

        @Override public long usedMemory() { return delegate.usedMemory(); }

        @Override public int classCount() { return delegate.classCount(); }

        @Override public SlabClassStats classStats(int classId) {
            reads[classId]++;
            total++;
            return delegate.classStats(classId);
        }
    }
}
