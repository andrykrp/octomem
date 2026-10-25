# v3 explicitly versioned measurement core

`tests/platform/perf/v3/` is a **new, explicitly versioned** TEST-05 measurement core
(`V3_CORE_VERSION = "v3.0.0"`) derived from the frozen campaign-pinned estimator in
`tests/platform/perf/pinned/`. It repairs **only** the seven instrument defects (a)-(g)
and leaves every threshold, window, validity definition, scenario definition and
workload definition unchanged. The live `tests/platform/perf/*.py` files, the pinned
copy, the retained attempt-harness directories and all historical raw evidence were
not modified.

## Files

| File | Purpose |
|---|---|
| `contracts.py` | pure evidence contracts and the repaired generator-CPU / headroom / topology / protocol helpers |
| `finalize.py` | import-safe reader and scenario builder (adds NOT_RUN, protocol/topology binding, offline CPU recompute) |
| `gates.py` | machine-readable validity gates plus the new cgroup-scope, topology and protocol gates |
| `run.py` | bounded collector (server-pid cgroup scope, per-row measured headroom, topology/protocol capture) |
| `generator.py` | bounded generator (discard-sink labelled; responding-echo per-row headroom probe) |
| `V3-DELTAS.md` | per-file base hash -> v3 hash and the exact change |
| `THRESHOLDS-BEFORE-AFTER.md` | before/after table proving no threshold or definition changed |
| `V3-NOT-CHANGED.md` | explicit statement of what v3 does not change |

## Defects repaired

- **(a)** per-row generator CPU is accumulated per consecutive in-window sample per
  thread; a thread that vanished between samples is explicit, never silently zero.
- **(b)** an unknown value is never reported or treated as 0 (invalid/absent markers,
  absent peak-thread observation is a warning, no `.get(k, 0)` CPU substitution).
- **(c)** cgroup CPU/throttling evidence is read from the server PID's own cgroup
  scope; an unidentified scope fails closed.
- **(d)** per-shape headroom is a per-row measured value; a discard-sink extrapolation
  is rejected and an absent headroom is never headroom-clear.
- **(e)** an SMT/physical-core topology check rejects a mask pair that does not
  isolate physical cores, recording the actual `thread_siblings` topology.
- **(f)** the frozen protocol is bound by digest to the attempt and fails closed when
  missing, not frozen before collection, or mis-dated/post-dating collection; the
  known `2026-09-23T20:17:00Z` defect is recorded as data and never silently corrected.
- **(g)** insufficient or absent evidence yields INCONCLUSIVE or NOT_RUN, never PASS.

## Verification

```
python tests/platform/perf/pinned/verify_pinned.py
python -B -m unittest discover -s tests/harness_tests -p "test_v3_repairs.py" -v
python -B -m unittest discover -s tests/harness_tests -v
```

`test_v3_repairs.py` corrupts one instrument input at a time and asserts the scenario
never reaches PASS, that a fully qualified row still can PASS, and that no threshold
moved. See `tests/reports/release/perf-qual/HARNESS-PROVENANCE.md` for the full
harness-generation map.

## NOT_RUN list (honest)

- No stand, load campaign, Docker, Maven build or product test was executed.
- No v3 measurement was collected: every v3 campaign qualification remains NOT_RUN.
- The 72-hour soak and second-machine qualification remain NOT_RUN.
- The known mis-dated `frozenUtc` in `prep-20260922/protocol-stage7b.json` was
  **recorded as data, not repaired** (per instruction, correct nothing silently).

## Defects found but not repaired

- The **live** `tests/platform/perf/{finalize,contracts,run}.py` drift from the pins
  is recorded in `HARNESS-PROVENANCE.md`; the live files were intentionally not edited.
- The **on-stand** `soak.py` drift (`719541df…` vs pin `73bb54de…`) is outside the
  five-file estimator core and was not repaired.
- The `correction-cycle/self-verification-*/.../harness/` archives retain only
  `run.py`; the missing four files are recorded, not fabricated.
- v3 keeps the single-host layout; a two-machine runner is still not implemented.
- `P03 set_admission_reconciliation_failed` semantics are unchanged (it is
  reference-symmetric, not a product-specific defect).
