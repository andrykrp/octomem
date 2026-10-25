# ADR-018: Engine class geometry and constructor ownership

Date: 2026-09-18. Status: coordinator-approved design; implementation awaits independent verification/review.

Task: `task_904418d45639`, dispatch `ctx_59564b438250`, run `run_3da2786de276`.
Baseline: `.opencode/autonomous/run_3da2786de276-post-opt-review.md`, accepted R1–R3.
Ordering/API clarification: coordinator answer `msg_8c5e96443121` to `msg_ee2222ceb42b`.

## Contract

`MemoryAllocator` retains its method set. Its positive class count and contiguous
zero-based class identifiers are fixed for the allocator lifetime. Positive chunk
sizes strictly increase with identifier. `classStats(id)` supplies matching IDs
and this geometry immediately after construction, before engine start or page
allocation. Usage counters may vary; geometry must not. Delegating allocators
must preserve these invariants. Finite startup checks do not prove a provider's
future stability.

The engine reads each class's statistics exactly once during construction,
rejecting null statistics, mismatched IDs, nonpositive/nonincreasing sizes and
nonpositive/changing count. No command re-reads class statistics for class lookup.
The allocator remains the geometry source; no concrete-backend cast is introduced.

## Heap accounting

`SegmentedItemTable.classGeometryMetadataBytes(int)` computes
`align8(24 + 4*N)` with checked long arithmetic and a positive count requirement.
The conservative 24-byte array header and alignment are included: N=44 costs
200 bytes, N=1 costs 32 bytes. This is logical ownership accounting, not a measured
heap layout, native charge or RSS guarantee.

`reserveClassGeometry(int)` admits exactly one snapshot charge on an empty,
unconfigured table under all segment locks. A denied reservation changes nothing;
the table's existing limit does not grow. `classGeometryMetadataUsedBytes()`
reports that portion of total metadata; completed close resets both to zero.
The startup owner must close the table on subsequent preparation failure.

`CacheServer` adds this same charge to its metadata limit exactly once. The engine
reserves it exactly once before `new int[N]`. Existing epoch, queue and page
metadata allowances are unchanged, so net variable index capacity is unchanged.
Direct engine callers must supply enough table metadata for the added charge.

## Ownership and initialization transaction

1. Validate non-null arguments and verify the supplied table is open and empty.
   Rejection here leaves the caller's table untouched. The caller must prevent
   concurrent table mutation throughout constructor ownership transfer.
2. Transfer table ownership, but never allocator lifecycle ownership.
3. Check positive class count, reserve snapshot metadata, allocate its array,
   capture/validate geometry once and verify the count remains stable.
4. Only after valid capture, configure queues and initialize registry/service
   metadata using the captured count.
5. Any `RuntimeException` or `Error` after transfer closes the owned table and
   propagates the original failure. A distinct cleanup failure is suppressed.
   The externally owned allocator stays open.

Reservation is accounting-only preparation, not queue publication. This ordering
preserves the existing exactly-one-read construction contract while ensuring
allocation is admitted first and invalid geometry never configures queues.
Registry callbacks cannot be rolled back through the present registry interface;
failed construction must not be treated as a usable engine/registry composition.

Normal stop still closes the table; existing leases must be released before the
external allocator owner closes its allocator. No wire behavior, public settings,
package edges, runtime dependencies or performance threshold changes are made.
