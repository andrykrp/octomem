package ru.octomem.storage;

import ru.octomem.stats.SlabClassStats;

/**
 * Thread-safe bounded block allocation independent of a concrete native backend.
 * Pages plus future-page reservations never exceed memory.limit. Used blocks
 * include live, retired/retained and reserved blocks, counted once regardless
 * of pin count. Block reuse is not page deallocation or a return of RSS to the OS.
 *
 * <p>Under segment locks the allocator may take only its own mutex then LRU
 * metadata; it must not acquire another segment lock or initiate eviction.
 * Eviction preflight occurs after unlock, protecting target keys, followed by
 * a complete recapture with a fresh clock. Allocation never waits on sockets.
 * The backend is selected by the pre-T-009 prototype, not by this interface.
 *
 * @since 1.0
 */
public interface MemoryAllocator {
    /**
     * Optionally releases fully empty pooled pages without changing usedMemory.
     * Wrappers must delegate explicitly to expose their backend's trim behavior.
     * @return physical page bytes released, or zero when unsupported
     */
    default long trim() { return 0; }
    /**
     * Atomically reserves and allocates a complete record block.
     * Denial rolls back every partial reservation, without changing old records.
     * @param size positive header + key + value + alignment size, checked before narrowing
     * @return owned unpublished block, or null for recoverable capacity denial
     * @throws IllegalArgumentException if size is not positive
     */
    SlabHandle allocate(int size);

    /**
     * Returns a block for reuse after retirement and the last pin release.
     * Unpublished blocks may also be returned on preparation failure.
     * Unlike lease close, this operation is not idempotent.
     * @param handle non-null block owned by this allocator with no remaining readers
     * @throws NullPointerException if handle is null
     * @throws IllegalArgumentException if foreign or already freed
     * @throws IllegalStateException if still published or pinned
     */
    void free(SlabHandle handle);

    /**
     * Returns native page allocation, not the configured limit or RSS.
     * @return nonnegative allocated page bytes within memory.limit
     */
    long totalMemory();

    /**
     * Returns occupied block bytes, including retained and reserved blocks.
     * @return nonnegative bytes without counting a block twice for multiple pins
     */
    long usedMemory();

    /**
     * Returns the positive, lifetime-stable number of size classes. Identifiers
     * are contiguous from zero through {@code classCount() - 1}; geometry is
     * available immediately after allocator construction, before engine start
     * or any allocation. Wrappers must preserve this geometry contract.
     * @return positive fixed class count
     */
    int classCount();

    /**
     * Captures coherent counters for one allocator-local size class. The returned
     * identifier equals {@code classId}. Chunk sizes are positive and strictly
     * increasing with identifier, and neither identifiers nor sizes change over
     * this allocator's lifetime. Geometry is available before engine start and
     * before any pages exist; only the usage counters vary with allocation.
     * @param classId identifier of an existing class
     * @return non-null immutable statistics, not a live mutable view
     * @throws IllegalArgumentException if the identifier is unknown
     */
    SlabClassStats classStats(int classId);
}
