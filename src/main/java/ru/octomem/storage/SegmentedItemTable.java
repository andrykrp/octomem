package ru.octomem.storage;

import java.util.Objects;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;
import ru.octomem.util.Hashing;
import ru.octomem.stats.ClassStatsRegistry.ClassState;

/**
 * Bounded, independently locked key index owning immutable stored versions.
 * Entries retain only a cached 64-bit hash, a record reference and a chain link;
 * no key or value arrays are retained or copied. Hash collisions are resolved
 * by comparing all key bytes in the record before pinning or replacing it.
 *
 * <p>Successful puts transfer exclusive retirement responsibility to this table.
 * Callers must not retire table-owned records, publish them in another table,
 * or race retirement with publication. Borrowed input keys must remain unchanged
 * throughout each call. Separate operations on one key serialize under its
 * segment lock; lock order is ascending segments, record, allocator. No locks
 * are exposed, and this API does not implement atomic multi-key snapshots.
 * Expiry, eviction and LRU policy belong to later engine work.
 *
 * <p>Logical metadata charges are root {@code 128 + 8*S}, each segment 192,
 * each bucket array {@code 64 + 8*B}, and each entry 64 bytes. Eight-byte
 * references and conservative lock/wrapper allowances are assumptions, not
 * measured heap layout or an RSS guarantee. Reservations include simultaneous
 * old and new arrays during growth. The limit is explicit and independent of
 * native record accounting; no global configuration is modified.
 *
 * @since 1.0
 */
public final class SegmentedItemTable implements AutoCloseable {
    private static final long ENTRY_BYTES = 64;
    private static final int MAX_BUCKETS = 1 << 30;
    private final Segment[] segments;
    private final long metadataLimit;
    private final AtomicLong metadataUsed;
    private volatile long classGeometryMetadataUsed;
    private final ToLongFunction<byte[]> hasher;
    private final AllocationHook allocationHook;
    private volatile boolean closed;
    private final boolean standardHasher;

    /**
     * Creates an empty index with a checked initial metadata reservation.
     * Initial charge is {@code 128 + 8*S + S*(256 + 8*B)} bytes.
     * Only the requested initial arrays are allocated, never the limit itself.
     *
     * @param segmentCount positive power of two
     * @param initialBucketsPerSegment power of two, at least two
     * @param metadataLimit positive logical metadata limit covering initial charge
     * @throws IllegalArgumentException if ranges, initial charge or limit are invalid
     */
    public SegmentedItemTable(int segmentCount, int initialBucketsPerSegment,
                              long metadataLimit) {
        this(segmentCount, initialBucketsPerSegment, metadataLimit, null, () -> { });
    }

    /**
     * Test seam for deterministic collisions. The hasher must be deterministic,
     * thread-safe, non-retaining and must not mutate keys or reenter the table.
     * Routing is {@code segment = (int) hash & (segmentCount - 1)} and
     * {@code bucket = (int) (hash >>> Integer.numberOfTrailingZeros(segmentCount))
     * & (capacity - 1)}, using the same rule when growing a segment.
     */
    SegmentedItemTable(int segmentCount, int initialBucketsPerSegment,
                       long metadataLimit, ToLongFunction<byte[]> hasher) {
        this(segmentCount, initialBucketsPerSegment, metadataLimit, hasher, () -> { });
    }

    /**
     * Test seam injecting preparation failures before entry and new bucket-array
     * allocations in put. The hook must not reenter or change table state.
     * Trusted tests may use bounded synchronization barriers; production uses
     * a nonblocking no-op, not an application callback. Exceptions propagate
     * after rollback.
     */
    SegmentedItemTable(int segmentCount, int initialBucketsPerSegment,
                       long metadataLimit, ToLongFunction<byte[]> hasher,
                       AllocationHook allocationHook) {
        standardHasher = hasher == null;
        this.hasher = standardHasher ? Hashing::hash64 : hasher;
        this.allocationHook = Objects.requireNonNull(allocationHook, "allocationHook");
        if (!isPowerOfTwo(segmentCount) || !isPowerOfTwo(initialBucketsPerSegment)
                || initialBucketsPerSegment < 2 || metadataLimit <= 0) {
            throw new IllegalArgumentException("Invalid table parameters");
        }
        long initial;
        try {
            initial = Math.addExact(128L + 8L * segmentCount,
                    Math.multiplyExact((long) segmentCount, 256L + 8L * initialBucketsPerSegment));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Initial metadata charge overflows", overflow);
        }
        if (initial > metadataLimit) {
            throw new IllegalArgumentException("Metadata limit cannot cover initial table");
        }
        this.metadataLimit = metadataLimit;
        metadataUsed = new AtomicLong(initial);
        segments = new Segment[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new Segment(initialBucketsPerSegment);
        }
    }

    /**
     * Publishes a record, retiring any replaced version after publication.
     * The exact record already mapped at this key is a successful no-op and is
     * never retired. A record cannot be mapped under a different byte key.
     * Metadata denial leaves the input caller-owned and prior entries unchanged.
     * Preparation allocation failures likewise preserve ownership and state,
     * release reservations, and propagate rather than returning false; this is
     * not a guarantee of recovery from arbitrary JVM failure.
     *
     * @param key borrowed key of 1..250 bytes, equal to the record key
     * @param item live caller-owned record, or the exact already mapped record
     * @return true on ownership transfer or same-record no-op; false on metadata
     *         capacity denial, including the maximum representable bucket capacity
     * @throws NullPointerException if key or item is null
     * @throws IllegalArgumentException if key length or record key is invalid
     * @throws IllegalStateException if closed or the supplied record is retired
     */
    public boolean put(byte[] key, StoredItem item) {
        requireOpen();
        validateKey(key);
        Objects.requireNonNull(item, "item");
        long hash = hasher.applyAsLong(key);
        Segment segment = segment(hash);
        segment.lock.lock();
        try {
            requireOpen();
            if (item.isRetired()) {
                throw new IllegalStateException("Cannot publish retired record");
            }
            if (!item.keyEquals(key, 0, key.length)) {
                throw new IllegalArgumentException("Record key differs from index key");
            }
            int bucket = bucket(hash, segment.buckets.length);
            for (Entry entry = segment.buckets[bucket]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.item.keyEquals(key, 0, key.length)) {
                    if (entry.item != item) {
                        StoredItem old = entry.item;
                        validateClass(segment,item);
                        unlinkClass(segment,old);
                        entry.item = item;
                        linkClass(segment,entry,item);
                        old.retire();
                    }
                    return true;
                }
            }
            int oldCapacity = segment.buckets.length;
            validateClass(segment,item);
            boolean grow = segment.size + 1 > oldCapacity * 3L / 4;
            if (grow && oldCapacity == MAX_BUCKETS) {
                return false;
            }
            int newCapacity = grow ? oldCapacity * 2 : oldCapacity;
            long reservation = ENTRY_BYTES + (grow ? arrayBytes(newCapacity) : 0);
            if (!reserve(reservation)) {
                return false;
            }
            Entry added;
            Entry[] replacement = null;
            try {
                allocationHook.beforeAllocation();
                added = new Entry(hash, item);
                if (grow) {
                    allocationHook.beforeAllocation();
                    replacement = new Entry[newCapacity];
                }
            } catch (RuntimeException | Error failure) {
                metadataUsed.addAndGet(-reservation);
                throw failure;
            }
            // No allocation or fallible record access from here to publication.
            if (grow) {
                for (Entry head : segment.buckets) {
                    while (head != null) {
                        Entry next = head.next;
                        int target = bucket(head.hash, newCapacity);
                        head.next = replacement[target];
                        replacement[target] = head;
                        head = next;
                    }
                }
                segment.buckets = replacement;
                bucket = bucket(hash, newCapacity);
            }
            added.next = segment.buckets[bucket];
            segment.buckets[bucket] = added;
            segment.size++;
            linkMaintenance(segment, added);
            linkClass(segment,added,item);
            if (grow) {
                metadataUsed.addAndGet(-arrayBytes(oldCapacity));
            }
            return true;
        } finally {
            segment.lock.unlock();
        }
    }

    /**
     * Pins the mapped version while its segment lock still protects membership.
     * Expired versions may be returned; the caller's engine applies expiry policy.
     *
     * @param key borrowed key of 1..250 bytes
     * @return independently owned lease to close, or null on a miss
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if key length is invalid
     * @throws IllegalStateException if closed or the record pin count is exhausted
     */
    public ItemLease get(byte[] key) {
        requireOpen();
        validateKey(key);
        long hash = hasher.applyAsLong(key);
        Segment segment = segment(hash);
        segment.lock.lock();
        try {
            requireOpen();
            for (Entry entry = segment.buckets[bucket(hash, segment.buckets.length)];
                    entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.item.keyEquals(key, 0, key.length)) {
                    return entry.item.pin();
                }
            }
            return null;
        } finally {
            segment.lock.unlock();
        }
    }

    /**
     * Detaches and retires one version under its segment lock.
     * Existing leases remain valid; bucket arrays are not shrunk.
     *
     * @param key borrowed key of 1..250 bytes
     * @return true if a mapping was removed, false on a miss
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if key length is invalid
     * @throws IllegalStateException if closed
     */
    public boolean remove(byte[] key) {
        requireOpen();
        validateKey(key);
        long hash = hasher.applyAsLong(key);
        Segment segment = segment(hash);
        segment.lock.lock();
        try {
            requireOpen();
            int bucket = bucket(hash, segment.buckets.length);
            Entry previous = null;
            for (Entry entry = segment.buckets[bucket]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.item.keyEquals(key, 0, key.length)) {
                    if (previous == null) {
                        segment.buckets[bucket] = entry.next;
                    } else {
                        previous.next = entry.next;
                    }
                    segment.size--;
                    unlinkMaintenance(segment, entry);
                    unlinkClass(segment,entry.item);
                    metadataUsed.addAndGet(-ENTRY_BYTES);
                    entry.item.retire();
                    return true;
                }
                previous = entry;
            }
            return false;
        } finally {
            segment.lock.unlock();
        }
    }

    /**
     * Captures exact membership while holding all segments in ascending order.
     * @return nonnegative mapping count, including expired entries; zero after close
     */
    public long size() {
        lockAll();
        try {
            long size = 0;
            for (Segment segment : segments) {
                size += segment.size;
            }
            return size;
        } finally {
            unlockAll();
        }
    }

    /**
     * Reports atomic logical ownership accounting, including in-flight reserves.
     * Zero after completed close means logical ownership released, not immediate
     * GC of the root/locks or return of JVM heap or native leased records to OS.
     * @return nonnegative logical bytes no greater than the constructor limit
     */
    public long metadataUsedBytes() {
        return metadataUsed.get();
    }

    /**
     * Computes the conservative eight-byte-aligned charge of the engine's
     * {@code int[classes]} geometry snapshot, including a 24-byte array header.
     * This is logical heap accounting, not a measured JVM layout or RSS bound.
     * @param classes positive allocator class count
     * @return checked aligned logical bytes
     * @throws IllegalArgumentException if classes is not positive
     */
    public static long classGeometryMetadataBytes(int classes) {
        if (classes <= 0) throw new IllegalArgumentException("Invalid class count");
        long bytes = Math.addExact(24L, Math.multiplyExact(4L, classes));
        return Math.addExact(bytes, 7L) & ~7L;
    }

    /**
     * Reserves the engine geometry snapshot before its array is allocated.
     * One successful reservation is permitted on an empty, unconfigured table;
     * denial changes nothing. Close releases this charge with all table metadata.
     * The exclusive startup owner must close the table if later preparation fails.
     * @param classes positive allocator class count
     * @return false if the unchanged table limit cannot admit the snapshot
     * @throws IllegalArgumentException if classes is not positive
     * @throws IllegalStateException if closed, configured, nonempty or already reserved
     */
    public boolean reserveClassGeometry(int classes) {
        long charge = classGeometryMetadataBytes(classes);
        lockAll();
        try {
            requireOpen();
            if (classGeometryMetadataUsed != 0) throw new IllegalStateException("Geometry already reserved");
            for (Segment segment : segments) {
                if (segment.size != 0 || segment.classHeads != null)
                    throw new IllegalStateException("Geometry requires empty unconfigured table");
            }
            if (!reserve(charge)) return false;
            classGeometryMetadataUsed = charge;
            return true;
        } finally { unlockAll(); }
    }

    /**
     * Reports the geometry part of the logical table metadata reservation.
     * @return snapshot charge, or zero before reservation and after completed close
     */
    public long classGeometryMetadataUsedBytes() { return classGeometryMetadataUsed; }

    /**
     * Reserves a composition-owned fixed startup allowance before class
     * initialization: the W+3 additional-epoch charge plus any other bounded
     * fixed heap metadata the composition accounts, such as the allocator's
     * exact per-page free-slot metadata. The W+3 reserve plus the already-live
     * current epoch equals the W+4 complete-epoch design bound; one epoch
     * measures 5928 bytes compressed / 5968 uncompressed against a 6144-byte
     * charge. Direct table limits never grow.
     * @param bytes nonnegative checked startup allowance in bytes
     * @return true when the allowance was reserved
     */
    public boolean reserveEpochAllowance(long bytes) {
        if(bytes<0) throw new IllegalArgumentException("Negative epoch allowance");
        lockAll();
        try {
            requireOpen();
            for(Segment segment:segments) if(segment.size!=0 || segment.classHeads!=null)
                throw new IllegalStateException("Epoch allowance requires unconfigured table");
            return reserve(bytes);
        } finally { unlockAll(); }
    }

    /**
     * Reports whether closure has begun under all segment locks.
     * @return true once new operations have been permanently disabled
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Idempotently disables operations, clears the index and retires all versions.
     * Acquires all segments in ascending order and releases in reverse order.
     * Existing leases keep their native versions alive independently of the table.
     * The allocator must remain usable until the last such lease closes.
     * If an allocator retirement unexpectedly fails, remaining entries are still
     * retired and logical metadata released before the first failure propagates.
     */
    @Override
    public void close() {
        lockAll();
        try {
            if (closed) {
                return;
            }
            closed = true;
            Throwable failure = null;
            for (Segment segment : segments) {
                for (int i = 0; i < segment.buckets.length; i++) {
                    Entry entry = segment.buckets[i];
                    segment.buckets[i] = null;
                    while (entry != null) {
                        unlinkClass(segment,entry.item);
                        try {
                            entry.item.retire();
                        } catch (RuntimeException | Error retirementFailure) {
                            if (failure == null) {
                                failure = retirementFailure;
                            }
                        }
                        entry = entry.next;
                    }
                }
                segment.buckets = null;
                segment.size = 0;
                segment.maintenance = null;
                segment.classHeads = null;
                segment.classNumbers = null;
                segment.classBytes = null;
            }
            classGeometryMetadataUsed = 0;
            metadataUsed.set(0);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        } finally {
            unlockAll();
        }
    }

    private boolean reserve(long bytes) {
        long used = metadataUsed.get();
        while (bytes <= metadataLimit - used) {
            if (metadataUsed.compareAndSet(used, used + bytes)) {
                return true;
            }
            used = metadataUsed.get();
        }
        return false;
    }

    /** Returns the fixed number of independently locked segments. */
    public int segmentCount() { return segments.length; }

    /**
     * Initializes separately accounted size-class queues on an empty table.
     * Called once before engine admission; failure leaves all prior state intact.
     * @param classes positive fixed allocator class count
     * @return false if the existing metadata limit cannot admit all queue heads
     */
    public boolean initializeClassQueues(int classes) {
        if (classes <= 0) throw new IllegalArgumentException("Invalid class count");
        lockAll();
        try {
            requireOpen();
            for (Segment segment : segments) {
                if (segment.size != 0 || segment.classHeads != null) throw new IllegalStateException("Queues require empty unconfigured table");
            }
            long charge = Math.addExact(512L + 128L * classes,
                    Math.multiplyExact((long) segments.length,192L+24L*classes));
            if (!reserve(charge)) return false;
            try {
                for (Segment segment : segments) {
                    segment.classHeads = new StoredItem[classes];
                    segment.classNumbers = new long[classes];
                    segment.classBytes = new long[classes];
                }
                return true;
            } catch (RuntimeException | Error failure) {
                for (Segment segment : segments) {
                    segment.classHeads = null; segment.classNumbers = null; segment.classBytes = null;
                }
                metadataUsed.addAndGet(-charge); throw failure;
            }
        } finally { unlockAll(); }
    }

    /** Samples linked class state one segment at a time; no item or lock escapes. */
    public ClassState classState(int classId) {
        long number = 0, requested = 0, oldest = Long.MAX_VALUE;
        for (Segment segment : segments) {
            segment.lock.lock();
            try {
                if (closed) return new ClassState(0, 0, 0);
                Objects.checkIndex(classId, segment.classHeads.length);
                number += segment.classNumbers[classId];
                requested += segment.classBytes[classId];
                StoredItem head = segment.classHeads[classId];
                if (head != null) oldest = Math.min(oldest, head.classPrevious.activityTime());
            } finally { segment.lock.unlock(); }
        }
        return new ClassState(number, requested, number == 0 ? 0 : oldest);
    }

    /** Routes a borrowed key slice without a production key copy. */
    public int segmentId(byte[] key, int offset, int length) {
        return (int) sliceHash(key, offset, length) & (segments.length - 1);
    }

    private long sliceHash(byte[] key, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, key.length);
        if (length < 1 || length > 250) throw new IllegalArgumentException("Invalid key length");
        if (!standardHasher) {
            // The package-private collision seam accepts whole arrays only.
            return hasher.applyAsLong(offset == 0 && length == key.length ? key
                    : Arrays.copyOfRange(key, offset, offset + length));
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = offset; i < offset + length; i++) {
            hash = (hash ^ (key[i] & 255)) * 0x100000001b3L;
        }
        return hash;
    }

    /** Scoped trusted engine work. No I/O, reentry, escaping view or borrowed owner is permitted. */
    @FunctionalInterface
    public interface TransactionWork<T, E extends Exception> {
        /** Executes with every selected segment locked in ascending order. */
        T run(Transaction transaction) throws E;
    }

    /**
     * Runs a scoped transaction over strictly increasing segment IDs. Pending
     * replacements are rolled back unless commit is called; the view is revoked
     * before unlocking. The supplied IDs remain immutable throughout the call.
     */
    public <T, E extends Exception> T transaction(int[] ids, int count,
            TransactionWork<T, E> work) throws E {
        Objects.checkFromIndexSize(0, count, ids.length);
        for (int i = 0; i < count; i++) {
            if (ids[i] < 0 || ids[i] >= segments.length || (i > 0 && ids[i] <= ids[i - 1])) {
                throw new IllegalArgumentException("Segments must be sorted and unique");
            }
        }
        Transaction view = new Transaction(ids, count, -1);
        for (int i = 0; i < count; i++) segments[ids[i]].lock.lock();
        try {
            requireOpen();
            return work.run(view);
        } finally {
            try { view.finish(); }
            finally { for (int i = count - 1; i >= 0; i--) segments[ids[i]].lock.unlock(); }
        }
    }

    /** Runs one-key or one-segment maintenance without allocating an ID array. */
    public <T, E extends Exception> T transaction(int id, TransactionWork<T, E> work) throws E {
        Objects.checkIndex(id, segments.length);
        Transaction view = new Transaction(null, 1, id);
        segments[id].lock.lock();
        try { requireOpen(); return work.run(view); }
        finally { try { view.finish(); } finally { segments[id].lock.unlock(); } }
    }

    /** Runs rare flush/lifecycle boundary work under all existing segment locks. */
    public <T, E extends Exception> T transactionAll(TransactionWork<T, E> work) throws E {
        Transaction view = new Transaction(null, segments.length, -2);
        lockAll();
        try { requireOpen(); return work.run(view); }
        finally { try { view.finish(); } finally { unlockAll(); } }
    }

    /** Revocable, thread-confined borrowed table view; never retain it or its owners. */
    public final class Transaction {
        private final int[] ids;
        private final int count;
        private final int single;
        private final Thread owner = Thread.currentThread();
        private boolean valid = true;
        private Entry prepared;
        private Entry visiting;
        private Entry selected;

        private Transaction(int[] ids, int count, int single) {
            this.ids = ids; this.count = count; this.single = single;
        }

        private void check(int id) {
            if (!valid || owner != Thread.currentThread()) throw new IllegalStateException("Expired transaction");
            if (single != -2 && !(single == id || (ids != null
                    && Arrays.binarySearch(ids, 0, count, id) >= 0))) {
                throw new IllegalArgumentException("Segment outside transaction");
            }
        }

        private Entry entry(byte[] key, int offset, int length) {
            long hash = sliceHash(key, offset, length);
            int id = (int) hash & (segments.length - 1);
            check(id);
            Segment segment = segments[id];
            for (Entry e = segment.buckets[bucket(hash, segment.buckets.length)]; e != null; e = e.next) {
                if (e.hash == hash && e.item.keyEquals(key, offset, length)) return e;
            }
            return null;
        }

        /** Borrows the current owner until this callback ends, without pin allocation. */
        public StoredItem find(byte[] key, int offset, int length) {
            Entry e = entry(key, offset, length);
            return e == null ? null : e.item;
        }

        /** Promotes the exact member to the MRU head under its owning segment lock. */
        public void promote(StoredItem item) {
            if (!valid || owner != Thread.currentThread()) throw new IllegalStateException("Expired transaction");
            Entry entry = (Entry) item.tableEntry;
            if (entry == null || entry.item != item) throw new IllegalArgumentException("Not a mapped owner");
            check((int)entry.hash & (segments.length-1));
            Segment segment = segment(entry.hash);
            unlinkClass(segment,item);
            linkClass(segment,entry,item);
        }

        /**
         * Removes one eligible owner in exact least-recent-first class order.
         * Inspection is bounded; callback true authorizes removal of that owner.
         */
        public boolean removeLeastRecent(int id,int classId,int limit,
                java.util.function.Predicate<StoredItem> eligible) {
            check(id);
            if (limit <= 0) throw new IllegalArgumentException("Nonpositive limit");
            Segment segment = segments[id];
            Objects.checkIndex(classId,segment.classHeads.length);
            StoredItem head = segment.classHeads[classId];
            if (head == null) return false;
            StoredItem item = head.classPrevious;
            for (int inspected = 0; inspected < limit; inspected++) {
                StoredItem previous = item.classPrevious;
                if (eligible.test(item)) { detach((Entry)item.tableEntry); return true; }
                if (item == head) break;
                item = previous;
            }
            return false;
        }

        /**
         * Stages an existing-key replacement without changing membership. Ownership
         * transfers to this transaction; finish retires it on rollback. All key
         * checks and possible exceptions occur before commit.
         */
        public void prepareReplacement(byte[] key, int offset, int length, StoredItem replacement) {
            Entry e = entry(key, offset, length);
            if (e == null || e.pending != null || replacement == e.item
                    || replacement.isRetired() || !replacement.keyEquals(key, offset, length)) {
                throw new IllegalArgumentException("Invalid prepared replacement");
            }
            validateClass(segment(e.hash),replacement);
            e.pending = replacement;
            replacement.preparationLink = prepared;
            prepared = e;
        }

        /** Publishes the entire prepared set without allocations or key reads. */
        public void commit() {
            if (!valid || owner != Thread.currentThread()) throw new IllegalStateException("Expired transaction");
            for (Entry e = prepared; e != null;) {
                Entry next = (Entry) e.pending.preparationLink;
                StoredItem old = e.item;
                Segment segment = segment(e.hash);
                unlinkClass(segment,old);
                e.item = e.pending;
                linkClass(segment,e,e.item);
                e.pending = old;
                old.preparationLink = next;
                e.item.preparationLink = null;
                e = next;
            }
            // Every reference is published before any old owner is retired.
            clearPrepared();
        }

        /** Publishes a single whole-array-key insertion using existing table reservations. */
        public boolean put(byte[] key, StoredItem item) {
            check(segmentId(key, 0, key.length));
            if (prepared != null) throw new IllegalStateException("Pending batch");
            return SegmentedItemTable.this.put(key, item);
        }

        /** Removes a borrowed key slice and retires its owner. */
        public boolean remove(byte[] key, int offset, int length) {
            Entry e = entry(key, offset, length);
            if (e == null) return false;
            detach(e);
            return true;
        }

        /** Selects the currently visited owner for later removal in this callback. */
        public void selectForRemoval(StoredItem item) {
            if (!valid || owner != Thread.currentThread() || visiting == null || visiting.item != item) {
                throw new IllegalStateException("Not the visited owner");
            }
            selected = visiting;
        }

        /** Removes the previously selected member without scanning the maintenance ring. */
        public boolean removeSelected() {
            if (!valid || owner != Thread.currentThread()) throw new IllegalStateException("Expired transaction");
            if (selected == null) return false;
            Entry e = selected; selected = null; detach(e); return true;
        }

        /**
         * Visits at most limit existing owners in a rotating per-segment ring.
         * Returning true removes the visited owner. The predicate is trusted,
         * synchronous and may not reenter the table or retain an owner.
         */
        public int scan(int id, int limit, java.util.function.Predicate<StoredItem> remove) {
            check(id);
            if (limit < 0) throw new IllegalArgumentException("Negative work limit");
            Segment segment = segments[id];
            int visited = (int) Math.min(limit, segment.size);
            for (int i = 0; i < visited && segment.maintenance != null; i++) {
                Entry e = segment.maintenance;
                segment.maintenance = e.maintenanceNext;
                visiting = e;
                try { if (remove.test(e.item)) detach(e); }
                finally { visiting = null; }
            }
            return visited;
        }

        /** Rotates through at most limit members of exactly one size-class queue. */
        public int scanClass(int id, int classId, int limit, java.util.function.Predicate<StoredItem> remove) {
            check(id);
            if (limit < 0) throw new IllegalArgumentException("Negative work limit");
            Segment segment = segments[id];
            Objects.checkIndex(classId,segment.classHeads.length);
            StoredItem first = segment.classHeads[classId];
            if (first == null || limit == 0) return 0;
            StoredItem item = first;
            int visited = 0;
            do {
                StoredItem next = item.classNext;
                segment.classHeads[classId] = next;
                visiting = (Entry) item.tableEntry;
                try { if (remove.test(item)) detach(visiting); }
                finally { visiting = null; }
                visited++;
                item = next;
            } while (visited < limit && segment.classHeads[classId] != null && item != first);
            return visited;
        }

        private void detach(Entry target) {
            if (target.pending != null) throw new IllegalStateException("Prepared target");
            if (selected == target) selected = null;
            Segment segment = segment(target.hash);
            int b = bucket(target.hash, segment.buckets.length);
            Entry previous = null;
            for (Entry e = segment.buckets[b]; e != null; e = e.next) {
                if (e == target) {
                    if (previous == null) segment.buckets[b] = e.next;
                    else previous.next = e.next;
                    unlinkMaintenance(segment, e);
                    unlinkClass(segment,e.item);
                    segment.size--;
                    metadataUsed.addAndGet(-ENTRY_BYTES);
                    e.item.retire();
                    return;
                }
                previous = e;
            }
        }

        private void clearPrepared() {
            while (prepared != null) {
                Entry e = prepared;
                prepared = (Entry) e.pending.preparationLink;
                StoredItem item = e.pending;
                e.pending = null;
                item.preparationLink = null;
                item.retire();
            }
        }

        private void finish() { try { clearPrepared(); } finally { valid = false; } }
    }

    private static void linkMaintenance(Segment segment, Entry entry) {
        Entry head = segment.maintenance;
        if (head == null) {
            entry.maintenanceNext = entry;
            entry.maintenancePrevious = entry;
            segment.maintenance = entry;
        } else {
            entry.maintenanceNext = head;
            entry.maintenancePrevious = head.maintenancePrevious;
            head.maintenancePrevious.maintenanceNext = entry;
            head.maintenancePrevious = entry;
        }
    }

    private static void validateClass(Segment segment,StoredItem item) {
        if (segment.classHeads != null) Objects.checkIndex(item.classId(),segment.classHeads.length);
    }

    private static void linkClass(Segment segment,Entry entry,StoredItem item) {
        if (segment.classHeads == null) return;
        int id = item.classId();
        StoredItem head = segment.classHeads[id];
        segment.classNumbers[id]++;
        segment.classBytes[id] += item.requestedBytes();
        item.tableEntry = entry;
        if (head == null) {
            item.classNext = item; item.classPrevious = item;
            segment.classHeads[id] = item;
        } else {
            item.classNext = head; item.classPrevious = head.classPrevious;
            head.classPrevious.classNext = item; head.classPrevious = item;
            segment.classHeads[id] = item;
        }
    }

    private static void unlinkClass(Segment segment,StoredItem item) {
        if (segment.classHeads == null) return;
        int id = item.classId();
        segment.classNumbers[id]--;
        segment.classBytes[id] -= item.requestedBytes();
        if (item.classNext == item) segment.classHeads[id] = null;
        else {
            item.classPrevious.classNext = item.classNext;
            item.classNext.classPrevious = item.classPrevious;
            if (segment.classHeads[id] == item) segment.classHeads[id] = item.classNext;
        }
        item.classNext = null; item.classPrevious = null; item.tableEntry = null;
    }

    private static void unlinkMaintenance(Segment segment, Entry entry) {
        if (entry.maintenanceNext == entry) segment.maintenance = null;
        else {
            entry.maintenancePrevious.maintenanceNext = entry.maintenanceNext;
            entry.maintenanceNext.maintenancePrevious = entry.maintenancePrevious;
            if (segment.maintenance == entry) segment.maintenance = entry.maintenanceNext;
        }
        entry.maintenanceNext = null;
        entry.maintenancePrevious = null;
    }

    private Segment segment(long hash) {
        return segments[(int) hash & (segments.length - 1)];
    }

    private int bucket(long hash, int capacity) {
        return (int) (hash >>> Integer.numberOfTrailingZeros(segments.length)) & (capacity - 1);
    }

    private static long arrayBytes(int capacity) {
        return 64L + 8L * capacity;
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private static void validateKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < 1 || key.length > 250) {
            throw new IllegalArgumentException("Key length must be 1..250");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Table is closed");
        }
    }

    private void lockAll() {
        for (Segment segment : segments) {
            segment.lock.lock();
        }
    }

    private void unlockAll() {
        for (int i = segments.length - 1; i >= 0; i--) {
            segments[i].lock.unlock();
        }
    }

    /** Trusted package-private preparation-failure seam for independent tests. */
    @FunctionalInterface
    interface AllocationHook {
        /** Runs before a put metadata allocation, while its segment is locked. */
        void beforeAllocation();
    }

    /** Membership and chain links are guarded exclusively by this segment lock. */
    private static final class Segment {
        private final ReentrantLock lock = new ReentrantLock();
        private Entry[] buckets;
        private long size;
        private Entry maintenance;
        private StoredItem[] classHeads;
        private long[] classNumbers;
        private long[] classBytes;

        private Segment(int capacity) {
            buckets = new Entry[capacity];
        }
    }

    /** Fixed-size index metadata, never a second heap store for record bytes. */
    private static final class Entry {
        private final long hash;
        private StoredItem item;
        private Entry next;
        private Entry maintenanceNext;
        private Entry maintenancePrevious;
        private StoredItem pending;

        private Entry(long hash, StoredItem item) {
            this.hash = hash;
            this.item = item;
        }
    }
}
