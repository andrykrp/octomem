package ru.octomem.statsacceptance;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import ru.octomem.engine.CacheEngine;
import ru.octomem.engine.CounterResult;
import ru.octomem.engine.GetResult;
import ru.octomem.engine.KeySequence;
import ru.octomem.engine.KeyVisitor;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.engine.StoreResult;
import ru.octomem.stats.Counter;
import ru.octomem.stats.Gauge;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.stats.Snapshot;
import ru.octomem.stats.StatsRegistry;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SlabHandle;
import ru.octomem.util.Clock;

/**
 * Independent bounded stand-ins for the storage/statistics seams used by the
 * T-013 acceptance suite. These are test-owned doubles: they never derive an
 * expectation from production output and exist only to drive documented engine
 * and protocol behavior under the frozen oracle.
 */
final class CoverageStubs {
    private CoverageStubs() { }

    /** Test-owned mutable clock with explicit forward/backward control. */
    static final class MutableClock implements Clock {
        volatile long seconds = 1_700_000_000L;
        @Override public long currentTimeSeconds() { return seconds; }
        @Override public long nanoTime() { return seconds * 1_000_000_000L; }
        void set(long value) { seconds = value; }
        void advance(long delta) { seconds += delta; }
    }

    /** Minimal plain statistics registry that is deliberately not a ClassStatsRegistry. */
    static final class PlainStats implements StatsRegistry {
        final Map<Counter, Long> counters = new EnumMap<>(Counter.class);
        final Map<Gauge, Long> gauges = new EnumMap<>(Gauge.class);
        @Override public void increment(Counter counter, long delta) { counters.merge(counter, delta, Long::sum); }
        @Override public void setGauge(Gauge gauge, long value) { gauges.put(gauge, value); }
        @Override public void reset() { counters.clear(); }
        @Override public Snapshot snapshot() {
            return new Snapshot() {
                @Override public Map<Counter, Long> counters() { return Map.copyOf(counters); }
                @Override public Map<Gauge, Long> gauges() { return Map.copyOf(gauges); }
                @Override public Map<String, String> constants() { return Map.of(); }
            };
        }
    }

    /** Test-owned block backed by a plain byte array. */
    static final class StubHandle implements SlabHandle {
        final int classId;
        final int capacity;
        final byte[] data;
        int failWriteCall = -1;
        int writeCalls;
        StubHandle(int classId, int capacity, int request) {
            this.classId = classId;
            this.capacity = capacity;
            this.data = new byte[Math.max(capacity, request)];
        }
        @Override public int classId() { return classId; }
        @Override public int capacity() { return capacity; }
        @Override public int read(int offset, ByteBuffer dst) {
            int count = Math.min(dst.remaining(), capacity - offset);
            if (count < 0) { count = 0; }
            dst.put(data, offset, count);
            return count;
        }
        @Override public int write(int offset, ByteBuffer src) {
            writeCalls++;
            if (writeCalls == failWriteCall) { return 0; }
            int count = Math.min(src.remaining(), capacity - offset);
            if (count < 0) { count = 0; }
            src.get(data, offset, count);
            return count;
        }
    }

    /** Test-owned allocator with configurable native capacity and page reporting. */
    static final class StubAllocator implements MemoryAllocator, AutoCloseable {
        final int[] sizes;
        final Map<Integer, Long> pages = new HashMap<>();
        int denyAfter = Integer.MAX_VALUE;
        boolean denyAll;
        boolean denyNext;
        boolean trimPositive;
        StubHandle lastHandle;
        int allocations;
        long freed;
        StubAllocator(int... sizes) { this.sizes = sizes; }
        @Override public SlabHandle allocate(int size) {
            allocations++;
            if (denyAll || denyNext) { denyNext = false; return null; }
            if (allocations > denyAfter) { return null; }
            int id = 0;
            while (id < sizes.length && sizes[id] < size) { id++; }
            if (id >= sizes.length) { return null; }
            lastHandle = new StubHandle(id, sizes[id], size);
            return lastHandle;
        }
        @Override public void free(SlabHandle handle) {
            if (handle == null) { throw new NullPointerException("handle"); }
            freed++;
        }
        @Override public long totalMemory() { return 0; }
        @Override public long usedMemory() { return 0; }
        @Override public int classCount() { return sizes.length; }
        @Override public SlabClassStats classStats(int classId) {
            return new StubClassStats(classId, sizes[classId], pages.getOrDefault(classId, 0L));
        }
        @Override public long trim() { return trimPositive ? 1 : 0; }
        @Override public void close() { }
        void page(int classId, long totalPages) { pages.put(classId, totalPages); }
    }

    /** Immutable slab statistics double. */
    static final class StubClassStats implements SlabClassStats {
        private final int id;
        private final int chunk;
        private final long pageCount;
        StubClassStats(int id, int chunk, long pageCount) {
            this.id = id; this.chunk = chunk; this.pageCount = pageCount;
        }
        @Override public int classId() { return id; }
        @Override public int chunkSize() { return chunk; }
        @Override public long totalPages() { return pageCount; }
        @Override public long totalChunks() { return pageCount * 4; }
        @Override public long usedChunks() { return pageCount; }
        @Override public long freeChunks() { return totalChunks() - usedChunks(); }
    }

    /** Borrowed multi-key sequence over explicit byte slices. */
    static final class Slices implements KeySequence {
        private final byte[][] keys;
        Slices(byte[]... keys) { this.keys = keys; }
        @Override public int size() { return keys.length; }
        @Override public void forEachKey(KeyVisitor visitor) {
            for (byte[] key : keys) { visitor.accept(key, 0, key.length); }
        }
    }

    /** Configurable CacheEngine double for direct dispatcher coverage. */
    static class StubEngine implements CacheEngine {
        StoreResult store = StoreResult.STORED;
        boolean deleteResult = true;
        boolean touchResult = true;
        CounterResult counter = new CounterResult() {
            @Override public Status status() { return Status.VALUE; }
            @Override public long value() { return 1; }
        };
        RetrievalSnapshot snapshotResult;
        @Override public RetrievalSnapshot snapshot(KeySequence keys) { return snapshotResult; }
        @Override public RetrievalSnapshot snapshotAndTouch(KeySequence keys, long exptime) { return snapshotResult; }
        @Override public GetResult get(byte[] key) { return null; }
        @Override public GetResult getWithCas(byte[] key) { return null; }
        @Override public GetResult getAndTouch(byte[] key, long exptime) { return null; }
        @Override public StoreResult set(byte[] key, byte[] value, int flags, long exptime) { return store; }
        @Override public StoreResult add(byte[] key, byte[] value, int flags, long exptime) { return store; }
        @Override public StoreResult replace(byte[] key, byte[] value, int flags, long exptime) { return store; }
        @Override public StoreResult append(byte[] key, byte[] value) { return store; }
        @Override public StoreResult prepend(byte[] key, byte[] value) { return store; }
        @Override public StoreResult cas(byte[] key, byte[] value, int flags, long exptime, long casId) { return store; }
        @Override public boolean delete(byte[] key) { return deleteResult; }
        @Override public CounterResult increment(byte[] key, long delta) { return counter; }
        @Override public CounterResult decrement(byte[] key, long delta) { return counter; }
        @Override public boolean touch(byte[] key, long exptime) { return touchResult; }
        @Override public void flushAll(long delaySeconds) { }
        @Override public void start() { }
        @Override public void stop() { }
    }
}
