# T-013 coordinator contract decisions

Run `run_3da2786de276`, 2026-09-16. Release profile, no release verdict.
Read together with the independently frozen `tests/reports/T013/contract.md`.
The source oracle remains unchanged. These decisions constrain implementation;
they do not authorize dropping mandatory fields or weakening data safety.

## Resolved mappings

- **Q01:** Mandatory slabs `N:mem_requested` is an additional product field:
  sum of requested record bytes of linked records in that class. It is not
  occupied block capacity, retired versions, ingress reservations or pages.
  Differential tests must identify this extension, not look for an absent
  reference line. It survives reset. Keep every manifest field.
- **Q02/Q03:** Retrieval/touch command and result counts follow original key
  occurrences, including duplicate misses. Deduplicated response/snapshot and
  atomic one-publication-per-unique-key semantics remain unchanged. `gat/gats`
  count touch, not get. No per-line alternative.
- **Q06:** Product requested record size is `align8(32 + keyBytes + valueBytes)`:
  the existing allocator request layout, distinct from allocator class rounding.
  `bytes` and the extension `mem_requested` measure linked requested records.
  Native used bytes still include retired/pinned/unpublished occupied blocks;
  page totals still include all physically retained pages. C ABI offsets are
  never copied. Any logical arithmetic capacity rule must be recorded separately.
- **Q07:** Existing allocator uses a uniform physical size *within a class*:
  `max(configuredPageSize, classChunkSize)`, with configured page size bounded
  by the memory limit at composition. Classes may differ. Report actual page
  count, actual sum of slot capacities and the true slots-per-page for the class.
  There are no fictional small final pages or counted future pages. A future
  heterogeneous-within-class allocator needs its own contract decision.
  Wire IDs are allocator IDs + 1. Emit slabs classes with physical pages;
  emit items classes with linked entries. Historical counters survive empty
  classes/page trimming until reset. `free_chunks_end` follows the pinned wire
  compatibility definition of zero; unused slots are already in free_chunks.
- **Q08:** Flat per-segment/class LRU maps to the reference cold queue: age is
  now minus the oldest actual linked LRU activity timestamp for that class,
  saturated nonnegative. Evicted-time is the last victim's activity age at the
  event, resettable and not continuously ticking. Lazy lookup expiry unlinks
  without tail-reclaimed counters. Allocation-pressure tail expiry counts
  reclaimed; scheduled bounded expiry sweep counts crawler_reclaimed.
  Unfetched route distinctions follow the source oracle, including flushed
  victims. Tailrepairs remains zero because no unsafe stuck-refcount repair
  exists; tests must prove the structural distinction from pinned-tail skips.
  Touch preserves fetched/activity semantics of a logical item despite new
  immutable storage; retrieval touch marks fetched, ordinary replacement starts
  a new logical fetched state. No unsafe mutation of old pinned versions.
- **Q09:** connection_structures measures actual live/retained connection
  structures; because this implementation does not pool structures, closed and
  released structures cease counting. It is not cumulative accepted connections.
  conn_yields counts actual fairness-budget exhaustion, not blocked writes.
- **Q10:** Default info; configured error/warn/info/debug filters apply globally
  to this server, without altering process-wide logging configuration. Numeric
  verbosity 0/1/2 maps to error/info/debug and clamps above 2. Startup, safe
  effective configuration and normal shutdown are INFO; capacity pressure and
  broad-address warning WARN; startup/internal/transport/expiry failures ERROR.
  Bounded safe operational summaries DEBUG. No raw cache/config input, exception
  message/stack, command buffer, key or value at any level. Repeated capacity
  warnings are rate-limited; malformed client requests do not cause an unbounded
  log stream. Logging sink failure must not stop service or alter cache data.
- **Q11:** Keep the existing single-counter-epoch reset contract. Quiescent
  observations are exact; concurrent snapshots are weakly consistent across
  fields. All general and per-class resettable data must share the reset epoch
  or a demonstrably equivalent capture boundary; no replay of old increments
  into a new epoch. Preserve gauges, constants, item metadata, CAS, TTL and data.
  No segment locks may remain held while waiting on network output.

## Admission and publication: strict requirements needing implementation analysis

**Q04 is not permission to substitute completed commands for admitted attempts.**
Retain reference-visible cmd_set timing: once valid storage header and its
initial storage admission succeed, count once even if body later fails or the
client disconnects. Native admission failure and oversized records must not be
counted as successful admission. Heap ingress admission alone does not prove
native admission. An implementation proposal must identify a real bounded
native reservation/unpublished record owner, transfer/cancel/rollback paths and
how it avoids allocating a second record at dispatch. Preserve the approved
complete body budget, old-data preservation and nonblocking ingress. No native
allocation silently exceeding memory.limit, no waiting while holding a segment
lock, no double cmd_set on internal append allocation or retry. Class attribution
uses incoming fragment class for cmd_set and old class for CAS outcomes.

**Q05 is not permission to count all immutable versions as insertions.** Touch
and gat leave total_items and requested bytes unchanged. Storage publication
increments once. Numeric arithmetic hit is recorded once after successful numeric
lookup, before possible allocation failure, with old-class attribution. The
implementation must distinguish logical in-place-equivalent arithmetic from
reference-visible replacement (growth/retained reader), preserving the observable
accounting rule without mutating old bytes or weakening leases. It must explain
numeric shrink/capacity and pinned-reader cases against the pinned oracle before
editing those paths. Any unavoidable compatibility difference stays pending,
not silently approved here.

## Implementation boundaries

First investigate Q04/Q05 architecture and exact affected APIs; do not code them
before coordinator accepts an ownership design. No new public configuration,
runtime dependency, blanket interface-default fake metrics or acceptance exclusion.
Keep interface compatibility and package DAG; stats stays JDK-only. New metadata
must be bounded, included in logical budgets, and measured in both pointer modes.
Protocol statistics formatting remains incremental with bounded detached class
snapshots and no full formatted response materialization.

Independent acceptance author may prepare resolved cases but must retain Q04/Q05
as required unimplemented criteria until the architecture is settled. Full
`clean verify -Pcoverage`, independent review and coordinator verification remain
required after implementation; historical first-server evidence is not a T-013 PASS.
