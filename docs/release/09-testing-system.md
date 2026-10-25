# Testing System And Requalification

Version 1, 2026-09-17. This is the testing-infrastructure reference, not a release
verdict. [Commands](../../tests/README.md), [normative methods](../../tests/TESTING-METHOD.md)
and [product criteria](../06-test-specifications.md) must be read together.

## Layers And Ownership

| Layer | Entry / evidence | What a success establishes |
|---|---|---|
| Independent harness checks | `tests/harness_tests`, `tests/run-checks.ps1` | Bad fixtures are rejected and positive controls work; not server correctness |
| JVM product tests | `mvnw clean verify -Pcoverage`, retained XML and merged JaCoCo | Specific contracts exercised on identified sources/JDK; not universal race freedom |
| Wire integration | `tests/platform/linux/linux_suite.py`, sibling `oracle_wire.py` | Seeded real replies, exact permitted differences, bounded transport and cleanup |
| Paired performance | `tests/platform/perf/run.py`, `contracts.py`, `finalize.py` | Only qualified metrics/scenarios receive M1/M2/M3 verdicts |
| Platform/long-run acceptance | TEST-08/09 and stand passports | Only actually tested combinations/durations; no legal conclusion |

Harness writers do not supply the sole acceptance tests for their own changes.
Runtime defects are routed to the responsible writer; integration does not adjust
expected results to obtain green output. Preserve each failed attempt and rerun
after correction in a new directory with new identities.

## Expected And Negative Matrix

| Surface | Positive expectation | Negative case and required outcome |
|---|---|---|
| STAT framing | Unique complete ASCII fields ending in END | Missing/duplicate/malformed fields or absent END fail |
| Class inventory | Independently seeded expected nonempty classes, positive population | Empty inventory, wrong class, zero population or missing mandatory field fail |
| Counters | Exact deterministic deltas, explicit reference semantics | Wrong count fails even when both servers return the same wrong value |
| Known differences | Exact allowed reply pair or precise normalization | Arbitrary ERROR, corrupt literal or truncation fails; step name is not a waiver |
| EOF/noreply | Confirmed FIN for quit; bounded silence then response barrier | Timeout is not EOF; premature close is not successful silence |
| Deadlines | Absolute deadline and maximum line/frame size | Slow-drip peer cannot extend a request indefinitely; oversized frame fails |
| Workers | All requested workers report validated values/order and are reaped | Connect/read/assertion failure or unreaped worker makes command nonzero |
| Candidate pin | Exact approved hash and package/coverage equality | Wrong or missing JAR blocks success; historical D409 is not the new candidate |
| Native performance data | Current hash-bound native JSON, successful client/preload, exact offered workload | Stale JSON, bad exit, incomplete preload, identity/argv/time mismatch is INCONCLUSIVE |
| Paired inventory | Predeclared even count >=6, unique complete rows, balanced nonoverlapping order | Missing/duplicate/unexpected rows or wrong order are INCONCLUSIVE, never dropped |
| Ratio classifier | Valid metric first; unchanged per-scenario threshold | Invalid evidence with an adverse ratio is still INCONCLUSIVE; valid adverse median is FAIL |
| CPU | Busy-client warning retained as observation | >=85% alone is not proof of bottleneck or automatic invalidity; actual throttling is a qualification reason |
| RSS | Prespecified timestamped stable window, full server RSS | Too few/close samples, drift, gaps or unstable RSS make M3 INCONCLUSIVE, not necessarily M1/M2 |
| Java lock callback | Same-category gate checked outside swallowing callback | Deliberately locked callback fails external assertion |
| Java lifetime | Native/service charge returns before allocator teardown | Leaked pin cannot pass solely because mapping deletion succeeds |
| Java deadlock | Parent deadline terminates and reaps isolated child | Noninterruptible monitor control must fail on deadline; no successful teardown claim without reap |

## Performance Definitions

M1 is paired throughput ratio >=0.90; M2 paired p99 ratio <=1.25; M3 paired full
server RSS ratio <=1.20. Each mandatory scenario stands alone. Validate common
identity/workload/client/pair data and the particular metric's requirements
before calculating its verdict. Invalidity is not a product failure and is not
success. Valid adverse medians remain FAIL; a favorable median needs a supported
interval entirely within its threshold for PASS. A crossing interval is
INCONCLUSIVE, not an excuse to relax a threshold.

Freeze an even count of at least six pairs and alternating order before execution;
no optional stopping or extra pairs selected after seeing results. The exact
nonparametric binomial-rank median interval assumes independent, continuous,
stationary paired ratios. At n=6, min/max coverage is 96.875%; at n=3, it is only
75%, not a reliable 95% interval. Coverage does not imply narrow precision or
prove the assumptions on a shared host.

The current bounded RSS policy is fixed before collection: twelve-second
measurement, one-second edge trim, >=6 samples spanning >=3 seconds, gaps <=1.5
seconds, half-window mean drift <=5%, full range <=15%. Full RSS is the qualified
window median; mean and sampled peak are additional observations. No forced GC,
favorable-subwindow search, native-counter substitution or cold/loaded subtraction
qualifies M3. Preparatory warmup is five seconds on distinct connections, not a
claim of complete JIT warmup or equilibrium. Cold P07 does not establish a stable
RSS window and cannot silently pass M3. Dataset RSS and logical/native accounting
are explanatory only; three sizes do not establish retained per-entry asymptotics.

Equal offered definitions are required, not equal completed counts in a timed
throughput comparison. Record actual operation proportions, hits/misses, bytes,
errors and residency; P06 deliberately pressures capacity rather than claiming
equal retained cardinality. CPU observations cannot establish causality without
an approved workload-preserving intervention. Do not infer P03 causality from P08
or reject a two-CPU machine solely from its advertised CPU count.

Current runtime prerequisite: Linux cgroup v2 exposes the actual limits and
counter files used by the collector. Cgroup v1 is not silently translated from
requested Docker settings; it yields explicit INCONCLUSIVE missing-evidence
reasons. The local Windows Docker Desktop daemon observed in integration uses
cgroup v1. Wire validation and native JSON collection can work there without
establishing qualified performance rows. No host/daemon conversion was performed.
Native throughput convergence uses complete interior one-second buckets after
fixed edge trim; partial boundary buckets are excluded by exposure, not because
their counts are inconvenient. All provided discrete counts/bytes are validated
before exclusion; the unchanged 5% drift policy still applies.

## Identity And History

Each new output directory is unique and retained after completion. Record hashes
of production sources, tests/resources, POM, manifest, harness, JDK, reference,
client and JAR, as applicable, before and after execution. Bind raw artifacts to
the attempt and keep analysis in a new sibling directory. Include commands,
flags, exit codes, timestamps, OS/cgroup/affinity details and owned cleanup.

The coverage-profile POM change yields candidate SHA-256
`63764015708EDBDCB2284DF53E827FA5CF2D58E904E73D3004D3F7D0165F9B70`.
It changes embedded metadata; historical D409 JAR identities cannot be asserted
equal. Do not edit old raw reports or relax pin predicates. Test/harness changes
require requalification even if production bytes do not change. Updating a stand
driver's pin does not update its staged source/test manifests or authorize remote
execution; those require a separately approved new staging attempt.

| Historical observation | Current use | Required requalification |
|---|---|---|
| Old unseeded wire/65-check report | Historical only; cannot pass new inventory oracle | Seed real classes and verify deterministic counters with new harness/current candidate |
| Old three-pair performance/bootstrapped interval | Exploratory raw observations, not 95% acceptance | Fixed >=6 balanced pairs and current metric-specific validity, if a campaign is authorized |
| Old CPU explanation or cold RSS subtraction | Unproven explanation, not exoneration | Actual measurements and causal intervention if attribution is needed |
| Earlier Java coverage/assertions | History tied to old tests/POM | Current full gate with merged child coverage and independent review |
| ROSA platform reports | Observed platform history, not new candidate qualification | New manifests and explicitly approved current-candidate run |

Two separate machines are planned. ROSA has observed historical evidence; the
second machine is not yet qualified. Neither a container smoke nor OS branding
establishes legal access or trusted-platform status. The 72-hour test remains
`NOT_RUN` in this rework, not waived or replaced by a short soak. A bounded
single paired scenario may prove native JSON reaches the classifier, but cannot
meet the fixed-pair campaign requirement or establish release performance PASS.
