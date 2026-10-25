package ru.octomem.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import ru.octomem.expiry.ExpiryPolicy;
import ru.octomem.stats.Counter;
import ru.octomem.stats.Gauge;
import ru.octomem.stats.StatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry.Removal;
import ru.octomem.stats.SafeLogger;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.storage.ItemLease;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.StoredItem;
import ru.octomem.util.Clock;

/**
 * Segment-transaction cache engine with immutable versions and admitted command
 * snapshots. The supplied empty table is owned and the allocator exclusively
 * borrowed: callers must not mutate either until stop. Stop closes the table, not the
 * allocator; existing result owners must close their leases before its owner
 * closes the allocator. No command performs network I/O or waits for readers.
 *
 * <p>Eviction inspects at most 1024 records per segment/class in exact LRU
 * order on each bounded preflight, skipping pinned and command-target records.
 * Each reclaimed victim is followed by a fresh admission attempt, with at most
 * 1024 attempts per command. Capacity retries never evict target keys. Flush briefly
 * acquires all segments; normal commands acquire only their selected segments.
 * @since 1.0
 */
public final class DefaultCacheEngine implements CacheEngine, StorageAdmission {
    /** Trusted package-local failure seams; production callbacks do nothing. */
    interface EngineHooks {
        default void beforeResultWrap() { }
        default void beforeLeaseAcquire() { }
        default void beforeArithmeticRetry() { }
    }
    private static final EngineHooks NO_HOOKS = new EngineHooks() { };
    private final EngineHooks hooks;
    private final Supplier<ScheduledExecutorService> schedulerFactory;

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "octomem-expiry");
            thread.setDaemon(true);
            return thread;
        });
    }
    /** Internal settings, independent of the public configuration package. */
    public record Settings(long memoryLimit, int itemMaxSize, String evictionPolicy,
                           long reclaimIntervalSeconds) {
        /** Validates existing public modes without introducing configuration keys. */
        public Settings {
            Objects.requireNonNull(evictionPolicy, "evictionPolicy");
            if (memoryLimit <= 0 || itemMaxSize <= 0 || itemMaxSize > memoryLimit || reclaimIntervalSeconds <= 0
                    || reclaimIntervalSeconds > Long.MAX_VALUE / 1_000_000_000L
                    || !(evictionPolicy.equals("lru") || evictionPolicy.equals("noeviction"))) {
                throw new IllegalArgumentException("Invalid engine settings");
            }
        }
    }

    private final AtomicLong cas;
    private static final int RETRIES = 1024;
    private final MemoryAllocator allocator;
    private final SegmentedItemTable table;
    private final Clock clock;
    private final StatsRegistry stats;
    private final ClassStatsRegistry classStats;
    private final Settings settings;
    private final int[] classSizes;
    private final long snapshotLimit;
    private final AtomicLong snapshotUsed = new AtomicLong();
    private final StoredItem.ServiceBudget serviceBudget;
    private final AtomicLong activity = new AtomicLong();
    private final AtomicLong items = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger maintenanceSegment = new AtomicInteger();
    private volatile int state;
    private ScheduledExecutorService sweeper;
    // Changed only while all segments are held; any segment lock publishes them.
    private long generation = 1;
    private long delayedFlush;
    private boolean delayed;
    private long completedFlush;
    private boolean completed;

    /**
     * Creates a stopped engine, taking exclusive command ownership of an empty
     * table but not allocator lifecycle ownership.
     * After all arguments are non-null and the table is verified open and empty,
     * ownership transfers: any subsequent initialization failure closes the table
     * and releases its logical metadata, while leaving the allocator open.
     * Rejection before that boundary never closes a caller-owned table. Callers
     * must exclude concurrent table mutation throughout construction. Allocator
     * geometry is admitted before snapshot allocation and validated before queue
     * configuration; invalid geometry fails closed, releasing that reservation.
     * @param allocator externally owned record allocator
     * @param table empty, open index
     * @param clock deterministic or system clock
     * @param stats shared registry
     * @param settings validated independent engine settings
     */
    public DefaultCacheEngine(MemoryAllocator allocator, SegmentedItemTable table,
            Clock clock, StatsRegistry stats, Settings settings) {
        this(allocator,table,clock,stats,settings,0);
    }

    /** Deterministic unsigned-wrap seam for independently authored tests. */
    DefaultCacheEngine(MemoryAllocator allocator, SegmentedItemTable table,
            Clock clock, StatsRegistry stats, Settings settings,long initialCas) {
        this(allocator,table,clock,stats,settings,initialCas,NO_HOOKS,DefaultCacheEngine::newScheduler);
    }

    /** Trusted independent-test injection without public configuration changes. */
    DefaultCacheEngine(MemoryAllocator allocator, SegmentedItemTable table,
            Clock clock, StatsRegistry stats, Settings settings,long initialCas,
            EngineHooks hooks,Supplier<ScheduledExecutorService> schedulerFactory) {
        this.hooks = Objects.requireNonNull(hooks,"hooks");
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory,"schedulerFactory");
        cas = new AtomicLong(initialCas);
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.table = Objects.requireNonNull(table, "table");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.classStats = stats instanceof ClassStatsRegistry capability ? capability : null;
        this.settings = Objects.requireNonNull(settings, "settings");
        if (table.isClosed() || table.size() != 0) throw new IllegalArgumentException("Table must be empty and open");
        // Ownership transfers here; no fallible owned initialization escapes cleanup.
        try {
            int classes = allocator.classCount();
            if (classes <= 0) throw new IllegalArgumentException("Invalid allocator class count");
            if (!table.reserveClassGeometry(classes)) throw new IllegalArgumentException("Insufficient table metadata for class geometry");
            this.classSizes = new int[classes];
            validateClassSizes(allocator, classes, classSizes);
            if (!table.initializeClassQueues(classes)) throw new IllegalArgumentException("Insufficient table metadata for class queues");
            snapshotLimit = Math.min(settings.memoryLimit / 16, 16L * 1024 * 1024);
            if (classStats != null) classStats.initializeClasses(classes, table::classState, snapshotUsed, snapshotLimit);
            long versions = settings.memoryLimit/64;
            serviceBudget = new StoredItem.ServiceBudget(versions > Long.MAX_VALUE/512 ? Long.MAX_VALUE : versions*512);
        } catch (RuntimeException | Error failure) {
            try { table.close(); }
            catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override public void start() {
        if (state == 1) return;
        if (state != 0) throw new IllegalStateException("Engine cannot restart");
        try {
            sweeper = Objects.requireNonNull(schedulerFactory.get(),"scheduler");
            state = 1;
            sweeper.scheduleWithFixedDelay(() -> {
                try { reclaimExpired(256); }
                catch (RuntimeException failure) {
                    if (state == 1 && classStats != null) classStats.logger().log(SafeLogger.Event.EXPIRY_FAILURE);
                }
            }, settings.reclaimIntervalSeconds, settings.reclaimIntervalSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException | Error failure) {
            state = 2;
            try {
                if (sweeper != null) sweeper.shutdownNow();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            try {
                table.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override public void stop() {
        if (state == 2) return;
        state = 2;
        if (sweeper != null) sweeper.shutdownNow();
        table.close();
        items.set(0); bytes.set(0); gauges();
    }

    private void requireStarted() { if (state != 1) throw new IllegalStateException("Engine not running"); }
    static void validateKey(byte[] key, int offset, int length) {
        Objects.requireNonNull(key, "key");
        Objects.checkFromIndexSize(offset, length, key.length);
        if (length < 1 || length > 250) throw new IllegalArgumentException("Invalid key length");
        for (int i = offset; i < offset + length; i++) {
            int value = key[i] & 255;
            if (value <= 32 || value == 127) throw new IllegalArgumentException("Invalid key byte");
        }
    }
    private void key(byte[] key) { requireStarted(); validateKey(key, 0, key.length); }
    private boolean visible(StoredItem item, long now) {
        return item != null && item.generation() == generation
                && (!item.hasExpiry() || item.expiryTime() > now)
                && !(completed && item.createdAt() <= completedFlush)
                && !(delayed && now >= delayedFlush && item.createdAt() <= delayedFlush);
    }
    private StoredItem live(SegmentedItemTable.Transaction tx, byte[] key, int offset, int length, long now) {
        StoredItem item = tx.find(key, offset, length);
        if (item != null && !visible(item, now)) {
            removed(item, Removal.LAZY, now);
            tx.remove(key, offset, length);
            return null;
        }
        return item;
    }
    private void initialize(StoredItem item, long now) {
        item.initializeVisibility(now, generation);
        item.recordActivity(activity.incrementAndGet(), now, false);
    }
    private void fetched(SegmentedItemTable.Transaction tx,StoredItem item,long now) {
        tx.promote(item);
        item.recordActivity(activity.incrementAndGet(), now, true);
    }
    private void count(Counter counter) { stats.increment(counter, 1); }
    private void count(StoredItem item, Counter counter, long amount) {
        if (classStats != null) classStats.incrementClass(item.classId(), counter, amount);
        else stats.increment(counter, amount);
    }
    private void gauges() {
        // Serializes only tiny statistics publication, never cache/allocator work.
        synchronized (items) {
            stats.setGauge(Gauge.CURR_ITEMS, items.get());
            stats.setGauge(Gauge.BYTES, bytes.get());
        }
    }
    private void published(StoredItem old, StoredItem replacement) {
        if (old == null) items.incrementAndGet();
        bytes.addAndGet(replacement.requestedBytes() - (old == null ? 0L : old.requestedBytes()));
        count(Counter.TOTAL_ITEMS);
    }
    private void removed(StoredItem item, Removal route, long now) {
        items.decrementAndGet(); bytes.addAndGet(-item.requestedBytes());
        boolean unfetched = !item.wasFetched();
        if (route == Removal.CRAWLER) unfetched &= item.hasExpiry() && item.expiryTime() <= now
                && item.generation() == generation
                && !(completed && item.createdAt() <= completedFlush)
                && !(delayed && now >= delayedFlush && item.createdAt() <= delayedFlush);
        if (classStats != null) classStats.recordRemoval(item.classId(), route, unfetched,
                item.hasExpiry(), age(now, item.activityTime()));
        else {
            if (route == Removal.EVICTION) count(Counter.EVICTIONS);
            if (route == Removal.TAIL) count(Counter.RECLAIMED);
            if (unfetched && route != Removal.LAZY) count(route == Removal.EVICTION
                    ? Counter.EVICTED_UNFETCHED : Counter.EXPIRED_UNFETCHED);
        }
    }
    private static long age(long now, long then) {
        return now <= then ? 0 : now - then < 0 ? Long.MAX_VALUE : now - then;
    }

    @Override public GetResult get(byte[] key) { return read(key); }
    @Override public GetResult getWithCas(byte[] key) { return read(key); }
    private GetResult read(byte[] key) {
        key(key); count(Counter.CMD_GET);
        try {
            return table.transaction(table.segmentId(key, 0, key.length), tx -> {
                requireStarted();
                long now = clock.currentTimeSeconds();
                StoredItem item = live(tx, key, 0, key.length, now);
                if (item == null) count(Counter.GET_MISSES); else count(item, Counter.GET_HITS, 1);
                ItemLease lease = item == null ? null : item.pin();
                try {
                    hooks.beforeResultWrap();
                    LeasedGetResult result = new LeasedGetResult(lease);
                    if (item != null) fetched(tx,item,now);
                    return result;
                } catch (RuntimeException | Error failure) {
                    if (lease != null) lease.close();
                    throw failure;
                }
            });
        } finally { gauges(); }
    }

    @Override public StoreResult set(byte[] k, byte[] v, int f, long e) { return store(k,v,f,e,0,0); }
    @Override public StoreResult add(byte[] k, byte[] v, int f, long e) { return store(k,v,f,e,0,1); }
    @Override public StoreResult replace(byte[] k, byte[] v, int f, long e) { return store(k,v,f,e,0,2); }
    @Override public StoreResult append(byte[] k, byte[] v) { return store(k,v,0,0,0,3); }
    @Override public StoreResult prepend(byte[] k, byte[] v) { return store(k,v,0,0,0,4); }
    @Override public StoreResult cas(byte[] k, byte[] v, int f, long e, long c) { return store(k,v,f,e,c,5); }

    private StoreResult store(byte[] key, byte[] value, int flags, long exptime, long expected, int mode) {
        Objects.requireNonNull(value, "value");
        Result admitted=admit(Mode.values()[mode],key,flags,exptime,expected,value.length);
        if(admitted.token()==null) return admitted.status();
        try(Token token=admitted.token()) {
            return storePrepared(token,Mode.values()[mode],key,value,flags,exptime,expected);
        }
    }

    private final class Incoming implements Token {
        final Mode mode; final byte[] key; final int flags; final long exptime, expected;
        final int length; final StoredItem.Reservation reservation;
        boolean consumed;
        Incoming(Mode mode,byte[] key,int flags,long exptime,long expected,int length,StoredItem.Reservation reservation) {
            this.mode=mode; this.key=key.clone(); this.flags=flags; this.exptime=exptime;
            this.expected=expected; this.length=length; this.reservation=reservation;
        }
        DefaultCacheEngine issuer() { return DefaultCacheEngine.this; }
        @Override public synchronized void close() { consumed=true; reservation.close(); }
    }

    @Override public Result admit(Mode mode,byte[] key,int flags,long exptime,long expected,int length) {
        requireStarted(); Objects.requireNonNull(mode); Objects.requireNonNull(key);
        try { validateKey(key,0,key.length); } catch(IllegalArgumentException failure) { return new Result(StoreResult.INVALID,null); }
        if(length<0) return new Result(StoreResult.INVALID,null);
        long size=(32L+key.length+length+7)&~7L;
        if(size>settings.itemMaxSize) {
            if(mode==Mode.SET) rejectOversizedSet(key);
            return new Result(StoreResult.TOO_LARGE,null);
        }
        int requestedClass=classFor(size);
        for(int attempt=0;;attempt++) {
            StoredItem.ReservationResult result=StoredItem.reserve(serviceBudget,allocator,key.length,length,settings.itemMaxSize);
            if(result.reservation()!=null) {
                try {
                    Incoming token=new Incoming(mode,key,flags,exptime,expected,length,result.reservation());
                    if(classStats!=null) classStats.incrementClass(requestedClass,Counter.CMD_SET,1); else count(Counter.CMD_SET);
                    gauges();
                    return new Result(StoreResult.STORED,token);
                } catch(RuntimeException | Error failure) { result.reservation().close(); throw failure; }
            }
            if(!result.nativeDenied() || attempt==RETRIES || !preflight(item->item.keyEquals(key,0,key.length),requestedClass)) {
                if(result.nativeDenied() && classStats!=null) classStats.outOfMemory(requestedClass);
                if(result.nativeDenied() && mode==Mode.SET) invalidateRejectedSet(key);
                capacityDenied();
                gauges(); return new Result(StoreResult.OUT_OF_MEMORY,null);
            }
        }
    }

    /**
     * Invalidates a valid SET rejected by the decoder's full-record size check.
     * This runs at header rejection, never as a deferred body-drain callback.
     * Existing readers retain their immutable versions until their leases close.
     * @param key borrowed validated key
     */
    @Override public void rejectOversizedSet(byte[] key) {
        key(key);
        try { invalidateRejectedSet(key); }
        finally { gauges(); }
    }

    /** Invalidates a raw-key SET at header rejection, before any body drain. */
    public void rejectExtendedSet(byte[] key) {
        requireStarted();Objects.requireNonNull(key);
        if(key.length<1 || key.length>250)throw new IllegalArgumentException("Invalid key length");
        try{invalidateRejectedSet(key);}finally{gauges();}
    }

    private void invalidateRejectedSet(byte[] key) {
        table.transaction(table.segmentId(key,0,key.length), tx -> {
            requireStarted();
            StoredItem old=tx.find(key,0,key.length);
            if(old!=null) {
                items.decrementAndGet();
                bytes.addAndGet(-old.requestedBytes());
                tx.remove(key,0,key.length);
            }
            return null;
        });
    }

    @Override public StoreResult storePrepared(Token admitted,Mode operation,byte[] key,byte[] value,
            int flags,long exptime,long expected) {
        Objects.requireNonNull(value);
        if(!(admitted instanceof DefaultCacheEngine.Incoming incoming) || incoming.issuer()!=this
                || incoming.mode!=operation || !java.util.Arrays.equals(incoming.key,key)
                || incoming.length!=value.length || incoming.flags!=flags || incoming.exptime!=exptime
                || incoming.expected!=expected) throw new IllegalArgumentException("Admission identity mismatch");
        synchronized(incoming) {
            if(incoming.consumed) throw new IllegalStateException("Admission already consumed");
            incoming.consumed=true;
            try { return preparedStore(incoming,key,value,flags,exptime,expected,operation.ordinal()); }
            finally { incoming.reservation.close(); }
        }
    }

    private StoreResult preparedStore(Incoming incoming,byte[] key, byte[] value, int flags, long exptime, long expected, int mode) {
        requireStarted(); Objects.requireNonNull(key, "key"); Objects.requireNonNull(value, "value");
        try { validateKey(key, 0, key.length); } catch (IllegalArgumentException invalid) { return StoreResult.INVALID; }
        ByteBuffer scratch = ByteBuffer.allocate(256);
        int[] failedClass = {-1};
        boolean[] nativeDenied = {false};
        try {
            for (int attempt = 0; ; attempt++) {
                nativeDenied[0]=false;
                StoreResult result = table.transaction(table.segmentId(key, 0, key.length), tx -> {
                    requireStarted(); long now = clock.currentTimeSeconds();
                    StoredItem old = live(tx,key,0,key.length,now);
                    if (mode == 1 && old != null) {
                        tx.promote(old); old.recordActivity(activity.incrementAndGet(), now, false);
                        return StoreResult.NOT_STORED;
                    }
                    if (old == null && mode >= 2) {
                        if (mode == 5) count(Counter.CAS_MISSES);
                        return mode == 5 ? StoreResult.NOT_FOUND : StoreResult.NOT_STORED;
                    }
                    if (mode == 5 && old.casId() != expected) { count(old, Counter.CAS_BADVAL, 1); return StoreResult.EXISTS; }
                    long length = value.length + (mode == 3 || mode == 4 ? (long) old.valueLength() : 0);
                    if (((32L + key.length + length + 7) & ~7L) > settings.itemMaxSize) {
                        return mode==3 || mode==4 ? StoreResult.NOT_STORED : StoreResult.TOO_LARGE;
                    }
                    int f = mode == 3 || mode == 4 ? old.flags() : flags;
                    long expiry = mode == 3 || mode == 4 ? old.expiryTime() : ExpiryPolicy.deadline(exptime, now);
                    StoredItem replacement;
                    if(mode!=3 && mode!=4) {
                        replacement=incoming.reservation.finish(key,(position,dst)->dst.put(value,position,dst.remaining()),scratch,
                                f,exptime!=0,expiry,cas.incrementAndGet());
                    } else {
                        StoredItem.ReservationResult combined=StoredItem.reserve(serviceBudget,allocator,key.length,(int)length,settings.itemMaxSize);
                        nativeDenied[0]=combined.nativeDenied();
                        try(StoredItem.Reservation reservation=combined.reservation()) {
                            replacement=reservation==null ? null : reservation.finish(key,
                                    (position,dst)->copyCombined(old,value,mode,position,dst),scratch,f,old.hasExpiry(),expiry,cas.incrementAndGet());
                        }
                    }
                    if (replacement == null) {
                        failedClass[0] = classFor(32L+key.length+length);
                        return StoreResult.OUT_OF_MEMORY;
                    }
                    boolean transferred = false;
                    try {
                        initialize(replacement, now);
                        if (!tx.put(key,replacement)) return StoreResult.OUT_OF_MEMORY;
                        transferred = true; published(old,replacement);
                        if (mode == 5) count(old, Counter.CAS_HITS, 1);
                        return StoreResult.STORED;
                    } finally { if (!transferred) replacement.retire(); }
                });
                if (result != StoreResult.OUT_OF_MEMORY) return result;
                if(mode!=3 && mode!=4 || !nativeDenied[0] || attempt==RETRIES
                        || !preflight(item -> item.keyEquals(key,0,key.length), failedClass[0])) {
                    if(nativeDenied[0] && classStats!=null) classStats.outOfMemory(failedClass[0]);
                    capacityDenied();
                    return mode==3 || mode==4 ? StoreResult.NOT_STORED : result;
                }
            }
        } finally { gauges(); }
    }

    private static void copyCombined(StoredItem old, byte[] input, int mode, int position, ByteBuffer dst) {
        if (mode != 3 && mode != 4) { dst.put(input,position,dst.remaining()); return; }
        int firstLength = mode == 3 ? old.valueLength() : input.length;
        if (position < firstLength) {
            int count = Math.min(dst.remaining(),firstLength-position);
            int limit = dst.limit(); dst.limit(dst.position()+count);
            if (mode == 3) old.copyValue(position,dst); else dst.put(input,position,count);
            dst.limit(limit); position += count;
        }
        if (dst.hasRemaining()) {
            if (mode == 3) dst.put(input,position-firstLength,dst.remaining());
            else old.copyValue(position-firstLength,dst);
        }
    }

    @Override public boolean delete(byte[] key) {
        key(key);
        try {
            return table.transaction(table.segmentId(key,0,key.length), tx -> {
                requireStarted(); StoredItem item = live(tx,key,0,key.length,clock.currentTimeSeconds());
                if (item == null) count(Counter.DELETE_MISSES); else count(item, Counter.DELETE_HITS, 1);
                if (item == null) return false;
                items.decrementAndGet(); bytes.addAndGet(-item.requestedBytes());
                return tx.remove(key,0,key.length);
            });
        } finally { gauges(); }
    }

    @Override public CounterResult increment(byte[] key, long delta) { return counter(key,delta,true); }
    @Override public CounterResult decrement(byte[] key, long delta) { return counter(key,delta,false); }
    private CounterResult counter(byte[] key, long delta, boolean increment) {
        key(key); ByteBuffer scratch = ByteBuffer.allocate(256);
        int[] failedClass = {-1};
        boolean[] oversized = {false};
        boolean[] counted = {false};
        boolean[] nativeDenied = {false};
        try {
            for (int attempt = 0; ; attempt++) {
                oversized[0] = false;
                nativeDenied[0] = false;
                CounterResult result = table.transaction(table.segmentId(key,0,key.length), tx -> {
                    requireStarted(); long now = clock.currentTimeSeconds();
                    StoredItem old = live(tx,key,0,key.length,now);
                    if (old == null) {
                        if(!counted[0]) count(increment ? Counter.INCR_MISSES : Counter.DECR_MISSES);
                        return new ImmutableCounterResult(CounterResult.Status.NOT_FOUND,0);
                    }
                    long number = 0;
                    if (old.valueLength() == 0) return new ImmutableCounterResult(CounterResult.Status.NON_NUMERIC,0);
                    int numericLength=old.numericLength()==0 ? old.valueLength() : old.numericLength();
                    for (int position = 0; position < old.valueLength();) {
                        scratch.clear().limit(Math.min(256,old.valueLength()-position));
                        int read = old.copyValue(position,scratch); position += read; scratch.flip();
                        while (scratch.hasRemaining()) {
                            int index=position-scratch.remaining();
                            int raw=scratch.get();
                            if(index>=numericLength) {
                                if(raw!=' ') return new ImmutableCounterResult(CounterResult.Status.NON_NUMERIC,0);
                                continue;
                            }
                            int digit = raw - '0';
                            if (digit < 0 || digit > 9 || Long.compareUnsigned(number,1844674407370955161L) > 0
                                    || (number == 1844674407370955161L && digit > 5)) {
                                return new ImmutableCounterResult(CounterResult.Status.NON_NUMERIC,0);
                            }
                            number = number * 10 + digit;
                        }
                    }
                    if(!counted[0]) { count(old,increment ? Counter.INCR_HITS : Counter.DECR_HITS,1); counted[0]=true; }
                    long updated = increment ? number + delta : Long.compareUnsigned(number,delta) < 0 ? 0 : number-delta;
                    byte[] value = Long.toUnsignedString(updated).getBytes(StandardCharsets.US_ASCII);
                    boolean logicalInPlace=old.pinCount()==0 && value.length<=old.valueLength();
                    int capacity=logicalInPlace ? old.valueLength() : value.length;
                    long recordSize = (32L + key.length + capacity + 7) & ~7L;
                    if (recordSize > settings.itemMaxSize) {
                        oversized[0] = true;
                        return new ImmutableCounterResult(CounterResult.Status.OUT_OF_MEMORY,0);
                    }
                    ImmutableCounterResult success = new ImmutableCounterResult(CounterResult.Status.VALUE,updated);
                    StoredItem.ReservationResult admission=StoredItem.reserve(serviceBudget,allocator,key.length,capacity,settings.itemMaxSize);
                    nativeDenied[0]=admission.nativeDenied();
                    StoredItem replacement;
                    try(StoredItem.Reservation reservation=admission.reservation()) {
                        replacement=reservation==null ? null : reservation.finish(key,
                                (offset,dst)-> { while(dst.hasRemaining()) { dst.put(offset<value.length ? value[offset] : (byte)' '); offset++; } },
                                scratch,old.flags(),old.hasExpiry(),old.expiryTime(),cas.incrementAndGet());
                    }
                    if (replacement == null) {
                        failedClass[0] = classFor(recordSize);
                        return new ImmutableCounterResult(CounterResult.Status.OUT_OF_MEMORY,0);
                    }
                    boolean transferred = false;
                    try {
                        initialize(replacement,now);
                        replacement.initializeNumericLength(value.length);
                        if(logicalInPlace) replacement.recordActivity(replacement.activityOrder(),now,old.wasFetched());
                        if (!tx.put(key,replacement)) return new ImmutableCounterResult(CounterResult.Status.OUT_OF_MEMORY,0);
                        transferred = true;
                        if(!logicalInPlace) published(old,replacement);
                        return success;
                    } finally { if (!transferred) replacement.retire(); }
                });
                if (result.status() != CounterResult.Status.OUT_OF_MEMORY || oversized[0]) return result;
                if(nativeDenied[0] && attempt<RETRIES) hooks.beforeArithmeticRetry();
                if(!nativeDenied[0] || attempt==RETRIES || !preflight(item -> item.keyEquals(key,0,key.length),failedClass[0])) {
                    if(nativeDenied[0] && classStats!=null) classStats.outOfMemory(failedClass[0]);
                    capacityDenied();
                    return result;
                }
            }
        } finally { gauges(); }
    }

    @Override public GetResult getAndTouch(byte[] key, long exptime) throws CacheCapacityException {
        return touchResult(key,exptime,true);
    }
    @Override public boolean touch(byte[] key, long exptime) throws CacheCapacityException {
        try (GetResult result = touchResult(key,exptime,false)) { return result.found(); }
    }
    private GetResult touchResult(byte[] key, long exptime, boolean retrieval) throws CacheCapacityException {
        key(key); count(Counter.CMD_TOUCH);
        ByteBuffer scratch = ByteBuffer.allocate(256);
        int[] failedClass = {-1};
        boolean[] nativeDenied={false};
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    return table.transaction(table.segmentId(key,0,key.length), tx -> {
                        requireStarted(); long now = clock.currentTimeSeconds();
                        StoredItem old = live(tx,key,0,key.length,now);
                        if (old == null) {
                            count(Counter.TOUCH_MISSES);
                            return new LeasedGetResult(null);
                        }
                        StoredItem replacement = copy(old,key,0,key.length,exptime,now,scratch,nativeDenied);
                        if (replacement == null) {
                            failedClass[0] = old.classId();
                            throw new CacheCapacityException();
                        }
                        ItemLease lease = null; boolean transferred = false;
                        try {
                            hooks.beforeLeaseAcquire();
                            lease = replacement.pin();
                            hooks.beforeResultWrap();
                            LeasedGetResult result = new LeasedGetResult(lease);
                            tx.prepareReplacement(key,0,key.length,replacement); transferred = true;
                            tx.commit();
                            if (retrieval) fetched(tx,replacement,now);
                            count(old, Counter.TOUCH_HITS, 1);
                            return result;
                        } catch (RuntimeException | Error failure) {
                            if (lease != null) lease.close(); throw failure;
                        } finally { if (!transferred) replacement.retire(); }
                    });
                } catch (CacheCapacityException denied) {
                    if (!nativeDenied[0] || attempt == RETRIES
                            || !preflight(item -> item.keyEquals(key,0,key.length),failedClass[0])) {
                        if(nativeDenied[0] && classStats!=null) classStats.outOfMemory(failedClass[0]);
                        capacityDenied(); throw denied;
                    }
                }
            }
        } finally { gauges(); }
    }

    private StoredItem copy(StoredItem old, byte[] key, int offset, int length,
            long exptime, long now, ByteBuffer scratch,boolean[] nativeDenied) {
        StoredItem.ReservationResult result=StoredItem.reserve(serviceBudget,allocator,length,old.valueLength(),settings.itemMaxSize);
        nativeDenied[0]=result.nativeDenied();
        StoredItem replacement;
        try(StoredItem.Reservation reservation=result.reservation()) {
            replacement=reservation==null ? null : reservation.finish(key,offset,length,old::copyValue,scratch,
                    old.flags(),exptime!=0,ExpiryPolicy.deadline(exptime,now),old.casId());
        }
        if (replacement != null) {
            initialize(replacement,now);
            replacement.metaState(old.metaStale(),old.metaWinner(),old.binaryKey());
            replacement.initializeNumericLength(old.numericLength());
            replacement.recordActivity(activity.incrementAndGet(), now, old.wasFetched());
        }
        return replacement;
    }

    @Override public RetrievalSnapshot snapshot(KeySequence keys) throws CacheCapacityException {
        return capture(keys,0,false);
    }
    @Override public RetrievalSnapshot snapshotAndTouch(KeySequence keys,long exptime) throws CacheCapacityException {
        return capture(keys,exptime,true);
    }
    private RetrievalSnapshot capture(KeySequence sequence,long exptime,boolean touch) throws CacheCapacityException {
        requireStarted(); Objects.requireNonNull(sequence,"keys");
        int size = sequence.size();
        if (size < 1) throw new IllegalArgumentException("Empty key sequence");
        long charge = 512L + 512L * size;
        if (size > Integer.MAX_VALUE / 250) { capacityDenied(); throw new CacheCapacityException(); }
        long used = snapshotUsed.get();
        for (;;) {
            if (charge > snapshotLimit-used) { capacityDenied(); throw new CacheCapacityException(); }
            if (snapshotUsed.compareAndSet(used,used+charge)) break;
            used = snapshotUsed.get();
        }
        CommandSnapshot snapshot;
        try { snapshot = new CommandSnapshot(size,snapshotUsed,charge); }
        catch (RuntimeException | Error failure) { snapshotUsed.addAndGet(-charge); throw failure; }
        boolean success = false;
        boolean[] nativeDenied={false};
        try {
            sequence.forEachKey(snapshot); snapshot.prepare(table);
            stats.increment(touch ? Counter.CMD_TOUCH : Counter.CMD_GET,size);
            ByteBuffer scratch = touch ? ByteBuffer.allocate(32) : null;
            for (int attempt = 0; ; attempt++) {
                try {
                    table.transaction(snapshot.segmentIds,snapshot.segmentCount,tx -> {
                        requireStarted(); snapshot.clock = clock.currentTimeSeconds();
                        int hits = 0;
                        for (int i = 0; i < snapshot.uniqueCount; i++) {
                            int index = snapshot.firstOccurrenceOrder[i];
                            int offset = index*250, length = snapshot.length(index);
                            // Do not remove targets during preparation: rollback preserves all originals.
                            StoredItem old = tx.find(snapshot.keys,offset,length);
                            if (!visible(old,snapshot.clock)) continue;
                            StoredItem item = touch ? copy(old,snapshot.keys,offset,length,exptime,snapshot.clock,scratch,nativeDenied) : old;
                            if (item == null) {
                                snapshot.failedClass = old.classId();
                                throw new CacheCapacityException();
                            }
                            boolean transferred = !touch;
                            try {
                                if (touch) {
                                    tx.prepareReplacement(snapshot.keys,offset,length,item); transferred = true;
                                    item.recordActivity(activity.incrementAndGet(),snapshot.clock,true);
                                }
                                hooks.beforeLeaseAcquire();
                                snapshot.references[index] = item.pin();
                            } finally { if (!transferred) item.retire(); }
                            hits += snapshot.occurrences[index];
                        }
                        if (touch) tx.commit();
                        for (int i = 0; i < snapshot.uniqueCount; i++) {
                            int index = snapshot.firstOccurrenceOrder[i];
                            if (snapshot.references[index] != null) {
                                StoredItem item = tx.find(snapshot.keys,index*250,snapshot.length(index));
                                if (!touch) fetched(tx,item,snapshot.clock);
                                count(item, touch ? Counter.TOUCH_HITS : Counter.GET_HITS, snapshot.occurrences[index]);
                            } else {
                                StoredItem item = tx.find(snapshot.keys,index*250,snapshot.length(index));
                                if (item != null && !visible(item,snapshot.clock)) {
                                    removed(item,Removal.LAZY,snapshot.clock); tx.remove(snapshot.keys,index*250,snapshot.length(index));
                                }
                            }
                        }
                        stats.increment(touch ? Counter.TOUCH_MISSES : Counter.GET_MISSES,size-hits);
                        return null;
                    });
                    success = true; return snapshot;
                } catch (CacheCapacityException denied) {
                    snapshot.releasePins();
                    if (!nativeDenied[0] || attempt == RETRIES || !preflight(snapshot::contains,snapshot.failedClass)) {
                        if(nativeDenied[0] && classStats!=null) classStats.outOfMemory(snapshot.failedClass);
                        capacityDenied(); throw denied;
                    }
                }
            }
        } finally { if (!success) snapshot.close(); gauges(); }
    }

    /**
     * Executes one binary/meta operation atomically, including conditional creation
     * and returning the mutation's CAS. Keys are raw bytes; textual validation belongs
     * to the codec. The caller owns the returned result and any lease it contains.
     * @param request borrowed connection-confined arguments
     * @return non-null owned outcome
     */
    public ExtendedResult execute(ExtendedRequest request) {
        requireStarted(); Objects.requireNonNull(request); Objects.requireNonNull(request.operation);
        Objects.requireNonNull(request.key);
        if(request.key.length<1 || request.key.length>250) throw new IllegalArgumentException("Invalid key length");
        int[] deniedClass={-1};
        boolean[] nativeDenied={false};
        ByteBuffer scratch=ByteBuffer.allocate(256);
        try {
            for(int attempt=0;;attempt++) {
                nativeDenied[0]=false;
                ExtendedResult result=table.transaction(table.segmentId(request.key,0,request.key.length),
                        tx->extended(tx,request,scratch,deniedClass,nativeDenied));
                if(result.status!=ExtendedResult.Status.OUT_OF_MEMORY || !nativeDenied[0]
                        || attempt==RETRIES || !preflight(item->item.keyEquals(request.key,0,request.key.length),deniedClass[0])) {
                    if(result.status==ExtendedResult.Status.OUT_OF_MEMORY) capacityDenied();
                    return result;
                }
                result.close();
            }
        } finally { request.close(); gauges(); }
    }

    /** Reserves an incoming storage record at header time, after heap admission.
     * Returns an error without a token only after bounded eviction attempts;
     * valid failed SET invalidates at this boundary, never after body drain.
     */
    public ExtendedResult.Status admitExtended(ExtendedRequest r,int length) {
        requireStarted();
        long size=(32L+r.key.length+length+7)&~7L;
        if(size>settings.itemMaxSize) {
            if(r.operation==ExtendedRequest.Operation.SET || r.metaStore)rejectExtendedSet(r.key);
            return ExtendedResult.Status.TOO_LARGE;
        }
        int cls=classFor(size);
        for(int attempt=0;;attempt++) {
            StoredItem.ReservationResult a=StoredItem.reserve(serviceBudget,allocator,r.key.length,length,settings.itemMaxSize);
            if(a.reservation()!=null){r.admitted=a.reservation();return ExtendedResult.Status.OK;}
            if(!a.nativeDenied() || attempt==RETRIES || !preflight(item->item.keyEquals(r.key,0,r.key.length),cls)) {
                if(a.nativeDenied() && (r.operation==ExtendedRequest.Operation.SET || r.metaStore))rejectExtendedSet(r.key);
                capacityDenied();return ExtendedResult.Status.OUT_OF_MEMORY;
            }
        }
    }

    private ExtendedResult extended(SegmentedItemTable.Transaction tx,ExtendedRequest r,ByteBuffer scratch,
            int[] deniedClass,boolean[] nativeDenied) {
        requireStarted();
        byte[] key=r.key;
        long now=clock.currentTimeSeconds();
        StoredItem old=live(tx,key,0,key.length,now);
        var op=r.operation;
        boolean reading=op==ExtendedRequest.Operation.GET || op==ExtendedRequest.Operation.DEBUG;
        if(reading) {
            boolean debug=op==ExtendedRequest.Operation.DEBUG;
            boolean touching=r.hasTtl && !debug;
            if(old==null && (!r.create || debug)) {
                if(!debug){count(touching?Counter.CMD_TOUCH:Counter.CMD_GET);count(touching?Counter.TOUCH_MISSES:Counter.GET_MISSES);}
                return new ExtendedResult(ExtendedResult.Status.NOT_FOUND);
            }
            ExtendedResult out=new ExtendedResult(ExtendedResult.Status.OK);
            if(old!=null) describe(out,old,now);
            long selectedTtl=old==null?r.createTtl:r.ttl;
            long reportedTtl=old==null?(r.createTtl==0?-1:r.createTtl):out.ttl;
            boolean orderedWin=false;
            if(r.claimRecache && r.orderedFlags!=null) {
                long currentExpiry=old==null?ExpiryPolicy.deadline(r.createTtl,now):old.expiryTime();
                boolean expires=old==null?r.createTtl!=0:old.hasExpiry();
                for(String flag:r.orderedFlags){
                    switch(flag.charAt(0)){
                        case 'T'->{selectedTtl=r.ttl;currentExpiry=ExpiryPolicy.deadline(r.ttl,now);expires=r.ttl!=0;}
                        case 'N'->{if(old==null){selectedTtl=r.createTtl;currentExpiry=ExpiryPolicy.deadline(r.createTtl,now);expires=r.createTtl!=0;}}
                        case 'R'->{if(expires && currentExpiry-now<r.recacheTtl)orderedWin=true;}
                        case 't'->reportedTtl=expires?currentExpiry-now:-1;
                        default->{ }
                    }
                }
            }
            StoredItem item=old;
            boolean replacement=old==null || r.hasTtl && !debug;
            if(replacement) {
                int length=old==null?0:old.valueLength();
                long expiry=ExpiryPolicy.deadline(selectedTtl,now);
                item=extendedAllocate(r,old,length,old==null?0:old.flags(),
                        selectedTtl!=0,expiry,
                        old==null?(r.overrideCas?r.newCas:cas.incrementAndGet()):old.casId(),
                        scratch,deniedClass,nativeDenied);
                if(item==null) return new ExtendedResult(ExtendedResult.Status.OUT_OF_MEMORY);
            }
            boolean transferred=!replacement;
            try {
                if(replacement){
                    initialize(item,now);
                    if(old!=null){item.metaState(old.metaStale(),old.metaWinner(),old.binaryKey());item.recordActivity(old.activityOrder(),old.activityTime(),old.wasFetched());}
                    else item.metaState(false,false,r.binaryKey);
                }
                out.attach(item.pin());
                if(replacement) {
                    if(!tx.put(key,item)) { out.close(); return new ExtendedResult(ExtendedResult.Status.OUT_OF_MEMORY); }
                    transferred=true;
                    if(old==null) published(null,item);
                }
                if(old==null) describe(out,item,now);
                boolean wonBefore=item.metaWinner();
                boolean win=r.claimRecache && !wonBefore && (old==null || item.metaStale()
                        || orderedWin);
                if(win) item.metaState(item.metaStale(),true,item.binaryKey());
                out.winner=win; out.alreadyWon=wonBefore; out.stale=item.metaStale();
                out.cas=item.casId(); out.ttl=item.hasExpiry()?item.expiryTime()-now:-1;
                if(r.claimRecache)out.ttl=reportedTtl;
                if(op!=ExtendedRequest.Operation.DEBUG && !r.noUpdate) fetched(tx,item,now);
                if(!debug){count(touching?Counter.CMD_TOUCH:Counter.CMD_GET);count(item,touching?Counter.TOUCH_HITS:Counter.GET_HITS,1);}
                return out;
            } catch(RuntimeException | Error failure) { out.close(); throw failure; }
            finally { if(!transferred) item.retire(); }
        }
        boolean checkCas=r.compareCas && !(r.metaStore && op==ExtendedRequest.Operation.ADD);
        boolean staleCapable=op==ExtendedRequest.Operation.SET || op==ExtendedRequest.Operation.REPLACE;
        if(checkCas && old!=null && old.casId()!=r.expectedCas
                && !(r.invalidate && staleCapable
                && Long.compareUnsigned(r.expectedCas,old.casId())<0)) {count(old,Counter.CAS_BADVAL,1);return new ExtendedResult(ExtendedResult.Status.EXISTS);}
        boolean arithmetic=op==ExtendedRequest.Operation.INCR || op==ExtendedRequest.Operation.DECR;
        boolean append=op==ExtendedRequest.Operation.APPEND || op==ExtendedRequest.Operation.PREPEND;
        if(checkCas && old==null && !((arithmetic || append) && r.create)){count(Counter.CAS_MISSES);return new ExtendedResult(append?ExtendedResult.Status.NOT_STORED:ExtendedResult.Status.NOT_FOUND);}
        if(op==ExtendedRequest.Operation.DELETE && old==null){count(Counter.DELETE_MISSES);return new ExtendedResult(ExtendedResult.Status.NOT_FOUND);}
        if(op==ExtendedRequest.Operation.ADD && old!=null && !(r.binaryProtocol && r.compareCas)) {
            tx.promote(old); old.recordActivity(activity.incrementAndGet(),now,old.wasFetched());
            return new ExtendedResult(ExtendedResult.Status.NOT_STORED);
        }
        if(old==null && (op==ExtendedRequest.Operation.REPLACE || append && !r.create))return new ExtendedResult(ExtendedResult.Status.NOT_STORED);
        if(op==ExtendedRequest.Operation.DELETE && !r.invalidate && !r.removeValue) {
            ExtendedResult out=new ExtendedResult(ExtendedResult.Status.OK); describe(out,old,now);
            items.decrementAndGet(); bytes.addAndGet(-old.requestedBytes());
            count(old,Counter.DELETE_HITS,1); tx.remove(key,0,key.length); return out;
        }
        byte[] value=r.value;
        long number=0;
        if(arithmetic) {
            if(old==null) {
                if(!r.create){count(op==ExtendedRequest.Operation.INCR?Counter.INCR_MISSES:Counter.DECR_MISSES);return new ExtendedResult(ExtendedResult.Status.NOT_FOUND);}
                number=r.initial;
            } else {
                Long parsed=extendedNumber(old,scratch);
                if(parsed==null) return new ExtendedResult(ExtendedResult.Status.NON_NUMERIC);
                number=op==ExtendedRequest.Operation.INCR?parsed+r.delta:
                        Long.compareUnsigned(parsed,r.delta)<0?0:parsed-r.delta;
            }
            value=Long.toUnsignedString(number).getBytes(StandardCharsets.US_ASCII);
        }
        long length=op==ExtendedRequest.Operation.DELETE?(r.removeValue?0:old.valueLength()):
                (long)Objects.requireNonNull(value,"value").length+(append && old!=null?old.valueLength():0);
        if(((32L+key.length+length+7)&~7L)>settings.itemMaxSize) {
            if(op==ExtendedRequest.Operation.SET && old!=null) {
                items.decrementAndGet(); bytes.addAndGet(-old.requestedBytes()); tx.remove(key,0,key.length);
            }
            return new ExtendedResult(append?ExtendedResult.Status.NOT_STORED:ExtendedResult.Status.TOO_LARGE);
        }
        boolean staleSet=r.invalidate && checkCas && old!=null && staleCapable
                && Long.compareUnsigned(r.expectedCas,old.casId())<0;
        long ttl=old==null && r.create && (append || arithmetic)?r.createTtl:r.ttl;
        if(arithmetic && old==null && r.hasTtl && !r.createTtlLast)ttl=r.ttl;
        boolean tombstone=op==ExtendedRequest.Operation.DELETE && r.removeValue;
        boolean keepExpiry=old!=null && (staleSet || append || arithmetic && !r.hasTtl || op==ExtendedRequest.Operation.DELETE && !r.hasTtl && !tombstone);
        boolean expires=keepExpiry?old.hasExpiry():ttl!=0;
        long expiry=keepExpiry?old.expiryTime():ExpiryPolicy.deadline(ttl,now);
        int flags=old!=null && (append || arithmetic || op==ExtendedRequest.Operation.DELETE && !tombstone)?old.flags():r.flags;
        long nextCas=r.overrideCas?r.newCas:tombstone && !r.invalidate?old.casId():cas.incrementAndGet();
        byte[] data=value;
        boolean useIncoming=r.admitted!=null && !append;
        StoredItem.ReservationResult admission=useIncoming?new StoredItem.ReservationResult(r.admitted,false):StoredItem.reserve(serviceBudget,allocator,key.length,(int)length,settings.itemMaxSize);
        if(useIncoming)r.admitted=null;
        nativeDenied[0]=admission.nativeDenied(); deniedClass[0]=classFor(32L+key.length+length);
        StoredItem item;
        try(StoredItem.Reservation reservation=admission.reservation()) {
            item=reservation==null?null:reservation.finish(key,(position,dst)-> {
                if(op==ExtendedRequest.Operation.DELETE) old.copyValue(position,dst);
                else if(append && old!=null) copyCombined(old,data,op==ExtendedRequest.Operation.APPEND?3:4,position,dst);
                else dst.put(data,position,dst.remaining());
            },scratch,flags,expires,expiry,nextCas);
        }
        if(item==null) {
            return new ExtendedResult(append?ExtendedResult.Status.NOT_STORED:ExtendedResult.Status.OUT_OF_MEMORY);
        }
        boolean transferred=false;
        try {
            initialize(item,now);
            boolean stale=op==ExtendedRequest.Operation.DELETE?r.invalidate:staleSet;
            item.metaState(stale,stale && op!=ExtendedRequest.Operation.DELETE && old!=null && old.metaWinner(),
                    r.binaryKey || old!=null && old.binaryKey());
            if(!tx.put(key,item)) return new ExtendedResult(ExtendedResult.Status.OUT_OF_MEMORY);
            transferred=true; published(old,item);
            ExtendedResult out=new ExtendedResult(ExtendedResult.Status.OK); describe(out,item,now); out.number=number;
            out.previousTtl=old==null?-1:old.hasExpiry()?old.expiryTime()-now:-1;out.created=old==null;
            count(item,op==ExtendedRequest.Operation.DELETE?Counter.DELETE_HITS:arithmetic?(op==ExtendedRequest.Operation.INCR?Counter.INCR_HITS:Counter.DECR_HITS):Counter.CMD_SET,1);
            if(checkCas && !arithmetic && op!=ExtendedRequest.Operation.DELETE)count(item,Counter.CAS_HITS,1);
            return out;
        } finally { if(!transferred) item.retire(); }
    }

    private StoredItem extendedAllocate(ExtendedRequest r,StoredItem old,int length,int flags,boolean expires,
            long expiry,long token,ByteBuffer scratch,int[] deniedClass,boolean[] nativeDenied) {
        StoredItem.ReservationResult a=StoredItem.reserve(serviceBudget,allocator,r.key.length,length,settings.itemMaxSize);
        nativeDenied[0]=a.nativeDenied(); deniedClass[0]=classFor(32L+r.key.length+length);
        try(StoredItem.Reservation reservation=a.reservation()) {
            return reservation==null?null:reservation.finish(r.key,(offset,dst)->old.copyValue(offset,dst),scratch,
                    flags,expires,expiry,token);
        }
    }

    private static void describe(ExtendedResult out,StoredItem item,long now) {
        out.cas=item.casId(); out.flags=item.flags(); out.size=item.valueLength();
        out.ttl=item.hasExpiry()?item.expiryTime()-now:-1; out.age=age(now,item.activityTime());
        out.fetched=item.wasFetched(); out.stale=item.metaStale(); out.binaryKey=item.binaryKey(); out.classId=item.classId();
    }

    private static Long extendedNumber(StoredItem item,ByteBuffer scratch) {
        if(item.valueLength()==0) return null;
        long number=0; boolean padding=false; int digits=0;
        for(int offset=0;offset<item.valueLength();) {
            scratch.clear().limit(Math.min(scratch.capacity(),item.valueLength()-offset));
            offset+=item.copyValue(offset,scratch); scratch.flip();
            while(scratch.hasRemaining()) {
                int c=scratch.get()&255;
                if(c==' ' && digits>0) { padding=true; continue; }
                if(padding || c<'0' || c>'9' || Long.compareUnsigned(number,1844674407370955161L)>0
                        || number==1844674407370955161L && c>'5') return null;
                number=number*10+c-'0'; digits++;
            }
        }
        return number;
    }

    /** Returns outstanding admitted command metadata, independently of native memory. */
    public long snapshotMetadataUsedBytes() { return snapshotUsed.get(); }

    /** Returns admitted pending/live/retired record service metadata bytes. */
    public long serviceMetadataUsedBytes() { return serviceBudget.usedBytes(); }

    @Override public void flushAll(long delaySeconds) {
        requireStarted();
        table.transactionAll(tx -> {
            requireStarted(); long now = clock.currentTimeSeconds();
            if (delayed && now >= delayedFlush) {
                completedFlush = completed ? Math.max(completedFlush,delayedFlush) : delayedFlush;
                completed = true;
            }
            if (delaySeconds <= 0) {
                generation++; if (generation == 0) generation++;
                delayed = false; completed = false;
            } else { delayedFlush = ExpiryPolicy.deadline(delaySeconds,now); delayed = true; }
            count(Counter.CMD_FLUSH); return null;
        });
    }

    @Override public void malformedFlush() { count(Counter.CMD_FLUSH); }
    @Override public void capacityDenied() {
        if(classStats!=null) classStats.logger().log(SafeLogger.Event.CAPACITY);
    }

    /**
     * Performs a deterministic bounded sweep using the injected clock. At most
     * workLimit records are inspected; each small batch releases its segment.
     * @param workLimit nonnegative maximum inspected records
     * @return number of removed expired or flushed mappings
     */
    public int reclaimExpired(int workLimit) {
        requireStarted(); if (workLimit <= 0) throw new IllegalArgumentException("Nonpositive limit");
        int reclaimed = 0, remaining = workLimit;
        try {
            for (int n = 0; n < table.segmentCount() && remaining > 0; n++) {
                int id = Math.floorMod(maintenanceSegment.getAndIncrement(),table.segmentCount());
                int limit = Math.min(64,remaining);
                int[] result = table.transaction(id,tx -> {
                    requireStarted(); long now = clock.currentTimeSeconds(); int[] counts = new int[2];
                    counts[0] = tx.scan(id,limit,item -> {
                        if (visible(item,now)) return false;
                        removed(item,Removal.CRAWLER,now); counts[1]++; return true;
                    });
                    return counts;
                });
                remaining -= result[0]; reclaimed += result[1];
            }
            return reclaimed;
        } finally { gauges(); }
    }

    private boolean preflight(Predicate<StoredItem> protectedKey,int requestedClass) {
        // One successful removal returns immediately, so admission is retried
        // before another live owner can be retired.
        for (int phase = 0; phase < 4; phase++) {
            if (phase == 2 && allocator.trim() > 0) return true;
            boolean otherClasses = phase >= 2;
            boolean permitLive = (phase & 1) != 0 && settings.evictionPolicy.equals("lru");
            if ((phase & 1) != 0 && !permitLive) continue;
            for (int n = 0; n < table.segmentCount(); n++) {
                int id = Math.floorMod(maintenanceSegment.getAndIncrement(),table.segmentCount());
                boolean reclaimed = table.transaction(id,tx -> {
                    requireStarted(); long now = clock.currentTimeSeconds();
                    for (int classId = 0; classId < allocator.classCount(); classId++) {
                        boolean matches = requestedClass < 0 || classId == requestedClass;
                        if (otherClasses == matches) continue;
                        if (tx.removeLeastRecent(id,classId,1024,item -> {
                            if (item.pinCount() != 0 || protectedKey.test(item)) return false;
                            boolean expired = !visible(item,now);
                            if (!expired && !permitLive) return false;
                            removed(item,expired ? Removal.TAIL : Removal.EVICTION,now); return true;
                        })) return true;
                    }
                    return false;
                });
                if (reclaimed) {
                    allocator.trim();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Captures and validates admitted geometry with exactly one statistics read
     * per class, before queue configuration. Lifetime stability
     * remains an allocator contract, not something finite startup reads can prove.
     */
    private static void validateClassSizes(MemoryAllocator allocator, int classes, int[] sizes) {
        if (classes <= 0 || allocator.classCount() != classes)
            throw new IllegalArgumentException("Invalid allocator class count");
        int previous = 0;
        for (int i = 0; i < classes; i++) {
            SlabClassStats geometry = allocator.classStats(i);
            if (geometry == null || geometry.classId() != i)
                throw new IllegalArgumentException("Invalid allocator class identifier");
            int size = geometry.chunkSize();
            if (size <= previous) throw new IllegalArgumentException("Allocator class sizes must be positive and strictly increasing");
            sizes[i] = size;
            previous = size;
        }
        if (allocator.classCount() != classes)
            throw new IllegalArgumentException("Allocator class count changed during initialization");
    }

    /**
     * Returns the lowest class id whose block size covers the aligned request,
     * or {@code -1} when the aligned request exceeds every class. This pure
     * lower-bound over the construction snapshot is identical to the former
     * traversal of {@code allocator.classStats(...).chunkSize()}.
     */
    private int classFor(long size) {
        long aligned = (size+7)&~7L;
        int low = 0, high = classSizes.length - 1, found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (classSizes[middle] >= aligned) {
                found = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return found;
    }
}
