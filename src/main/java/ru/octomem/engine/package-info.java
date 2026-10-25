/**
 * Atomic cache contracts accepted for T-004 under ADR-013 through ADR-016.
 *
 * <p>Allowed dependencies: storage, expiry, eviction, stats and util. No network
 * dependency. The coordinator-approved support contract uses CounterResult with
 * VALUE/NOT_FOUND/NON_NUMERIC/OUT_OF_MEMORY and unsigned value bits meaningful
 * only for VALUE. CacheCapacityException is checked with a fixed no-argument
 * constructor and no cause or untrusted diagnostic payload.
 *
 * <p>Snapshot admission is atomic and precedes capture and the first output byte.
 * Its global heap cap is min(floor(memory.limit / 16), 16 MiB); checked charge is
 * 512 + 512*N, counting every input occurrence, including duplicates and misses.
 * Count via KeySequence.size(), reserve, allocate, then sequentially fill from
 * borrowed keys; no per-key allocation or quadratic indexed rescans.
 *
 * <p>ADR-016 requires exactly six arrays: Item references[N], int firstOccurrenceOrder[N],
 * int segmentIds[N], int dedupOrder[N], byte keyLengths[N] (unsigned), and byte
 * keys[250*N]. All lengths use checked long before allocation/narrowing, no growth.
 * Iterative in-place heapsort and segment compaction use constant scratch, no
 * recursion, extra hash table or array indexed by the total segment count.
 * Internal lookup uses a bounded borrowed storage key view, not Item.key() copies.
 * References at most 8 bytes and array headers/alignment at most 64 imply 271*N
 * payload, 384 headers and at most 128 wrapper/cursor/scratch bytes, bounded by
 * 512+512*N. Target HotSpot JDK 25 layout must verify these assumptions; these
 * are logical budgets, not measured layout, performance or an RSS guarantee.
 *
 * <p>Acquire all unique segments in increasing index order, capture one util
 * Clock value and flush boundary, then pin every unique hit before unlock.
 * Misses are fixed; output is distinct first-occurrence order. Multi-gat prepares
 * every replacement, reservation and result pin before nonallocating publication.
 * Failure rolls back every unpublished resource without changing any target.
 * Lock order is sorted segments then allocator then LRU. Eviction preflight is
 * outside those locks and protects targets; retry recaptures the entire set and
 * a new clock. No I/O, queue waits or transport callbacks under locks.
 *
 * <p>Snapshot metadata is released once with every pin on completion, failed
 * transfer, error, cancellation, disconnect or shutdown, including unvisited hits.
 * Retired blocks remain in native memory.limit until the last pin, without
 * duplicate accounting for multiple readers. A cursor borrows Item until close;
 * cursor/writer/close remain event-loop confined. No real backend is selected
 * here; the pre-T-009 prototype and independent contract tests remain required.
 *
 * @since 1.0
 */
package ru.octomem.engine;
