"""Independent negative / discriminating tests for the v3 measurement core.

This file is authored from the documented contract only:

* ``tests/reports/release/perf-qual/HARNESS-PROVENANCE.md`` (defect map (a)-(g),
  the pinned generation hashes and the recorded frozen-protocol timestamp defect);
* ``tests/platform/perf/v3/V3-DELTAS.md`` (per-file v3 delta record and hashes);
* ``tests/platform/perf/v3/V3-NOT-CHANGED.md`` (what v3 explicitly does not change);
* the threshold/window/scenario/workload definitions in the frozen
  ``tests/platform/perf/pinned/contracts.py``.

No expected value is read out of the v3 implementation body and no expected value is
read out of observed output. Every verdict assertion is accompanied by a benign
control (the same input with the repaired instrument field restored reaches PASS) so
the assertions cannot be satisfied by an analyzer that simply refuses everything.

For each instrument-defect class (a)-(g) one or more adversarial inputs are built that
the frozen pinned core classifies as PASS (or, for (g) uncollected, that the pinned
core also refuses to pass) and the repaired v3 core must classify as INCONCLUSIVE or
NOT_RUN. Where the repair demonstrably changes the outcome the same input is run
through the pinned core and the discriminating difference is asserted. Where that
difference cannot be demonstrated the test records the gap explicitly instead of
inventing an assertion.

The suite is deliberately independent of ``fixtures.py`` and of
``test_v3_repairs.py``; it defines its own fixtures inline.

Re-validation against the six coordinator-accepted corrections
(``v3-corrections-task_2fd479124ea9/REPORT.md`` and
``V3-CORRECTIONS-BEFORE-AFTER.md``): F5.1 headroom probe provenance, F5.2
``protocol_expected_hash_missing``, F5.3 unsealed/analysis-only NOT_RUN, A
present-but-malformed ``peakClientThreadCpu`` invalid, B generator-CPU reasons common
to every metric including M3. The positive control now carries real probe provenance
and the superseded permissive expectations were tightened (never relaxed).
"""
import copy
import hashlib
import importlib.util
import pathlib
import re
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
V3_DIR = ROOT / "tests/platform/perf/v3"
PINNED_DIR = ROOT / "tests/platform/perf/pinned"
DOC_PROVENANCE = ROOT / "tests/reports/release/perf-qual/HARNESS-PROVENANCE.md"
DOC_DELTAS = V3_DIR / "V3-DELTAS.md"

# Documented contract facts (HARNESS-PROVENANCE.md, V3-DELTAS.md, V3-NOT-CHANGED.md).
DOCUMENTED_V3_CORE_VERSION = "v3.0.0"
DOCUMENTED_KNOWN_FROZEN_UTC_DEFECT = "2026-09-23T20:17:00Z"


def _load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _load_core(dirname, tag):
    """Load a core directory so its internal ``import contracts`` resolves to itself."""
    directory = ROOT / "tests/platform/perf" / dirname
    saved_contracts = sys.modules.get("contracts")
    saved_path = list(sys.path)
    contracts = _load_module(directory / "contracts.py", tag + "_contracts")
    sys.modules["contracts"] = contracts
    try:
        finalize = _load_module(directory / "finalize.py", tag + "_finalize")
        gates = _load_module(directory / "gates.py", tag + "_gates")
    finally:
        sys.path[:] = saved_path
        if saved_contracts is None:
            sys.modules.pop("contracts", None)
        else:
            sys.modules["contracts"] = saved_contracts
    return {"dir": directory, "tag": tag, "contracts": contracts,
            "finalize": finalize, "gates": gates}


PINNED = _load_core("pinned", "indep_pinned")
V3 = _load_core("v3", "indep_v3")

PINC, PINF, PING = PINNED["contracts"], PINNED["finalize"], PINNED["gates"]
V3C, V3F, V3G = V3["contracts"], V3["finalize"], V3["gates"]

# Workload and window are taken from the pinned contract (the documented reference),
# never restated as literals and never read from the v3 body.
EXPECTED_P01 = copy.deepcopy(PINC.workload("P01-c1"))
WINDOW = copy.deepcopy(PINC.WINDOW_CONFIG)
SCENARIO = "P01-c1"

IDENTITY = dict(
    attemptId="independent-attempt",
    jarSha256="a" * 64,
    referenceSha256="b" * 64,
    memtierSha256="c" * 64,
    harnessSha256="d" * 64,
    javaSha256="e" * 64,
    javaVersion="25.0.2",
    referenceVersion="1.6.45",
    octomemVersion="1.0.0 (octomem)",
)
COUNTERS = ("cmd_get", "cmd_set", "get_hits", "get_misses", "evictions", "total_items")

# A topology whose cpu 0 shares a physical core with cpu 2 and cpu 1 with cpu 3.
ISOLATED_TOPOLOGY = {"siblings": {"0": [0, 2], "1": [1, 3], "2": [0, 2], "3": [1, 3]}}
# A topology in which cpu 0 and cpu 1 are SMT siblings of the same physical core.
SMT_SHARED_TOPOLOGY = {"siblings": {"0": [0, 1], "1": [0, 1]}}
# A protocol frozen before the (epoch-based) collection start of these fixtures.
GOOD_PROTOCOL = {"frozenUtc": "1970-01-01T00:00:00Z", "frozenBeforeCollection": True,
                 "sha256": "a" * 64}
PROTOCOL_SHA = "a" * 64

# Correction F5.1 (V3-CORRECTIONS-BEFORE-AFTER.md): a per-row headroom claim is only
# admissible when the attempt records the probe it came from (source, method,
# measurement window, measured ceiling, and the probe artifact name + 64-hex sha256)
# and the row names it by probeRef; the row ceiling must reproduce the probe ceiling.
PROBE_REF = "probe-P01-c1"
PROBE_CEILING_OPS = 1000.0
HEADROOM_ACHIEVED_OPS = 50.0
PROBE_ARTIFACT = {"name": "headroom-independent.memtier.json", "sha256": "f" * 64}
GENERATOR_HEADROOM_PROBES = {
    PROBE_REF: {"source": "row-measured", "method": "responding-echo-capacity",
                "windowSeconds": 2.0, "ceilingOpsPerSecond": PROBE_CEILING_OPS,
                "probeArtifact": dict(PROBE_ARTIFACT)},
}


def _samples(start, rss):
    return [dict(monotonic=start + i, wall=start + i, total_rss=rss, connections=1,
                 serverCpuTicks={"1": i + 1}, clientCpuTicks={"2": i + 1},
                 previousServerCpuTicks={"1": i}, previousClientCpuTicks={"2": i},
                 intervalSeconds=1.0, clockTicksPerSecond=100) for i in range(10)]


def make_row(rep, server, octomem_rate=110):
    """One internally consistent, fully evidenced P01-c1 row.

    All numeric expectations here follow the pinned contract's own checks (window
    convergence, native accounting, response-byte bounds, counter reconciliation)
    and are deliberately arranged so the unmutated row reaches PASS.
    """
    reference_rss, octomem_rss = 1000000, 800000
    rate = octomem_rate if server == "octomem" else 100
    value = octomem_rss if server == "octomem" else reference_rss
    order = ["reference", "octomem"] if rep % 2 == 0 else ["octomem", "reference"]
    start = 100 + rep * 100 + order.index(server) * 30
    before = {key: "0" for key in COUNTERS}
    before.update(total_items="4096", curr_items="4096", limit_maxbytes=str(256 * 2 ** 20))
    after = dict(before)
    after.update(cmd_get=str(rate * 12), get_hits=str(rate * 12))
    cgroup = {"cpu.max": "max 100000", "cpuset.cpus.effective": "0-3",
              "memory.max": "8589934592", "memory.swap.max": "0",
              "cpu.stat": "nr_throttled 0\nthrottled_usec 0\n",
              "memory.events": "oom 0\noom_kill 0\nmax 0\n",
              "scope": "/system.slice/octomem.service", "scopeSource": "server-pid"}
    get_series = {str(i): {"Count": rate, "Bytes RX": rate * 286} for i in range(12)}
    set_series = {str(i): {"Count": 0, "Bytes RX": 0} for i in range(12)}
    native = {
        "configuration": dict(protocol="memcache_text", key_minimum=1, key_maximum=4096,
                              key_prefix="memtier-", key_pattern="R:R", data_size=256,
                              ratio="0:1", pipeline=1, expiry_range="0:0", test_time=12,
                              threads=1, clients=1),
        "ALL STATS": {
            "Runtime": {"Start time": start * 1000, "Finish time": (start + 12) * 1000,
                        "Time unit": "MILLISECONDS"},
            "Sets": {"Count": 0, "Time-Serie": set_series},
            "Gets": {"Count": rate * 12, "Misses/sec": 0, "Time-Serie": get_series},
            "Totals": {"Ops/sec": rate, "Percentile Latencies": {"p99.00": 1.0}},
        },
    }
    rss_samples = _samples(start + 1, value)
    return dict(
        scenario=SCENARIO, rep=rep, server=server, identity=copy.deepcopy(IDENTITY),
        workload=copy.deepcopy(EXPECTED_P01), exitCode=0, timedOut=False,
        preload=dict(requested=4096, stored=4096, response="STORED"),
        process=dict(argv=["server", server], version=IDENTITY[server + "Version"],
                     startedWall=start - 1),
        expectedServerArgv=["server", server], argv=["client"], expectedClientArgv=["client"],
        statsBefore=before, statsAfter=after, windowConfig=copy.deepcopy(WINDOW),
        forcedGcDuringMeasurement=False,
        cgroupBefore=copy.deepcopy(cgroup), cgroupAfter=copy.deepcopy(cgroup),
        rssWindow=copy.deepcopy(rss_samples), samples=copy.deepcopy(rss_samples),
        total_rss=value, native=native, wallStart=start - 0.1, wallEnd=start + 12.1,
        pairOrder=order, orderIndex=order.index(server), performanceQualification=True,
        generatorCpu={"status": "measured", "processCpuFraction": 0.1, "clientCpuCount": 2,
                      "firstLastSampleMismatch": False, "reasons": []},
        generatorHeadroom={"source": "row-measured", "method": "responding-echo-capacity",
                           "ceilingOpsPerSecond": PROBE_CEILING_OPS,
                           "achievedOpsPerSecond": HEADROOM_ACHIEVED_OPS,
                           "probeRef": PROBE_REF},
        peakClientThreadCpu=0.5,
    )


def baseline_rows():
    return [make_row(rep, server) for rep in range(6)
            for server in ("reference", "octomem")]


def make_passport(topology=None, protocol=None, protocol_sha=PROTOCOL_SHA,
                  server_cpus="0,2", client_cpus="1,3", qualification=True, probes=None):
    return dict(
        schemaVersion=2, repetitions=6, stoppingRule="fixed-pairs-no-extension",
        identity=copy.deepcopy(IDENTITY), workloads={SCENARIO: copy.deepcopy(EXPECTED_P01)},
        windowConfig=copy.deepcopy(WINDOW), qualification=qualification,
        serverCpus=server_cpus, clientCpus=client_cpus,
        topology=ISOLATED_TOPOLOGY if topology is None else topology,
        protocol=GOOD_PROTOCOL if protocol is None else protocol,
        protocolSha256=protocol_sha,
        generatorHeadroomProbes=copy.deepcopy(
            GENERATOR_HEADROOM_PROBES if probes is None else probes),
    )


def strip_probe_provenance(rows, passport):
    """Remove the F5.1 attempt probe provenance and every row's probeRef.

    The per-row headroom claim is left otherwise intact, so the only difference from
    the fully evidenced baseline is the missing probe provenance.
    """
    passport.pop("generatorHeadroomProbes", None)
    for row in rows:
        headroom = row.get("generatorHeadroom")
        if isinstance(headroom, dict):
            headroom.pop("probeRef", None)


def _sha256(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


class IndependentV3Case(unittest.TestCase):
    """Shared builders. v3 and pinned are driven through their documented entry points."""

    def build_pinned(self, rows, passport):
        return PINF.build_scenario(copy.deepcopy(rows), SCENARIO, copy.deepcopy(passport))

    def build_v3(self, rows, passport, qualification=True):
        return V3F.build_scenario(copy.deepcopy(rows), SCENARIO, copy.deepcopy(passport),
                                  qualification=qualification)

    def adversary(self, mutate, qualification=True):
        rows, passport = baseline_rows(), make_passport()
        mutate(rows, passport)
        return self.build_pinned(rows, passport), self.build_v3(rows, passport, qualification)

    def assert_not_pass(self, scenario, context=""):
        self.assertNotEqual(scenario["verdict"], "PASS", (context, scenario["verdict"]))
        self.assertIn(scenario["verdict"], ("INCONCLUSIVE", "NOT_RUN"), (context, scenario["verdict"]))
        self.assertTrue(any(metric["verdict"] != "PASS" for metric in scenario["metrics"].values()),
                        (context, {k: v["verdict"] for k, v in scenario["metrics"].items()}))

    def assert_pass(self, scenario, context=""):
        self.assertEqual(scenario["verdict"], "PASS", (context, scenario))

    def metric_reasons(self, scenario, metric):
        return scenario["metrics"][metric]["reasons"]

    def assert_reason(self, scenario, metric, needle, context=""):
        reasons = self.metric_reasons(scenario, metric)
        self.assertTrue(any(needle in reason for reason in reasons), (context, needle, reasons))


# --------------------------------------------------------------------------- (5)
class DocumentedContractIdentity(IndependentV3Case):
    """Threshold / window / scenario / workload equality by comparison, plus version."""

    def test_thresholds_and_tables_are_identical_by_comparison(self):
        self.assertEqual(V3C.THRESHOLDS, PINC.THRESHOLDS)
        self.assertEqual(V3C.GENERATOR_CPU_BUDGET, PINC.GENERATOR_CPU_BUDGET)
        self.assertEqual(V3C.GENERATOR_HEADROOM_MIN, PINC.GENERATOR_HEADROOM_MIN)
        self.assertEqual(V3C.WINDOW_CONFIG, PINC.WINDOW_CONFIG)
        self.assertEqual(V3C.SCENARIOS, PINC.SCENARIOS)
        self.assertEqual(V3C.IDENTITY_KEYS, PINC.IDENTITY_KEYS)
        self.assertEqual(sorted(V3C.SCENARIOS), sorted(PINC.SCENARIOS))
        for name in PINC.SCENARIOS:
            self.assertEqual(V3C.workload(name), PINC.workload(name), name)

    def test_classify_is_identical_to_the_pinned_threshold_logic(self):
        cases = [
            ("M1", [0.95] * 6, (), 6),
            ("M1", [0.5] * 6, (), 6),
            ("M2", [1.3] * 6, (), 6),
            ("M2", [1.0] * 6, (), 6),
            ("M1", [1.1] * 6, ["validity_defect"], 6),
            ("M3", [], (), None),
            ("M1", [1.1, 1.1, 1.1], (), 6),
        ]
        for metric, values, reasons, pairs in cases:
            self.assertEqual(V3C.classify(metric, values, reasons, pairs),
                             PINC.classify(metric, values, reasons, pairs),
                             (metric, values, reasons, pairs))

    def test_version_and_known_defect_are_recorded_as_documented(self):
        self.assertEqual(V3C.V3_CORE_VERSION, DOCUMENTED_V3_CORE_VERSION)
        self.assertIn(DOCUMENTED_KNOWN_FROZEN_UTC_DEFECT, V3C.KNOWN_FROZEN_UTC_DEFECTS)


# ----------------------------------------------------------- provenance binding
CORE_FILES = {"finalize.py", "contracts.py", "gates.py", "run.py", "generator.py"}


def _documented_pinned_hashes():
    text = DOC_PROVENANCE.read_text(encoding="utf-8")
    start = text.index("## 1. Campaign-pinned attempt harness")
    end = text.index("## 2. Historical three-pair attempt harness")
    result = {}
    for line in text[start:end].splitlines():
        if line.startswith("| `"):
            cells = [cell.strip().strip("`") for cell in line.strip("|").split("|")]
            name = cells[0].split(" ")[0]
            if name in CORE_FILES and len(cells) >= 2 and re.fullmatch(r"[0-9a-f]{64}", cells[1]):
                result[name] = cells[1]
    return result


def _documented_v3_hashes():
    result = {}
    for line in DOC_DELTAS.read_text(encoding="utf-8").splitlines():
        if line.startswith("| `"):
            cells = [cell.strip().strip("`") for cell in line.strip("|").split("|")]
            if (cells[0] in CORE_FILES and len(cells) >= 3
                    and re.fullmatch(r"[0-9a-f]{64}", cells[2])):
                result[cells[0]] = cells[2]
    return result


class DocumentedArtifactBinding(IndependentV3Case):
    """The documented hashes must bind the artifacts they claim to describe."""

    def test_pinned_copy_matches_the_documented_campaign_pin(self):
        documented = _documented_pinned_hashes()
        self.assertEqual(set(documented), {"finalize.py", "contracts.py", "gates.py",
                                           "run.py", "generator.py"})
        for name, expected in documented.items():
            self.assertEqual(_sha256(PINNED_DIR / name), expected, name)

    def test_v3_base_pin_map_matches_the_documented_campaign_pin(self):
        self.assertEqual(V3C.V3_BASE_SHA256, _documented_pinned_hashes())

    def test_v3_files_match_the_documented_delta_record(self):
        documented = _documented_v3_hashes()
        self.assertEqual(set(documented), {"finalize.py", "contracts.py", "gates.py",
                                           "run.py", "generator.py"})
        for name, expected in documented.items():
            with self.subTest(file=name):
                self.assertEqual(_sha256(V3_DIR / name), expected,
                                 f"{name}: documented v3 delta hash does not bind the "
                                 f"actual v3 file")


# --------------------------------------------------------------------------- (4)
class BaselinePositiveControl(IndependentV3Case):
    """A fully qualified, well-formed row still reaches PASS in both cores."""

    def test_fully_evidenced_baseline_reaches_pass(self):
        rows, passport = baseline_rows(), make_passport()
        pinned = self.build_pinned(rows, passport)
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assert_pass(pinned, "pinned baseline")
        self.assert_pass(v3, "v3 baseline")
        for metric in ("M1", "M2", "M3"):
            self.assertEqual(v3["metrics"][metric]["verdict"], "PASS", metric)
        self.assertEqual(v3["rowCount"], 12)
        self.assertEqual(v3["metrics"]["M1"]["reasons"], [])
        self.assertEqual(v3["metrics"]["M2"]["reasons"], [])
        # The fully evidenced baseline must actually exercise the F5.1 probe path: it
        # carries the probe provenance and therefore raises no probe reason.
        self.assertEqual(passport["generatorHeadroomProbes"], GENERATOR_HEADROOM_PROBES)
        self.assertFalse(any("headroom_probe" in reason
                             for metric in v3["metrics"].values()
                             for reason in metric["reasons"]))

    def test_baseline_without_probe_provenance_is_inconclusive_where_pinned_passes(self):
        rows, passport = baseline_rows(), make_passport()
        strip_probe_provenance(rows, passport)
        pinned = self.build_pinned(rows, passport)
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assert_pass(pinned, "pinned cannot see the missing probe provenance")
        self.assert_not_pass(v3, "headroom claim without probe provenance")
        self.assert_reason(v3, "M1", "generator_headroom_probe_missing")
        self.assert_reason(v3, "M2", "generator_headroom_probe_missing")
        self.assert_reason(v3, "M3", "generator_headroom_probe_missing")

    def test_v3_scenario_level_protocol_and_topology_evidence_is_valid(self):
        v3 = self.build_v3(baseline_rows(), make_passport(), qualification=True)
        self.assertTrue(v3["protocol"]["valid"], v3["protocol"])
        self.assertTrue(v3["topology"]["valid"], v3["topology"])
        self.assertEqual(v3["protocol"]["observed"]["knownFrozenUtcDefect"], False)
        self.assertEqual(v3["topology"]["observed"]["sharedCores"], [])


# --------------------------------------------------------------------------- (a)
def _mutate_a_mismatch(rows, passport):
    rows[0]["generatorCpu"]["firstLastSampleMismatch"] = True


class DefectAVanishedThreadGeneratorCpu(IndependentV3Case):
    """(a): per-row generator CPU baseline/end with a thread that vanished."""

    def test_reconstruction_never_invents_zero_for_a_retired_thread(self):
        def sample(wall, current, previous):
            return {"wall": wall, "monotonic": wall, "intervalSeconds": 1.0,
                    "clientCpuTicks": current, "previousClientCpuTicks": previous,
                    "clockTicksPerSecond": 100}
        clean = [sample(101, {"1": 10, "2": 5}, {"1": 0, "2": 0}),
                 sample(102, {"1": 20, "2": 10}, {"1": 10, "2": 5}),
                 sample(103, {"1": 30, "2": 15}, {"1": 20, "2": 10})]
        retired = [sample(101, {"1": 10, "9": 4}, {"1": 0, "9": 0}),
                   sample(102, {"1": 20}, {"1": 10, "9": 4}),
                   sample(103, {"1": 30}, {"1": 20})]
        good = V3C.corrected_generator_cpu(clean, 2, (100.0, 103.0))
        bad = V3C.corrected_generator_cpu(retired, 2, (100.0, 103.0))
        # Benign control: the clean samples are admitted (the check is not refusing
        # everything) and the frozen first/last total agrees with the accumulation.
        self.assertEqual(good["status"], "measured", good)
        self.assertFalse(good["firstLastSampleMismatch"])
        self.assertAlmostEqual(good["accumulatedCounterSeconds"],
                               good["firstLastCounterSeconds"], places=9)
        # Adversarial: the retired thread is an explicit reason, never a silent zero.
        self.assertEqual(bad["status"], "invalid", bad)
        self.assertIn("generator_cpu_thread_retired_unobserved", bad["reasons"])
        self.assertIn("generator_cpu_first_last_sample_mismatch", bad["reasons"])
        self.assertTrue(bad["firstLastSampleMismatch"])
        self.assertNotIn("processCpuFraction", bad)
        self.assertNotIn("processCpuSeconds", bad)
        # The frozen core cannot even represent this reconstruction.
        self.assertFalse(hasattr(PINC, "corrected_generator_cpu"))

    def test_mismatch_flag_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_a_mismatch)
        self.assert_pass(pinned, "pinned still passes the vanished-thread row")
        self.assert_not_pass(v3, "vanished-thread row")
        self.assert_reason(v3, "M1", "generator_cpu_first_last_sample_mismatch")
        self.assert_reason(v3, "M2", "generator_cpu_first_last_sample_mismatch")
        # Mutation-sensitivity control: clearing the repaired field cannot itself be
        # what fails, because the benign row (covered by BaselinePositiveControl)
        # reaches PASS and this exact reason is absent there.
        benign = self.build_v3(baseline_rows(), make_passport(), qualification=True)
        self.assertFalse(any("first_last_sample_mismatch" in reason
                             for metric in benign["metrics"].values()
                             for reason in metric["reasons"]))


# --------------------------------------------------------------------------- (b)
def _mutate_b_unknown(rows, passport):
    rows[0].pop("performanceQualification", None)
    rows[0]["generatorCpu"] = {"processCpuFraction": None, "clientCpuCount": 2}


class DefectBUnknownNotZero(IndependentV3Case):
    """(b): an unknown value must never be reported or treated as 0."""

    def test_unknown_cpu_evidence_refuses_where_pinned_exemption_passes(self):
        pinned, v3 = self.adversary(_mutate_b_unknown, qualification=False)
        self.assert_pass(pinned, "pinned exempts the unqualified row")
        self.assert_not_pass(v3, "unknown generator CPU")
        self.assert_reason(v3, "M1", "generator_cpu_evidence_missing")
        self.assert_reason(v3, "M2", "generator_cpu_evidence_missing")
        # Correction B: the generator-CPU reason is common, so it also gates M3.
        self.assert_reason(v3, "M3", "generator_cpu_evidence_missing")

    def test_stale_caller_alias_cannot_reopen_the_unknown(self):
        row = {"generatorCpu": {"processCpuFraction": None, "clientCpuCount": 2}}
        expected = V3C.workload(SCENARIO)
        # Adversarial: the v3 alias still fails closed; the pinned function passes.
        self.assertTrue(V3C.generator_saturation_reasons(row, expected))
        self.assertEqual(PINC.generator_saturation_reasons(row, copy.deepcopy(expected)), [])
        # Mutation-sensitivity control: with a real measured fraction both accept.
        measured = {"generatorCpu": {"processCpuFraction": 0.1, "clientCpuCount": 2},
                    "generatorHeadroom": {"source": "row-measured",
                                          "method": "responding-echo-capacity",
                                          "ceilingOpsPerSecond": 1000.0,
                                          "achievedOpsPerSecond": 50.0}}
        self.assertEqual(V3C.generator_saturation_reasons(measured, copy.deepcopy(expected)), [])
        self.assertEqual(PINC.generator_saturation_reasons(measured, copy.deepcopy(expected)), [])

    def test_absent_peak_thread_cpu_is_disclosed_not_zero(self):
        rows, passport = baseline_rows(), make_passport()
        rows[0].pop("peakClientThreadCpu", None)
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assertIn("client_thread_cpu_absent", v3["validation"][0]["warnings"])
        # The pinned core substitutes 0 via row.get(..., 0) and emits no disclosure.
        pinned = self.build_pinned(rows, passport)
        self.assertEqual(
            [w for w in pinned["validation"][0]["warnings"] if "client_thread_cpu" in w], [])


# --------------------------------------------------------------------------- (c)
def _mutate_c_root_scope(rows, passport):
    for side in ("cgroupBefore", "cgroupAfter"):
        rows[0][side] = dict(rows[0][side], scope="/")


def _mutate_c_collector_scope(rows, passport):
    for side in ("cgroupBefore", "cgroupAfter"):
        rows[0][side] = dict(rows[0][side], scope="/host.slice/collector", scopeSource="collector-pid")


class DefectCCgroupScope(IndependentV3Case):
    """(c): evidence must come from the server's own cgroup scope, not the host root."""

    def test_host_root_scope_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_c_root_scope)
        self.assert_pass(pinned, "pinned ignores the scope")
        self.assert_not_pass(v3, "host-root scope")
        self.assert_reason(v3, "M1", "cgroup_scope_unidentified:cgroupBefore")
        self.assert_reason(v3, "M1", "cgroup_scope_unidentified:cgroupAfter")

    def test_collector_scope_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_c_collector_scope)
        self.assert_pass(pinned, "pinned ignores the scope source")
        self.assert_not_pass(v3, "collector scope")
        self.assert_reason(v3, "M1", "cgroup_scope_unidentified:cgroupBefore")

    def test_scope_gate_is_strict_and_the_pinned_matrix_lacks_it(self):
        good = V3G.cgroup_scope_identifiable(
            {"scope": "/a/b", "scopeSource": "server-pid"},
            {"scope": "/a/b", "scopeSource": "server-pid"})
        collector = V3G.cgroup_scope_identifiable(
            {"scope": "/a/b", "scopeSource": "collector-pid"},
            {"scope": "/a/b", "scopeSource": "server-pid"})
        root = V3G.cgroup_scope_identifiable(
            {"scope": "/", "scopeSource": "server-pid"},
            {"scope": "/", "scopeSource": "server-pid"})
        self.assertTrue(good["valid"], good)
        self.assertFalse(collector["valid"], collector)
        self.assertFalse(root["valid"], root)
        # Mutation-sensitivity: the only difference between these inputs is the
        # repaired scope identity, so removing the check would collapse them.
        self.assertTrue(any("cgroup_scope" in r for r in collector["reasons"]))
        self.assertNotIn("cgroup_scope_identifiable", PING.gate_matrix({}))
        self.assertIn("cgroup_scope_identifiable", V3G.gate_matrix({}))


# --------------------------------------------------------------------------- (d)
def _mutate_d_absent_headroom(rows, passport):
    rows[0].pop("generatorHeadroom", None)


def _mutate_d_discard_sink(rows, passport):
    for row in rows:
        row["generatorHeadroom"] = {"source": "discard-sink",
                                    "method": "discard-sink-upper-bound",
                                    "ceilingOpsPerSecond": 100000.0,
                                    "achievedOpsPerSecond": 1.0}


# Correction F5.1 adversarial inputs: the row still *self-declares* a row-measured
# headroom, but it no longer matches the probe recorded on the attempt.
def _mutate_d_forged_probe_artifact(rows, passport):
    for probe in passport["generatorHeadroomProbes"].values():
        probe["probeArtifact"] = dict(PROBE_ARTIFACT, sha256="not-a-64-hex-digest")


def _mutate_d_fabricated_ceiling(rows, passport):
    for row in rows:
        row["generatorHeadroom"]["ceilingOpsPerSecond"] = 1e12


def _mutate_d_probe_ref_unknown(rows, passport):
    for row in rows:
        row["generatorHeadroom"]["probeRef"] = "no-such-recorded-probe"


def _mutate_d_probe_method_mismatch(rows, passport):
    for probe in passport["generatorHeadroomProbes"].values():
        probe["method"] = "discard-sink-upper-bound"


class DefectDHeadroom(IndependentV3Case):
    """(d): per-row measured headroom; a discard-sink extrapolation is not admissible."""

    def test_absent_row_headroom_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_d_absent_headroom)
        self.assert_pass(pinned, "pinned ignores absent headroom")
        self.assert_not_pass(v3, "absent per-row headroom")
        self.assert_reason(v3, "M1", "generator_headroom_evidence_missing")
        self.assert_reason(v3, "M2", "generator_headroom_evidence_missing")

    def test_discard_sink_extrapolation_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_d_discard_sink)
        self.assert_pass(pinned, "pinned accepts the discard-sink extrapolation")
        self.assert_not_pass(v3, "discard-sink headroom")
        self.assert_reason(v3, "M1", "generator_headroom_not_row_measured")

    def test_forged_probe_artifact_digest_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_d_forged_probe_artifact)
        self.assert_pass(pinned, "pinned ignores the recorded probe")
        self.assert_not_pass(v3, "probe artifact digest is not a 64-hex digest")
        self.assert_reason(v3, "M1", "generator_headroom_probe_artifact_digest_invalid")

    def test_fabricated_ceiling_does_not_reproduce_the_probe_and_refuses(self):
        pinned, v3 = self.adversary(_mutate_d_fabricated_ceiling)
        self.assert_pass(pinned, "pinned trusts the self-declared ceiling")
        self.assert_not_pass(v3, "fabricated headroom ceiling")
        self.assert_reason(v3, "M1", "generator_headroom_ceiling_mismatch")

    def test_unknown_probe_ref_and_method_mismatch_refuse(self):
        pinned, v3 = self.adversary(_mutate_d_probe_ref_unknown)
        self.assert_pass(pinned, "pinned ignores the probe reference")
        self.assert_not_pass(v3, "probeRef names no recorded probe")
        self.assert_reason(v3, "M1", "generator_headroom_probe_missing")
        pinned_method, v3_method = self.adversary(_mutate_d_probe_method_mismatch)
        self.assert_pass(pinned_method, "pinned ignores the probe method")
        self.assert_not_pass(v3_method, "probe method differs from the row method")
        self.assert_reason(v3_method, "M1", "generator_headroom_probe_method_mismatch")

    def test_matching_probe_provenance_is_admitted(self):
        # Mutation-sensitivity control: with the digest, ceiling, method and reference
        # all matching the recorded probe, the same headroom claim is admitted.
        rows, passport = baseline_rows(), make_passport()
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assert_pass(v3, "matching probe provenance")
        self.assertFalse(any("headroom_probe" in reason
                             for metric in v3["metrics"].values()
                             for reason in metric["reasons"]))

    def test_discard_sink_gate_rejects_but_the_require_switch_is_sensitive(self):
        cpu = {"processCpuFraction": 0.1, "logicalCpus": 2}
        sink = {"source": "discard-sink", "method": "discard-sink-upper-bound",
                "ceilingOpsPerSecond": 100000.0, "achievedOpsPerSecond": 1.0}
        self.assertFalse(V3G.generator_non_saturation(cpu, sink)["valid"])
        self.assertFalse(V3G.generator_non_saturation(
            cpu, sink, require_row_measured=False)["valid"])  # the method name alone is fatal
        self.assertTrue(PING.generator_non_saturation(cpu, copy.deepcopy(sink))["valid"])
        # Mutation-sensitivity control: a source-only defect is admitted once the
        # repaired per-row-source requirement is switched off.
        source_only = {"source": "some-other-collector", "method": "responding-echo-capacity",
                       "ceilingOpsPerSecond": 1000.0, "achievedOpsPerSecond": 50.0}
        strict = V3G.generator_non_saturation(cpu, source_only, require_row_measured=True)
        permissive = V3G.generator_non_saturation(cpu, source_only, require_row_measured=False)
        self.assertFalse(strict["valid"], strict)
        self.assertIn("generator_headroom_not_row_measured", strict["reasons"])
        self.assertTrue(permissive["valid"], permissive)

    def test_gate_never_marks_absent_headroom_clear(self):
        gate = V3G.generator_non_saturation({"processCpuFraction": 0.1, "logicalCpus": 2}, None)
        self.assertFalse(gate["observed"].get("headroomCleared"))
        measured = {"source": "row-measured", "method": "responding-echo-capacity",
                    "ceilingOpsPerSecond": 1000.0, "achievedOpsPerSecond": 50.0}
        self.assertTrue(V3G.generator_non_saturation(
            {"processCpuFraction": 0.1, "logicalCpus": 2}, measured)["observed"]["headroomCleared"])


# --------------------------------------------------------------------------- (e)
def _mutate_e_smt_shared(rows, passport):
    passport["serverCpus"] = "0"
    passport["clientCpus"] = "1"
    passport["topology"] = SMT_SHARED_TOPOLOGY


def _mutate_e_missing_topology(rows, passport):
    passport["topology"] = {}


class DefectETopology(IndependentV3Case):
    """(e): reject a mask pair that does not isolate physical cores."""

    def test_smt_shared_core_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_e_smt_shared)
        self.assert_pass(pinned, "pinned ignores topology")
        self.assert_not_pass(v3, "SMT-shared mask pair")
        self.assert_reason(v3, "M1", "topology:smt_mask_pair_not_core_isolated")
        self.assertEqual(v3["topology"]["observed"]["sharedCores"], [0])
        self.assertEqual(v3["topology"]["observed"]["serverCpus"], [0])
        self.assertEqual(v3["topology"]["observed"]["clientCpus"], [1])

    def test_missing_topology_fails_closed_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_e_missing_topology)
        self.assert_pass(pinned, "pinned ignores missing topology")
        self.assert_not_pass(v3, "missing topology")
        self.assert_reason(v3, "M1", "topology:topology_evidence_missing")

    def test_isolated_masks_are_admitted_and_the_observed_topology_is_published(self):
        good = V3C.topology_isolation("0,2", "1,3", ISOLATED_TOPOLOGY)
        self.assertTrue(good["valid"], good)
        self.assertEqual(good["observed"]["sharedCores"], [])
        self.assertEqual(good["observed"]["threadSiblings"], ISOLATED_TOPOLOGY["siblings"])
        # Mutation-sensitivity: the shared-core input differs only in the masks; the
        # observed topology is recorded so the decision is auditable.
        shared = V3C.topology_isolation("0", "1", SMT_SHARED_TOPOLOGY)
        self.assertFalse(shared["valid"], shared)
        self.assertEqual(shared["observed"]["sharedCores"], [0])
        self.assertFalse(hasattr(PINC, "topology_isolation"))
        self.assertNotIn("topology_isolation", PING.gate_matrix({}))
        self.assertIn("topology_isolation", V3G.gate_matrix({}))


# --------------------------------------------------------------------------- (f)
def _mutate_f_misdated(rows, passport):
    passport["protocol"] = dict(GOOD_PROTOCOL, frozenUtc=DOCUMENTED_KNOWN_FROZEN_UTC_DEFECT)


def _mutate_f_hash_mismatch(rows, passport):
    passport["protocol"] = dict(GOOD_PROTOCOL, sha256="b" * 64)


def _mutate_f_missing(rows, passport):
    passport.pop("protocol", None)
    passport.pop("protocolSha256", None)


def _mutate_f_not_frozen(rows, passport):
    passport["protocol"] = dict(GOOD_PROTOCOL, frozenBeforeCollection=False)


def _mutate_f_expected_hash_absent(rows, passport):
    passport.pop("protocolSha256", None)


class DefectFProtocol(IndependentV3Case):
    """(f): the frozen protocol must be bound to the attempt and fairly dated."""

    def test_misdated_frozen_utc_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_f_misdated)
        self.assert_pass(pinned, "pinned ignores the protocol")
        self.assert_not_pass(v3, "mis-dated frozenUtc")
        self.assert_reason(v3, "M1", "protocol:protocol_frozen_utc_postdates_collection")
        self.assertTrue(v3["protocol"]["observed"]["knownFrozenUtcDefect"])
        self.assertIn(DOCUMENTED_KNOWN_FROZEN_UTC_DEFECT, V3C.KNOWN_FROZEN_UTC_DEFECTS)

    def test_protocol_hash_mismatch_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_f_hash_mismatch)
        self.assert_pass(pinned, "pinned ignores the protocol digest binding")
        self.assert_not_pass(v3, "protocol digest mismatch")
        self.assert_reason(v3, "M1", "protocol:protocol_attempt_binding_mismatch")

    def test_missing_protocol_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_f_missing)
        self.assert_pass(pinned, "pinned ignores a missing protocol")
        self.assert_not_pass(v3, "missing protocol")
        self.assert_reason(v3, "M1", "protocol:protocol_evidence_missing")

    def test_not_frozen_before_collection_refuses(self):
        pinned, v3 = self.adversary(_mutate_f_not_frozen)
        self.assert_pass(pinned, "pinned ignores the freeze flag")
        self.assert_not_pass(v3, "not frozen before collection")
        self.assert_reason(v3, "M1", "protocol:protocol_not_frozen_before_collection")

    def test_absent_expected_protocol_hash_refuses_where_pinned_passes(self):
        pinned, v3 = self.adversary(_mutate_f_expected_hash_absent)
        self.assert_pass(pinned, "pinned ignores the protocol binding entirely")
        self.assert_not_pass(v3, "attempt carries no expected protocol hash")
        self.assert_reason(v3, "M1", "protocol:protocol_expected_hash_missing")

    def test_binding_is_sensitive_and_correct_protocol_is_valid(self):
        start = "1970-01-01T00:01:39Z"
        correct = dict(GOOD_PROTOCOL)
        wrong = dict(GOOD_PROTOCOL, sha256="b" * 64)
        self.assertTrue(V3C.protocol_identity(correct, start, PROTOCOL_SHA)["valid"])
        bound = V3C.protocol_identity(wrong, start, PROTOCOL_SHA)
        self.assertFalse(bound["valid"], bound)
        self.assertIn("protocol_attempt_binding_mismatch", bound["reasons"])
        # Correction F5.2: an absent expected hash now fails closed for BOTH a correct
        # and an incorrect digest; it no longer silently skips the binding (the
        # superseded contract asserted valid=True here).
        for document in (correct, wrong):
            absent = V3C.protocol_identity(document, start, None)
            self.assertFalse(absent["valid"], absent)
            self.assertIn("protocol_expected_hash_missing", absent["reasons"])


# --------------------------------------------------------------------------- (g)
def _mutate_g_under_evidenced(rows, passport):
    rows[0].pop("performanceQualification", None)
    rows[0].pop("generatorCpu", None)
    rows[0].pop("generatorHeadroom", None)


class DefectGFailClosedInsufficiency(IndependentV3Case):
    """(g): insufficient or absent evidence must be INCONCLUSIVE or NOT_RUN, never PASS."""

    def test_under_evidenced_unqualified_row_refuses_where_pinned_exempts(self):
        pinned, v3 = self.adversary(_mutate_g_under_evidenced, qualification=False)
        self.assert_pass(pinned, "pinned qualification exemption admits the row")
        self.assert_not_pass(v3, "under-evidenced row")
        self.assert_reason(v3, "M1", "generator_cpu_evidence_missing")
        # The absent headroom is also refused, seen on a row whose CPU evidence is
        # present but whose per-row headroom is missing.
        rows, passport = baseline_rows(), make_passport()
        rows[0].pop("generatorHeadroom", None)
        with_headroom_gap = self.build_v3(rows, passport, qualification=True)
        self.assert_reason(with_headroom_gap, "M1", "generator_headroom_evidence_missing")

    def test_uncollected_scenario_is_not_run_and_not_pass(self):
        passport = make_passport()
        v3 = self.build_v3([], passport, qualification=True)
        pinned = self.build_pinned([], passport)
        self.assertEqual(v3["verdict"], "NOT_RUN", v3["verdict"])
        for metric in v3["metrics"].values():
            self.assertEqual(metric["verdict"], "NOT_RUN")
            self.assertNotIn("medianPairedRatio", metric)
            self.assertIn("scenario_not_run", metric["reasons"])
        # Gap record: the pinned core also refuses to PASS here (INCONCLUSIVE), so
        # this class has no "pinned PASS" discriminator - it is reported, not forced.
        self.assertNotEqual(pinned["verdict"], "PASS", pinned["verdict"])

    def test_a_missing_pair_refuses_in_both_cores(self):
        rows = baseline_rows()[:-1]
        passport = make_passport()
        v3 = self.build_v3(rows, passport, qualification=True)
        pinned = self.build_pinned(rows, passport)
        self.assert_not_pass(v3, "missing pair v3")
        self.assert_not_pass(pinned, "missing pair pinned")

    def test_no_reason_is_ever_needed_for_pass_in_the_benign_control(self):
        # The complement of this class: a fully evidenced scenario (including the
        # now-required F5.1 probe provenance) has empty reasons and reaches PASS, so
        # the fail-closed behaviour is not unconditional.
        rows, passport = baseline_rows(), make_passport()
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assert_pass(v3, "benign control")
        self.assertEqual(v3["metrics"]["M1"]["reasons"], [])
        # Stripping only the probe provenance from the same qualified rows refuses.
        strip_probe_provenance(rows, passport)
        stripped = self.build_v3(rows, passport, qualification=True)
        self.assert_not_pass(stripped, "benign control without probe provenance")
        self.assert_reason(stripped, "M1", "generator_headroom_probe_missing")


# ------------------------------------------------- six-correction hardening checks
class CorrectionHardening(IndependentV3Case):
    """The six corrections have no permissive default on the documented verdict path.

    F5.3 (unsealed/analysis-only NOT_RUN), A (malformed peak invalid, absent
    disclosed) and B (generator reasons common to M1/M2/M3) are additionally pinned
    here because they are not "pinned PASS" discriminators.
    """

    def test_analysis_only_scenario_cannot_report_pass(self):
        rows, passport = baseline_rows(), make_passport()
        pinned = self.build_pinned(rows, passport)
        v3 = self.build_v3(rows, passport, qualification=False)
        self.assert_pass(pinned, "pinned has no analysis-only concept")
        self.assert_not_pass(v3, "analysis-only scenario")
        self.assertEqual(v3["verdict"], "NOT_RUN", v3["verdict"])
        for metric in v3["metrics"].values():
            self.assertEqual(metric["verdict"], "NOT_RUN")
            self.assertIn("analysis_only_not_qualified", metric["reasons"])

    def test_unsealed_attempt_is_not_run(self):
        rows, passport = baseline_rows(), make_passport()
        passport.pop("qualification", None)
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assert_not_pass(v3, "attempt with no recorded seal")
        self.assertEqual(v3["verdict"], "NOT_RUN", v3["verdict"])
        for metric in v3["metrics"].values():
            self.assertIn("attempt_seal_missing", metric["reasons"])

    def test_malformed_peak_thread_cpu_is_a_common_invalid_reason(self):
        rows, passport = baseline_rows(), make_passport()
        rows[0]["peakClientThreadCpu"] = "not-a-number"
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assert_not_pass(v3, "present-but-malformed peak thread cpu")
        for metric in ("M1", "M2", "M3"):
            self.assert_reason(v3, metric, "client_thread_cpu_invalid")
        # A genuinely absent value stays a disclosed warning, never invalid and never 0.
        rows2, passport2 = baseline_rows(), make_passport()
        rows2[0].pop("peakClientThreadCpu", None)
        clean = self.build_v3(rows2, passport2, qualification=True)
        self.assert_pass(clean, "absent peak is disclosed but not fatal")
        self.assertIn("client_thread_cpu_absent", clean["validation"][0]["warnings"])
        self.assertFalse(any("client_thread_cpu_invalid" in reason
                             for metric in clean["metrics"].values()
                             for reason in metric["reasons"]))

    def test_generator_cpu_reasons_gate_every_metric_including_m3(self):
        rows, passport = baseline_rows(), make_passport()
        rows[0]["generatorCpu"] = {"processCpuFraction": None, "clientCpuCount": 2}
        v3 = self.build_v3(rows, passport, qualification=True)
        for metric in ("M1", "M2", "M3"):
            self.assert_reason(v3, metric, "generator_cpu_evidence_missing")

    def test_build_scenario_never_admits_a_provenance_less_headroom(self):
        # The documented verdict path always constructs the provenance dict (it never
        # passes None), so a self-declared row-measured headroom without recorded
        # probe provenance cannot reach a scenario PASS on any metric.
        rows, passport = baseline_rows(), make_passport()
        strip_probe_provenance(rows, passport)
        v3 = self.build_v3(rows, passport, qualification=True)
        self.assert_not_pass(v3, "provenance-less headroom on the verdict path")
        for metric in ("M1", "M2", "M3"):
            self.assert_reason(v3, metric, "generator_headroom_probe_missing")


# --------------------------------------------------------- explicit discriminator map
def _mutate_unseal(rows, passport):
    passport.pop("qualification", None)


# Per the corrected contract: `baseline_benign` now carries the F5.1 probe provenance
# and is the only PASS/PASS row; a baseline without it is the superseded permissive
# case that must now refuse (the review's F5.1 finding).
DISCRIMINATOR_MATRIX = [
    ("baseline_benign", lambda rows, passport: None, True, ("PASS", "PASS")),
    ("baseline_without_probe_provenance", strip_probe_provenance, True,
     ("PASS", "INCONCLUSIVE")),
    ("a_vanished_thread_mismatch", _mutate_a_mismatch, True, ("PASS", "INCONCLUSIVE")),
    ("b_unknown_cpu", _mutate_b_unknown, False, ("PASS", "INCONCLUSIVE")),
    ("c_host_root_scope", _mutate_c_root_scope, True, ("PASS", "INCONCLUSIVE")),
    ("c_collector_scope", _mutate_c_collector_scope, True, ("PASS", "INCONCLUSIVE")),
    ("d_absent_headroom", _mutate_d_absent_headroom, True, ("PASS", "INCONCLUSIVE")),
    ("d_discard_sink_headroom", _mutate_d_discard_sink, True, ("PASS", "INCONCLUSIVE")),
    ("d_forged_probe_artifact", _mutate_d_forged_probe_artifact, True,
     ("PASS", "INCONCLUSIVE")),
    ("d_fabricated_ceiling", _mutate_d_fabricated_ceiling, True, ("PASS", "INCONCLUSIVE")),
    ("e_smt_shared_core", _mutate_e_smt_shared, True, ("PASS", "INCONCLUSIVE")),
    ("f_misdated_protocol", _mutate_f_misdated, True, ("PASS", "INCONCLUSIVE")),
    ("f_protocol_hash_mismatch", _mutate_f_hash_mismatch, True, ("PASS", "INCONCLUSIVE")),
    ("f_missing_protocol", _mutate_f_missing, True, ("PASS", "INCONCLUSIVE")),
    ("f_expected_hash_absent", _mutate_f_expected_hash_absent, True,
     ("PASS", "INCONCLUSIVE")),
    ("g_under_evidenced_row", _mutate_g_under_evidenced, False, ("PASS", "INCONCLUSIVE")),
    ("f5_3_analysis_only", lambda rows, passport: None, False, ("PASS", "NOT_RUN")),
    ("f5_3_unsealed_attempt", _mutate_unseal, True, ("PASS", "NOT_RUN")),
    ("g_uncollected_scenario", lambda rows, passport: rows.clear(), True,
     ("INCONCLUSIVE", "NOT_RUN")),
]


class DiscriminatorMatrix(IndependentV3Case):
    """The explicit list of classes and whether the pinned core passes where v3 refuses."""

    def test_each_class_has_the_recorded_pinned_vs_v3_outcome(self):
        for name, mutate, qualification, expected in DISCRIMINATOR_MATRIX:
            with self.subTest(case=name):
                pinned, v3 = self.adversary(mutate, qualification)
                observed = (pinned["verdict"], v3["verdict"])
                self.assertEqual(observed, expected, (name, observed))
                if expected[1] != "PASS":
                    self.assertNotEqual(v3["verdict"], "PASS", name)

    def test_every_class_with_a_pinned_pass_has_v3_refusing(self):
        demonstrated, gaps = [], []
        for name, mutate, qualification, _ in DISCRIMINATOR_MATRIX:
            if name == "baseline_benign":
                continue
            pinned, v3 = self.adversary(mutate, qualification)
            if pinned["verdict"] == "PASS" and v3["verdict"] != "PASS":
                demonstrated.append(name)
            elif pinned["verdict"] != "PASS" and v3["verdict"] != "PASS":
                gaps.append(name)
            else:
                self.fail(f"{name}: unexpected pinned={pinned['verdict']} v3={v3['verdict']}")
        # Every instrument-defect class except the uncollected-scenario case (where
        # the pinned core also fails closed) has a demonstrated discriminating
        # difference. This asserts the matrix is complete and not accidentally empty.
        self.assertEqual(sorted(demonstrated), sorted(
            name for name, _, _, expected in DISCRIMINATOR_MATRIX
            if expected[0] == "PASS" and name != "baseline_benign"))
        self.assertEqual(gaps, ["g_uncollected_scenario"])


if __name__ == "__main__":
    unittest.main()
