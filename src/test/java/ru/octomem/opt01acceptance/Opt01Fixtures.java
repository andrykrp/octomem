package ru.octomem.opt01acceptance;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.util.Clock;

/**
 * Independent OPT-01 acceptance oracle and rig helpers.
 *
 * <p><b>Provenance.</b> Every expected class id is computed here from the
 * documented contract (classes start at 64, each next class rounds
 * {@code ceil(previous * 1.25)} up to eight bytes and is capped at the
 * eight-byte-rounded ceiling; a request is aligned with {@code (size+7)&~7} and
 * mapped to the lowest class whose reported chunk covers it, else {@code -1})
 * over geometry reported by the allocator's own public
 * {@link MemoryAllocator#classStats(int)}. No expected value is read from the
 * engine's private output, and no implementer test was copied.
 */
final class Opt01Fixtures {
    /** Documented default {@code item.maxSize} ceiling: 1 MiB. */
    static final int DEFAULT_ITEM_MAX = 1_048_576;
    /** Deterministic clock reading used by every rig. */
    static final long NOW = 1_700_000_000L;
    /** Record metadata header used by the storage contract, in bytes. */
    static final int RECORD_HEADER_BYTES = 32;

    private Opt01Fixtures() { }

    /** Aligns a request size exactly as the contract specifies. */
    static int align(int size) { return (size + 7) & ~7; }

    /** Aligns a request size exactly as the contract specifies, without narrowing. */
    static long align(long size) { return (size + 7) & ~7L; }

    /**
     * Rebuilds the documented growth geometry independently with unbounded
     * integer arithmetic. Index {@code i} is the chunk size of class {@code i}.
     */
    static int[] contractGeometry(int maxBlockSize) {
        BigInteger cap = BigInteger.valueOf((maxBlockSize + 7) & ~7);
        BigInteger value = BigInteger.valueOf(64);
        List<Integer> sizes = new ArrayList<>();
        sizes.add(value.intValueExact());
        while (value.compareTo(cap) < 0) {
            BigInteger grown = value.multiply(BigInteger.valueOf(5)).add(BigInteger.valueOf(3))
                    .divide(BigInteger.valueOf(4));
            BigInteger rounded = grown.add(BigInteger.valueOf(7)).divide(BigInteger.valueOf(8))
                    .multiply(BigInteger.valueOf(8));
            value = rounded.min(cap);
            sizes.add(value.intValueExact());
        }
        int[] result = new int[sizes.size()];
        for (int i = 0; i < result.length; i++) result[i] = sizes.get(i);
        return result;
    }

    /**
     * Independent lower-bound oracle over an ascending chunk-size array, or
     * {@code -1} when the aligned request exceeds every class.
     */
    static int lowerBound(int[] sizes, long requestSize) {
        long aligned = align(requestSize);
        int low = 0;
        int high = sizes.length - 1;
        int found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (sizes[middle] >= aligned) {
                found = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return found;
    }

    /** Reads the allocator's own reported geometry through its public API. */
    static int[] reportedGeometry(MemoryAllocator allocator) {
        int[] sizes = new int[allocator.classCount()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = allocator.classStats(i).chunkSize();
        return sizes;
    }

    /**
     * Invokes the engine's private {@code classFor(long)} lookup. There is no
     * public API that exposes class selection directly, so reflection is the
     * only way to exercise the pure lookup over arbitrary request sizes; the
     * expected result is still computed by {@link #lowerBound(int[], long)}.
     */
    static int classFor(DefaultCacheEngine engine, long size) {
        try {
            return (int) CLASS_FOR.invokeExact(engine, size);
        } catch (Throwable failure) {
            throw new AssertionError("classFor lookup failed for size " + size, failure);
        }
    }

    private static final MethodHandle CLASS_FOR = classForHandle();

    private static MethodHandle classForHandle() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    DefaultCacheEngine.class, MethodHandles.lookup());
            return lookup.findVirtual(DefaultCacheEngine.class, "classFor",
                    MethodType.methodType(int.class, long.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Creates an engine with the injectable construction seam, without starting it. */
    static DefaultCacheEngine engine(MemoryAllocator allocator, ru.octomem.storage.SegmentedItemTable table,
            long memoryLimit, int itemMaxSize, String policy) {
        return engine(allocator, table, registry(memoryLimit), memoryLimit, itemMaxSize, policy);
    }

    /** Creates an engine bound to a caller-supplied statistics registry, without starting it. */
    static DefaultCacheEngine engine(MemoryAllocator allocator, ru.octomem.storage.SegmentedItemTable table,
            ru.octomem.stats.StatsRegistry stats, long memoryLimit, int itemMaxSize, String policy) {
        return new DefaultCacheEngine(allocator, table, fixedClock(), stats,
                new DefaultCacheEngine.Settings(memoryLimit, itemMaxSize, policy, 86_400));
    }

    static AtomicStatsRegistry registry(long memoryLimit) {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", "opt-01-independent",
                "pointer_size", "64", "threads", "1", "limit_maxbytes", Long.toString(memoryLimit)));
    }

    static Clock fixedClock() { return new FixedClock(); }

    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    private static final class FixedClock implements Clock {
        @Override public long currentTimeSeconds() { return NOW; }
        @Override public long nanoTime() { return 0; }
    }
}
