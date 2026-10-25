# What v3 does NOT change

This is the explicit non-change statement for `tests/platform/perf/v3/` (`v3.0.0`).

v3 does **not** change, raise, lower, add or remove:

- any M1/M2/M3 threshold: `M1 >= 0.90`, `M2 <= 1.25`, `M3 total RSS <= 1.20`
  (`THRESHOLDS` unchanged);
- `GENERATOR_CPU_BUDGET = 0.85` or `GENERATOR_HEADROOM_MIN = 2.0`;
- the measurement window `WINDOW_CONFIG` (minSamples 6, minSpanSeconds 3.0,
  maxGapSeconds 1.5, maxRelativeDrift 0.05, maxRelativeRange 0.15, trimSeconds 1.0,
  ratioTolerance 0.03, measurementSeconds 12);
- the 15 `SCENARIOS` tuples or any `workload(name)` output;
- `IDENTITY_KEYS`, the `classify` threshold comparison, `median_interval`,
  `validate_pairs`, `qualify_window`, `stats_delta` or any pinned row check;
- the campaign pairing/order rules, the stop rule, or the attempt/calibration/
  harness-archive completion checks;
- the product candidate JAR pin `950a8edb702e51c9979320c117257426f1109076845e80f30774ef0855ff682b`,
  `pom.xml`, `compatibility-manifest.json` or any product source;
- the live `tests/platform/perf/*.py`, the pinned copy under
  `tests/platform/perf/pinned/`, the retained attempt-harness directories under
  `tests/reports/release/perf-qual/rosa-20260918-6pairs/` and
  `tests/reports/release/perf-qual/correction-cycle/`, or any historical raw file;
- the M1/M2/M3 definitions or which metric a reason gates, except that v3 **adds**
  fail-closed instrument reasons (which can only make a scenario INCONCLUSIVE or
  NOT_RUN, never PASS).

v3 **adds** only instrument-integrity evidence and repaired extraction:

- a server-pid cgroup scope requirement (defect c);
- a per-row measured headroom requirement that rejects discard-sink extrapolation
  (defect d);
- an SMT/physical-core topology isolation requirement, recording the observed
  `thread_siblings` topology (defect e);
- a frozen-protocol digest and timestamp binding that fails closed (defect f);
- a per-consecutive-sample generator-CPU reconstruction that never invents zero
  (defects a/b);
- `NOT_RUN` for a scenario that was never collected (defect g).

Repairing a defect is not a threshold change. The before/after comparison is in
`THRESHOLDS-BEFORE-AFTER.md` and is asserted by `tests/harness_tests/test_v3_repairs.py`.

## The six coordinator-accepted corrections further restrict the core

The corrections F5.1, F5.2, F5.3, A and B (and the test-only F5.4) change **no**
threshold, window bound, scenario, workload, pair/warmup rule or validity gate
criterion. They only ADD fail-closed reasons or move an existing reason to a
stricter scope:

- F5.1 adds attempt-level headroom-probe provenance binding (a fabricated or
  unproven ceiling now fails).
- F5.2 makes `protocol_identity` fail closed when the expected protocol hash is
  absent, instead of being permissive.
- A restores the pinned invalidity for a present-but-malformed
  `peakClientThreadCpu` (absent remains a disclosed, non-zero warning).
- B restores generator-CPU validity reasons as COMMON reasons over every metric,
  as the pinned core had them, so no row can newly PASS M3 on absent evidence.
- F5.3 makes an unrecorded/unsealed attempt NOT_RUN and stops an analysis-only
  scenario from reporting PASS; `main` returns `EXIT_NOT_RUN`.

Every correction makes the core stricter and cannot create a new PASS path; the
pinned core already rejected the class-B row at M3 and the pinned `protocol`
behaviour was fail-closed only by the reader, not by the isolated API.
