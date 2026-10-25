"""V3 instrument-defect repairs: corrupted inputs must be INCONCLUSIVE or NOT_RUN.

Every test loads the new explicitly versioned core from
`tests/platform/perf/v3/**` and, where useful, the frozen `pinned/**` copy. The
suite deliberately corrupts one instrument input at a time and asserts that the
repaired defect now fails closed (never PASS) and that no M1/M2/M3 threshold moved.
"""
import copy
import hashlib
import importlib.util
import pathlib
import sys
import unittest

from fixtures import P01, ROOT, WINDOW, passport, row  # noqa: F401  (P01/WINDOW used below)

V3_DIR = ROOT / "tests/platform/perf/v3"
PINNED_DIR = ROOT / "tests/platform/perf/pinned"


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _sha256(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


class V3CoreCase(unittest.TestCase):
    """Loads v3 exactly as a caller would, without letting module-name collisions win."""

    @classmethod
    def setUpClass(cls):
        cls._saved_modules = {name: sys.modules.get(name) for name in ("contracts", "finalize", "run", "generator")}
        cls._saved_path = list(sys.path)
        cls.c = _load(V3_DIR / "contracts.py", "v3_contracts_under_test")
        sys.modules["contracts"] = cls.c  # finalize/run fallback must resolve to v3
        cls.f = _load(V3_DIR / "finalize.py", "v3_finalize_under_test")
        cls.g = _load(V3_DIR / "gates.py", "v3_gates_under_test")

    @classmethod
    def tearDownClass(cls):
        for name, value in cls._saved_modules.items():
            if value is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = value
        sys.path[:] = cls._saved_path

    # -- fixtures -----------------------------------------------------------
    def v3_row(self, rep, server, rate=100, rss=1000000, cpu_count=2, headroom=True, scope=True, cpu=True):
        item = copy.deepcopy(row(rep, server, rate if server == "octomem" else 100, rss if server == "octomem" else 1000000))
        item["performanceQualification"] = True
        if scope:
            for side in ("cgroupBefore", "cgroupAfter"):
                item[side] = dict(item[side], scope="/system.slice/octomem.service", scopeSource="server-pid")
        if cpu:
            item["generatorCpu"] = {"status": "measured", "processCpuFraction": 0.1,
                                    "clientCpuCount": cpu_count, "firstLastSampleMismatch": False, "reasons": []}
        if headroom:
            item["generatorHeadroom"] = {"source": "row-measured", "method": "responding-echo-capacity",
                                         "ceilingOpsPerSecond": 1000.0, "achievedOpsPerSecond": 50.0,
                                         "probeRef": "P01-c1"}
        return item

    def v3_rows(self, rate=100, **kw):
        return [self.v3_row(rep, server, rate, **kw) for rep in range(6) for server in ("reference", "octomem")]

    def v3_passport(self, protocol_utc="1969-12-31T23:00:00Z", server_cpus="0,2", client_cpus="1,3", topology=None):
        result = passport()
        result["serverCpus"] = server_cpus
        result["clientCpus"] = client_cpus
        result["topology"] = topology if topology is not None else {"siblings": {"0": [0, 2], "1": [1, 3], "2": [0, 2], "3": [1, 3]}}
        result["protocol"] = {"frozenUtc": protocol_utc, "frozenBeforeCollection": True, "sha256": "a" * 64}
        result["protocolSha256"] = "a" * 64
        result["qualification"] = True
        result["captureStatus"] = {"sealed": True}
        result["generatorHeadroomProbes"] = {"P01-c1": {
            "source": "row-measured", "method": "responding-echo-capacity", "windowSeconds": 2.0,
            "ceilingOpsPerSecond": 1000.0,
            "probeArtifact": {"name": "headroom-P01-c1.memtier.json", "sha256": "f" * 64}}}
        return result

    def assert_not_pass(self, scenario):
        self.assertIn(scenario["verdict"], ("INCONCLUSIVE", "NOT_RUN"), scenario)
        self.assertTrue(any(metric["verdict"] != "PASS" for metric in scenario["metrics"].values()), scenario)

    def assert_metric_not_pass(self, scenario, metric):
        self.assertNotEqual(scenario["metrics"][metric]["verdict"], "PASS", scenario["metrics"][metric])
        self.assertTrue(scenario["metrics"][metric]["reasons"], scenario["metrics"][metric])


class BaselinePass(V3CoreCase):
    def test_fully_qualified_row_still_passes(self):
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", self.v3_passport(), qualification=True)
        self.assertEqual(scenario["verdict"], "PASS", scenario)
        for metric in scenario["metrics"].values():
            self.assertEqual(metric["verdict"], "PASS", metric)


class DefectA(V3CoreCase):
    def _sample(self, wall, current, previous):
        return {"wall": wall, "monotonic": wall, "intervalSeconds": 1.0, "clientCpuTicks": current,
                "previousClientCpuTicks": previous, "clockTicksPerSecond": 100}

    def test_clean_samples_measure_without_mismatch(self):
        samples = [self._sample(101, {"1": 10, "2": 5}, {"1": 0, "2": 0}),
                   self._sample(102, {"1": 20, "2": 10}, {"1": 10, "2": 5}),
                   self._sample(103, {"1": 30, "2": 15}, {"1": 20, "2": 10})]
        result = self.c.corrected_generator_cpu(samples, 2, (100.0, 103.0))
        self.assertEqual(result["status"], "measured", result)
        self.assertFalse(result["firstLastSampleMismatch"])
        self.assertAlmostEqual(result["accumulatedCounterSeconds"], result["firstLastCounterSeconds"], places=9)

    def test_retired_thread_is_explicit_never_zero(self):
        samples = [self._sample(101, {"1": 10, "9": 4}, {"1": 0, "9": 0}),
                   self._sample(102, {"1": 20}, {"1": 10, "9": 4}),
                   self._sample(103, {"1": 30}, {"1": 20})]
        result = self.c.corrected_generator_cpu(samples, 2, (100.0, 103.0))
        self.assertEqual(result["status"], "invalid", result)
        self.assertIn("generator_cpu_thread_retired_unobserved", result["reasons"])
        self.assertIn("generator_cpu_first_last_sample_mismatch", result["reasons"])
        self.assertIsNone(result["processCpuFraction"]) if "processCpuFraction" in result else None
        self.assertTrue(result["firstLastSampleMismatch"])

    def test_first_last_mismatch_is_represented(self):
        # A thread present in the first baseline and last current, absent in the middle.
        samples = [self._sample(101, {"1": 10, "7": 10}, {"1": 0, "7": 0}),
                   self._sample(102, {"1": 20}, {"1": 10, "7": 10}),
                   self._sample(103, {"1": 30}, {"1": 20})]
        result = self.c.corrected_generator_cpu(samples, 2, (100.0, 103.0))
        self.assertTrue(result["firstLastSampleMismatch"])
        self.assertNotEqual(result["firstLastCounterSeconds"], result["accumulatedCounterSeconds"])

    def test_invalid_cpu_row_cannot_pass(self):
        rows = self.v3_rows(rate=110, rss=800000)
        rows[1]["generatorCpu"] = {"status": "invalid", "processCpuFraction": None,
                                   "clientCpuCount": 2, "reasons": ["generator_cpu_thread_retired_unobserved"]}
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_not_pass(scenario)
        self.assert_metric_not_pass(scenario, "M1")
        self.assert_metric_not_pass(scenario, "M2")


class DefectB(V3CoreCase):
    def test_unknown_cpu_fraction_is_not_zero(self):
        rows = self.v3_rows(rate=110, rss=800000)
        rows[0]["generatorCpu"] = {"status": "absent", "processCpuFraction": None, "clientCpuCount": 2}
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_not_pass(scenario)
        self.assert_metric_not_pass(scenario, "M1")
        self.assertTrue(any("generator_cpu_evidence_missing" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_missing_peak_thread_observation_is_disclosed_not_zero(self):
        rows = self.v3_rows(rate=110, rss=800000)
        rows[0].pop("peakClientThreadCpu", None)
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assertIn("client_thread_cpu_absent", scenario["validation"][0]["warnings"])


class DefectC(V3CoreCase):
    def test_host_root_scope_fails_closed(self):
        rows = self.v3_rows(rate=110, rss=800000)
        for side in ("cgroupBefore", "cgroupAfter"):
            rows[0][side] = dict(rows[0][side], scope="/", scopeSource="server-pid")
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_not_pass(scenario)
        self.assertTrue(any("cgroup_scope_unidentified" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_missing_scope_fails_closed(self):
        rows = self.v3_rows(rate=110, rss=800000)
        for side in ("cgroupBefore", "cgroupAfter"):
            rows[0][side] = {k: v for k, v in rows[0][side].items() if k not in ("scope", "scopeSource")}
        self.assert_not_pass(self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True))

    def test_cgroup_scope_gate_rejects_collector_scope(self):
        gate = self.g.cgroup_scope_identifiable({"scope": "/", "scopeSource": "collector-pid"}, {"scope": "/x", "scopeSource": "server-pid"})
        self.assertFalse(gate["valid"])
        self.assertTrue(any("cgroup_scope" in r for r in gate["reasons"]))
        ok = self.g.cgroup_scope_identifiable({"scope": "/a/b", "scopeSource": "server-pid"}, {"scope": "/a/b", "scopeSource": "server-pid"})
        self.assertTrue(ok["valid"], ok)


class DefectD(V3CoreCase):
    def test_absent_per_row_headroom_is_not_headroom_clear(self):
        rows = self.v3_rows(rate=110, rss=800000, headroom=False)
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_not_pass(scenario)
        self.assertTrue(any("generator_headroom_evidence_missing" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_discard_sink_extrapolation_is_rejected(self):
        rows = self.v3_rows(rate=110, rss=800000)
        for item in rows:
            item["generatorHeadroom"] = {"source": "discard-sink", "method": "discard-sink-upper-bound",
                                         "ceilingOpsPerSecond": 100000.0, "achievedOpsPerSecond": 1.0}
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_not_pass(scenario)
        self.assertTrue(any("generator_headroom_not_row_measured" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_gate_never_marks_absent_headroom_clear(self):
        gate = self.g.generator_non_saturation({"processCpuFraction": 0.1, "logicalCpus": 2}, None)
        # F5.4: the key must be PRESENT and exactly False (a revert removing the
        # kwarg would make .get() return None and still look falsy).
        self.assertIn("headroomCleared", gate["observed"])
        self.assertIs(gate["observed"]["headroomCleared"], False)

    def test_below_minimum_headroom_fails(self):
        rows = self.v3_rows(rate=110, rss=800000)
        for item in rows:
            item["generatorHeadroom"] = {"source": "row-measured", "method": "responding-echo-capacity",
                                         "ceilingOpsPerSecond": 100.0, "achievedOpsPerSecond": 60.0}
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_not_pass(scenario)
        self.assertTrue(any("generator_headroom_below_minimum" in r for r in scenario["metrics"]["M1"]["reasons"]))


class DefectE(V3CoreCase):
    def test_non_isolating_masks_are_rejected(self):
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1",
                                         self.v3_passport(server_cpus="0-1", client_cpus="2-3"), qualification=True)
        self.assert_not_pass(scenario)
        self.assertTrue(any("topology:smt_mask_pair_not_core_isolated" in r for r in scenario["metrics"]["M1"]["reasons"]))
        self.assertEqual(scenario["topology"]["observed"]["sharedCores"], [0, 1])

    def test_isolating_masks_are_accepted(self):
        gate = self.g.topology_isolation("0,2", "1,3", {"siblings": {"0": [0, 2], "1": [1, 3], "2": [0, 2], "3": [1, 3]}})
        self.assertTrue(gate["valid"], gate)
        self.assertEqual(gate["observed"]["sharedCores"], [])

    def test_missing_topology_fails_closed(self):
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1",
                                         self.v3_passport(topology={}), qualification=True)
        self.assert_not_pass(scenario)


class DefectF(V3CoreCase):
    def test_known_misdated_frozen_utc_fails_closed_and_is_recorded(self):
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1",
                                         self.v3_passport(protocol_utc="2026-09-23T20:17:00Z"), qualification=True)
        self.assert_not_pass(scenario)
        self.assertTrue(any("protocol:protocol_frozen_utc_postdates_collection" in r for r in scenario["metrics"]["M1"]["reasons"]))
        self.assertTrue(scenario["protocol"]["observed"]["knownFrozenUtcDefect"])

    def test_protocol_hash_mismatch_fails_closed(self):
        passport_ = self.v3_passport()
        passport_["protocol"]["sha256"] = "b" * 64
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport_, qualification=True)
        self.assert_not_pass(scenario)

    def test_missing_protocol_fails_closed(self):
        passport_ = self.v3_passport()
        passport_.pop("protocol")
        passport_.pop("protocolSha256")
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport_, qualification=True)
        self.assert_not_pass(scenario)


class DefectG(V3CoreCase):
    def test_uncollected_scenario_is_not_run(self):
        scenario = self.f.build_scenario([], "P01-c1", self.v3_passport(), qualification=True)
        self.assertEqual(scenario["verdict"], "NOT_RUN", scenario)
        for metric in scenario["metrics"].values():
            self.assertEqual(metric["verdict"], "NOT_RUN")
            self.assertNotIn("medianPairedRatio", metric)

    def test_unqualified_row_can_never_reach_pass(self):
        rows = self.v3_rows(rate=110, rss=800000, cpu=False, headroom=False)
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=False)
        self.assertNotEqual(scenario["verdict"], "PASS", scenario)
        self.assert_metric_not_pass(scenario, "M1")
        self.assert_metric_not_pass(scenario, "M2")

    def test_missing_pairs_are_inconclusive(self):
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000)[:-1], "P01-c1", self.v3_passport(), qualification=True)
        self.assert_not_pass(scenario)


class ThresholdsUnchanged(V3CoreCase):
    def test_thresholds_window_scenarios_unchanged(self):
        pinned = _load(PINNED_DIR / "contracts.py", "pinned_contracts_under_test")
        self.assertEqual(self.c.THRESHOLDS, pinned.THRESHOLDS)
        self.assertEqual(self.c.GENERATOR_CPU_BUDGET, pinned.GENERATOR_CPU_BUDGET)
        self.assertEqual(self.c.GENERATOR_HEADROOM_MIN, pinned.GENERATOR_HEADROOM_MIN)
        self.assertEqual(self.c.WINDOW_CONFIG, pinned.WINDOW_CONFIG)
        self.assertEqual(self.c.SCENARIOS, pinned.SCENARIOS)
        self.assertEqual(self.c.IDENTITY_KEYS, pinned.IDENTITY_KEYS)
        self.assertEqual(self.c.workload("P03"), pinned.workload("P03"))

    def test_no_threshold_moved_values(self):
        self.assertEqual(self.c.THRESHOLDS, {"M1": .90, "M2": 1.25, "M3": 1.20})


class PinnedCopy(V3CoreCase):
    def test_pinned_copy_is_byte_identical(self):
        sums = {}
        for line in (PINNED_DIR / "SHA256SUMS").read_text(encoding="ascii").splitlines():
            line = line.strip()
            if line:
                digest, name = line.split()
                sums[name] = digest
        self.assertEqual(set(sums), {"finalize.py", "contracts.py", "gates.py", "run.py", "generator.py"})
        for name, expected in sums.items():
            self.assertEqual(_sha256(PINNED_DIR / name), expected, name)


class CorrectionF51HeadroomProvenance(V3CoreCase):
    """F5.1: a per-row headroom claim must be bound to attempt probe provenance."""

    def test_fabricated_ceiling_is_rejected(self):
        rows = self.v3_rows(rate=110, rss=800000)
        for item in rows:
            item["generatorHeadroom"] = dict(item["generatorHeadroom"], ceilingOpsPerSecond=1e12)
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_metric_not_pass(scenario, "M1")
        self.assertTrue(any("generator_headroom_ceiling_mismatch" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_missing_probe_provenance_is_rejected(self):
        passport = self.v3_passport()
        passport.pop("generatorHeadroomProbes", None)
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport, qualification=True)
        self.assert_metric_not_pass(scenario, "M1")
        self.assertTrue(any("generator_headroom_probe_missing" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_unknown_probe_ref_is_rejected(self):
        rows = self.v3_rows(rate=110, rss=800000)
        for item in rows:
            item["generatorHeadroom"] = dict(item["generatorHeadroom"], probeRef="no-such-probe")
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_metric_not_pass(scenario, "M1")
        self.assertTrue(any("generator_headroom_probe_missing" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_probe_method_mismatch_is_rejected(self):
        passport = self.v3_passport()
        passport["generatorHeadroomProbes"]["P01-c1"]["method"] = "fabricated-method"
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport, qualification=True)
        self.assert_metric_not_pass(scenario, "M1")
        self.assertTrue(any("generator_headroom_probe_method_mismatch" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_probe_artifact_digest_invalid_is_rejected(self):
        passport = self.v3_passport()
        passport["generatorHeadroomProbes"]["P01-c1"]["probeArtifact"] = {"name": "x", "sha256": "not-a-64hex"}
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport, qualification=True)
        self.assert_metric_not_pass(scenario, "M1")
        self.assertTrue(any("generator_headroom_probe_artifact_digest_invalid" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_consistent_provenance_still_passes(self):
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", self.v3_passport(), qualification=True)
        self.assertEqual(scenario["verdict"], "PASS", scenario)


class CorrectionF52ProtocolExpectedHash(V3CoreCase):
    """F5.2: an absent expected protocol hash must fail closed."""

    def test_protocol_identity_without_expected_hash_refuses(self):
        protocol = {"frozenUtc": "1969-12-31T23:00:00Z", "frozenBeforeCollection": True, "sha256": "a" * 64}
        result = self.c.protocol_identity(protocol, "1970-01-01T00:01:39Z", None)
        self.assertFalse(result["valid"], result)
        self.assertIn("protocol_expected_hash_missing", result["reasons"])

    def test_build_scenario_without_protocol_sha_refuses(self):
        passport = self.v3_passport()
        passport.pop("protocolSha256", None)
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport, qualification=True)
        self.assert_metric_not_pass(scenario, "M1")
        self.assertTrue(any("protocol:protocol_expected_hash_missing" in r for r in scenario["metrics"]["M1"]["reasons"]))


class CorrectionF53AttemptSeal(V3CoreCase):
    """F5.3: an unrecorded/unsealed attempt is NOT_RUN, never PASS."""

    def test_unsealed_attempt_is_not_run_not_pass(self):
        passport = self.v3_passport()
        passport.pop("qualification", None)
        passport.pop("captureStatus", None)
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport, qualification=True)
        self.assertEqual(scenario["verdict"], "NOT_RUN", scenario)
        self.assertTrue(any("attempt_seal_missing" in r for r in scenario["metrics"]["M1"]["reasons"]))

    def test_analysis_only_scenario_never_reports_pass(self):
        passport = self.v3_passport()
        passport["qualification"] = False
        scenario = self.f.build_scenario(self.v3_rows(rate=110, rss=800000), "P01-c1", passport, qualification=False)
        self.assertNotEqual(scenario["verdict"], "PASS")
        self.assertEqual(scenario["metrics"]["M3"]["verdict"], "NOT_RUN")
        self.assertTrue(any("analysis_only_not_qualified" in r for r in scenario["metrics"]["M3"]["reasons"]))

    def test_not_run_exit_status_is_distinct(self):
        self.assertEqual(self.f.EXIT_NOT_RUN, 2)


class CorrectionAPeakThread(V3CoreCase):
    """A: a present-but-malformed peak thread CPU is invalid; absent is disclosed."""

    def test_present_malformed_peak_is_invalid(self):
        rows = self.v3_rows(rate=110, rss=800000)
        rows[0]["peakClientThreadCpu"] = "not-a-number"
        provenance = {"headroomProbes": self.v3_passport()["generatorHeadroomProbes"]}
        validation = self.c.validate_row(rows[0], self.c.workload("P01-c1"), rows[0]["identity"],
                                         self.c.WINDOW_CONFIG, True, provenance)
        self.assertFalse(validation["valid"], validation)
        self.assertIn("client_thread_cpu_invalid", validation["reasons"])

    def test_absent_peak_stays_disclosed_not_zero(self):
        rows = self.v3_rows(rate=110, rss=800000)
        rows[0].pop("peakClientThreadCpu", None)
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assertIn("client_thread_cpu_absent", scenario["validation"][0]["warnings"])


class CorrectionBGeneratorReasonsCommon(V3CoreCase):
    """B: generator-CPU validity reasons gate EVERY metric, including M3."""

    def test_absent_generator_evidence_gates_all_metrics(self):
        rows = self.v3_rows(rate=110, rss=800000)
        rows[0].pop("generatorCpu", None)
        scenario = self.f.build_scenario(rows, "P01-c1", self.v3_passport(), qualification=True)
        self.assert_metric_not_pass(scenario, "M3")
        self.assertTrue(any("generator_cpu_evidence_missing" in r for r in scenario["metrics"]["M3"]["reasons"]))
        self.assert_metric_not_pass(scenario, "M1")

    def test_pinned_core_also_routes_generator_reasons_to_every_metric(self):
        pinned = _load(PINNED_DIR / "contracts.py", "indep_pinned_for_B")
        row = self.v3_row(0, "reference")
        row.pop("generatorCpu", None)
        row["performanceQualification"] = True
        self.assertIn("generator_cpu_evidence_missing",
                      pinned.generator_saturation_reasons(row, pinned.workload("P01-c1")))


if __name__ == "__main__":
    unittest.main()
