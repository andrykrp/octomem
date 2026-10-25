package ru.octomem.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one immutable native record and issues independently revocable leased views.
 * The single block contains a 32-byte little-endian header, key, value and
 * eight-byte alignment. Only primitive version metadata is duplicated on heap;
 * neither caller array is retained. Activity and pin state are separate metadata.
 *
 * <p>All reads, pins, retirement and releases serialize on the private record
 * lock, acquired before any allocator lock. No allocator callback may acquire
 * this record lock. A caller publishing the owner to a table must use the table's
 * synchronization; initialization completes before this factory returns.
 * Each lease and its view are owner-thread confined; separate leases may be used
 * by separate threads. Retirement denies new pins but preserves existing views.
 *
 * @since 1.0
 */
public final class StoredItem {
    private static final int HEADER_BYTES = 32;
    private final Object lock = new Object();
    private final MemoryAllocator allocator;
    private final SlabHandle handle;
    private final int keyLength;
    private final int valueLength;
    private final int flags;
    private final long expiryTime;
    private final boolean hasExpiry;
    private final long casId;
    private final int allocatedBytes;
    // Reused only under lock; no per-lookup key array or buffer is allocated.
    private final ByteBuffer keyScratch = ByteBuffer.allocate(Long.BYTES);
    private int pins;
    private boolean retired;
    private boolean reclaimed;
    private boolean active;
    private long createdAt;
    private long generation;
    private volatile long activityOrder;
    private volatile long activityTime;
    // Intrusive transaction linkage belongs to unpublished record metadata.
    Object preparationLink;
    StoredItem classNext;
    StoredItem classPrevious;
    Object tableEntry;
    private final int classId;
    private ServiceBudget serviceBudget;
    private int numericLength;
    // Meta coordination state: caller must own the item's segment transaction.
    private boolean metaStale;
    private boolean metaWinner;
    private boolean binaryKey;

    /** Returns stale state under the caller-owned segment transaction. */
    public boolean metaStale() { return metaStale; }
    /** Returns recache winner state under the caller-owned segment transaction. */
    public boolean metaWinner() { return metaWinner; }
    /** Returns whether a key requires base64 reflection in meta responses. */
    public boolean binaryKey() { return binaryKey; }
    /** Updates coordination metadata under the owning segment lock, never value bytes. */
    public void metaState(boolean stale, boolean winner, boolean binary) {
        metaStale = stale; metaWinner = winner; binaryKey = binary;
    }

    /** Typed native admission result; service denial is not native out-of-memory. */
    public record ReservationResult(Reservation reservation, boolean nativeDenied) { }

    /** Owns an actual unpublished block and one service charge until transfer/cancel. */
    public static final class Reservation implements AutoCloseable {
        private final MemoryAllocator allocator;
        private final ServiceBudget budget;
        private SlabHandle block;
        private final int keyLength;
        private final int valueLength;
        private Reservation(MemoryAllocator allocator, ServiceBudget budget, SlabHandle block, int keyLength, int valueLength) {
            this.allocator=allocator; this.budget=budget; this.block=block;
            this.keyLength=keyLength; this.valueLength=valueLength;
        }
        /** Returns the admitted allocation class while this reservation still owns it.
         * @return immutable class identifier of the reserved native block
         * @throws IllegalStateException after transfer or cancellation
         */
        public synchronized int classId(){
            if(block==null)throw new IllegalStateException("Reservation no longer owns a block");
            return block.classId();
        }
        /** Fills exactly the admitted block, transferring ownership only on success. */
        public synchronized StoredItem finish(byte[] key, ValueSource source, ByteBuffer scratch,
                int flags, boolean expires, long expiry, long cas) {
            return finish(key,0,key.length,source,scratch,flags,expires,expiry,cas);
        }
        /** Fills from a borrowed key slice without materializing snapshot key copies. */
        public synchronized StoredItem finish(byte[] key,int offset,int length,ValueSource source,ByteBuffer scratch,
                int flags,boolean expires,long expiry,long cas) {
            Objects.checkFromIndexSize(offset,length,key.length);
            if (block == null || length != keyLength) throw new IllegalStateException("Invalid reservation");
            scratch.clear().limit(32); scratch.order(ByteOrder.LITTLE_ENDIAN);
            scratch.putInt(flags).putInt(keyLength).putInt(valueLength).putInt(0).putLong(expiry).putLong(cas).flip();
            if (block.write(0,scratch)!=32) throw new IllegalStateException("Incomplete header");
            if (block.write(32,ByteBuffer.wrap(key,offset,length))!=keyLength) throw new IllegalStateException("Incomplete key");
            for(int position=0;position<valueLength;) {
                int count=Math.min(scratch.capacity(),valueLength-position);
                scratch.clear().limit(count); source.read(position,scratch);
                if(scratch.position()!=count) throw new IllegalStateException("Incomplete value");
                scratch.flip();
                if(block.write(32+keyLength+position,scratch)!=count) throw new IllegalStateException("Incomplete write");
                position+=count;
            }
            StoredItem item=new StoredItem(allocator,block,keyLength,valueLength,flags,expires,expiry,cas);
            item.serviceBudget=budget; block=null; return item;
        }
        @Override public synchronized void close() {
            if(block!=null) { SlabHandle owned=block; block=null;
                try { allocator.free(owned); } finally { budget.used.addAndGet(-ServiceBudget.CHARGE); }
            }
        }
    }

    /** Admits native capacity first, then one service charge; either denial rolls back. */
    public static ReservationResult reserve(ServiceBudget budget, MemoryAllocator allocator,
            int keyLength, int valueLength, int maximum) {
        long size=(32L+keyLength+valueLength+7)&~7L;
        if(keyLength<1 || keyLength>250 || valueLength<0 || size>maximum) throw new IllegalArgumentException("Invalid reservation size");
        SlabHandle block=allocator.allocate((int)size);
        if(block==null) return new ReservationResult(null,true);
        boolean charged=false, transferred=false;
        try {
            charged=budget.reserve();
            if(!charged) return new ReservationResult(null,false);
            ReservationResult result=new ReservationResult(new Reservation(allocator,budget,block,keyLength,valueLength),false);
            transferred=true; return result;
        } finally {
            if(!transferred) { allocator.free(block); if(charged) budget.used.addAndGet(-ServiceBudget.CHARGE); }
        }
    }

    /** Internal generated decimal prefix length, zero for ordinary strict user values. */
    public int numericLength() { return numericLength; }
    /** Sets generated padding interpretation only before publication/pinning. */
    public void initializeNumericLength(int length) {
        synchronized(lock) {
            if(pins!=0 || retired || tableEntry!=null || length<0 || length>valueLength) throw new IllegalStateException("Invalid numeric marker");
            numericLength=length;
        }
    }

    /** Separate logical heap admission for engine-owned record service graphs. */
    public static final class ServiceBudget {
        /** Conservative per-version charge, independent of native block bytes. */
        public static final long CHARGE = 512;
        private final long limit;
        private final AtomicLong used = new AtomicLong();
        private final Runnable reserveAttemptHook;
        /** Creates a checked-counter budget with a nonnegative derived ceiling. */
        public ServiceBudget(long limit) {
            this(limit,()->{ });
        }
        /** Trusted package-local first-attempt contention seam for independent tests. */
        ServiceBudget(long limit,Runnable reserveAttemptHook) {
            if (limit < 0) throw new IllegalArgumentException("Negative service limit");
            this.limit = limit;
            this.reserveAttemptHook = Objects.requireNonNull(reserveAttemptHook,"reserveAttemptHook");
        }
        private boolean reserve() {
            long current = used.get();
            while (CHARGE <= limit-current) {
                reserveAttemptHook.run();
                if (used.compareAndSet(current,current+CHARGE)) return true;
                current = used.get();
            }
            return false;
        }
        /** Returns pending, live and retained-retired service charges. */
        public long usedBytes() { return used.get(); }
    }

    /** Pre-admits one complete record service graph before any native allocation. */
    public static StoredItem create(ServiceBudget budget,MemoryAllocator allocator, byte[] key,int offset,
            int length,int valueLength,ValueSource source,ByteBuffer scratch,int flags,
            boolean hasExpiry,long expiry,long cas,int maximum) {
        Objects.requireNonNull(budget,"budget");
        if (!budget.reserve()) return null;
        boolean transferred = false;
        try {
            StoredItem item = create(allocator,key,offset,length,valueLength,source,scratch,flags,hasExpiry,expiry,cas,maximum);
            if (item != null) { item.serviceBudget=budget; transferred=true; }
            return item;
        } finally { if (!transferred) budget.used.addAndGet(-ServiceBudget.CHARGE); }
    }

    private StoredItem(MemoryAllocator allocator, SlabHandle handle, int keyLength,
                       int valueLength, int flags, long expiryTime, long casId) {
        this(allocator,handle,keyLength,valueLength,flags,expiryTime != 0,expiryTime,casId);
    }

    private StoredItem(MemoryAllocator allocator, SlabHandle handle, int keyLength,
                       int valueLength, int flags, boolean hasExpiry,long expiryTime, long casId) {
        this.allocator = allocator;
        this.handle = handle;
        this.keyLength = keyLength;
        this.valueLength = valueLength;
        this.flags = flags;
        this.expiryTime = expiryTime;
        this.hasExpiry = hasExpiry;
        this.casId = casId;
        this.allocatedBytes = handle.capacity();
        this.classId = handle.classId();
    }

    /**
     * Prepares a complete immutable version without retaining or modifying inputs.
     * Input arrays must not be mutated concurrently during this call. Size checks
     * include header, key, value and alignment, before requesting allocation.
     * Preparation failures free the unpublished block and propagate; they are not
     * disguised as capacity denials or a promise of recovery from arbitrary OOME.
     *
     * @param allocator allocator that will own the complete block
     * @param key key containing 1..250 bytes
     * @param value value bytes, possibly empty
     * @param flags opaque unsigned 32-bit flags
     * @param expiryTime normalized Unix-seconds deadline, or zero for no expiry
     * @param casId opaque unsigned 64-bit version token
     * @param itemMaxSize positive maximum complete aligned record size
     * @return new owner, or null for an oversized record or allocation denial
     * @throws NullPointerException if allocator, key or value is null
     * @throws IllegalArgumentException if key length, expiry or maximum is invalid
     */
    public static StoredItem create(MemoryAllocator allocator, byte[] key, byte[] value,
                                    int flags, long expiryTime, long casId, int itemMaxSize) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.length < 1 || key.length > 250 || expiryTime < 0 || itemMaxSize <= 0) {
            throw new IllegalArgumentException("Invalid record parameters");
        }
        long size = (HEADER_BYTES + (long) key.length + value.length + 7) & ~7L;
        if (size > itemMaxSize || size > Integer.MAX_VALUE) {
            return null;
        }
        SlabHandle handle = allocator.allocate((int) size);
        if (handle == null) {
            return null;
        }
        try {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(flags).putInt(key.length).putInt(value.length).putInt(0)
                    .putLong(expiryTime).putLong(casId).flip();
            if (handle.write(0, header) != HEADER_BYTES
                    || handle.write(HEADER_BYTES, ByteBuffer.wrap(key)) != key.length
                    || handle.write(HEADER_BYTES + key.length, ByteBuffer.wrap(value)) != value.length) {
                throw new IllegalStateException("Incomplete record initialization");
            }
            return new StoredItem(allocator, handle, key.length, value.length,
                    flags, expiryTime, casId);
        } catch (RuntimeException | Error failure) {
            try {
                allocator.free(handle);
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Prepares an immutable replacement from bounded streaming input. The source
     * is borrowed only during this call; it must fill each requested slice and
     * must not perform I/O or reenter the table. Scratch is caller-owned and may
     * be reused across every replacement in a transaction.
     * @param allocator block owner
     * @param key borrowed key backing
     * @param offset key offset
     * @param length key length
     * @param valueLength exact value length
     * @param source bounded value producer
     * @param scratch nonempty caller-owned copy buffer
     * @param flags unsigned flags
     * @param expiry normalized deadline
     * @param cas version bits
     * @param maximum maximum aligned record size
     * @return unpublished owner, or null on capacity denial
     */
    public static StoredItem create(MemoryAllocator allocator, byte[] key, int offset,
            int length, int valueLength, ValueSource source, ByteBuffer scratch,
            int flags, long expiry, long cas, int maximum) {
        if (expiry < 0) throw new IllegalArgumentException("Negative legacy deadline");
        return create(allocator,key,offset,length,valueLength,source,scratch,flags,expiry != 0,expiry,cas,maximum);
    }

    /** Prepares a version with explicit expiry presence and a signed Unix deadline. */
    public static StoredItem create(MemoryAllocator allocator, byte[] key, int offset,
            int length, int valueLength, ValueSource source, ByteBuffer scratch,
            int flags, boolean hasExpiry,long expiry, long cas, int maximum) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(scratch, "scratch");
        Objects.checkFromIndexSize(offset, length, key.length);
        if (length < 1 || length > 250 || valueLength < 0
                || maximum <= 0 || scratch.capacity() < 32 || scratch.isReadOnly()) {
            throw new IllegalArgumentException("Invalid streaming record parameters");
        }
        long size = (32L + length + valueLength + 7) & ~7L;
        if (size > maximum || size > Integer.MAX_VALUE) return null;
        SlabHandle block = allocator.allocate((int) size);
        if (block == null) return null;
        try {
            scratch.clear().limit(32);
            scratch.order(ByteOrder.LITTLE_ENDIAN);
            scratch.putInt(flags).putInt(length).putInt(valueLength).putInt(0)
                    .putLong(expiry).putLong(cas).flip();
            if (block.write(0, scratch) != 32) {
                throw new IllegalStateException("Incomplete record header");
            }
            for (int position = 0; position < length;) {
                int count = Math.min(scratch.capacity(),length-position);
                scratch.clear().put(key,offset+position,count).flip();
                if (block.write(32+position,scratch) != count) throw new IllegalStateException("Incomplete key");
                position += count;
            }
            for (int position = 0; position < valueLength;) {
                int count = Math.min(scratch.capacity(), valueLength - position);
                scratch.clear().limit(count);
                source.read(position, scratch);
                if (scratch.position() != count) throw new IllegalStateException("Incomplete source");
                scratch.flip();
                if (block.write(32 + length + position, scratch) != count) {
                    throw new IllegalStateException("Incomplete record value");
                }
                position += count;
            }
            return new StoredItem(allocator, block, length, valueLength, flags, hasExpiry,expiry, cas);
        } catch (RuntimeException | Error failure) {
            allocator.free(block);
            throw failure;
        } finally {
            scratch.clear();
        }
    }

    /** Bounded, synchronous source used only while preparing an unpublished record. */
    @FunctionalInterface
    public interface ValueSource {
        /** Fills the requested remaining bytes at the specified value offset. */
        void read(int offset, ByteBuffer destination);
    }

    /** Copies bytes while the caller holds table membership or an existing pin. */
    public int copyValue(int offset, ByteBuffer destination) {
        synchronized (lock) {
            if (reclaimed) throw new IllegalStateException("Record reclaimed");
            Objects.checkIndex(offset, (long) valueLength + 1);
            int count = Math.min(destination.remaining(), valueLength - offset);
            int limit = destination.limit();
            try {
                destination.limit(destination.position() + count);
                return handle.read(HEADER_BYTES + keyLength + offset, destination);
            } finally {
                destination.limit(limit);
            }
        }
    }

    /** Returns immutable flags, including after retirement. */
    public int flags() { return flags; }
    /** Returns immutable normalized expiry. */
    public long expiryTime() { return expiryTime; }
    /** Returns immutable deadline presence, including epoch zero. */
    public boolean hasExpiry() { return hasExpiry; }
    /** Returns immutable version bits. */
    public long casId() { return casId; }
    /** Returns immutable value length. */
    public int valueLength() { return valueLength; }
    /** Returns the size-class identifier for maintenance selection. */
    public int classId() { return classId; }
    /** Returns the publication clock supplied before ownership transfer. */
    public long createdAt() { return createdAt; }
    /** Returns the flush generation supplied before ownership transfer. */
    public long generation() { return generation; }
    /**
     * Initializes engine visibility metadata before publication, exactly once.
     * The caller must exclusively own this unpublished record.
     */
    public void initializeVisibility(long time, long epoch) {
        synchronized (lock) {
            if (generation != 0 || retired || pins != 0 || epoch == 0) {
                throw new IllegalStateException("Visibility already initialized");
            }
            createdAt = time;
            generation = epoch;
        }
    }
    /** Records approximate recency separately from immutable version data. */
    public void recordActivity(long order, boolean fetched) {
        synchronized (lock) {
            activityOrder = order;
            active |= fetched;
        }
    }
    /** Records LRU activity with a clock sample, without changing native version bytes. */
    public void recordActivity(long order, long time, boolean fetched) {
        synchronized (lock) {
            activityOrder = order; activityTime = time; active |= fetched;
        }
    }
    /** Returns the last logical LRU activity time under table membership protection. */
    public long activityTime() { return activityTime; }
    /** Returns aligned requested native record bytes, excluding size-class slack. */
    public int requestedBytes() { return (HEADER_BYTES + keyLength + valueLength + 7) & ~7; }
    /** Returns recency metadata for bounded maintenance under table locks. */
    public long activityOrder() { return activityOrder; }
    /** Returns whether this version was fetched. */
    public boolean wasFetched() { synchronized (lock) { return active; } }

    /**
     * Acquires an independent pin after allocating its lease and private view.
     * @return a new lease, or null if this owner is retired
     * @throws IllegalStateException if the pin count cannot be represented
     */
    public ItemLease pin() {
        synchronized (lock) {
            if (retired) {
                return null;
            }
            if (pins == Integer.MAX_VALUE) {
                throw new IllegalStateException("Pin count exhausted");
            }
            Lease lease = new Lease();
            pins++;
            return lease;
        }
    }

    /** Denies new pins and frees the block when no pins remain; idempotent. */
    public void retire() {
        synchronized (lock) {
            retired = true;
            reclaimIfUnpinned();
        }
    }

    /**
     * Reports retirement, independently of whether pinned readers remain.
     * @return true once retirement has occurred
     */
    public boolean isRetired() {
        synchronized (lock) {
            return retired;
        }
    }

    /**
     * Captures the current number of independently owned pins for diagnostics.
     * @return nonnegative pin count
     */
    public int pinCount() {
        synchronized (lock) {
            return pins;
        }
    }

    /**
     * Reports the allocator size-class capacity, including any class slack.
     * This diagnostic remains valid after reclamation; it is not live accounting.
     * @return positive capacity originally charged for this block
     */
    public int allocatedBytes() {
        return allocatedBytes;
    }

    /**
     * Compares a borrowed key slice without allocating a key copy or lookup view.
     * The owner may compare before retirement or while existing pins retain it;
     * the result does not acquire a pin or promise later liveness. The candidate
     * must remain unchanged during this call and is never retained or modified.
     * The allocator may use its own bounded temporary copy wrappers.
     *
     * @param candidate borrowed array containing the key slice
     * @param offset starting byte offset
     * @param length slice length
     * @return true if the slice equals the stored key byte for byte
     * @throws NullPointerException if candidate is null
     * @throws IndexOutOfBoundsException if the slice is invalid
     * @throws IllegalStateException if the block has been reclaimed
     */
    public boolean keyEquals(byte[] candidate, int offset, int length) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.checkFromIndexSize(offset, length, candidate.length);
        synchronized (lock) {
            if (reclaimed) {
                throw new IllegalStateException("Record has been reclaimed");
            }
            if (length != keyLength) {
                return false;
            }
            try {
            for (int index = 0; index < length;) {
                int count = Math.min(keyScratch.capacity(), length - index);
                keyScratch.clear().limit(count);
                if (handle.read(HEADER_BYTES + index, keyScratch) != count) {
                    throw new IllegalStateException("Incomplete key read");
                }
                for (int i = 0; i < count; i++) {
                    if (keyScratch.get(i) != candidate[offset + index + i]) {
                        return false;
                    }
                }
                index += count;
            }
            return true;
            } finally {
                keyScratch.clear();
                while (keyScratch.hasRemaining()) {
                    keyScratch.put((byte) 0);
                }
                keyScratch.clear();
            }
        }
    }

    private void reclaimIfUnpinned() {
        if (retired && pins == 0 && !reclaimed) {
            reclaimed = true;
            allocator.free(handle);
            if (serviceBudget != null) serviceBudget.used.addAndGet(-ServiceBudget.CHARGE);
        }
    }

    private final class Lease implements ItemLease {
        private final Item view = new View(this);
        private boolean closed;

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Lease is closed");
            }
        }

        /** {@inheritDoc} */
        @Override
        public Item item() {
            synchronized (lock) {
                requireOpen();
                return view;
            }
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            synchronized (lock) {
                if (!closed) {
                    closed = true;
                    pins--;
                    reclaimIfUnpinned();
                }
            }
        }
    }

    private final class View implements Item {
        @Override public boolean hasExpiry() {
            synchronized (lock) { lease.requireOpen(); return hasExpiry; }
        }
        private final Lease lease;

        private View(Lease lease) {
            this.lease = lease;
        }

        /** {@inheritDoc} */
        @Override
        public byte[] key() {
            synchronized (lock) {
                lease.requireOpen();
                byte[] copy = new byte[keyLength];
                if (handle.read(HEADER_BYTES, ByteBuffer.wrap(copy)) != keyLength) {
                    throw new IllegalStateException("Incomplete key read");
                }
                return copy;
            }
        }

        /** {@inheritDoc} */
        @Override
        public int flags() {
            synchronized (lock) {
                lease.requireOpen();
                return flags;
            }
        }

        /** {@inheritDoc} */
        @Override
        public long expiryTime() {
            synchronized (lock) {
                lease.requireOpen();
                return expiryTime;
            }
        }

        /** {@inheritDoc} */
        @Override
        public long casId() {
            synchronized (lock) {
                lease.requireOpen();
                return casId;
            }
        }

        /** {@inheritDoc} */
        @Override
        public int valueLength() {
            synchronized (lock) {
                lease.requireOpen();
                return valueLength;
            }
        }

        /** {@inheritDoc} */
        @Override
        public int readValue(long offset, ByteBuffer dst) {
            synchronized (lock) {
                lease.requireOpen();
                Objects.requireNonNull(dst, "dst");
                Objects.checkIndex(offset, (long) valueLength + 1);
                if (dst.isReadOnly()) {
                    throw new ReadOnlyBufferException();
                }
                int count = (int) Math.min(dst.remaining(), valueLength - offset);
                if (count == 0) {
                    return 0;
                }
                int limit = dst.limit();
                try {
                    dst.limit(dst.position() + count);
                    return handle.read(HEADER_BYTES + keyLength + (int) offset, dst);
                } finally {
                    dst.limit(limit);
                }
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean isExpired(long now) {
            synchronized (lock) {
                lease.requireOpen();
                return hasExpiry && expiryTime <= now;
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean isActive() {
            synchronized (lock) {
                lease.requireOpen();
                return active;
            }
        }

        /** {@inheritDoc} */
        @Override
        public void markActive() {
            synchronized (lock) {
                lease.requireOpen();
                active = true;
            }
        }
    }
}
