/**
 * Storage ownership contracts, without a hash table or allocator implementation.
 *
 * <p>ADR-014 permits dependencies only on stats and util. Item and ItemLease
 * belong here; GetResult and RetrievalSnapshot belong to engine. Protocol may
 * directly use storage for segmented reads; no reverse edge is permitted.
 *
 * <p>A complete record is one off-heap header/key/value/aligned block. Published
 * bytes and version metadata are immutable; mutations allocate, publish, retire.
 * LRU activity and refcounts are separately synchronized metadata. Independent
 * readers have their own offsets; retained versions cannot be rewritten/reused.
 * A block's release is not page deallocation. Pages plus future-page reserves
 * are bounded by memory.limit; heap pools and measured RSS are separate.
 * Stable FFM or direct buffers may be prototyped before T-009; no backend,
 * raw addresses, preview APIs or internal cleaners are part of these contracts.
 *
 * <p>The coordinator-approved SlabHandle exposes classId, capacity and bounded
 * ByteBuffer read/write only; writes are owner-confined before publication and
 * all access ends at free. MemoryAllocator.allocate returns null for capacity
 * denial and rejects nonpositive sizes. Free rejects null, foreign and double
 * free handles; only retired unpinned or unpublished blocks can be freed.
 * SlabClassStats is the six-getter immutable interface in the JDK-only stats
 * package; allocator reserve/close APIs and implementation lifecycle are deferred.
 *
 * @since 1.0
 */
package ru.octomem.storage;
