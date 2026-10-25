package ru.octomem.storage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.Objects;
import ru.octomem.stats.SlabClassStats;

/**
 * Bounded, reusable size-class pages backed by standard shared FFM arenas.
 * Each page owns one arena; neither native segments nor addresses escape.
 * All lifecycle and handle operations serialize on one allocator lock.
 * Callers must exclusively own buffer positions during a copy; no external I/O
 * is performed under this lock.
 *
 * <p>Free invalidates every operation on that handle. Empty pages remain pooled
 * and charged until {@link #trim()} or {@link #close()}. Close stops allocation,
 * but outstanding handles remain readable and writable until explicitly freed;
 * their pages close after the last block is freed. Close is idempotent.
 *
 * <p>This allocator does not implement item publication or leases. The item layer
 * must prohibit writes after publication and defer free until all references
 * are gone. Physical page bytes are bounded by {@link #memoryLimit}; each page
 * also holds a bounded free-slot stack on the Java heap whose exact per-page and
 * worst-case size is exposed by {@link #pageMetadataHeapBytes(int)} and
 * {@link #maximumPageMetadataHeapBytes()}. Process RSS is not bounded.
 * ADR-017 selects this backend for controlled lifetime, not proven performance.
 *
 * @since 1.0
 */
public final class FfmSlabAllocator implements MemoryAllocator, AutoCloseable {
    /**
     * Conservative HotSpot array header for the per-page free-slot stack in
     * either pointer mode; the measured header is 16 bytes compressed and
     * 24 bytes uncompressed.
     */
    private static final long FREE_SLOT_ARRAY_HEADER_BYTES = 24;
    /**
     * Per-page allowance covering the {@link Page} node, its native
     * {@code MemorySegment} wrapper and alignment. The measured residual over
     * the free-slot array is at most 140 bytes for one 1 MiB page; rounded up.
     */
    private static final long PAGE_SUPPORT_HEAP_BYTES = 256;

    private final Object lock = new Object();
    private final long memoryLimit;
    private final int pageSize;
    private final int maxBlockSize;
    private final int[] sizes;
    private final Page[] pages;
    private final ArenaFactory arenaFactory;
    private long totalMemory;
    private long usedMemory;
    private boolean closed;

    /**
     * Creates an initially empty allocator with stable, zero-based size classes.
     * Classes start at 64; each next class rounds ceil(previous * 1.25) up to
     * eight bytes, capped at the eight-byte-rounded maximum. Requests above the
     * original maximum are denied even if they fit the rounded terminal class.
     * Pages reserve exactly pageSize bytes, or exactly the rounded block size
     * when larger; unused page tails remain charged but are not chunks.
     *
     * <p>These are internal allocator constraints, not public configuration
     * restrictions: the engine must translate its public item-size policy into
     * an effective allocator maximum including record metadata.
     *
     * @param memoryLimit positive maximum physical page bytes
     * @param pageSize positive physical page size, not necessarily aligned
     * @param maxBlockSize internal request ceiling in [64, 2147483640]
     * @throws IllegalArgumentException if a parameter is outside its range
     */
    public FfmSlabAllocator(long memoryLimit, int pageSize, int maxBlockSize) {
        this(memoryLimit, pageSize, maxBlockSize, Arena::ofShared);
    }

    /**
     * Test-only factory returning fresh, exclusively owned shared arenas.
     * Callbacks run under the allocator lock and must not reenter the allocator.
     */
    interface ArenaFactory {
        Arena create();

        /** Injects preparation failure after native allocation, before publication. */
        default void beforePublish() { }
    }

    FfmSlabAllocator(long memoryLimit, int pageSize, int maxBlockSize,
                     ArenaFactory arenaFactory) {
        if (memoryLimit <= 0 || pageSize <= 0 || maxBlockSize < 64
                || maxBlockSize > Integer.MAX_VALUE - 7) {
            throw new IllegalArgumentException("Invalid allocator limits");
        }
        this.memoryLimit = memoryLimit;
        this.pageSize = pageSize;
        this.maxBlockSize = maxBlockSize;
        this.arenaFactory = Objects.requireNonNull(arenaFactory, "arenaFactory");
        int maximum = (maxBlockSize + 7) & ~7;
        int count = 1;
        for (int size = 64; size < maximum; size = nextSize(size, maximum)) {
            count++;
        }
        sizes = new int[count];
        sizes[0] = 64;
        for (int i = 1; i < count; i++) {
            sizes[i] = nextSize(sizes[i - 1], maximum);
        }
        pages = new Page[count];
    }

    private static int nextSize(int size, int maximum) {
        long grown = (size * 5L + 3) / 4;
        return (int) Math.min((grown + 7) & ~7L, maximum);
    }

    /**
     * {@inheritDoc}
     * Returns null when closed, over the request ceiling, or over the native
     * budget. Nonpositive sizes always throw, including after close.
     * Preparation failures roll back ownership and propagate; arbitrary JVM
     * allocation errors are not disguised as budget denials.
     */
    @Override
    public SlabHandle allocate(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive");
        }
        synchronized (lock) {
            if (closed || size > maxBlockSize) {
                return null;
            }
            int found = Arrays.binarySearch(sizes, size);
            int classId = found >= 0 ? found : -found - 1;
            int capacity = sizes[classId];
            for (Page page = pages[classId]; page != null; page = page.next) {
                if (page.used < page.freeSlots.length) {
                    int slot = page.freeCount == 0 ? page.nextUnused
                            : page.freeSlots[page.freeCount - 1];
                    Handle handle = new Handle(page, slot);
                    if (page.freeCount == 0) {
                        page.nextUnused++;
                    } else {
                        page.freeCount--;
                    }
                    page.used++;
                    usedMemory += capacity;
                    return handle;
                }
            }
            long bytes = Math.max(pageSize, capacity);
            if (bytes > memoryLimit - totalMemory) {
                return null;
            }
            // Allocate the largest heap metadata before taking native ownership.
            int[] freeSlots = new int[(int) (bytes / capacity)];
            Arena arena = Objects.requireNonNull(arenaFactory.create(), "arena");
            try {
                MemorySegment memory = arena.allocate(bytes, 8);
                Page page = new Page(classId, arena, memory, freeSlots);
                Handle handle = new Handle(page, 0);
                arenaFactory.beforePublish();
                // Everything that can allocate or invoke a hook precedes commit.
                page.used = 1;
                page.nextUnused = 1;
                page.next = pages[classId];
                pages[classId] = page;
                totalMemory += bytes;
                usedMemory += capacity;
                return handle;
            } catch (RuntimeException | Error failure) {
                try {
                    arena.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
    }

    /** {@inheritDoc} Item publication and pin eligibility are the caller's responsibility. */
    @Override
    public void free(SlabHandle handle) {
        Objects.requireNonNull(handle, "handle");
        synchronized (lock) {
            if (!(handle instanceof Handle owned) || owned.owner != this || !owned.live) {
                throw new IllegalArgumentException("Foreign or freed handle");
            }
            Page page = owned.page;
            page.freeSlots[page.freeCount++] = owned.slot;
            page.used--;
            usedMemory -= sizes[page.classId];
            owned.live = false;
            if (closed && page.used == 0) {
                releaseEmptyPages();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public long totalMemory() {
        synchronized (lock) {
            return totalMemory;
        }
    }

    /** {@inheritDoc} */
    @Override
    public long usedMemory() {
        synchronized (lock) {
            return usedMemory;
        }
    }

    /** {@inheritDoc} Class identifiers and sizes never change, including after close. */
    @Override
    public int classCount() {
        return sizes.length;
    }

    /** {@inheritDoc} */
    @Override
    public SlabClassStats classStats(int classId) {
        if (classId < 0 || classId >= sizes.length) {
            throw new IllegalArgumentException("Unknown size class");
        }
        synchronized (lock) {
            long pageCount = 0;
            long chunks = 0;
            long used = 0;
            for (Page page = pages[classId]; page != null; page = page.next) {
                pageCount++;
                chunks += page.freeSlots.length;
                used += page.used;
            }
            return new ClassStats(classId, sizes[classId], pageCount, chunks, used, chunks - used);
        }
    }

    /**
     * Returns the exact heap bytes of one page's free-slot stack for a size
     * class, including a conservative array header and the per-page node
     * allowance. Every native page holds one such stack, so this is a heap
     * charge in addition to the native page bytes counted by
     * {@link #totalMemory()}.
     * @param classId identifier of an existing class
     * @return positive per-page heap metadata bytes
     * @throws IllegalArgumentException if the identifier is unknown
     */
    public long pageMetadataHeapBytes(int classId) {
        if (classId < 0 || classId >= sizes.length) {
            throw new IllegalArgumentException("Unknown size class");
        }
        long pageBytes = Math.max(pageSize, sizes[classId]);
        long freeSlots = pageBytes / sizes[classId];
        return FREE_SLOT_ARRAY_HEADER_BYTES + 4L * freeSlots + PAGE_SUPPORT_HEAP_BYTES;
    }

    /**
     * Returns the maximum number of native pages reachable at the configured
     * memory limit. Every page reserves at least {@code max(pageSize, 64)}
     * native bytes, so no more pages can fit within {@code memoryLimit}.
     * @return nonnegative maximum page count
     */
    public long maximumPageCount() {
        return memoryLimit / Math.max(pageSize, sizes[0]);
    }

    /**
     * Returns the bounded worst-case heap bytes of all per-page free-slot
     * metadata at the configured memory limit: the maximum page count times the
     * largest per-page metadata. The smallest class (64 bytes) always holds the
     * largest free-slot stack, so its per-page size is the maximum over classes.
     * The free-slot data term is at most {@code memoryLimit/16}; the array
     * headers and per-page node allowance add a small bounded term. Compositions
     * include this value in their fixed heap metadata baseline.
     * @return nonnegative bounded heap metadata bytes
     */
    public long maximumPageMetadataHeapBytes() {
        return maximumPageCount() * pageMetadataHeapBytes(0);
    }

    /**
     * Closes only empty page arenas, without moving or invalidating live blocks.
     * This releases allocator ownership, not a guarantee of immediate RSS reduction.
     * @return physical page bytes released; zero when no page is empty
     */
    public long trim() {
        synchronized (lock) {
            return releaseEmptyPages();
        }
    }

    /**
     * Returns whether admission has stopped, not whether all arenas are closed.
     * @return true after close has been called
     */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Stops allocation and releases empty pages; outstanding blocks survive until free. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            releaseEmptyPages();
        }
    }

    private long releaseEmptyPages() {
        long released = 0;
        for (int i = 0; i < pages.length; i++) {
            Page previous = null;
            Page page = pages[i];
            while (page != null) {
                Page next = page.next;
                if (page.used == 0) {
                    long bytes = page.memory.byteSize();
                    page.arena.close();
                    if (previous == null) {
                        pages[i] = next;
                    } else {
                        previous.next = next;
                    }
                    totalMemory -= bytes;
                    released += bytes;
                } else {
                    previous = page;
                }
                page = next;
            }
        }
        return released;
    }

    /** Intrusive page node with an allocation-free returned-slot stack. */
    private static final class Page {
        private final int classId;
        private final Arena arena;
        private final MemorySegment memory;
        private final int[] freeSlots;
        private Page next;
        private int nextUnused;
        private int freeCount;
        private int used;

        private Page(int classId, Arena arena, MemorySegment memory, int[] freeSlots) {
            this.classId = classId;
            this.arena = arena;
            this.memory = memory;
            this.freeSlots = freeSlots;
        }
    }

    /** Immutable coherent statistics detached from mutable page state. */
    private record ClassStats(int classId, int chunkSize, long totalPages,
                              long totalChunks, long usedChunks, long freeChunks)
            implements SlabClassStats { }

    /** An exclusive block token; its liveness and all copies use the owner lock. */
    private final class Handle implements SlabHandle {
        private final FfmSlabAllocator owner = FfmSlabAllocator.this;
        private final Page page;
        private final int slot;
        private boolean live = true;

        private Handle(Page page, int slot) {
            this.page = page;
            this.slot = slot;
        }

        private void requireLive() {
            if (!live) {
                throw new IllegalStateException("Handle has been freed");
            }
        }

        @Override
        public int classId() {
            synchronized (lock) {
                requireLive();
                return page.classId;
            }
        }

        @Override
        public int capacity() {
            synchronized (lock) {
                requireLive();
                return sizes[page.classId];
            }
        }

        @Override
        public int read(int offset, ByteBuffer dst) {
            synchronized (lock) {
                requireLive();
                Objects.requireNonNull(dst, "dst");
                int capacity = sizes[page.classId];
                Objects.checkIndex(offset, (long) capacity + 1);
                if (dst.isReadOnly()) {
                    throw new ReadOnlyBufferException();
                }
                int length = Math.min(dst.remaining(), capacity - offset);
                ByteBuffer source = page.memory.asSlice((long) slot * capacity + offset, length)
                        .asByteBuffer();
                dst.put(source);
                return length;
            }
        }

        @Override
        public int write(int offset, ByteBuffer src) {
            synchronized (lock) {
                requireLive();
                Objects.requireNonNull(src, "src");
                int capacity = sizes[page.classId];
                Objects.checkIndex(offset, (long) capacity + 1);
                int length = Math.min(src.remaining(), capacity - offset);
                ByteBuffer source = src.duplicate();
                source.limit(source.position() + length);
                page.memory.asSlice((long) slot * capacity + offset, length).asByteBuffer().put(source);
                src.position(src.position() + length);
                return length;
            }
        }
    }
}
