# v3 six-correction before/after table

Source of truth: `tests/reports/release/perf-qual/verification/v3-review-task_543c88eb9f49.md`
(sha256 `A02985A14C9E71889B89CB569ECA5FFD0C97E55344089B83395DC1C1FFDEBDC1`).
All six corrections make the core **stricter** and create **no new PASS path**.

| Class | Adversarial input | Previous v3 verdict | Corrected v3 verdict | New reason |
|---|---|---|---|---|
| F5.1 | `generatorHeadroom={"source":"row-measured","method":"responding-echo-capacity","ceilingOpsPerSecond":1e12,"achievedOpsPerSecond":1.0}` on every row | scenario **PASS** (review F5.1) | **INCONCLUSIVE** | `generator_headroom_ceiling_mismatch` |
| F5.1 | headroom present but attempt carries no `generatorHeadroomProbes` | scenario **PASS** | **INCONCLUSIVE** | `generator_headroom_probe_missing` |
| F5.1 | `probeRef` names no recorded probe / probe method differs / probe artifact sha not 64-hex | scenario **PASS** | **INCONCLUSIVE** | `generator_headroom_probe_missing` / `generator_headroom_probe_method_mismatch` / `generator_headroom_probe_artifact_digest_invalid` |
| F5.2 | `protocol_identity(protocol, start, expected_sha256=None)` | **valid=True** (review F5.2; isolated API) | **valid=False** | `protocol_expected_hash_missing` |
| A | `peakClientThreadCpu = "not-a-number"` (present, malformed) | **valid=True** + warning (review caveat A) | **valid=False** | `client_thread_cpu_invalid` |
| A | `peakClientThreadCpu` absent | warning `client_thread_cpu_absent` (never 0) | warning `client_thread_cpu_absent` (never 0) | unchanged (already fail-closed/disclosed) |
| B | 6 pairs with `generatorCpu.status="absent"` | M1/M2 INCONCLUSIVE, **M3 PASS** (review caveat B) | M1/M2 **INCONCLUSIVE**, M3 **INCONCLUSIVE** | `generator_cpu_evidence_missing` now a COMMON reason on every metric |
| F5.3 | unrecorded/unsealed attempt with full valid evidence, driven through `main` | scenario **PASS**, `main` returns 0 | **NOT_RUN**, `main` returns `EXIT_NOT_RUN` (2) | `attempt_seal_missing` |
| F5.3 | analysis-only scenario (`qualification=False`) with a valid metric | metric **PASS** | metric **NOT_RUN** | `analysis_only_not_qualified` |
| F5.4 | `test_gate_never_marks_absent_headroom_clear` assertion | `assertFalse(gate["observed"].get("headroomCleared"))` (passes if key absent) | `assertIn(...)` + `assertIs(..., False)` | (test-only; mutation-sensitive) |

Baseline control: a fully qualified, fully evidenced row (with matching probe
provenance and a recorded seal) still reaches **PASS**; `THRESHOLDS`,
`GENERATOR_CPU_BUDGET`, `GENERATOR_HEADROOM_MIN`, `WINDOW_CONFIG`, `SCENARIOS` and
every `workload(...)` are unchanged (see `THRESHOLDS-BEFORE-AFTER.md`).

## New v3 file hashes after the corrections

```
contracts.py       414ee5178439511a94a968a53a71a3f1b9ddad755ec3550febb3d07151d109c6
finalize.py        a9d1562df12e151f8fe69de292fa377876b439f4877ebbef96071df9ee81ee1e
gates.py           4df1dd1a269776cf31e2ed4c128161c6c4033a28ea1a56d2e97c8722e021007a  (unchanged)
run.py             99d26fd3760f9a4228197792af9a308adcc0cdfc0f0ef1558957509c16490422  (unchanged)
generator.py       d9862339e0c207cc7fac6939926cf91c04ca7e59887d888daadf6fdf887d8f22  (unchanged)
run_two_machine.py dd77c38a0f4708dfe0aa92a0d24591cc827a97f0200ba563c92e8fa81c8d733a  (F5.1 provenance added to the runner)
```

## Suite result

- `tests/harness_tests/test_v3_repairs.py`: **41 tests, OK** (15 new focused tests:
  6 for F5.1, 2 for F5.2, 3 for F5.3, 2 for A, 2 for B; F5.4 assertion fixed).
- `tests/harness_tests/test_two_machine_runner.py`: **15 tests, OK** (updated per the
  coordinator ruling: the missing-headroom reason is asserted in the COMMON reasons
  that gate M1, M2 and M3, and the scenario must not be PASS).
- Full harness discovery: **134 discovered, 130 pass, exactly 4 fail**, all in
  `test_v3_independent.py` (left untouched by ruling), each traced to a correction:
  - `BaselinePositiveControl.test_fully_evidenced_baseline_reaches_pass` -> expected
    PASS, actual INCONCLUSIVE (F5.1: the fixture baseline carries no probe provenance);
  - `DefectFProtocol.test_binding_is_sensitive_and_correct_protocol_is_valid` ->
    asserted `protocol_identity(..., None)` valid, actual invalid (F5.2);
  - `DefectGFailClosedInsufficiency.test_no_reason_is_ever_needed_for_pass_in_the_benign_control`
    -> expected PASS, actual INCONCLUSIVE (F5.1);
  - `DiscriminatorMatrix.test_each_class_has_the_recorded_pinned_vs_v3_outcome[baseline_benign]`
    -> expected `('PASS','PASS')`, actual `('PASS','INCONCLUSIVE')` (F5.1).
  Raw command and output: `tests/reports/release/perf-qual/v3-corrections-task_2fd479124ea9/independent-suite-output.txt`.
- Pinned copy verifier: `PINNED_COPY_BYTE_IDENTICAL`, exit 0 (`pinned/**` untouched).

## Functional check: the real two-machine path satisfies F5.1

`run_two_machine.py` was updated to record, and the bounded NON-QUALIFYING rehearsal
re-run (attempt `attempt-35a3259b039140f7b1b0dea50ad3abd9`): the passport now carries
`generatorHeadroomProbes` for `P01-c1` and `P04` (method, `windowSeconds`, measured
`ceilingOpsPerSecond`, and `probeArtifact` name + 64-hex sha256), and each row's
`generatorHeadroom` carries the matching `probeRef`. The corrected core reports
**zero** `generator_headroom_probe*` reasons, so F5.1 is satisfiable by the real
runner; the rehearsal remains INCONCLUSIVE/NOT_RUN (no PASS) only because the bounded
subset deliberately omits calibration and the 6-pair plan. An official campaign under
this contract would therefore not be refused by F5.1; it is still NOT_RUN here.
