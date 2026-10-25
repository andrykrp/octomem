package ru.octomem.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.SlabHandle;
import ru.octomem.util.Clock;

/**
 * Implementer unit tests for OPT-01: the engine size-class lookup is a pure
 * lower-bound over an immutable construction-time snapshot of the allocator's
 * class geometry. These are not the independent acceptance tests.
 */
@Timeout(30)
class Opt01ClassLookupTest {
    private static final int[] GEOMETRY_1024 =
            {64, 80, 104, 136, 176, 224, 280, 352, 440, 552, 696, 872, 1024};

    @Test
    void classLookupCoversEveryBoundaryAndTheMaximum() throws Exception {
        try (FfmSlabAllocator nativeAllocator = new FfmSlabAllocator(8192, 257, 1024);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 1 << 20)) {
            assertGeometry(nativeAllocator, GEOMETRY_1024);
            DefaultCacheEngine engine = engine(nativeAllocator, table, 1024);
            for (int i = 0; i < GEOMETRY_1024.length; i++) {
                int size = GEOMETRY_1024[i];
                assertEquals(i, classFor(engine, size), "exact class " + i);
                assertEquals(i, classFor(engine, size - 1L), "lower boundary of class " + i);
                int upper = i + 1 < GEOMETRY_1024.length ? i + 1 : -1;
                assertEquals(upper, classFor(engine, size + 1L), "upper boundary of class " + i);
            }
            for (long size = 1; size <= GEOMETRY_1024[GEOMETRY_1024.length - 1] + 2; size++) {
                assertEquals(oracle(GEOMETRY_1024, size), classFor(engine, size), "dense size " + size);
            }
            assertEquals(GEOMETRY_1024.length - 1, classFor(engine, GEOMETRY_1024[GEOMETRY_1024.length - 1]));
            assertEquals(-1, classFor(engine, GEOMETRY_1024[GEOMETRY_1024.length - 1] + 1L));
            assertEquals(-1, classFor(engine, Integer.MAX_VALUE));
            assertEquals(-1, classFor(engine, Integer.MAX_VALUE * 4L));
        }
    }

    @Test
    void overCeilingResultMatchesTheAlignedTerminalClass() throws Exception {
        try (FfmSlabAllocator nativeAllocator = new FfmSlabAllocator(8192, 257, 1001);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 1 << 20)) {
            int last = nativeAllocator.classCount() - 1;
            assertEquals(1008, nativeAllocator.classStats(last).chunkSize(),
                    "unaligned ceiling rounds up to the terminal class");
            DefaultCacheEngine engine = engine(nativeAllocator, table, 1001);
            assertEquals(last, classFor(engine, 1001), "request at the unaligned ceiling");
            assertEquals(last, classFor(engine, 1002), "request above maxBlockSize but below the rounded terminal class");
            assertEquals(last, classFor(engine, 1008), "rounded terminal class");
            assertEquals(-1, classFor(engine, 1009), "first size beyond the rounded terminal class");
            assertEquals(-1, classFor(engine, Integer.MAX_VALUE));
            assertEquals(-1, classFor(engine, 1L + Integer.MAX_VALUE));
        }
    }

    @Test
    void perCommandPathNeverTraversesAllocatorClassStatistics() throws Exception {
        try (FfmSlabAllocator nativeAllocator = new FfmSlabAllocator(1 << 20, 4096, 4096)) {
            CountingAllocator allocator = new CountingAllocator(nativeAllocator);
            SegmentedItemTable table = new SegmentedItemTable(1, 2, 1 << 20);
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, new FixedClock(), registry(1 << 20),
                    new DefaultCacheEngine.Settings(1 << 20, 4096, "lru", 86400));
            engine.start();
            try {
                assertEquals(nativeAllocator.classCount(), allocator.classStatsCalls,
                        "geometry snapshot reads every class exactly once at construction");
                int captured = allocator.classStatsCalls;
                String[] values = {"v", "123", "x".repeat(64), "y".repeat(300), "z".repeat(2000), "w".repeat(4062)};
                for (int i = 0; i < values.length; i++) {
                    assertEquals(StoreResult.STORED, engine.set(b("k" + i), b(values[i]), i, 0));
                }
                for (int i = 0; i < values.length; i++) {
                    try (GetResult result = engine.get(b("k" + i))) {
                        assertNotNull(result.item());
                    }
                }
                assertEquals(CounterResult.Status.VALUE, engine.increment(b("k1"), 1).status());
                assertEquals(CounterResult.Status.NON_NUMERIC, engine.increment(b("k0"), 1).status());
                try (RetrievalSnapshot snapshot = engine.snapshot(keys("k0", "k1", "k2"))) {
                    assertNotNull(snapshot);
                }
                for (int i = 0; i < values.length; i++) {
                    engine.delete(b("k" + i));
                }
                assertEquals(captured, allocator.classStatsCalls,
                        "no per-command or per-connection class statistics traversal");
            } finally {
                engine.stop();
            }
        }
    }

    private static DefaultCacheEngine engine(FfmSlabAllocator allocator, SegmentedItemTable table, int itemMax) {
        DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, new FixedClock(), registry(8192),
                new DefaultCacheEngine.Settings(8192, itemMax, "noeviction", 86400));
        engine.start();
        return engine;
    }

    private static void assertGeometry(FfmSlabAllocator allocator, int[] expected) {
        assertEquals(expected.length, allocator.classCount());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], allocator.classStats(i).chunkSize(), "class " + i);
            assertEquals(i, allocator.classStats(i).classId(), "class id " + i);
        }
    }

    private static int oracle(int[] sizes, long size) {
        long aligned = (size + 7) & ~7L;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] >= aligned) return i;
        }
        return -1;
    }

    private static int classFor(DefaultCacheEngine engine, long size) throws Exception {
        return (Integer) CLASS_FOR.invoke(engine, size);
    }

    private static final Method CLASS_FOR = classForLookup();

    private static Method classForLookup() {
        try {
            Method method = DefaultCacheEngine.class.getDeclaredMethod("classFor", long.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static byte[] b(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static AtomicStatsRegistry registry(long memory) {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", "opt-01", "pointer_size", "64",
                "threads", "1", "limit_maxbytes", Long.toString(memory)));
    }

    private static KeySequence keys(String... names) {
        byte[][] keys = new byte[names.length][];
        for (int i = 0; i < names.length; i++) keys[i] = b(names[i]);
        return new KeySequence() {
            public int size() { return keys.length; }
            public void forEachKey(KeyVisitor visitor) {
                for (byte[] key : keys) visitor.accept(key, 0, key.length);
            }
        };
    }

    private static final class FixedClock implements Clock {
        public long currentTimeSeconds() { return 1_700_000_000L; }
        public long nanoTime() { return 0; }
    }

    /** Counts every allocator class-statistics read without altering its results. */
    private static final class CountingAllocator implements MemoryAllocator {
        private final FfmSlabAllocator delegate;
        int classStatsCalls;

        CountingAllocator(FfmSlabAllocator delegate) { this.delegate = delegate; }

        public SlabHandle allocate(int size) { return delegate.allocate(size); }
        public void free(SlabHandle handle) { delegate.free(handle); }
        public long totalMemory() { return delegate.totalMemory(); }
        public long usedMemory() { return delegate.usedMemory(); }
        public int classCount() { return delegate.classCount(); }
        public SlabClassStats classStats(int classId) { classStatsCalls++; return delegate.classStats(classId); }
    }
}
