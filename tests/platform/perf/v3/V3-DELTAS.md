# v3 per-file delta record

`tests/platform/perf/v3/` is the new explicitly versioned measurement core, derived
from the frozen campaign-pinned estimator in `tests/platform/perf/pinned/`. Each row
gives the base file hash, the v3 file hash, and the exact change. `V3_CORE_VERSION`
is `v3.0.0`; the base pins are also embedded in `v3/contracts.py:V3_BASE_SHA256`.

| File | Base (pinned) SHA-256 | v3 SHA-256 | Defects repaired |
|---|---|---|---|
| `contracts.py` | `244be682e755242bed38c54c4fb94e3c54b2f6d783116347831f5954c5602793` | `414ee5178439511a94a968a53a71a3f1b9ddad755ec3550febb3d07151d109c6` | a b d e f g + F5.1 F5.2 A B |
| `finalize.py` | `9cc15c1ac61cfb4cada6fb77dcfd681495ee66114fab5ea0bcc4916b6654ca77` | `a9d1562df12e151f8fe69de292fa377876b439f4877ebbef96071df9ee81ee1e` | a f g + F5.3 |
| `gates.py` | `1243737576f4e3a76b1f8389b9af306d4ed12a16f190db5e69f615b4a5ce921a` | `4df1dd1a269776cf31e2ed4c128161c6c4033a28ea1a56d2e97c8722e021007a` | c d e f |
| `run.py` | `07b07ea6e978572edad99ec6cda751b0ad242f30a8d78b165643d4ff8a1de7d2` | `99d26fd3760f9a4228197792af9a308adcc0cdfc0f0ef1558957509c16490422` | a c d e f |
| `generator.py` | `473eaf3956514519fb1ac759edb4fe22ba397b89d289f77f1a3818813da267cd` | `d9862339e0c207cc7fac6939926cf91c04ca7e59887d888daadf6fdf887d8f22` | d |

Transport-only follow-up (two-machine contract, `run_two_machine.py`):

| File | Base | v3 | Note |
|---|---|---|---|
| `run.py` | `473eaf3956514519fb1ac759edb4fe22ba397b89d289f77f1a3818813da267cd` | `99d26fd3…0422` | `server_argv`/`client_argv` gain an optional `address` (default `127.0.0.1`); only the bind/target token changes |
| `finalize.py` | `9cc15c1a…ca77` | `a9d1562d…ee1e` | `read_attempt` reads `passport.serverAddress` (default `127.0.0.1`); F5.3 seal/analysis-only NOT_RUN |
| `contracts.py` | `244be682…2793` | `414ee517…09c6` | `topology_isolation` two-machine branch; F5.1 headroom provenance, F5.2 protocol expected-hash, A peak-thread, B common generator reasons |
| `run_two_machine.py` | (new) | `dd77c38a0f4708dfe0aa92a0d24591cc827a97f0200ba563c92e8fa81c8d733a` | two-machine collector under `TWO-MACHINE-CONTRACT.md`; records F5.1 headroom-probe provenance |

## Six coordinator-accepted corrections (independent review `v3-review-task_543c88eb9f49`)

All six corrections make the core **stricter** and create **no new PASS path** versus
the pinned core. Applied to `contracts.py` (F5.1, F5.2, A, B) and `finalize.py` (F5.3).

| ID | Correction | Previous v3 hash | Corrected v3 hash |
|---|---|---|---|
| F5.1 | bind per-row `generatorHeadroom` to attempt probe provenance (method, window, probe ceiling, probe artifact name+64-hex sha256); a fabricated/inconsistent/missing-provenance ceiling yields INCONCLUSIVE with reason | `8ce64fe2…c143` | `414ee517…09c6` |
| F5.2 | `protocol_identity` fails closed when the expected protocol hash is absent (`protocol_expected_hash_missing`), distinct from a mismatch | `8ce64fe2…c143` | `414ee517…09c6` |
| A | a present-but-malformed `peakClientThreadCpu` is a common invalid reason (`client_thread_cpu_invalid`); a genuinely absent value stays a disclosed warning, never 0 | `8ce64fe2…c143` | `414ee517…09c6` |
| B | generator-CPU validity reasons are COMMON reasons gating every metric, including M3 (as the pinned core did) | `8ce64fe2…c143` | `414ee517…09c6` |
| F5.3 | an attempt without a recorded qualification/attempt seal is NOT_RUN (`attempt_seal_missing`); an analysis-only scenario never reports PASS (`analysis_only_not_qualified`); `finalize.main` returns `EXIT_NOT_RUN` | `f2b08b39…8d99` | `a9d1562d…ee1e` |
| F5.4 | test-only: `tests/harness_tests/test_v3_repairs.py` asserts a present, exactly-False `headroomCleared` | (test) | (test) |

`gates.py` (`4df1dd1a…007a`), `run.py` (`99d26fd3…0422`) and `generator.py`
(`d9862339…8f22`) are unchanged by these corrections. `run_two_machine.py` was updated
only to record the F5.1 probe provenance the corrected contract now requires
(`dd77c38a…733a`). The before/after verdict table is in
`V3-CORRECTIONS-BEFORE-AFTER.md`.

## `contracts.py`

Added:
- `V3_CORE_VERSION = "v3.0.0"` and `V3_BASE_SHA256` (explicit version binding).
- `KNOWN_FROZEN_UTC_DEFECTS` (data record for the `2026-09-23T20:17:00Z` frozenUtc).
- `finite_number(value)` - a JSON number usable by the arithmetic, never a bool.
- `corrected_generator_cpu(samples, client_cpu_count, window)` - defect (a)/(b):
  per-consecutive-sample, per-thread accumulation from real in-window samples; a
  thread that retires between samples is an explicit reason, never a silent zero;
  an unmeasurable row is returned `status="invalid"` (never reported/summed as 0);
  the frozen first/last disagreement is represented on every return via
  `firstLastSampleMismatch`, `firstLastCounterSeconds`, `accumulatedCounterSeconds`.
- `cpu_schema_reasons(cpu)` - pre-arithmetic validation of optional CPU fields.
- `generator_evidence_reasons(row, expected, qualification)` - defect (b)/(d)/(g):
  unknown/absent generator evidence is an explicit reason for every row; per-shape
  headroom is required to be `source == "row-measured"`, a discard-sink method is
  rejected, and an absent headroom is never treated as headroom-clear.
- `parse_cpu_mask(spec)`, `topology_isolation(server_cpus, client_cpus, topology)` -
  defect (e): reject a mask pair sharing physical cores, publishing the observed
  `thread_siblings` topology.
- `_parse_iso(text)`, `protocol_identity(protocol, collection_start_utc, expected_sha256)` -
  defect (f): bind the frozen protocol hash to the attempt, fail closed when the
  protocol is missing, not frozen before collection, or `frozenUtc` post-dates
  collection; record the known defect as data.

Changed:
- `_validate_row(...)` / `validate_row(...)` gain a `qualification` parameter and
  now also: run `generator_evidence_reasons`; require a server-pid cgroup scope
  (`scopeSource == "server-pid"`, non-root) for both snapshots (defect c); disclose
  an absent `peakClientThreadCpu` as a warning instead of reading it as 0 (defect b);
  and add `generator_cpu_first_last_sample_mismatch` to M1/M2 when the row's
  generator CPU carries `firstLastSampleMismatch`.
- `generator_saturation_reasons(...)` is retained as a thin alias that delegates to
  `generator_evidence_reasons`, so a stale caller cannot reopen defect (b).

Unchanged: `THRESHOLDS`, `GENERATOR_CPU_BUDGET`, `GENERATOR_HEADROOM_MIN`,
`SCENARIOS`, `IDENTITY_KEYS`, `WINDOW_CONFIG`, `positive`, `discrete`,
`median_interval`, `classify`, `validate_pairs`, `qualify_window`, `_counter`,
`stats_delta`, `workload`, and every pinned row check verbatim.

## `finalize.py`

Added: `_iso_utc`, `collection_start_utc(rows)`, `read_cpu_observation(row, passport, kind)`.

Changed:
- `read_attempt` recomputes each row's generator CPU from the retained raw samples
  (defect a) and binds/digest-checks the frozen protocol file named by
  `passport.protocolPath` / `passport.protocolSha256` (defect f, missing/drifting
  protocol fails closed).
- `build_scenario` takes `qualification`, adds the attempt-level protocol and
  topology reasons, marks a scenario that was never collected `NOT_RUN` (defect g),
  echoes `v3CoreVersion`, and returns the `protocol`/`topology` gate records.
- `render` labels the v3 core and NOT_RUN, `main` passes the campaign qualification.

Unchanged: all pinned completion/calibration/harness-archive/interval checks, the
event interval overlap logic, metric ratio extraction and `classify` usage.

## `gates.py`

Added: `cgroup_scope_identifiable(before, after)` (defect c), `topology_isolation(...)`
(defect e), `protocol_identity(...)` (defect f).

Changed: `generator_non_saturation` rejects a non-`row-measured` or discard-sink
headroom, requires the per-row minimum, and always publishes `headroomCleared` so
an absent headroom is never headroom-clear (defect d); `gate_matrix` includes the
three new gates.

Unchanged: `unique_attempt_directory`, `stale_artifact_rejection`, `identity_match`,
`jdk_identity`, `harness_hashes`, `client_exit`, `preload_success`,
`workload_composition`, `stable_measurement_window`, `cgroup_v2_evidence`,
`soak_completeness`, `aggregate`, and the pinned constants.

## `run.py`

Changed:
- `cgroup_snapshot(pid)` reads `/proc/<server-pid>/cgroup`, records `scope` and
  `scopeSource="server-pid"`, and fails closed with no scope when it cannot be
  identified (defect c). All `bench`/`memory`/`cycles` snapshots pass the server pid.
- Added `_expand_cpu_list`, `topology_snapshot`, `load_protocol`; the passport now
  carries `topology`, `protocol`, `protocolPath`, `protocolSha256` (defects e/f).
- `client_cpu_summary(result, client_cpus, window)` delegates to
  `contracts.corrected_generator_cpu` (defect a).
- Added `per_row_headroom(w, native)` which calls `gen.headroom_probe` for the row's
  own shape and stores `generatorHeadroom` with `source="row-measured"` (defect d);
  a missing artifact/rate leaves it absent so the row is not headroom-clear.
- `peakClientThreadCpu` is only set from real samples (defect b).
- New `--protocol` CLI argument.

Unchanged: `server_argv`, `client_argv`, `cpu_ticks`, `rss`, `Wire`, `stats`,
`store_one`, `load`, the campaign loop/pairing, calibration, `start`/`stop`.

## `generator.py`

Changed: `capacity_probe` now labels itself `source="discard-sink"` and
`method="discard-sink-upper-bound"` (explicitly not admissible as per-row headroom);
added `EchoSink` (a responding loopback sink) and `headroom_probe(host, port, shape,
probe_seconds)` which measures the generator's capacity for one shape against the
responding echo and returns `source="row-measured"` (defect d).

Unchanged: the `Engine`, `Worker`, `Conn`, `Claim`, `Totals`, `DiscardSink`,
`preload`, `churn`, `latency_summary` implementation.

## How a generator-CPU first/last-sample mismatch is represented

`corrected_generator_cpu` always returns `firstLastSampleMismatch` (bool or null),
`firstLastCounterSeconds` (the frozen first-previous/last-current total) and
`accumulatedCounterSeconds` (the v3 per-consecutive-sample total), alongside
`clockTicksPerSecond` and `samplesUsed`. When the two totals disagree beyond
tolerance the row is `status="invalid"` with reason
`generator_cpu_first_last_sample_mismatch`, and `_validate_row` additionally places
`generator_cpu_first_last_sample_mismatch` on M1/M2, so the row cannot reach PASS.
