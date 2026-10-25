"""Two-machine runner mock tests.

Proves, without any stand: (1) the row schema the runner builds is consumed by the
v3 core AND the pinned estimator; (2) the transport change (explicit server address
instead of loopback) alters only the address token and cannot touch the estimator,
workload, window or thresholds; (3) every fail-closed precondition actually refuses.
"""
import copy
import hashlib
import importlib.util
import json
import pathlib
import shutil
import sys
import tempfile
import unittest

from fixtures import IDENTITY, WINDOW, passport, row, samples  # noqa: F401

ROOT = pathlib.Path(__file__).resolve().parents[2]
V3 = ROOT / "tests/platform/perf/v3"
PINNED = ROOT / "tests/platform/perf/pinned"


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RunnerCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._saved = {n: sys.modules.get(n) for n in ("contracts", "finalize", "run", "generator")}
        cls._path = list(sys.path)
        cls.c = _load(V3 / "contracts.py", "tm_contracts_under_test")
        sys.modules["contracts"] = cls.c
        cls.gen = _load(V3 / "generator.py", "tm_generator_under_test")
        sys.modules["generator"] = cls.gen
        cls.r = _load(V3 / "run.py", "tm_run_under_test")
        sys.modules["run"] = cls.r
        cls.rt = _load(V3 / "run_two_machine.py", "tm_runner_under_test")
        cls.f = _load(V3 / "finalize.py", "tm_finalize_under_test")
        cls.pinned = _load(PINNED / "contracts.py", "tm_pinned_contracts_under_test")

    @classmethod
    def tearDownClass(cls):
        for name, value in cls._saved.items():
            if value is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = value
        sys.path[:] = cls._path

    ADDRESS = "10.56.3.148"

    def make_row(self, rep=0, server="reference", rate=110, rss=1000000, address=ADDRESS,
                 headroom=True, cpu=True, scope=True, perf_qual=False):
        base = row(rep, server, rate if server == "octomem" else 100,
                   rss if server == "octomem" else 1000000)
        w = base["workload"]
        paths = {"java": "/jdk/bin/java", "reference": "/ref/memcached", "jar": "/w/octomem.jar", "client": "/mt"}
        server_argv = self.rt.build_server_argv(server, paths, w["memoryMiB"], "0-3", address)
        client_argv = self.rt.build_client_argv("/mt", pathlib.Path("/out"), "P01-c1-r0-" + server, w, 12, "0-1", address)
        cg = dict(base["cgroupBefore"], scope="/user.slice/session.scope", scopeSource="server-pid")
        if not scope:
            cg = {k: v for k, v in cg.items() if k not in ("scope", "scopeSource")}
        cg2 = dict(cg)
        generator_cpu = ({"status": "measured", "processCpuFraction": 0.1, "clientCpuCount": 2,
                          "firstLastSampleMismatch": False, "reasons": []} if cpu else None)
        generator_headroom = ({"source": "row-measured", "method": "memtier-responding-echo",
                               "ceilingOpsPerSecond": 1000.0, "achievedOpsPerSecond": 50.0,
                               "probeRef": "P01-c1"} if headroom else None)
        built = self.rt.build_row(
            "P01-c1", rep, server, base["identity"], passport(),
            process=dict(base["process"], argv=server_argv), preload=base["preload"],
            samples=base["samples"], rss_window=base["rssWindow"], total_rss=base["total_rss"],
            stats_before=base["statsBefore"], stats_after=base["statsAfter"],
            cgroup_before=cg, cgroup_after=cg2,
            artifact={"name": "P01-c1-r0.memtier.json", "sha256": "0" * 64},
            server_argv_list=server_argv, client_argv_list=client_argv,
            generator_cpu=generator_cpu, generator_headroom=generator_headroom,
            peak_client_thread_cpu=0.5, warmup={"exitCode": 0, "timedOut": False, "qualifiedAsWarm": False},
            performance_qualification=perf_qual, wall_start=base["wallStart"], wall_end=base["wallEnd"])
        # The reader embeds the digest-verified native JSON; emulate that here so the
        # direct validate_row call sees exactly what finalize.py would pass.
        built["native"] = base["native"]
        return built


    def kind_server_argv(self, scenario, server):
        w = self.c.workload(scenario)
        paths = {"java": "/jdk/bin/java", "reference": "/ref/memcached", "jar": "/w/octomem.jar", "client": "/mt"}
        return self.rt.build_server_argv(server, paths, w["memoryMiB"], "0-3", self.ADDRESS)

    def _counters(self, curr_items, limit_maxbytes=256 * 2 ** 20):
        before = {k: "0" for k in ("cmd_get", "cmd_set", "get_hits", "get_misses", "evictions", "total_items")}
        before.update(curr_items=str(curr_items), limit_maxbytes=str(limit_maxbytes))
        return before, dict(before)

    def memory_row(self, rep=0, server="reference", stable=True, scope=True):
        base = row(rep, server, 100, 1000000)
        sargv = self.kind_server_argv("M3-1024", server)
        rss = 1000000
        if stable:
            rss_samples = [dict(monotonic=100 + i, wall=100 + i, total_rss=rss) for i in range(10)]
        else:
            rss_samples = [dict(monotonic=100 + i, wall=100 + i, total_rss=rss + i * 80000) for i in range(10)]
        before, after = self._counters(1024)
        cg = dict(base["cgroupBefore"], scope="/system.slice/octomem.service", scopeSource="server-pid")
        if not scope:
            cg = {k: v for k, v in cg.items() if k not in ("scope", "scopeSource")}
        return self.rt.build_row(
            "M3-1024", rep, server, IDENTITY, passport(),
            process=dict(base["process"], argv=sargv, version=IDENTITY[server + "Version"]),
            preload={"requested": 1024, "stored": 1024, "response": "STORED"},
            samples=[], rss_window=rss_samples, total_rss=rss,
            stats_before=before, stats_after=after, cgroup_before=cg, cgroup_after=dict(cg),
            artifact=None, server_argv_list=sargv, client_argv_list=[], performance_qualification=True)

    def cycles_row(self, rep=0, server="reference", n=100, gen_cpu=True, headroom=True, scope=True):
        base = row(rep, server, 100, 1000000)
        sargv = self.kind_server_argv("P07", server)
        before, after = self._counters(0)
        cg = dict(base["cgroupBefore"], scope="/system.slice/octomem.service", scopeSource="server-pid")
        if not scope:
            cg = {k: v for k, v in cg.items() if k not in ("scope", "scopeSource")}
        gcpu = ({"status": "measured", "processCpuFraction": 0.1, "clientCpuCount": 2,
                 "firstLastSampleMismatch": False, "reasons": []} if gen_cpu else None)
        hr = ({"source": "row-measured", "method": "churn-responding-echo",
               "ceilingOpsPerSecond": 100000.0, "achievedOpsPerSecond": 2000.0, "probeRef": "P07"} if headroom else None)
        return self.rt.build_row(
            "P07", rep, server, IDENTITY, passport(),
            process=dict(base["process"], argv=sargv, version=IDENTITY[server + "Version"]),
            preload={"requested": 0, "stored": 0, "response": "STORED"},
            samples=[], rss_window=[], total_rss=1000000,
            stats_before=before, stats_after=after, cgroup_before=cg, cgroup_after=dict(cg),
            artifact=None, server_argv_list=sargv, client_argv_list=[],
            exit_code=0, generator_cpu=gcpu, generator_headroom=hr,
            cycles_seconds=[0.01] * n, validated_cycles=n, cycle_errors=[], unreaped_workers=[],
            client_engine="octomem-bounded-loadgen", performance_qualification=True)

    def probe_provenance(self, method="churn-responding-echo", ceiling=100000.0):
        return {"headroomProbes": {"P07": {
            "source": "row-measured", "method": method, "windowSeconds": 1.0,
            "ceilingOpsPerSecond": ceiling,
            "probeArtifact": {"name": "cycles-headroom.json", "sha256": "a" * 64}}}}


class SchemaCompatibility(RunnerCase):
    def test_row_is_consumed_by_v3_core(self):
        item = self.make_row()
        validation = self.c.validate_row(item, self.c.workload("P01-c1"), IDENTITY, self.c.WINDOW_CONFIG, False)
        self.assertNotIn("row_schema_invalid", validation["reasons"], validation)
        self.assertTrue(validation["valid"], validation)

    def test_row_is_consumed_by_pinned_estimator(self):
        item = self.make_row()
        validation = self.pinned.validate_row(item, self.pinned.workload("P01-c1"), IDENTITY, self.pinned.WINDOW_CONFIG)
        self.assertNotIn("row_schema_invalid", validation["reasons"], validation)
        self.assertTrue(validation["valid"], validation)

    def test_row_carries_every_required_field(self):
        item = self.make_row()
        for key in ("scenario", "rep", "server", "identity", "workload", "pairOrder", "orderIndex",
                    "performanceQualification", "windowConfig", "forcedGcDuringMeasurement",
                    "expectedServerArgv", "process", "preload", "argv", "expectedClientArgv",
                    "statsBefore", "statsAfter", "cgroupBefore", "cgroupAfter", "samples",
                    "rssWindow", "total_rss", "exitCode", "timedOut", "artifact",
                    "generatorCpu", "generatorHeadroom", "peakClientThreadCpu"):
            self.assertIn(key, item, key)


class TransportInvariance(RunnerCase):
    def test_server_argv_changes_only_the_address_token(self):
        paths = {"java": "/j", "reference": "/r", "jar": "/jar", "client": "/c"}
        loopback = self.r.server_argv("octomem", "/j", "/r", "/jar", 256, "0-3", "127.0.0.1")
        remote = self.rt.build_server_argv("octomem", paths, 256, "0-3", self.ADDRESS)
        diffs = [i for i, (a, b) in enumerate(zip(loopback, remote)) if a != b]
        self.assertEqual(len(diffs), 1, (loopback, remote))
        self.assertIn(self.ADDRESS, remote[diffs[0]])
        self.assertEqual(loopback[:diffs[0]] + loopback[diffs[0] + 1:], remote[:diffs[0]] + remote[diffs[0] + 1:])

    def test_client_argv_changes_only_the_server_token(self):
        w = self.c.workload("P04")
        loopback = self.r.client_argv("/mt", pathlib.Path("/out"), "P04-r0-reference", w, 12, "0-1", "127.0.0.1")
        remote = self.rt.build_client_argv("/mt", pathlib.Path("/out"), "P04-r0-reference", w, 12, "0-1", self.ADDRESS)
        diffs = [i for i, (a, b) in enumerate(zip(loopback, remote)) if a != b]
        self.assertEqual(len(diffs), 1, (loopback, remote))
        self.assertIn(self.ADDRESS, remote[diffs[0]])

    def test_estimator_workload_window_thresholds_unchanged(self):
        self.assertEqual(self.rt.effective_thresholds(), self.pinned.THRESHOLDS)
        self.assertEqual(self.rt.effective_thresholds(), {"M1": .90, "M2": 1.25, "M3": 1.20})
        self.assertEqual(self.rt.effective_window(), self.pinned.WINDOW_CONFIG)
        self.assertEqual(self.rt.effective_scenarios(), self.pinned.SCENARIOS)
        self.assertEqual(self.rt.PINNED_IDENTITY["jar"], "950a8edb702e51c9979320c117257426f1109076845e80f30774ef0855ff682b")

    def test_runner_has_no_local_gate_or_threshold_logic(self):
        source = (V3 / "run_two_machine.py").read_text(encoding="utf-8")
        for token in ("valid_median_threshold_breach", "interval_crosses_threshold",
                      "def classify", "def median_interval", "insufficient_precision"):
            self.assertNotIn(token, source, token)
        self.assertFalse(hasattr(self.rt, "classify"))
        self.assertFalse(hasattr(self.rt, "THRESHOLDS"))

    def test_two_machine_topology_layout_passes(self):
        topology = {"layout": "two-machine", "hosts": {
            "server": {"siblings": {"0": [0, 2], "1": [1, 3], "2": [0, 2], "3": [1, 3]}},
            "client": {"siblings": {"0": [0], "1": [1]}}}}
        result = self.c.topology_isolation("0-3", "0-1", topology)
        self.assertTrue(result["valid"], result)
        self.assertEqual(result["observed"]["sharedCores"], [])

    def test_two_machine_layout_missing_host_fails(self):
        result = self.c.topology_isolation("0-3", "0-1", {"layout": "two-machine", "hosts": {"server": {}}})
        self.assertFalse(result["valid"])
        self.assertIn("topology_evidence_missing", result["reasons"])


class FailClosed(RunnerCase):
    def good(self):
        return {"server_host_reachable": True, "client_self_reachable": True, "library_dir_present": True,
                "memtier_with_lib_ok": True, "memtier_without_lib_ok": False, "protocol_present": True,
                "protocol_frozen_before_collection": True, "protocol_misdated": False,
                "protocol_hash_mismatch": False, "java_version": "25.0.4.1", "memtier_version": "2.2.0",
                "server_port_occupied": False, "service_cgroup_scope_missing": False,
                "unsupported_kinds": [], "plan": "rehearsal"}

    def test_all_preconditions_met(self):
        self.assertEqual(self.rt.precondition_reasons(self.good()), [])

    def test_each_unmet_precondition_refuses(self):
        cases = {
            "server_host_unreachable": ("server_host_reachable", False),
            "client_host_unreachable": ("client_self_reachable", False),
            "library_dir_missing": ("library_dir_present", False),
            "memtier_not_usable_with_library_path": ("memtier_with_lib_ok", False),
            "memtier_usable_without_library_path_unexpected": ("memtier_without_lib_ok", True),
            "protocol_missing": ("protocol_present", False),
            "protocol_not_frozen_before_collection": ("protocol_frozen_before_collection", False),
            "protocol_misdated": ("protocol_misdated", True),
            "protocol_hash_mismatch": ("protocol_hash_mismatch", True),
            "server_port_occupied": ("server_port_occupied", True),
            "service_cgroup_scope_missing": ("service_cgroup_scope_missing", True),
        }
        for reason, (key, value) in cases.items():
            with self.subTest(reason=reason):
                facts = self.good()
                facts[key] = value
                self.assertIn(reason, self.rt.precondition_reasons(facts))

    def test_identity_mismatch_refuses(self):
        facts = self.good()
        facts["identity_mismatch_key"] = "jar"
        self.assertIn("identity_mismatch:jar", self.rt.precondition_reasons(facts))
        facts = self.good()
        facts["java_version"] = "25.0.2"
        self.assertIn("java_version_mismatch", self.rt.precondition_reasons(facts))
        facts = self.good()
        facts["memtier_version"] = "2.1.0"
        self.assertIn("memtier_version_mismatch", self.rt.precondition_reasons(facts))
        facts = self.good()
        facts["harness_hash_mismatch"] = "run.py"
        self.assertIn("harness_hash_mismatch:run.py", self.rt.precondition_reasons(facts))

    def test_unsupported_scenario_kind_refuses(self):
        facts = self.good()
        facts["unsupported_kinds"] = ["cycles"]
        self.assertIn("scenario_kind_unsupported:cycles", self.rt.precondition_reasons(facts))

    def test_full_plan_requires_official_declaration(self):
        facts = self.good()
        facts["plan"] = "full"
        self.assertIn("official_campaign_not_declared", self.rt.precondition_reasons(facts))
        facts["official_campaign_declared"] = True
        self.assertEqual(self.rt.precondition_reasons(facts), [])

    def scenario_passport(self):
        """A two-machine passport with recorded seal and F5.1 probe provenance."""
        p = passport()
        p["schemaVersion"] = 2
        p["repetitions"] = 6
        p["stoppingRule"] = "fixed-pairs-no-extension"
        p["serverCpus"] = "0,2"
        p["clientCpus"] = "1,3"
        p["topology"] = {"layout": "two-machine",
                         "hosts": {"server": {"siblings": {"0": [0, 2], "1": [1, 3], "2": [0, 2], "3": [1, 3]}},
                                   "client": {"siblings": {"0": [0], "1": [1]}}}}
        p["protocol"] = {"frozenUtc": "1969-12-31T23:00:00Z", "frozenBeforeCollection": True, "sha256": "a" * 64}
        p["protocolSha256"] = "a" * 64
        p["qualification"] = True
        p["captureStatus"] = {"sealed": True}
        p["generatorHeadroomProbes"] = {"P01-c1": {
            "source": "row-measured", "method": "memtier-responding-echo", "windowSeconds": 2.0,
            "ceilingOpsPerSecond": 1000.0,
            "probeArtifact": {"name": "headroom-P01-c1.memtier.json", "sha256": "f" * 64}}}
        return p

    def test_missing_scope_and_headroom_cannot_pass(self):
        # A row without service cgroup scope or per-row headroom can never be valid.
        no_scope = self.make_row(scope=False)
        v = self.c.validate_row(no_scope, self.c.workload("P01-c1"), IDENTITY, self.c.WINDOW_CONFIG, False)
        self.assertFalse(v["valid"])
        self.assertTrue(any("cgroup_scope_unidentified" in r for r in v["reasons"]), v)
        # Corrected contract (B): the generator headroom reason is a COMMON reason, so
        # it constrains M1, M2 AND M3 - not only M1/M2 - and the scenario is not PASS.
        rows = [self.make_row(rep, server, perf_qual=True) for rep in range(6)
                for server in ("reference", "octomem")]
        rows[0].pop("generatorHeadroom", None)
        scenario = self.f.build_scenario(rows, "P01-c1", self.scenario_passport(), qualification=True)
        self.assertNotEqual(scenario["verdict"], "PASS", scenario["verdict"])
        for metric in ("M1", "M2", "M3"):
            self.assertNotEqual(scenario["metrics"][metric]["verdict"], "PASS", metric)
            self.assertTrue(any("generator_headroom_evidence_missing" in r
                                for r in scenario["metrics"][metric]["reasons"]), metric)


class MemoryAndCyclesKinds(RunnerCase):
    """The extended runner supports memtier + memory + cycles, transport-only."""

    def test_runner_dispatches_all_three_kinds(self):
        self.assertTrue(hasattr(self.rt, "_collect_memtier_row"))
        self.assertTrue(hasattr(self.rt, "_collect_memory_row"))
        self.assertTrue(hasattr(self.rt, "_collect_cycles_row"))
        src = (V3 / "run_two_machine.py").read_text(encoding="utf-8")
        self.assertNotIn('k != "memtier"', src)
        self.assertIn('k not in ("memtier", "memory", "cycles")', src)

    def test_workloads_unchanged_for_new_kinds(self):
        for name in ("M3-1024", "M3-8192", "M3-32768", "P07"):
            self.assertEqual(self.c.workload(name), self.pinned.workload(name), name)
        self.assertEqual(self.rt.effective_scenarios(), self.pinned.SCENARIOS)
        self.assertEqual(self.rt.effective_thresholds(), self.pinned.THRESHOLDS)

    def test_memory_and_cycles_server_argv_change_only_address(self):
        paths = {"java": "/j", "reference": "/r", "jar": "/jar", "client": "/c"}
        for name in ("M3-1024", "P07"):
            w = self.c.workload(name)
            loopback = self.r.server_argv("octomem", "/j", "/r", "/jar", w["memoryMiB"], "0-3", "127.0.0.1")
            remote = self.rt.build_server_argv("octomem", paths, w["memoryMiB"], "0-3", self.ADDRESS)
            diffs = [i for i, (a, b) in enumerate(zip(loopback, remote)) if a != b]
            self.assertEqual(len(diffs), 1, (name, loopback, remote))
            self.assertIn(self.ADDRESS, remote[diffs[0]])

    def test_memory_row_is_consumed_by_v3_with_unchanged_rss_rules(self):
        validation = self.c.validate_row(self.memory_row(), self.c.workload("M3-1024"), IDENTITY, self.c.WINDOW_CONFIG, True)
        self.assertNotIn("row_schema_invalid", validation["reasons"], validation)
        self.assertTrue(validation["valid"], validation)
        self.assertEqual(validation["metricReasons"]["M3"], [])

    def test_memory_non_converged_window_is_not_pass(self):
        validation = self.c.validate_row(self.memory_row(stable=False), self.c.workload("M3-1024"), IDENTITY, self.c.WINDOW_CONFIG, True)
        self.assertTrue(any("rss_not_converged" in r or "rss_" in r for r in validation["metricReasons"]["M3"]),
                        validation["metricReasons"]["M3"])

    def test_memory_missing_scope_fails_closed(self):
        validation = self.c.validate_row(self.memory_row(scope=False), self.c.workload("M3-1024"), IDENTITY, self.c.WINDOW_CONFIG, True)
        self.assertFalse(validation["valid"])
        self.assertTrue(any("cgroup_scope_unidentified" in r for r in validation["reasons"]), validation)

    def test_cycles_row_is_consumed_by_v3_with_pinned_semantics(self):
        validation = self.c.validate_row(self.cycles_row(), self.c.workload("P07"), IDENTITY, self.c.WINDOW_CONFIG, True,
                                         self.probe_provenance())
        self.assertNotIn("row_schema_invalid", validation["reasons"], validation)
        self.assertTrue(validation["valid"], validation)
        row_obj = self.cycles_row()
        self.assertEqual(len(row_obj["cyclesSeconds"]), 100)
        self.assertEqual(row_obj["validatedCycles"], 100)
        self.assertEqual(row_obj["clientEngine"], "octomem-bounded-loadgen")

    def test_cycles_wrong_count_is_invalid(self):
        validation = self.c.validate_row(self.cycles_row(n=99), self.c.workload("P07"), IDENTITY, self.c.WINDOW_CONFIG, True,
                                         self.probe_provenance())
        self.assertFalse(validation["valid"])
        self.assertIn("connection_cycles_invalid", validation["reasons"])

    def test_cycles_missing_generator_cpu_fails_closed(self):
        validation = self.c.validate_row(self.cycles_row(gen_cpu=False), self.c.workload("P07"), IDENTITY,
                                         self.c.WINDOW_CONFIG, True, self.probe_provenance())
        self.assertFalse(validation["valid"])
        self.assertTrue(any("generator_cpu_evidence_missing" in r or "generator_cpu" in r for r in validation["reasons"]), validation)

    def test_cycles_missing_headroom_provenance_fails_closed(self):
        validation = self.c.validate_row(self.cycles_row(), self.c.workload("P07"), IDENTITY, self.c.WINDOW_CONFIG,
                                         True, {"headroomProbes": {}})
        self.assertFalse(validation["valid"])
        self.assertTrue(any("generator_headroom_probe_missing" in r for r in validation["reasons"]), validation)
        validation2 = self.c.validate_row(self.cycles_row(headroom=False), self.c.workload("P07"), IDENTITY,
                                          self.c.WINDOW_CONFIG, True, self.probe_provenance())
        self.assertTrue(any("generator_headroom_evidence_missing" in r for r in validation2["reasons"]), validation2)

    def test_preconditions_admit_implemented_kinds_and_refuse_unknown(self):
        facts = {"server_host_reachable": True, "client_self_reachable": True, "library_dir_present": True,
                 "memtier_with_lib_ok": True, "memtier_without_lib_ok": False, "protocol_present": True,
                 "protocol_frozen_before_collection": True, "protocol_misdated": False,
                 "protocol_hash_mismatch": False, "java_version": "25.0.4.1", "memtier_version": "2.2.0",
                 "server_port_occupied": False, "service_cgroup_scope_missing": False,
                 "unsupported_kinds": [], "plan": "rehearsal"}
        self.assertEqual(self.rt.precondition_reasons(facts), [])
        facts["unsupported_kinds"] = ["unknown_kind"]
        self.assertIn("scenario_kind_unsupported:unknown_kind", self.rt.precondition_reasons(facts))


class CalibrationAndEvidence(RunnerCase):
    """Calibration emission (pinned schema) and memory-row evidence completeness."""

    def _sha(self, path):
        return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()

    def _calibration_rows(self, attempt_id):
        rows = []
        for server in ("reference", "octomem"):
            for keylen in (1, 16, 250):
                for flags in (0, 1, 65535, 4294967295):
                    accepted = 1048000 - keylen
                    rejected = accepted + 1
                    checks = [
                        {"valueBytes": accepted, "response": "STORED", "readbackVerified": True,
                         "payloadSha256": hashlib.sha256(b"x" * accepted).hexdigest()},
                        {"valueBytes": rejected, "response": "SERVER_ERROR object too large for cache"},
                    ]
                    rows.append({"server": server, "keyBytes": keylen, "flags": flags,
                                 "accepted": accepted, "rejected": rejected, "checks": checks,
                                 "attempts": [], "identity": {"attemptId": attempt_id}})
        return rows

    def make_attempt(self, root_name="attempt-synthetic", with_calibration=True, memory_evidence=True):
        root = pathlib.Path(self.tmp) / root_name
        root.mkdir()
        (root / "harness").mkdir()
        run_bytes = b"# synthetic run.py\n"
        (root / "harness" / "run.py").write_bytes(run_bytes)
        (root / "protocol.json").write_text(json.dumps({"frozenUtc": "1969-12-31T23:00:00Z", "frozenBeforeCollection": True}), encoding="utf-8")
        identity = {"attemptId": root.name, "jarSha256": "a" * 64, "referenceSha256": "b" * 64,
                    "memtierSha256": "c" * 64, "harnessSha256": hashlib.sha256(run_bytes).hexdigest(),
                    "javaSha256": "e" * 64, "javaVersion": "25.0.4.1",
                    "referenceVersion": "VERSION 1.6.45", "octomemVersion": "VERSION 1.0.0 (octomem)"}
        passport = {"schemaVersion": 2, "identity": identity, "harnessFiles": {"run.py": hashlib.sha256(run_bytes).hexdigest()},
                    "paths": {}, "collectorOutput": str(root), "repetitions": 6,
                    "stoppingRule": "fixed-pairs-no-extension", "clientCpus": "0-1", "serverCpus": "0-3",
                    "serverAddress": self.ADDRESS, "windowConfig": copy.deepcopy(self.c.WINDOW_CONFIG),
                    "workloads": {n: self.c.workload(n) for n in self.c.SCENARIOS},
                    "topology": {"layout": "two-machine",
                                 "hosts": {"server": {"siblings": {"0": [0, 2], "1": [1, 3], "2": [0, 2], "3": [1, 3]}},
                                           "client": {"siblings": {"0": [0], "1": [1]}}}},
                    "protocol": {"frozenUtc": "1969-12-31T23:00:00Z", "frozenBeforeCollection": True,
                                 "sha256": "f" * 64},
                    "protocolPath": "protocol.json", "protocolSha256": "f" * 64,
                    "performanceQualification": True, "qualification": True}
        (root / "runs.json").write_text("[]", encoding="utf-8")
        (root / "passport.json").write_text(json.dumps(passport), encoding="utf-8")
        artifacts = {"runs.json": self._sha(root / "runs.json"), "passport.json": self._sha(root / "passport.json")}
        if with_calibration:
            (root / "calibration.json").write_text(json.dumps(self._calibration_rows(identity["attemptId"])), encoding="utf-8")
            artifacts["calibration.json"] = self._sha(root / "calibration.json")
        if memory_evidence:
            document = {"schemaVersion": 1, "kind": "memory", "record": "M3-1024-r0-reference",
                        "rssWindow": [{"monotonic": 1.0, "wall": 1.0, "total_rss": 1000000}],
                        "totalRss": 1000000}
            (root / "M3-1024-r0-reference.memory-evidence.json").write_text(json.dumps(document), encoding="utf-8")
            artifacts["M3-1024-r0-reference.memory-evidence.json"] = self._sha(root / "M3-1024-r0-reference.memory-evidence.json")
        (root / "complete.json").write_text(json.dumps({
            "completed": True, "captureSealed": True, "planComplete": False, "identity": identity,
            "artifacts": artifacts, "runsSha256": artifacts["runs.json"], "passportSha256": artifacts["passport.json"]}),
            encoding="utf-8")
        return root

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="tm-test-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_runner_exposes_calibration_and_evidence_helpers(self):
        self.assertTrue(hasattr(self.rt, "_run_calibration"))
        self.assertTrue(hasattr(self.rt, "_store_one"))
        self.assertTrue(hasattr(self.rt, "_write_evidence"))

    def test_calibration_schema_is_consumed_by_the_v3_reader(self):
        root = self.make_attempt(with_calibration=True)
        _, _, errors = self.f.read_attempt(root)
        self.assertNotIn("calibration_missing_or_invalid", errors, errors)
        self.assertNotIn("artifact_manifest_incomplete", errors, errors)
        self.assertFalse([e for e in errors if e.startswith("calibration_")], errors)
        self.assertFalse([e for e in errors if e.startswith("attempt_artifact_mismatch")], errors)

    def test_missing_calibration_is_reported_fail_closed(self):
        root = self.make_attempt(with_calibration=False)
        _, _, errors = self.f.read_attempt(root)
        self.assertIn("calibration_missing_or_invalid", errors)
        self.assertIn("artifact_manifest_incomplete", errors)

    def test_memory_evidence_file_is_digest_bound(self):
        root = self.make_attempt(memory_evidence=True)
        _, _, errors = self.f.read_attempt(root)
        self.assertFalse([e for e in errors if e.startswith("attempt_artifact_mismatch")], errors)
        # Mutation control: a tampered evidence file must be caught by the manifest digest.
        target = root / "M3-1024-r0-reference.memory-evidence.json"
        target.write_text(target.read_text(encoding="utf-8") + " ", encoding="utf-8")
        _, _, errors2 = self.f.read_attempt(root)
        self.assertTrue(any(e.startswith("attempt_artifact_mismatch") for e in errors2), errors2)

    def test_write_evidence_returns_name_and_digest(self):
        out = pathlib.Path(self.tmp)
        identity = self.rt._write_evidence(out, "X-r0-reference", "memory", {"rssWindow": [], "totalRss": None})
        path = out / identity["name"]
        self.assertTrue(path.is_file())
        self.assertEqual(identity["sha256"], self._sha(path))

    def test_memory_row_builder_can_carry_evidence_identity(self):
        row_obj = self.memory_row()
        artifact = {"name": "M3-1024-r0-reference.memory-evidence.json", "sha256": "a" * 64}
        row_obj["artifact"] = artifact
        row_obj["evidenceIdentity"] = {"record": "M3-1024-r0-reference", "artifact": artifact["name"],
                                       "sha256": artifact["sha256"], "rssWindowSamples": 10, "error": None}
        self.assertEqual(row_obj["artifact"], artifact)
        self.assertEqual(row_obj["evidenceIdentity"]["rssWindowSamples"], 10)
        validation = self.c.validate_row(row_obj, self.c.workload("M3-1024"), IDENTITY, self.c.WINDOW_CONFIG, True)
        self.assertTrue(validation["valid"], validation)


class SamplingRepair(RunnerCase):
    """Generator-CPU sampling boundary: alive-only samples + measured-window alignment."""

    START = 1000.0
    END = 1012.0

    def _ticks(self, i, tids=(0, 1)):
        return {str(t): t * 100 + i * 10 for t in tids}

    def samples(self, first_offset=0.37, cadence=0.5, n=24, post_exit=False, retire_at=None, gap_at=None):
        previous = self._ticks(0)
        result = []
        for i in range(1, n + 1):
            wall = self.START + first_offset + (i - 1) * cadence
            if wall > self.END:
                break
            tids = (0, 1) if retire_at is None or i < retire_at else (0,)
            current = self._ticks(i, tids)
            result.append({"wall": wall, "monotonic": wall, "intervalSeconds": cadence,
                           "clientCpuTicks": current, "previousClientCpuTicks": previous,
                           "clockTicksPerSecond": 100, "serverCpuTicks": {"1": i},
                           "previousServerCpuTicks": {"1": i - 1}, "connections": 1,
                           "total_rss": 1000000, "maxClientThreadCpu": 0.1})
            previous = current
        if gap_at is not None and 0 <= gap_at < len(result):
            del result[gap_at]
        if post_exit:
            result.append({"wall": self.END + 0.5, "monotonic": self.END + 0.5, "intervalSeconds": cadence,
                           "clientCpuTicks": {}, "previousClientCpuTicks": previous,
                           "clockTicksPerSecond": 100})
        return result

    def test_alignment_snaps_to_measured_window(self):
        aligned = self.rt._align_cpu_samples(self.samples(), self.START, self.END)
        self.assertTrue(aligned)
        first, last = aligned[0], aligned[-1]
        self.assertAlmostEqual(first["wall"] - first["intervalSeconds"], self.START, places=9)
        self.assertAlmostEqual(last["wall"], self.END, places=9)
        self.assertAlmostEqual(last["monotonic"] - aligned[-2]["monotonic"], last["intervalSeconds"], places=9)

    def test_aligned_samples_produce_a_valid_measured_observation(self):
        aligned = self.rt._align_cpu_samples(self.samples(), self.START, self.END)
        result = self.c.corrected_generator_cpu(aligned, 2, (self.START, self.END))
        self.assertEqual(result["status"], "measured", result)
        self.assertFalse(result["firstLastSampleMismatch"], result)
        self.assertEqual(result["reasons"], [])

    def test_post_exit_zero_sample_is_dropped_by_alignment(self):
        raw = self.samples(post_exit=True)
        result = self.c.corrected_generator_cpu(raw, 2, (self.START, self.END))
        # Fail-closed control: the unaligned official-campaign shape is invalid.
        self.assertEqual(result["status"], "invalid", result)
        self.assertTrue(result["reasons"], result)
        aligned = self.rt._align_cpu_samples(raw, self.START, self.END)
        self.assertFalse([s for s in aligned if not s.get("clientCpuTicks")], aligned)
        fixed = self.c.corrected_generator_cpu(aligned, 2, (self.START, self.END))
        self.assertEqual(fixed["status"], "measured", fixed)
        self.assertFalse(fixed["firstLastSampleMismatch"], fixed)

    def test_vanished_thread_still_fails_closed_after_alignment(self):
        aligned = self.rt._align_cpu_samples(self.samples(retire_at=10), self.START, self.END)
        result = self.c.corrected_generator_cpu(aligned, 2, (self.START, self.END))
        self.assertEqual(result["status"], "invalid", result)
        self.assertIn("generator_cpu_thread_retired_unobserved", result["reasons"])

    def test_gap_still_fails_closed_after_alignment(self):
        aligned = self.rt._align_cpu_samples(self.samples(gap_at=8), self.START, self.END)
        result = self.c.corrected_generator_cpu(aligned, 2, (self.START, self.END))
        self.assertEqual(result["status"], "invalid", result)
        self.assertTrue(any("coverage_gap" in r for r in result["reasons"]), result)

    def test_official_campaign_shape_is_refused_when_unaligned(self):
        # frozen first/last ~ 0 while accumulated ~ 13 s -> explicit mismatch, invalid.
        previous = self._ticks(0)
        raw = []
        for i in range(1, 13):
            current = self._ticks(i)
            raw.append({"wall": self.START + i, "monotonic": self.START + i, "intervalSeconds": 1.0,
                        "clientCpuTicks": current, "previousClientCpuTicks": previous,
                        "clockTicksPerSecond": 100})
            previous = current
        raw.append({"wall": self.START + 13, "monotonic": self.START + 13, "intervalSeconds": 1.0,
                    "clientCpuTicks": {}, "previousClientCpuTicks": previous, "clockTicksPerSecond": 100})
        result = self.c.corrected_generator_cpu(raw, 2, (self.START + 1, self.START + 12))
        self.assertEqual(result["status"], "invalid", result)
        self.assertTrue(result["firstLastSampleMismatch"], result)


class QualificationMarker(RunnerCase):
    """The qualification-marker defect: reader keys on `qualification`; runner must record it."""

    def full_rows(self):
        return [self.make_row(rep, server, rate=110, rss=800000, perf_qual=True)
                for rep in range(6) for server in ("reference", "octomem")]

    def marker_passport(self, marker=True, seal=True, perf=None):
        p = passport()
        p["schemaVersion"] = 2
        p["repetitions"] = 6
        p["stoppingRule"] = "fixed-pairs-no-extension"
        p["serverCpus"] = "0,2"
        p["clientCpus"] = "1,3"
        p["topology"] = {"siblings": {"0": [0, 2], "1": [1, 3], "2": [0, 2], "3": [1, 3]}}
        p["protocol"] = {"frozenUtc": "1969-12-31T23:00:00Z", "frozenBeforeCollection": True, "sha256": "a" * 64}
        p["protocolSha256"] = "a" * 64
        if seal:
            p["captureStatus"] = {"sealed": True}
        if marker is not None:
            p["qualification"] = marker
        if perf is not None:
            p["performanceQualification"] = perf
        p["generatorHeadroomProbes"] = {"P01-c1": {
            "source": "row-measured", "method": "memtier-responding-echo", "windowSeconds": 2.0,
            "ceilingOpsPerSecond": 1000.0,
            "probeArtifact": {"name": "headroom-P01-c1.memtier.json", "sha256": "f" * 64}}}
        return p

    def verdict(self, rows, p, qualification):
        return self.f.build_scenario(rows, "P01-c1", p, qualification=qualification)

    def test_runner_records_the_marker_the_reader_keys_on(self):
        src = (V3 / "run_two_machine.py").read_text(encoding="utf-8")
        self.assertIn("def qualification_claim(", src)
        self.assertIn('"qualification": qualification_claim(', src)

    def test_qualification_claim_gate(self):
        self.assertTrue(self.rt.qualification_claim(True, True, False))
        self.assertFalse(self.rt.qualification_claim(False, True, False))
        self.assertFalse(self.rt.qualification_claim(True, False, False))
        self.assertFalse(self.rt.qualification_claim(True, True, True))

    def test_marker_present_sealed_complete_attempt_reaches_pass(self):
        p = self.marker_passport(marker=True, seal=True)
        # main reads passport.get("qualification", False)
        scenario = self.verdict(self.full_rows(), p, p.get("qualification", False))
        self.assertEqual(scenario["verdict"], "PASS", scenario)

    def test_old_runner_passport_without_marker_is_not_run(self):
        # Mutation control: the pre-fix runner wrote performanceQualification but no
        # `qualification`; main then passes False and every would-be PASS -> NOT_RUN.
        p = self.marker_passport(marker=None, seal=True, perf=True)
        scenario = self.verdict(self.full_rows(), p, p.get("qualification", False))
        self.assertEqual(scenario["verdict"], "NOT_RUN", scenario)
        self.assertTrue(any("analysis_only_not_qualified" in r for r in scenario["metrics"]["M1"]["reasons"]), scenario)

    def test_marker_false_analysis_only_never_pass(self):
        p = self.marker_passport(marker=False, seal=True)
        scenario = self.verdict(self.full_rows(), p, p.get("qualification", False))
        self.assertNotEqual(scenario["verdict"], "PASS")
        self.assertEqual(scenario["verdict"], "NOT_RUN")

    def test_marker_true_but_explicitly_unsealed_is_not_run(self):
        p = self.marker_passport(marker=True, seal=True)
        p["captureStatus"] = {"sealed": False}
        scenario = self.verdict(self.full_rows(), p, p.get("qualification", False))
        self.assertNotEqual(scenario["verdict"], "PASS")
        self.assertTrue(any("attempt_seal_missing" in r for r in scenario["metrics"]["M1"]["reasons"]), scenario)

    def test_marker_true_but_incomplete_plan_is_not_pass(self):
        p = self.marker_passport(marker=True, seal=True)
        rows = [self.make_row(0, server, rate=110, rss=800000, perf_qual=True) for server in ("reference", "octomem")]
        scenario = self.verdict(rows, p, p.get("qualification", False))
        self.assertNotEqual(scenario["verdict"], "PASS")


if __name__ == "__main__":
    unittest.main()
