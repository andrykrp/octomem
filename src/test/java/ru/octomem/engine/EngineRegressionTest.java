package ru.octomem.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.storage.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static ru.octomem.engine.EngineAcceptanceTest.*;

/** Independent reviewer regressions frozen before the corresponding production corrections. */
@Timeout(30)
class EngineRegressionTest {
    @Test
    void repeatedAllocationDenialStopsAfterBoundedRetriesAndProtectsTarget() {
        try(FfmSlabAllocator nativeAllocator=new FfmSlabAllocator(1<<20,4096,4096);
            SegmentedItemTable table=new SegmentedItemTable(1,2,1<<20)) {
            FailingAllocator allocator=new FailingAllocator(nativeAllocator);
            AtomicStatsRegistry stats=registry(1<<20);
            DefaultCacheEngine engine=new DefaultCacheEngine(allocator,table,new FakeClock(),stats,
                new DefaultCacheEngine.Settings(1<<20,4096,"lru",86400));
            engine.start();
            try {
                for(int i=0;i<1030;i++) assertEquals(StoreResult.STORED,engine.set(b("k"+i),b("v"),7,100));
                try(GetResult original=engine.get(b("k0"))) {
                    Version before=version(original.item());
                    int start=allocator.allocations;
                    allocator.failAt=start+1;
                    assertEquals(StoreResult.OUT_OF_MEMORY,engine.set(b("k0"),b("w"),1,200));
                    assertTrue(allocator.allocations-start<=1025,"Initial admission plus at most1024 retries");
                    assertTrue(stats.snapshot().counters().get(Counter.EVICTIONS)<=1024);
                    assertTrue(table.size()>1,"Bound prevents exhausting every unrelated eligible owner");
                    try(GetResult current=engine.get(b("k0"))) { assertFalse(current.found()); }
                    assertEquals(before,version(original.item()));
                }
            } finally { engine.stop(); }
        }
    }
    @Test
    void oversizedCounterGrowthFailsBeforeAllocationOrEviction() {
        try (FfmSlabAllocator nativeAllocator = new FfmSlabAllocator(65536,4096,4096);
             SegmentedItemTable table = new SegmentedItemTable(1,2,65536)) {
            FailingAllocator allocator = new FailingAllocator(nativeAllocator);
            AtomicStatsRegistry stats = registry(65536);
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator,table,new FakeClock(),stats,
                new DefaultCacheEngine.Settings(65536,40,"lru",86400));
            engine.start();
            try {
                assertEquals(StoreResult.STORED,engine.set(b("a"),b("9999999"),7,100));
                assertEquals(StoreResult.STORED,engine.set(b("b"),b("v"),3,0));
                try (GetResult old = engine.get(b("a"))) {
                    Version before = version(old.item());
                    int calls = allocator.allocations;
                    long used = allocator.usedMemory();
                    long service = engine.serviceMetadataUsedBytes();
                    assertEquals(CounterResult.Status.OUT_OF_MEMORY,engine.increment(b("a"),1).status());
                    assertEquals(calls,allocator.allocations);
                    assertEquals(used,allocator.usedMemory());
                    assertEquals(service,engine.serviceMetadataUsedBytes());
                    assertEquals(0L,stats.snapshot().counters().get(Counter.EVICTIONS));
                    try (GetResult current = engine.get(b("a")); GetResult unrelated = engine.get(b("b"))) {
                        assertEquals(before,version(current.item()));
                        assertEquals(before,version(old.item()));
                        assertTrue(unrelated.found());
                    }
                }
            } finally { engine.stop(); }
        }
    }

    @Test
    void emptyPageTrimAdmitsDifferentClassWithoutLiveEviction() {
        try (Fixture f = new Fixture(8192,1,4096,false)) {
            f.put("a",3000,0); f.put("b",3000,0);
            assertTrue(f.engine.delete(b("a")));
            assertEquals(StoreResult.STORED,f.engine.set(b("small"),b("x"),0,0));
            assertEquals(0L,f.count(Counter.EVICTIONS));
            assertTrue(f.found("b"));
        }
    }

    @Test
    void hugeLogicalBudgetDoesNotEagerlyAllocateNativeOrHeapCeiling() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(8192,4096,4096);
             SegmentedItemTable table = new SegmentedItemTable(1,2,65536)) {
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator,table,new FakeClock(),registry(Long.MAX_VALUE),
                new DefaultCacheEngine.Settings(Long.MAX_VALUE,4096,"noeviction",86400));
            engine.start();
            try {
                assertEquals(0,allocator.totalMemory());
                assertEquals(0,engine.serviceMetadataUsedBytes());
                assertEquals(StoreResult.STORED,engine.set(b("a"),b("v"),0,0));
                assertEquals(512,engine.serviceMetadataUsedBytes());
                assertTrue(allocator.totalMemory() <= 8192);
            } finally { engine.stop(); }
        }
    }

    static AtomicStatsRegistry registry(long memory) {
        return new AtomicStatsRegistry(Map.of("pid","1","version","fault-test","pointer_size","64","threads","1","limit_maxbytes",Long.toString(memory)));
    }

    @Test
    void approvedPostPinAndLeaseFaultsReleaseResourcesWithoutPublishing() throws Exception {
        for (boolean wrap : new boolean[]{true,false}) {
            try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536,4096,4096);
                 SegmentedItemTable table = new SegmentedItemTable(1,2,65536)) {
                RuntimeException injected = new IllegalStateException("approved boundary fault");
                boolean[] armed = {false};
                DefaultCacheEngine.EngineHooks hooks = new DefaultCacheEngine.EngineHooks() {
                    public void beforeResultWrap() { if (armed[0] && wrap) throw injected; }
                    public void beforeLeaseAcquire() { if (armed[0] && !wrap) throw injected; }
                };
                var scheduler = new CapturedScheduler();
                DefaultCacheEngine engine = new DefaultCacheEngine(allocator,table,new FakeClock(),registry(65536),
                    new DefaultCacheEngine.Settings(65536,4096,"noeviction",86400),0,hooks,() -> scheduler);
                engine.start();
                try {
                    assertEquals(StoreResult.STORED,engine.set(b("a"),b("original"),7,100));
                    Version before;
                    try (GetResult result = engine.get(b("a"))) { before=version(result.item()); }
                    long bytes = allocator.usedMemory();
                    armed[0]=true;
                    if (wrap) assertSame(injected,assertThrows(RuntimeException.class,() -> engine.get(b("a"))));
                    assertSame(injected,assertThrows(RuntimeException.class,() -> engine.touch(b("a"),999)));
                    if (!wrap) assertSame(injected,assertThrows(RuntimeException.class,() -> engine.snapshotAndTouch(keys("a"),999)));
                    armed[0]=false;
                    assertEquals(bytes,allocator.usedMemory());
                    assertEquals(512,engine.serviceMetadataUsedBytes());
                    assertEquals(0,engine.snapshotMetadataUsedBytes());
                    try (GetResult result=engine.get(b("a"))) { assertEquals(before,version(result.item())); }
                } finally { engine.stop(); }
                assertEquals(0,allocator.usedMemory());
            }
        }
    }

    @Test
    void capturedMaintenanceContainsClockFailureAndCannotRunAfterStop() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536,4096,4096);
             SegmentedItemTable table = new SegmentedItemTable(1,2,65536)) {
            boolean[] fail = {false};
            ru.octomem.util.Clock clock = new ru.octomem.util.Clock() {
                public long currentTimeSeconds() { if(fail[0]) throw new IllegalStateException("clock fault"); return NOW; }
                public long nanoTime() { return 0; }
            };
            CapturedScheduler scheduler=new CapturedScheduler();
            DefaultCacheEngine engine=new DefaultCacheEngine(allocator,table,clock,registry(65536),
                new DefaultCacheEngine.Settings(65536,4096,"noeviction",1),0,new DefaultCacheEngine.EngineHooks(){},() -> scheduler);
            engine.start();
            assertNotNull(scheduler.action);
            assertEquals(StoreResult.STORED,engine.set(b("a"),b("v"),0,1));
            fail[0]=true;
            assertDoesNotThrow(scheduler.action::run);
            fail[0]=false;
            try (GetResult result=engine.get(b("a"))) { assertTrue(result.found()); }
            engine.stop();
            assertTrue(scheduler.isShutdown());
            fail[0]=true;
            assertDoesNotThrow(scheduler.action::run);
            assertEquals(0,allocator.usedMemory());
        }
    }

    @Test
    void schedulerRejectionStopsAdmissionAndReleasesScheduler() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(65536,4096,4096);
             SegmentedItemTable table = new SegmentedItemTable(1,2,65536)) {
            CapturedScheduler scheduler=new CapturedScheduler();
            scheduler.reject=true;
            DefaultCacheEngine engine=new DefaultCacheEngine(allocator,table,new FakeClock(),registry(65536),
                new DefaultCacheEngine.Settings(65536,4096,"noeviction",1),0,new DefaultCacheEngine.EngineHooks(){},() -> scheduler);
            assertThrows(java.util.concurrent.RejectedExecutionException.class,engine::start);
            assertTrue(scheduler.isShutdown());
            assertTrue(table.isClosed(),"Rejected start must close table before caller cleanup");
            assertEquals(0,table.metadataUsedBytes());
            assertFalse(allocator.isClosed());
            SlabHandle block=allocator.allocate(64);
            assertNotNull(block);
            allocator.free(block);
            assertThrows(IllegalStateException.class,() -> engine.get(b("a")));
            engine.stop();
            engine.stop();
            assertThrows(IllegalStateException.class,engine::start);
        }
    }

    @Test
    void failedSchedulerSupplierClosesTableBeforeExceptionEscapes() {
        for(boolean returnsNull:new boolean[]{false,true}) {
            try(FfmSlabAllocator allocator=new FfmSlabAllocator(65536,4096,4096);
                SegmentedItemTable table=new SegmentedItemTable(1,2,65536)) {
                RuntimeException injected=new IllegalStateException("supplier failure");
                DefaultCacheEngine engine=new DefaultCacheEngine(allocator,table,new FakeClock(),registry(65536),
                    new DefaultCacheEngine.Settings(65536,4096,"noeviction",1),0,new DefaultCacheEngine.EngineHooks(){},
                    () -> { if(returnsNull) return null; throw injected; });
                if(returnsNull) assertThrows(NullPointerException.class,engine::start);
                else assertSame(injected,assertThrows(IllegalStateException.class,engine::start));
                assertTrue(table.isClosed(),"Supplier failure must clean table before stop/outer close");
                assertEquals(0,table.metadataUsedBytes());
                assertFalse(allocator.isClosed());
                SlabHandle block=allocator.allocate(64);
                assertNotNull(block);
                allocator.free(block);
                assertThrows(IllegalStateException.class,() -> engine.get(b("a")));
                engine.stop(); engine.stop();
                assertThrows(IllegalStateException.class,engine::start);
            }
        }
    }

    static final class CapturedScheduler extends java.util.concurrent.ScheduledThreadPoolExecutor {
        Runnable action;
        boolean reject;
        CapturedScheduler() { super(1); }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,long initial,long delay,java.util.concurrent.TimeUnit unit) {
            if(reject) throw new java.util.concurrent.RejectedExecutionException("injected schedule rejection");
            action=command;
            return new java.util.concurrent.ScheduledFuture<Object>() {
                public long getDelay(java.util.concurrent.TimeUnit u) { return 0; }
                public int compareTo(java.util.concurrent.Delayed other) { return 0; }
                public boolean cancel(boolean interrupt) { return true; }
                public boolean isCancelled() { return false; }
                public boolean isDone() { return false; }
                public Object get() { throw new UnsupportedOperationException(); }
                public Object get(long timeout,java.util.concurrent.TimeUnit u) { throw new UnsupportedOperationException(); }
            };
        }
    }
    @Test
    void crossClassInsertionReclaimsPhysicalPage() {
        try (Fixture f = new Fixture(8192, 1, 4096, false)) {
            f.put("a", 3000, 0);
            f.put("b", 3000, 0);
            assertEquals(StoreResult.STORED, f.engine.set(b("small"), b("s"), 0, 0));
            assertEquals(1L, f.count(Counter.EVICTIONS));
            assertEquals(2, f.table.size());
            assertTrue(f.found("small"));
        }
    }

    @Test
    void oneChunkPressureEvictsExactlyOneAcrossEightSegments() {
        try (Fixture f = new Fixture(32768, 8, 4096, false)) {
            List<String> names = new ArrayList<>();
            for (int segment = 0; segment < 8; segment++) {
                for (int i = 0; ; i++) {
                    String candidate = "s" + segment + "k" + i;
                    byte[] key = b(candidate);
                    if (f.table.segmentId(key, 0, key.length) == segment) {
                        names.add(candidate);
                        f.put(candidate, 3000, 0);
                        break;
                    }
                }
            }
            assertEquals(StoreResult.STORED, f.engine.set(b("ninth"), new byte[3000], 0, 0));
            assertEquals(1L, f.count(Counter.EVICTIONS));
            int survivors = 0;
            for (String name : names) if (f.found(name)) survivors++;
            assertEquals(7, survivors);
            assertEquals(8, f.table.size());
        }
    }

    @Test
    void expiredReclamationDoesNotAlsoEvictLiveRecord() {
        try (Fixture f = new Fixture(8192, 1, 4096, false)) {
            f.put("live", 3000, 0);
            f.put("expired", 3000, 1);
            f.clock.now++;
            assertEquals(StoreResult.STORED, f.engine.set(b("new"), new byte[3000], 0, 0));
            assertEquals(0L, f.count(Counter.EVICTIONS));
            assertEquals(1L, f.count(Counter.RECLAIMED));
            assertTrue(f.found("live"));
            assertTrue(f.found("new"));
            assertFalse(f.found("expired"));
        }
    }

    @Test
    void trueClassLruFindsSixtyFifthRecordBeyondFormerSample() {
        // One page has exactly 65 terminal-class chunks, each 4096 bytes.
        try (Fixture f = new Fixture(65L * 4096, 1, 65 * 4096, false)) {
            for (int i = 0; i < 65; i++) f.put("k" + i, 4000, 0);
            for (int i = 0; i < 64; i++) assertTrue(f.found("k" + i));
            assertEquals(StoreResult.STORED, f.engine.set(b("new"), new byte[4000], 0, 0));
            assertFalse(f.found("k64"));
            for (int i = 0; i < 64; i++) assertTrue(f.found("k" + i), "recent k" + i);
            assertEquals(1L, f.count(Counter.EVICTIONS));
        }
    }

    @Test
    void negativeAndZeroClockDeadlinesRemainDistinctFromImmortality() {
        try (Fixture f = new Fixture(65536, 1, 4096, false)) {
            f.clock.now = 0;
            assertEquals(StoreResult.STORED, f.engine.set(b("immediate"), b("v"), 0, -1));
            assertFalse(f.found("immediate"));
            f.clock.now = -100;
            assertEquals(StoreResult.STORED, f.engine.set(b("negative"), b("v"), 0, 1));
            try (GetResult result = f.engine.get(b("negative"))) {
                assertTrue(result.found());
                assertTrue(result.item().hasExpiry());
                assertEquals(-99, result.item().expiryTime());
                assertFalse(result.item().isExpired(-100));
                assertTrue(result.item().isExpired(-99));
            }
            f.clock.now = -99;
            assertFalse(f.found("negative"));
            f.clock.now = -1;
            assertEquals(StoreResult.STORED, f.engine.set(b("zero"), b("v"), 0, 1));
            assertEquals(StoreResult.STORED, f.engine.set(b("eternal"), b("v"), 0, 0));
            try (GetResult zero = f.engine.get(b("zero")); GetResult eternal = f.engine.get(b("eternal"))) {
                assertTrue(zero.found());
                assertEquals(0, zero.item().expiryTime());
                assertTrue(zero.item().hasExpiry());
                assertFalse(eternal.item().hasExpiry());
                f.clock.now = 0;
                assertTrue(zero.item().isExpired(0));
                assertFalse(eternal.item().isExpired(0));
                assertFalse(f.found("zero"));
                assertTrue(f.found("eternal"));
            }
        }
    }

    @Test
    void genuineReadFailureAtEveryBatchPositionRollsBackAllPreparedVersions() throws Exception {
        for (int failedPosition = 1; failedPosition <= 3; failedPosition++) {
            try (Fixture f = new Fixture(65536, 4, 4096, true)) {
                f.put("a", 100, 100);
                f.put("b", 100, 200);
                f.put("c", 100, 300);
                Map<String, Version> before = Map.of("a", f.version("a"), "b", f.version("b"), "c", f.version("c"));
                long nativeBefore = f.allocator.usedMemory();
                f.faults.arm(failedPosition);
                assertSame(f.faults.failure, assertThrows(IllegalStateException.class,
                    () -> f.engine.snapshotAndTouch(keys("a", "b", "c"), 500)));
                f.faults.disarm();
                assertEquals(nativeBefore, f.allocator.usedMemory());
                assertEquals(0, f.engine.snapshotMetadataUsedBytes());
                for (var entry : before.entrySet()) assertEquals(entry.getValue(), f.version(entry.getKey()));
            }
        }
    }

    @Test
    void genuineReadFailureDuringSingleReplacementPreservesOldPinAndValue() {
        for (int operation = 0; operation < 4; operation++) {
            try (Fixture f = new Fixture(65536, 1, 4096, true)) {
                f.put("a", 100, 100);
                Version before = f.version("a");
                long used = f.allocator.usedMemory();
                try (GetResult pin = f.engine.get(b("a"))) {
                    f.faults.arm(1);
                    int selected = operation;
                    assertSame(f.faults.failure, assertThrows(IllegalStateException.class, () -> {
                        switch (selected) {
                            case 0 -> f.engine.append(b("a"), b("suffix"));
                            case 1 -> f.engine.prepend(b("a"), b("prefix"));
                            case 2 -> f.engine.touch(b("a"), 500);
                            case 3 -> f.engine.getAndTouch(b("a"), 500);
                            default -> fail();
                        }
                    }));
                    f.faults.disarm();
                    assertEquals(before, version(pin.item()));
                    assertEquals(before, f.version("a"));
                    assertEquals(used, f.allocator.usedMemory());
                }
            }
        }
    }

    @Test
    void serviceMetadataChargesRetiredOwnerUntilLastPinAndRollsBackFailures() {
        try (Fixture f = new Fixture(65536, 1, 4096, true)) {
            assertEquals(0, f.engine.serviceMetadataUsedBytes());
            f.put("a", 100, 100);
            assertEquals(512, f.engine.serviceMetadataUsedBytes());
            try (GetResult pin = f.engine.get(b("a"))) {
                f.faults.arm(1);
                assertThrows(IllegalStateException.class, () -> f.engine.touch(b("a"), 200));
                f.faults.disarm();
                assertEquals(512, f.engine.serviceMetadataUsedBytes());
                assertEquals(StoreResult.STORED, f.engine.set(b("a"), b("new"), 0, 0));
                assertEquals(1024, f.engine.serviceMetadataUsedBytes());
                assertTrue(f.engine.delete(b("a")));
                assertEquals(512, f.engine.serviceMetadataUsedBytes());
                assertEquals(100, pin.item().valueLength());
            }
            assertEquals(0, f.engine.serviceMetadataUsedBytes());
        }
    }

    /** Fault only actual value reads; matching keys and header reads stay intact. */
    static final class ReadFaultAllocator implements MemoryAllocator {
        final FfmSlabAllocator delegate;
        final Map<SlabHandle, SlabHandle> owned = new java.util.IdentityHashMap<>();
        final IllegalStateException failure = new IllegalStateException("injected native value read failure");
        int failAt = Integer.MAX_VALUE;
        int reads;
        ReadFaultAllocator(FfmSlabAllocator delegate) { this.delegate = delegate; }
        void arm(int position) { reads = 0; failAt = position; }
        void disarm() { failAt = Integer.MAX_VALUE; }
        public SlabHandle allocate(int size) {
            SlabHandle raw = delegate.allocate(size);
            if (raw == null) return null;
            SlabHandle wrapped = new SlabHandle() {
                int keyLength;
                public int classId() { return raw.classId(); }
                public int capacity() { return raw.capacity(); }
                public int read(int offset, ByteBuffer dst) {
                    if (offset >= 32 + keyLength && dst.hasRemaining() && ++reads == failAt) throw failure;
                    return raw.read(offset, dst);
                }
                public int write(int offset, ByteBuffer src) {
                    if (offset == 0 && src.remaining() >= 8) keyLength = src.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(src.position() + 4);
                    return raw.write(offset, src);
                }
            };
            owned.put(wrapped, raw);
            return wrapped;
        }
        public void free(SlabHandle handle) { delegate.free(owned.remove(handle)); }
        public long totalMemory() { return delegate.totalMemory(); }
        public long usedMemory() { return delegate.usedMemory(); }
        public int classCount() { return delegate.classCount(); }
        public SlabClassStats classStats(int id) { return delegate.classStats(id); }
    }

    static final class Fixture implements AutoCloseable {
        final FakeClock clock = new FakeClock();
        final FfmSlabAllocator allocator;
        final ReadFaultAllocator faults;
        final SegmentedItemTable table;
        final AtomicStatsRegistry stats;
        final DefaultCacheEngine engine;
        Fixture(long memory, int segments, int pageSize, boolean faulting) {
            allocator = new FfmSlabAllocator(memory, pageSize, 4096);
            faults = new ReadFaultAllocator(allocator);
            table = new SegmentedItemTable(segments, 2, 1 << 20);
            stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "regression", "pointer_size", "64", "threads", "1", "limit_maxbytes", Long.toString(memory)));
            engine = new DefaultCacheEngine(faulting ? faults : allocator, table, clock, stats,
                new DefaultCacheEngine.Settings(memory, 4096, "lru", 86400));
            engine.start();
        }
        void put(String key, int length, long ttl) { assertEquals(StoreResult.STORED, engine.set(b(key), new byte[length], 7, ttl)); }
        boolean found(String key) { try (GetResult result = engine.get(b(key))) { return result.found(); } }
        Version version(String key) { try (GetResult result = engine.getWithCas(b(key))) { assertTrue(result.found()); return EngineAcceptanceTest.version(result.item()); } }
        long count(Counter counter) { return stats.snapshot().counters().get(counter); }
        public void close() { faults.disarm(); engine.stop(); assertEquals(0, allocator.usedMemory()); allocator.close(); }
    }
}
