# T013 independent preimplementation acceptance contract

Status: SOURCE ORACLE FROZEN; product mappings listed below require coordinator decisions. Research completed 2026-09-16 by fresh r37b/gpt-6-astra worker, task `task_fadc88e5c7ab`, dispatch `ctx_17a26d8aacb4`. This is not architecture approval, executed acceptance, or a release claim.

## Scope and evidence boundary

Only this file is owned. No octomem implementation or test code was read to derive expectations. No Maven, builds, test execution, external calls, or subworkers were used. Inputs were local project requirements, the compatibility manifest, and pinned Memcached source. All runtime comparisons and test execution are NOT_RUN. Runtime dependencies remain OpenJDK 25 standard library only, including logging; this research authorizes no dependencies, new settings, or native runtime component.

The reference is Memcached 1.6.45, text protocol, standard non-proxy/non-extstore behavior. Source citations below use `R/` for `.tools/memcached-src/memcached-1.6.45/`. The manifest records release archive SHA-256 `d362c64e6d8d5287153501eabf7c85b4a761432fbf53f5d7b085d0bb1653c1dd` and size 1272659 (`compatibility-manifest.json:5-11`); this session did not independently hash the archive or attest the extracted tree. Exact binary ABI, startup flags, reference runtime timing, and large-item boundary remain to be recorded by comparative testing, not invented here.

Authority: `docs/development.md:19-25` requires contract-first changes, independent test authorship, evidence, and no weakened checks. `tasks/T-013-stats-and-logging.md:9-28` requires all fields, real classes, reset, logging, and noninterference. `docs/04-protocol-compatibility-specification.md:173-204` and manifest arrays are the mandatory baseline. No absent reference field, unsupported implementation mechanism, or difficult fixture permits narrowing that baseline.

## Mandatory field inventory

Every general field below is mandatory. Every items/slabs suffix below is mandatory for each class selected by the approved class-emission rule. Require unique field names, decimal numeric values without loss of zero/boundary values, honest version string, CRLF framing, and `END\r\n`; reset returns exactly `RESET\r\n`. Additional reference fields do not replace mandatory fields. Numeric width in C is not permission for signed negative Java counter output.

| Surface | Complete mandatory inventory |
|---|---|
| General, process | `pid`, `uptime`, `time`, `version`, `pointer_size`, `threads` |
| General, connections | `curr_connections`, `total_connections`, `connection_structures`, `conn_yields` |
| General, commands | `cmd_get`, `cmd_set`, `cmd_flush`, `cmd_touch` |
| General, results | `get_hits`, `get_misses`, `delete_hits`, `delete_misses`, `incr_hits`, `incr_misses`, `decr_hits`, `decr_misses`, `cas_hits`, `cas_misses`, `cas_badval`, `touch_hits`, `touch_misses` |
| General, data/memory/I/O | `curr_items`, `total_items`, `bytes`, `reclaimed`, `expired_unfetched`, `evicted_unfetched`, `limit_maxbytes`, `evictions`, `bytes_read`, `bytes_written` |
| `items:N:` | `number`, `age`, `evicted`, `evicted_nonzero`, `evicted_time`, `outofmemory`, `tailrepairs`, `reclaimed`, `expired_unfetched`, `evicted_unfetched`, `crawler_reclaimed` |
| `N:` slabs | `chunk_size`, `chunks_per_page`, `total_pages`, `total_chunks`, `used_chunks`, `free_chunks`, `free_chunks_end`, `get_hits`, `cmd_set`, `delete_hits`, `incr_hits`, `decr_hits`, `cas_hits`, `cas_badval`, `mem_requested` |

Source: manifest `36-104`; docs/04 `184-202`. `eviction_policy` is conditional under docs/04:196, not a replacement for any baseline field; no such emission was found in the inspected pinned server stats source.

## Literal reference oracle

These rules are frozen independently of octomem code. Architecture-dependent product interpretations are questions later in this document, not silent rewrites of the oracle. Unless stated otherwise, deltas describe an isolated action with no concurrent expiry/crawler/eviction and a fully valid request. Internal lookups for mutation do not imply protocol get hits.

### Process, connections and I/O

| Fields | Reference meaning and reset behavior |
|---|---|
| `pid`, `version`, `pointer_size` | Actual process ID, compiled server version, `8 * sizeof(void*)`; unchanged by reset. Product version must instead be manifest `1.0.0 (octomem)`, never pretend to be Memcached. Pointer size is native address width, not Java compressed-reference size. |
| `uptime`, `time` | Current relative time minus `ITEM_UPDATE_INTERVAL`, and current relative time plus process start epoch. Continue through reset, not time since reset. Do not assert zero immediately after launch: reference deliberately offsets its internal start time. |
| `threads`, `limit_maxbytes` | Configured worker thread count and storage byte budget, not all JVM threads or RSS. Reset preserves them. |
| `curr_connections` | `stats_state.curr_conns - 1`, excluding one listener in the ordinary setup; client running stats is included. Not reset. |
| `total_connections` | Cumulative connection creation count, reset to zero even with active clients. Not a monotonic lifetime counter across reset. Setup/listener effects require a measured baseline. |
| `connection_structures` | Allocated connection structures, not just open connections. Structures can be reused and retained after close. Not reset. |
| `conn_yields` | Count of the specific request-budget exhaustion yield branch, not every select wakeup, short write, or backpressure event. Reset to zero. |
| `bytes_read`, `bytes_written` | Actual positive network read/write byte counts, including protocol framing, commands, errors and stats traffic; not requested/queued bytes or value payload only. Reset to zero, then immediately accrue further I/O. |

Citations: `R/memcached.c:194-214, 652, 779-780, 899, 1762-1784, 1838-1845, 2397, 2475, 2711, 2847, 2935, 3111-3137, 3176, 3247`; `R/memcached.h:403-459`; `R/doc/protocol.txt:1312-1333, 1390-1395`. A stats response cannot report its own subsequently transmitted bytes. The reset request may have been read before reset and erased; `RESET\r\n` and the next stats request add bytes afterward. Never demand a network-observed all-zero I/O snapshot after reset.

### Retrieval, touch, mutation and storage attempts

| Action | Exact reference deltas and class attribution |
|---|---|
| `get`/`gets`, each processed key occurrence | `cmd_get += 1`; hit adds `get_hits += 1` in found item's class (and general aggregate); miss adds `get_misses += 1`. Thus a valid three-key command counts three, not one. Duplicate occurrences are processed and counted separately. |
| `gat`/`gats`, each processed key occurrence | `cmd_touch += 1`, with `touch_hits` or `touch_misses`; no `cmd_get`/`get_hits`/`get_misses` increment from this retrieval path. A successful touch updates expiry in place and performs fetch/LRU update. |
| `touch` | Valid parsed attempt adds one `cmd_touch`; found adds class/general `touch_hits`, missing/expired/flushed adds `touch_misses`. It does not add `cmd_set`, `total_items`, or get counters. Invalid key/exptime returns before these increments. |
| All six storage verbs: `set`, `add`, `replace`, `append`, `prepend`, `cas` | Once request fields validate and its initial incoming item allocation succeeds, add one class `cmd_set` in that incoming item's class and hence one general `cmd_set`. This is before body completion or storage-result evaluation. Not success-only and not just the verb `set`. |
| Rejected add-existing, replace-missing, append/prepend-missing | Still count the admitted initial allocation as `cmd_set`; no `total_items` publication. Failed add can update the existing item's LRU timestamp, so failure is not proof of unchanged age. |
| CAS match | `cmd_set` belongs to incoming allocation class; `cas_hits` belongs to OLD found item's class, even if replacement changes class. Successful replacement publishes once. |
| CAS mismatch / CAS miss | Admitted `cmd_set` still increments; mismatch adds OLD class/general `cas_badval`; absent/expired/flushed adds general `cas_misses` only. No publication. A zero CAS token against an existing item is badval in this path. |
| Append/prepend success | Incoming fragment allocation counts one `cmd_set` in fragment class, not old or final combined class. Combined allocation does not add another `cmd_set`. One final replacement publication. If combined allocation fails, original incoming `cmd_set` remains counted, no publication; allocation failure may separately increase class `outofmemory`. |
| Invalid storage header, oversize or initial allocation failure | No `cmd_set` if no initial item was allocated. Oversize has no valid class and is not automatically class OOM. The reference SET allocation-failure path can unlink old data; this is a known conflict with product preservation rules, not authority to change product data semantics. |
| Bad storage body terminator / disconnected incomplete upload | If initial allocation succeeded, `cmd_set` has already increased; no publication/CAS-result count when storage evaluation was never reached. Abandoned temporary allocation must eventually be freed. |
| Delete present / absent | Present adds OLD class/general `delete_hits` and unlinks; absent adds general `delete_misses`. Neither adds cmd_set/get counters. |
| Arithmetic absent / nonnumeric / malformed delta | Absent adds matching general `incr_misses` or `decr_misses`. Nonnumeric or malformed delta adds neither hit nor miss. Internal item lookup is not a get hit. |
| Arithmetic numeric found | Add matching OLD class/general arithmetic hit BEFORE potential replacement allocation; an allocation failure after numeric parsing can therefore count a hit despite error. No `cmd_set`. In-place update versus replacement controls total_items, described below. |
| `flush_all` | Adds `cmd_flush` once on entry to its handler, before disabled-flush or invalid-exptime rejection. Does not itself add delete/eviction counts. Invalid requests rejected before handler dispatch are a separate parser case. |
| `noreply` | Suppresses eligible response, not admitted work/counters. Version, stats, verbosity and quit do not add these storage/get counters; they still affect I/O and possibly connection state. |

Citations: `R/proto_text.c:429-486` loops over every occurrence; `R/proto_parser.c:660-735` classifies get vs touch; `R/thread.c:1033-1037, 1047-1056` aggregates per-LRU hits into classes and class counters into general totals. Storage entry and rejection points: `R/proto_parser.c:741-818`; ASCII read stage `R/proto_text.c:764-783, 117-163`; CAS/append/add/replace publication `R/memcached.c:1547-1703`. Delete/touch/arithmetic dispatch: `R/proto_parser.c:856-981`; numeric hit-before-allocation `R/memcached.c:2232-2322`; flush `R/proto_text.c:1176-1209`.

Reference-only distinguishing examples (not automatically product duplicate semantics): with A present and B missing, `get A A B B` gives cmd_get +4, get_hits +2, get_misses +2; `gat 60 A A B B` gives cmd_touch +4, touch_hits +2, touch_misses +2 and get counters unchanged. A failed admitted CAS-mismatch still gives cmd_set +1 and cas_badval +1. These expectations must not be replaced by implementation-derived values.

### Item counts, bytes and reset

`curr_items` counts linked items, including expired/flushed items not yet physically unlinked. `bytes` is the sum of `ITEM_ntotal` of linked items, not rounded slab allocation, total native committed pages, payload-only length, or retained unlinked items. Link increases both and `total_items`; unlink decreases current gauges only. Replacing an existing item unlinks then links: current count is unchanged after completion, bytes change by new requested record size minus old, and total_items increases by one even for a same-value replacement. Insert increments both item counts; a failed publish does not. Deletion, lazy expiry, reclaim and eviction do not increment total_items. Stats observations alone do not proactively clean the cache.

`ITEM_ntotal = sizeof(struct _stritem) + key length + 1 + nbytes + optional sizeof(client_flags_t) + optional sizeof(uint64_t)`. `nbytes` includes the value's CRLF; flags occupy optional storage only when nonzero; CAS is normally enabled. The exact C ABI sizes are NOT a Java layout prescription. See `R/memcached.h:145-152`, `R/items.c:152-160, 249-265, 407-429, 447-474, 485-518, 578-585`; general emission `R/memcached.c:2010` and surrounding get_stats code.

Touch/gat reference modifies expiry in place, so total_items and bytes do not change. Arithmetic changes in place if the formatted number plus CRLF fits the previous `nbytes` and refcount is exactly 2; it pads leftover value space, preserving the original record byte count and total_items. Otherwise successful allocate/replace increments total_items once and changes bytes to the replacement size. A retained reader can force replacement even without numeric growth (`R/memcached.c:2290-2322`). This explicitly conflicts with naively counting every immutable product publication as total_items.

Reset is not flush and not a new process. `stats_reset` clears resettable global stats, all thread counters/slab counters/LRU hit arrays, and all itemstats structures; it does not clear state structs, items, LRU queues, sizes, sizes_bytes, allocator pages, CAS sequence, or configuration (`R/memcached.c:207-214`; `R/thread.c:983-1003`; `R/items.c:50-78`; `R/memcached.h:403-459`).

| Reset to zero | Preserve/recompute from actual state |
|---|---|
| General: `total_connections`, `conn_yields`, all four `cmd_*`, all thirteen result counters, `total_items`, `reclaimed`, `expired_unfetched`, `evicted_unfetched`, `evictions`, `bytes_read`, `bytes_written` | General: `pid`, `uptime`, `time`, `version`, `pointer_size`, `threads`, `curr_connections`, `connection_structures`, `curr_items`, `bytes`, `limit_maxbytes` |
| Items: `evicted`, `evicted_nonzero`, `evicted_time`, `outofmemory`, `tailrepairs`, `reclaimed`, `expired_unfetched`, `evicted_unfetched`, `crawler_reclaimed` | Items: `number`, `age`; reference's additional items `mem_requested` |
| Slabs: `get_hits`, `cmd_set`, `delete_hits`, `incr_hits`, `decr_hits`, `cas_hits`, `cas_badval` | Slabs: `chunk_size`, `chunks_per_page`, `total_pages`, `total_chunks`, `used_chunks`, `free_chunks`, `free_chunks_end`; mandatory product extension `mem_requested` must be a state gauge once mapped |

`evicted_time` is a resettable last-event sample, NOT a cumulative counter and NOT a continuously increasing age since the eviction event. Generic wording 'reset counters except immutable' is insufficient: mutable gauges survive, while this noncounter sample resets. Total_items/total_connections may be less than curr_items/curr_connections after reset; that is valid. Reference reset/snapshot uses multiple individual locks, not a single global transaction across every field, so concurrent exact cross-snapshot equalities require quiescent barriers.

### Per-class LRU events

| Required suffix | Literal reference rule |
|---|---|
| `number` | Sum of linked counts for HOT/WARM/COLD/TEMP of this class. Expired entries remain counted until unlinked. Class output is omitted when this sum is zero, even if historical counters exist. |
| `age` | `current_time - COLD tail->time`; zero if no cold tail. Not insertion age, not longest TTL, not oldest of all four LRUs. Timestamp updates depend on LRU mode and fetch activity. |
| `evicted` | One per live victim removed by the cold-tail eviction branch. General `evictions` sums these class/LRU counters. |
| `evicted_nonzero` | Same eviction event, but victim had nonzero exptime, independent of its remaining TTL length. |
| `evicted_time` | At that eviction, save `current_time - victim->time`. Later reads return the saved value until another eviction/reset; it does not tick upward. |
| `outofmemory` | One when a valid-class `do_item_alloc` fails to obtain its initial item/header. Stored in the requested logical class before LRU flags. Not every protocol SERVER_ERROR, oversize, connection rejection, snapshot failure or late chunk-allocation failure. |
| `tailrepairs` | Actual repair of old refcount-locked tail when tail_repair_time is enabled; increments before destructive repair. Not ordinary LRU movement, pinned-item skip, failed eviction or background expiry. Never implement unsafe refcount repair just to make a metric nonzero. |
| `reclaimed` | Expired OR flushed entries unlinked by lru_pull_tail, counted at reclamation, not guaranteed one-for-one with successful new stores. Lazy lookup expiry is not this counter. Crawler uses its separate counter. |
| `expired_unfetched` | Tail reclaim of an item lacking ITEM_FETCHED, including flushed victims in that path; plus crawler unfetched expired victims, but crawler excludes flushed victims. Not all elapsed TTLs or all removals of never-read items. |
| `evicted_unfetched` | Live eviction victim lacked ITEM_FETCHED. Delete/replacement do not increment it. |
| `crawler_reclaimed` | Actual crawler removals, including flushed entries, merged into class totals at crawler completion. Not synonymous with all expiration removals and not part of general reclaimed. |

Citations: `R/items.c:725-763, 782-882, 1090-1145, 1187-1207, 296-300`; `R/crawler.c:208-245, 525`; merge `R/items.c:73-77`. Lazy lookup unlinks expired/flushed items but increments only get_expired/get_flushed helper counters (`R/items.c:974-1005`), not reclaimed/unfetched. Fetch marking: `R/items.c:1032-1056`; standalone touch uses DO_UPDATE (`1059-1065`). Mutations use DONT_UPDATE lookups, so a numeric update is not automatically 'fetched'. A newly published replacement starts fresh fetch state rather than inheriting the old fetched bit.

Time edge: lookup expiration uses `<= current_time`, while tail/crawler use `< current_time` (`R/items.c:992, 1130`; `R/crawler.c:219`). Test deterministic clocks away from this edge for aggregate cases; explicitly test the edge separately rather than mislabeling scheduling variation as counter corruption. LRU timestamp refresh is throttled in unsegmented mode and activity-based in segmented mode (`R/items.c:548-575, 1032-1056`), so do not assert that every hit resets age to zero.

### Slab geometry and mandatory mem_requested conflict

Reference emits a class in stats slabs iff it has allocated pages, even if it contains zero linked items. `chunk_size = p->size`, `chunks_per_page = p->perslab`, `total_pages = p->slabs`, `total_chunks = slabs * perslab`, `free_chunks = p->sl_curr`, `used_chunks = total_chunks - free_chunks`. Allocated/unlinked incoming items and retired items with references occupy chunks: used_chunks is not necessarily number. `free_chunks_end` is deliberately hard-coded zero because the reference statistic is dead; this is a source-supported zero, not permission to fake other metrics (`R/slabs.c:559-611`).

Reference per-class hit counters are those in the action table; misses have no class. Historical hit counts can remain in a class whose pages remain but whose items are gone. General totals aggregate all classes, not only currently emitted item classes (`R/thread.c:1047-1056`; `R/items.c:725-747`). A sum over only visible nonempty items classes need not equal general lifetime counters.

Reference class capacity is floor(page size / class chunk size), including its maximum class (`R/slabs.c:256, 267`). Actual page allocation length is either configured page size or `p->size * p->perslab`, depending on slab reassignment and chunk configuration (`R/slabs.c:374-404`). Therefore do not assume allocated bytes always equal pages * 1 MiB even for the C reference. `used_chunks * chunk_size` is rounded occupied capacity; it is not requested record bytes or committed page bytes.

CRITICAL CONTRACT CONFLICT: mandatory `N:mem_requested` in manifest `103` / docs/04 `200` is NOT emitted by 1.6.45 `stats slabs` (`R/slabs.c:577-609`). It IS emitted as `items:N:mem_requested` by `R/items.c:818, 860`, using sizes_bytes of LINKED items, as described in `R/doc/protocol.txt:1578, 1619-1623`. The mandatory slabs field must remain present in octomem; coordinator must approve its explicit extension meaning and differential-harness handling. A defensible candidate is linked logical requested bytes per class, independent of allocator rounding; this is a proposal, not a decision. Do not silently omit it, substitute rounded usedMemory, or compare against a nonexistent reference line.

## Coordinator decision register

All questions go to the coordinator, not the user. No decision below is made by this research worker. These block the affected product expectation freeze, not completion of this source research. The reference oracle above remains fixed.

| ID | Required decision / conflict |
|---|---|
| Q01 | Approve mandatory slabs mem_requested as an explicit product extension and specify its linked/allocated/retained inclusion boundary; reference lacks that wire field. Preserve baseline. |
| Q02 | Duplicate-key stats: manifest `174-176` only declares deduplicated output, and docs/03 `151-163` deduplicates snapshot/touch publication. Should counters still count every original occurrence (literal reference) or unique keys? Do not infer unique-key statistics merely from unique output. Include missing duplicates and gat/gats, not just successful get. |
| Q03 | Preserve literal reference cmd_get per key and gat/gats under cmd_touch, or explicitly resolve any intended command-level alternative through requirements authority. No evidence here supports command-count-per-line. |
| Q04 | Map cmd_set admission when octomem has separate ingress/body and native allocation stages, and map OOM timing. Reference counts before body completion; product failure preservation is stricter than reference SET allocation failure. Define counts for malformed body, disconnect, staging failure, native failure, and combined append failure without changing product data-preservation rules. |
| Q05 | Logical total_items vs immutable publication: docs/03 `149` replaces on touch/gat and arithmetic, but literal reference touch/gat adds zero and arithmetic adds zero for in-place updates. Approve an observable logical accounting rule independent of internal version allocation, including pinned arithmetic replacement, or document the explicit compatibility difference. Physical publication count is not automatically the required statistic. |
| Q06 | Requested record bytes: docs/03 `147, 173` includes alignment in allocator request while reference ITEM_ntotal excludes slab rounding. Define product record-size formula and which alignment is intrinsic versus class rounding; use product own layout, never copy C ABI constants. Define bytes vs mem_requested and retained/reserved native accounting independently. |
| Q07 | Actual heterogeneous page sizing: docs/03 `182` states fixed 1 MiB defaults but budgets permit tiny limits and max items beyond that; task explicitly requires heterogeneous-page treatment. Architecture owner must supply actual page topology and approved stats model without this worker reading implementation. For class N with actual page capacities c1...cp, total_chunks must use their true sum, not p times an invented uniform capacity; what does the SINGLE chunks_per_page field mean if capacities differ? Specify small final pages, oversized dedicated pages, reserved-but-not-materialized pages, released pages and stable class IDs. No guessed rounding, synthetic pages or zero capacities. |
| Q08 | Map flat/segmented product LRU age and evicted_time, real background expiry to crawler_reclaimed versus reclaimed, and fetched metadata on touch/replacement. A crawler command being out of scope does not mean a crawler-style background metric must be zero. If no tail-repair mechanism exists, coordinator must document truthful structural-zero justification while retaining the field; no unsafe repair mechanism is requested. |
| Q09 | Connection_structures retention and conn_yields must correspond to actual transport objects and fairness budget events, not fake total_connections or arbitrary blocked writes. Confirm class-emission rule follows reference distinction (items nonempty, slabs allocated) and how counters survive empty class/page release. |
| Q10 | D-03 card requires severity/style but contains no completed event-level mapping. Confirm startup/config/stop INFO, capacity and broad-bind WARN, startup/internal failures ERROR as proposed below, exact verbosity mapping/clamping, filtering, and error-flood policy. Do not claim these proposed severity choices already exist in approved docs. |
| Q11 | Specify snapshot/reset concurrency contract: quiescent exact totals are mandatory; stronger cross-subsystem linearizability than the C reference must be explicitly agreed, not inferred. A reset must not reset per-item fetched state, touch TTL/CAS, or restart clocks. |

Other documented conflicts: protocol.txt describes total_items/total_connections as since startup (`1323-1330`) while reset code clears them; describes arithmetic hits as successful (`1364-1366`) while source increments before allocation; describes generic oldest-LRU age (`1577`) while source uses COLD only; describes free_chunks_end as usable tail capacity (`1716-1717`) while source deliberately emits zero. For literal 1.6.45 behavior, the cited source resolves these descriptions, and the contract records rather than conceals the discrepancy. No runtime proof is claimed.

## Logging acceptance contract

Normative requirements: startup, effective configuration, errors and shutdown (`docs/05:201-207`, task `13-20`); capacity warnings (`docs/03:424`); broad-address warning (`docs/07:37`); configurable `error`, `warn`, `info`, `debug`, case-insensitive, default info (`design/config-schema.md:49`, docs/03 `412`); human messages Russian, wire protocol English and unchanged (`design/D-03-messages-and-exit-codes.md:10-19`). Logging failure must not terminate the server, and collection failure must not mutate cached data (`docs/05:207`).

Proposed severity mapping awaiting Q10: startup/effective safe configuration/normal shutdown INFO; recoverable capacity pressure and non-loopback exposure WARN; startup failure and unexpected internal/transport/expiry failure ERROR; bounded operational diagnostic summaries DEBUG. Expected malformed client input need not emit an unbounded per-request ERROR stream. Filtering at warn/error legitimately suppresses info events; tests must distinguish filtered output from missing instrumentation. No new public log settings are authorized.

No cache keys or values may leak by default; D-03 also prohibits secrets and cache content generally. Test default and all supported levels, including after verbosity, before allowing any broader logging. Do not copy reference verbose key logging (`R/proto_parser.c:718-725`, `R/items.c:1009-1024`, `R/crawler.c:228-236`): it conflicts with product privacy requirements. Do not log raw command lines, buffers, payload fragments, user-derived exception messages/toString, or dynamic format arguments containing keys. Safe operation names, bounded error categories and numeric capacities are sufficient. Configuration diagnostics must not echo unknown raw property values that could contain secrets or cache markers.

Verbosity reference parses unsigned level and clamps to MAX_VERBOSITY_LEVEL=2 (`R/proto_text.c:786-796`, `R/memcached.h:464`); project docs/04 `214-218` require OK/noreply and docs/05 `203` prohibit changing protocol behavior. Mapping numeric verbosity onto four named log levels is not specified by those statements and needs Q10. A verbosity increase is not authorization to expose cache content.

## Acceptance IDs and independent test plan

This is a plan, not authored executable tests. A different worker from production implementer must author later tests from this frozen oracle plus coordinator-approved Q resolutions, before consulting implementation for fixture seams. Tests must not call production statistic calculations to obtain their expected answers. Baseline all-fields checks cannot be replaced with only cases easy to trigger.

| ID | Acceptance and independent evidence to author later |
|---|---|
| T013-A01 | Full inventory: compare all three manifest arrays to captured wire maps, instantiate at least two real classes, assert no duplicate names and all zero-valued mandatory suffixes present for emitted classes. Verify CRLF/END and RESET responses without changing T-007 framing ownership. |
| T013-A02 | Process/config: assert actual PID, manifest version marker, native pointer width, configured worker count and storage limit; bracket epoch/uptime with test clock bounds, then reset and verify continued time/config. |
| T013-A03 | Connections: open/close/reopen known clients around barriers, verify current and cumulative deltas and approved actual structure lifecycle, reset with clients open, trigger actual fairness yield rather than sleep/backpressure proxy. |
| T013-A04 | Get/gets: present and missing keys across classes, expiry/flush misses, multi-key and duplicate matrix, exact reference +1 per processed occurrence and approved product duplicate rule. Internal mutation lookups must not contaminate hits. |
| T013-A05 | Touch/gat/gats: present/missing, duplicate, unchanged/new TTL, expired, noreply and preparation-OOM cases; touch counter attribution rather than get counters; no false touch hit on product capacity failure unless expressly contracted. Cache state/CAS preserved on failed preparation. |
| T013-A06 | Storage admission: all six verbs, success and every conditional rejection, malformed header/body, partial upload/disconnect, oversized item, initial OOM, noreply. Freeze cmd_set timing and exact admitted versus rejected counts separately from successful storage. |
| T013-A07 | CAS/append class attribution: deliberately choose different incoming, old and combined classes; assert cmd_set in incoming class, CAS outcome in old class, one final publication, no double count for internal combined allocation. |
| T013-A08 | Arithmetic: found numeric, absent, malformed delta, nonnumeric, wrap/floor, growth, shrink-with-padding reference, retained-reader replacement and OOM after numeric hit. Separate arithmetic hits from successful-publication count and cmd_set. |
| T013-A09 | Item publication: insert/replacement/same-value replacement/delete/touch/append/arithmetic plus failed mutation; verify exact current/total deltas from logical operation oracle after Q05, not physical implementation counter updates. |
| T013-A10 | Bytes/requested/rounded: independently calculate approved product requested record sizes for zero/nonzero flags, key lengths, alignment/class boundaries; verify bytes and mem_requested, and show occupied chunk capacity/committed pages differ. Hold old version through replacement/delete and prove live gauges versus retained allocation stay distinct. |
| T013-A11 | Geometry: heterogeneous small/ordinary/oversized pages, multiple pages, freed blocks and outstanding reservation, emitted empty slab class, no-data empty items output. Assert sum of actual page capacities, used+free=total, approved chunks_per_page, stable IDs and truthful free_chunks_end; no floor/ceiling shortcuts. |
| T013-A12 | Eviction: forced live victims in two classes, expiring/nonexpiring, fetched/unfetched, known clock; verify evicted, nonzero, unfetched and general sum, saved evicted_time remains unchanged as time advances, subsequent eviction overwrites sample. |
| T013-A13 | Expiry routes: independently force lazy lookup, tail reclaim and background sweep/crawler; include flush and fetched/unfetched. Verify route-specific reclaimed/crawler/unfetched rules, exact expiry edge and eventual gauge decrease without relying on sleep-only scheduling. |
| T013-A14 | OOM/repair: separate invalid class/oversize, real valid-class allocator failure, late copy failure, unrelated transport/snapshot failure and pinned-tail skip. Verify outofmemory routing. Retain tailrepairs field and prove approved invariant or real repair event; never manufacture repair by breaking memory safety. |
| T013-A15 | Reset exhaustive: first create nonzero counters and nonempty/retained state; clear every resettable field in reset table including total_items/total_connections/evicted_time; preserve gauges, pages, fetched state, expiry, CAS and data. Verify reset twice and empty cache. Account for post-reset traffic/background work rather than forcing wire I/O to zero. |
| T013-A16 | Network bytes: raw client records exact sent/received bytes, partial read/write, errors, stats and noreply, use synchronization markers to separate sampling boundaries; assert actual wire-byte accounting, not buffer enqueue size. |
| T013-A17 | Flush: immediate/delayed/noreply and invalid-exptime handler path; command counter once, no immediate invented delete/eviction totals, later removal route supplies correct metrics. |
| T013-A18 | Concurrency/noninterference: predetermined operation counts across workers and classes, barriers before exact snapshots, approved reset race semantics, no lost updates/negative gauges; stats/reset must leave item contents and leases valid, no locks held during socket waits. |
| T013-A19 | Logging events/levels: capture startup/config/stop, broad bind warning, recoverable capacity warning, errors at each configured level and verbosity; consistent human wording and unchanged English wire responses, validated Q10 filtering. |
| T013-A20 | Privacy/failure isolation: unique key/value/secret markers including control characters, invalid command/config and injected exceptions across all events/levels; inspect all captured outputs for raw markers/fragments and newline injection; inject failing log sink and stats collection seam, verify server/data survive. |
| T013-A21 | Bounded formatting and boundary values: stream large class snapshots through tiny legal output chunks and stalled readers while another client progresses, zero counters, large long values, no overflow/truncation or whole-response unbounded materialization. Verify JDK-only dependencies statically in later implementation review. |
| T013-A22 | Evidence integrity: independently authored test provenance, fixed candidate revision/hashes, exact commands/JDK25 environment, expected and actual deltas, raw reports, all A IDs covered or honestly NOT_RUN/BLOCKED. T013 task evidence does not substitute for TEST-04/TEST-07 or full release verification. |

Test harness must stabilize background activity or explicitly await it, record source fixture and class calibration independently, and retain raw stats before/after every action. Use deterministic clocks/failure injection for hard-to-trigger paths without changing production semantics. For differential testing, record reference flags and class geometry, normalize only approved process/layout differences, and retain mandatory product-only mem_requested rather than skipping it. A zero observed in a baseline is not proof of structural impossibility.

## Research outcome

All manifest fields are inventoried, literal pinned action/reset rules are frozen, and unresolved product mappings are explicitly separated from evidence. Coordinator was notified through Orca of the cmd_get/gat admission semantics and mandatory mem_requested conflict before artifact completion. No production, test-code, documentation-source or POM changes were made; executable acceptance remains NOT_RUN pending coordinator decisions and independent test authorship.
