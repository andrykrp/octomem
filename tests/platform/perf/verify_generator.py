"""Bounded self-verification for the TEST-05 load generator and validity gates.

This is developer/harness evidence, not product acceptance. It runs only against an
in-process mock memcache server on loopback and pure gate functions; it never starts
octomem, memcached, Docker or a remote stand, and it never runs the 72-hour soak.

It proves two things:
1. the generator executes preload, mixed, churn and capacity-probe plans correctly
   against a known mock and produces the declared evidence shape;
2. every validity gate rejects a deliberately broken fixture with a specific,
   machine-readable reason and accepts the matching valid fixture.

Every run is retained in a unique directory. Earlier failing runs are kept.

Usage (from repository root, Windows or Linux):
    python -B tests/platform/perf/verify_generator.py --output-parent tests/reports/release/perf-gen
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import socket
import sys
import threading
import time
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import contracts as c  # noqa: E402
import gates as g  # noqa: E402
import generator as lg  # noqa: E402
import run as collector  # noqa: E402
import soak  # noqa: E402


# --------------------------------------------------------------------------- mock

class MockCacheServer:
    """Minimal, deterministic memcache text server with optional bounded capacity."""

    def __init__(self, host="127.0.0.1", capacity_bytes=None):
        self.host = host
        self.capacity_bytes = capacity_bytes
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((host, 0))
        self.server.listen(2048)
        self.port = self.server.getsockname()[1]
        self.items = {}
        self.order = []
        self.bytes_used = 0
        self.closed = False
        self.lock = threading.Lock()
        self.stats = {"set": 0, "get": 0, "hits": 0, "misses": 0, "evictions": 0}
        self._thread = threading.Thread(target=self._serve, daemon=True)

    def start(self):
        self._thread.start()
        return self

    def stop(self):
        self.closed = True
        try:
            self.server.close()
        except OSError:
            pass

    def _serve(self):
        self.server.settimeout(0.2)
        while not self.closed:
            try:
                client, _ = self.server.accept()
            except (socket.timeout, BlockingIOError, InterruptedError):
                continue
            except OSError:
                return
            threading.Thread(target=self._handle, args=(client,), daemon=True).start()

    def _handle(self, client):
        try:
            client.settimeout(30)
            buf = bytearray()
            while not self.closed:
                while True:
                    idx = buf.find(b"\r\n")
                    if idx < 0:
                        break
                    line = bytes(buf[:idx])
                    if line == b"version":
                        del buf[:idx + 2]
                        client.sendall(b"VERSION 1.6.45-mock\r\n")
                        continue
                    if line.startswith(b"set ") or line.startswith(b"add ") or line.startswith(b"replace "):
                        parts = line.split()
                        key, size = parts[1], int(parts[4])
                        need = (idx + 2) + size + 2
                        if len(buf) < need:
                            break
                        value = bytes(buf[idx + 2:idx + 2 + size])
                        del buf[:need]
                        with self.lock:
                            self.stats["set"] += 1
                            self._store(key, value)
                        client.sendall(b"STORED\r\n")
                        continue
                    if line.startswith(b"get "):
                        key = line.split()[1]
                        del buf[:idx + 2]
                        with self.lock:
                            self.stats["get"] += 1
                            entry = self.items.get(key)
                            if entry is None:
                                self.stats["misses"] += 1
                                client.sendall(b"END\r\n")
                            else:
                                self.stats["hits"] += 1
                                client.sendall(b"VALUE " + key + b" 0 " + str(len(entry)).encode() + b"\r\n" + entry + b"\r\nEND\r\n")
                        continue
                    if line == b"stats":
                        del buf[:idx + 2]
                        client.sendall(b"END\r\n")
                        continue
                    if line in (b"quit",):
                        client.close()
                        return
                    del buf[:idx + 2]
                    client.sendall(b"ERROR\r\n")
                data = client.recv(1 << 16)
                if not data:
                    break
                buf.extend(data)
        except OSError:
            pass
        finally:
            try:
                client.close()
            except OSError:
                pass

    def _store(self, key, value):
        if key in self.items:
            self.bytes_used -= len(self.items[key])
            self.order.remove(key)
        self.items[key] = value
        self.order.append(key)
        self.bytes_used += len(value)
        if self.capacity_bytes is not None:
            while self.bytes_used > self.capacity_bytes and len(self.order) > 1:
                victim = self.order.pop(0)
                self.bytes_used -= len(self.items.pop(victim))
                self.stats["evictions"] += 1


# --------------------------------------------------------------------------- fixtures

def stable_rss_window(base=64 * 1024 * 1024, count=12, step=0.5):
    samples = []
    now = time.monotonic()
    for index in range(count):
        samples.append({"monotonic": now + index * step, "wall": time.time() + index * step,
                        "total_rss": base + (index % 3) * 4096})
    return samples


def valid_identity():
    return {"attemptId": "attempt-abc", "jarSha256": "a" * 64, "referenceSha256": "b" * 64,
            "memtierSha256": "c" * 64, "harnessSha256": "d" * 64,
            "javaSha256": "e" * 64, "javaVersion": "25.0.4.1"}


def valid_cgroup():
    files = {"cpu.max": "max 100000", "cpuset.cpus.effective": "0-3", "memory.max": "268435456",
             "memory.swap.max": "0", "cpu.stat": "nr_throttled 0\nthrottled_usec 0\nusage_usec 123",
             "memory.events": "low 0\nhigh 0\nmax 0\noom 0\noom_kill 0"}
    return {"version": 2, "files": files, "errors": {}}


def synthetic_row(observed, plan):
    return {
        "attemptDirectory": "attempt-abc",
        "exitCode": 0, "timedOut": False,
        "preload": {"requested": plan["count"], "stored": plan["count"], "response": "STORED"},
        "identity": valid_identity(), "expectedIdentity": valid_identity(),
        "observed": observed, "plan": plan,
        "rssWindow": stable_rss_window(), "windowConfig": dict(c.WINDOW_CONFIG),
        "cgroupBefore": valid_cgroup(), "cgroupAfter": valid_cgroup(),
        "cpu": {"processCpuFraction": .2, "maxThreadCpuSeconds": .3, "logicalCpus": 8},
        "generatorHeadroom": {"commandsPerSecondUpperBound": 100000, "achievedOpsPerSecond": 25000},
    }


# --------------------------------------------------------------------------- cases

class Report:
    def __init__(self):
        self.cases = []

    def add(self, name, kind, passed, detail, evidence=None):
        self.cases.append({"name": name, "kind": kind, "passed": bool(passed),
                           "detail": detail, "evidence": evidence or {}})

    def expect_rejected(self, name, gate_result, expected_reason):
        passed = (not gate_result["valid"]) and any(expected_reason in r for r in gate_result["reasons"])
        self.add(name, "negative-fixture", passed,
                 f"valid={gate_result['valid']} reasons={gate_result['reasons']}", gate_result)

    def expect_accepted(self, name, gate_result):
        self.add(name, "positive-control", gate_result["valid"],
                 f"valid={gate_result['valid']} reasons={gate_result['reasons']}", gate_result)


def run_generator_controls(report):
    server = MockCacheServer().start()
    try:
        preload = lg.preload("127.0.0.1", server.port, 2000, 256, workers=4,
                             connections_per_worker=4, pipeline=16)
        report.add("generator.preload", "bounded-local-run",
                   preload["response"] == "STORED" and preload["stored"] == 2000,
                   f"stored={preload['stored']}/{preload['requested']} errors={preload['errors']}",
                   preload)

        engine = lg.Engine("127.0.0.1", server.port, workers=4, connections_per_worker=4,
                           pipeline=8, ratio="1:9", keys=2000, seed=7, duration_seconds=3,
                           mode="mixed", verify_values=True)
        mixed = engine.run()
        totals = mixed["totals"]
        report.add("generator.mixed", "bounded-local-run",
                   mixed["exitCode"] == 0 and totals["ops"] > 0 and totals["errors"] == 0
                   and 0.05 <= totals["setFraction"] <= 0.15,
                   f"ops={totals['ops']} setFraction={totals['setFraction']:.4f} "
                   f"hitRatio={totals['hitRatio']} errors={totals['errorKinds']}",
                   {"totals": totals, "measurement": mixed["measurement"],
                    "latencyUs": mixed["latencyUs"], "timelineSamples": len(mixed["timeline"]),
                    "rssSamples": len(mixed["rss"])})

        report.add("generator.timeline", "bounded-local-run", len(mixed["timeline"]) >= 3,
                   f"timelineSamples={len(mixed['timeline'])}")

        # Reproduce the plan keyspace so the composition gate sees a residency plan.
        plan = dict(mixed["plan"])
        plan.update({"connections": 16, "residency": "resident", "declaredHitRatio": 1.0})
        plan.setdefault("count", plan.get("keys", 0))
        row = synthetic_row(totals, plan)
        gate_matrix = g.gate_matrix(row)
        report.expect_accepted("gates.positive_control", g.aggregate(list(gate_matrix.values())))

        churn = lg.churn("127.0.0.1", server.port, cycles=100, workers=1)
        report.add("generator.churn", "bounded-local-run",
                   churn["validatedCycles"] == 100 and not churn["cycleErrors"],
                   f"validatedCycles={churn['validatedCycles']} errors={len(churn['cycleErrors'])}",
                   {"validatedCycles": churn["validatedCycles"], "exitCode": churn["exitCode"]})

        probe = lg.capacity_probe(mixed["plan"], probe_seconds=1.0)
        report.add("generator.capacity_probe", "bounded-local-run",
                   probe["commandsPerSecondUpperBound"] > 0,
                   f"ceiling={probe['commandsPerSecondUpperBound']:.0f} commands/s", probe)

        return mixed, plan, totals
    finally:
        server.stop()


def run_gate_fixtures(report, mixed, plan, totals):
    # unique_attempt_directory -------------------------------------------------
    good = g.unique_attempt_directory(pathlib.Path(".") / ("perf-gen-new-" + uuid.uuid4().hex))
    report.expect_accepted("gate.unique_attempt_directory.valid", good)
    report.expect_rejected("gate.unique_attempt_directory.reused", g.unique_attempt_directory(pathlib.Path(__file__).parent), "attempt_directory_not_empty_or_reused")
    report.expect_rejected("gate.unique_attempt_directory.missing_parent", g.unique_attempt_directory(pathlib.Path("no-such-parent") / "x"), "attempt_parent_missing")

    # stale_artifact_rejection -------------------------------------------------
    now, digest = time.time(), "a" * 64
    ok = g.stale_artifact_rejection({"name": "x.json", "sha256": digest, "recordedWall": now},
                                    now - 5, now + 5, root=None)
    report.expect_accepted("gate.stale_artifact_rejection.valid", ok)
    report.expect_rejected("gate.stale_artifact_rejection.predates",
                           g.stale_artifact_rejection({"name": "x.json", "sha256": digest, "recordedWall": now - 100},
                                                      now - 5, now + 5), "artifact_predates_or_postdates_attempt")
    report.expect_rejected("gate.stale_artifact_rejection.bad_digest",
                           g.stale_artifact_rejection({"name": "x.json", "sha256": "not-a-digest", "recordedWall": now},
                                                      now - 5, now + 5), "artifact_digest_invalid")

    # identity_match / jdk / harness ------------------------------------------
    report.expect_accepted("gate.identity_match.valid", g.identity_match(valid_identity(), valid_identity(), c.IDENTITY_KEYS))
    bad = dict(valid_identity(), jarSha256="f" * 64)
    report.expect_rejected("gate.identity_match.mismatch", g.identity_match(bad, valid_identity(), c.IDENTITY_KEYS), "identity_mismatch:jarSha256")
    report.expect_rejected("gate.identity_match.missing", g.identity_match({}, valid_identity(), c.IDENTITY_KEYS), "identity_missing:jarSha256")
    report.expect_accepted("gate.jdk_identity.valid", g.jdk_identity('openjdk version "25.0.4.1" 2026-08-18', "25.0.4.1"))
    report.expect_rejected("gate.jdk_identity.jdk17", g.jdk_identity('openjdk version "17.0.17" 2025-01-01', "25.0.4.1"), "jdk_not_25")
    report.expect_rejected("gate.jdk_identity.update", g.jdk_identity('openjdk version "25.0.2" 2026-01-01', "25.0.4.1"), "jdk_update_mismatch")
    report.expect_rejected("gate.jdk_identity.unparsable", g.jdk_identity("nonsense", "25.0.4.1"), "jdk_version_unparsable")
    report.expect_accepted("gate.harness_hashes.valid", g.harness_hashes({"run.py": "a" * 64}, {"run.py": "a" * 64}))
    report.expect_rejected("gate.harness_hashes.mismatch", g.harness_hashes({"run.py": "b" * 64}, {"run.py": "a" * 64}), "harness_digest_mismatch:run.py")
    report.expect_rejected("gate.harness_hashes.missing", g.harness_hashes({}, {"run.py": "a" * 64}), "harness_missing:run.py")

    # client_exit --------------------------------------------------------------
    report.expect_accepted("gate.client_exit.valid", g.client_exit({"exitCode": 0, "timedOut": False}))
    report.expect_rejected("gate.client_exit.nonzero", g.client_exit({"exitCode": 1, "timedOut": False}), "client_exit_nonzero_or_missing")
    report.expect_rejected("gate.client_exit.timeout", g.client_exit({"exitCode": 0, "timedOut": True}), "client_timeout_or_missing")
    report.expect_rejected("gate.client_exit.error", g.client_exit({"exitCode": 0, "timedOut": False, "error": "boom"}), "run_error")

    # preload_success ----------------------------------------------------------
    report.expect_accepted("gate.preload_success.valid", g.preload_success({"requested": 10, "stored": 10, "response": "STORED"}, 10))
    report.expect_rejected("gate.preload_success.short", g.preload_success({"requested": 10, "stored": 7, "response": "STORED"}, 10), "preload_stored_mismatch")
    report.expect_rejected("gate.preload_success.response", g.preload_success({"requested": 10, "stored": 10, "response": "PRELOAD_INCOMPLETE"}, 10), "preload_response:PRELOAD_INCOMPLETE")

    # workload_composition -----------------------------------------------------
    good_plan = {"ratioSetGet": "1:9", "connections": plan["connections"], "residency": "resident", "declaredHitRatio": 1.0}
    good_obs = {"setFraction": .1, "hitRatio": 1.0, "connections": plan["connections"], "ops": 1000, "errors": 0}
    report.expect_accepted("gate.workload_composition.valid", g.workload_composition(good_obs, good_plan))
    report.expect_rejected("gate.workload_composition.ratio", g.workload_composition(dict(good_obs, setFraction=.5), good_plan), "operation_ratio_mismatch")
    report.expect_rejected("gate.workload_composition.hit", g.workload_composition(dict(good_obs, hitRatio=.5), good_plan), "hit_ratio_below_declared")
    report.expect_rejected("gate.workload_composition.connections", g.workload_composition(dict(good_obs, connections=1), good_plan), "achieved_connections_unproven")
    report.expect_rejected("gate.workload_composition.errors", g.workload_composition(dict(good_obs, errors=3), good_plan), "client_operation_errors")
    report.expect_rejected("gate.workload_composition.missing", g.workload_composition({}, good_plan), "observed_workload_missing")

    # stable_measurement_window ------------------------------------------------
    report.expect_accepted("gate.stable_measurement_window.valid", g.stable_measurement_window(stable_rss_window(), dict(c.WINDOW_CONFIG)))
    report.expect_rejected("gate.stable_measurement_window.too_few", g.stable_measurement_window(stable_rss_window(count=2), dict(c.WINDOW_CONFIG)), "window_too_few_samples")
    drifting = stable_rss_window()
    for index, sample in enumerate(drifting):
        sample["total_rss"] += index * 6 * 1024 * 1024
    report.expect_rejected("gate.stable_measurement_window.drift", g.stable_measurement_window(drifting, dict(c.WINDOW_CONFIG)), "rss_not_converged")

    # cgroup_v2_evidence -------------------------------------------------------
    report.expect_accepted("gate.cgroup_v2_evidence.valid", g.cgroup_v2_evidence(valid_cgroup(), valid_cgroup()))
    report.expect_rejected("gate.cgroup_v2_evidence.v1", g.cgroup_v2_evidence({"version": 1, "files": {}, "errors": {}}, {"version": 1, "files": {}, "errors": {}}), "cgroup_v2_not_available")
    changed = valid_cgroup()
    changed["files"] = dict(changed["files"], **{"memory.max": "536870912"})
    report.expect_rejected("gate.cgroup_v2_evidence.changed_limit", g.cgroup_v2_evidence(valid_cgroup(), changed), "cgroup_limit_missing_or_changed:memory.max")
    throttled = valid_cgroup()
    throttled["files"] = dict(throttled["files"], **{"cpu.stat": "nr_throttled 5\nthrottled_usec 9000\nusage_usec 123"})
    report.expect_rejected("gate.cgroup_v2_evidence.throttle_regression", g.cgroup_v2_evidence(throttled, valid_cgroup()), "cgroup_counter_regressed:nr_throttled")

    # generator_non_saturation -------------------------------------------------
    report.expect_accepted("gate.generator_non_saturation.headroom", g.generator_non_saturation({}, {"commandsPerSecondUpperBound": 100000, "achievedOpsPerSecond": 25000}))
    report.expect_rejected("gate.generator_non_saturation.bottleneck", g.generator_non_saturation({}, {"commandsPerSecondUpperBound": 30000, "achievedOpsPerSecond": 25000}), "generator_headroom_below_minimum")
    report.expect_rejected("gate.generator_non_saturation.cpu", g.generator_non_saturation({"processCpuFraction": .95, "logicalCpus": 8}), "generator_cpu_over_budget")
    report.expect_rejected("gate.generator_non_saturation.cpu_missing", g.generator_non_saturation({"logicalCpus": 8}), "generator_cpu_unavailable")
    report.expect_accepted("gate.generator_non_saturation.cpu_budget", g.generator_non_saturation({"processCpuFraction": .4, "logicalCpus": 8}))

    # soak_completeness --------------------------------------------------------
    report.expect_accepted("gate.soak_completeness.complete", g.soak_completeness({"observedSeconds": 259200, "gaps": [], "restartCount": 0, "status": "COMPLETE"}, 259200, 60))
    report.expect_rejected("gate.soak_completeness.short", g.soak_completeness({"observedSeconds": 3600, "gaps": [], "restartCount": 0, "status": "INTERRUPTED"}, 259200, 60), "soak_duration_short")
    report.expect_rejected("gate.soak_completeness.gap", g.soak_completeness({"observedSeconds": 259200, "gaps": [600], "restartCount": 0, "status": "COMPLETE"}, 259200, 60), "soak_gaps_detected")
    report.expect_rejected("gate.soak_completeness.restart", g.soak_completeness({"observedSeconds": 259200, "gaps": [], "restartCount": 2, "status": "COMPLETE"}, 259200, 60), "server_restart_detected")

    # Explicit campaign qualification; warnings are not evidence invalidity. ----
    good_cpu = {"processCpuFraction": 3.0, "clientCpuCount": 12, "maxThreadCpuSeconds": .4}
    report.add("contracts.generator_saturation.valid", "positive-control",
               c.generator_evidence_reasons({"generatorCpu": good_cpu}, {"kind": "memtier"}, True) == ([], []),
               "expected no reasons for a claimed qualification row with evidence")
    report.add("contracts.generator_saturation.claim_missing", "negative-fixture",
               c.generator_evidence_reasons({}, {"kind": "memtier"}, True)
               == (["generator_cpu_evidence_missing"], []),
               "a qualification claim without evidence must be rejected")
    report.add("contracts.generator_saturation.claim_incomplete", "negative-fixture",
               c.generator_evidence_reasons({"generatorCpu": {}}, {"kind": "memtier"}, True) == (["generator_cpu_evidence_missing"], []),
               "a qualification claim with unusable evidence must be rejected")
    report.add("contracts.generator_saturation.synthetic_exempt", "positive-control",
               c.generator_evidence_reasons({}, {"kind": "memtier"}, False) == ([], []),
               "synthetic/non-qualification rows without a claim and without evidence are exempt")
    report.add("contracts.generator_saturation.over_budget", "negative-fixture",
               c.generator_evidence_reasons({"generatorCpu": dict(good_cpu, processCpuFraction=12.0)},
                   {"kind": "memtier"}, True) == ([], ["generator_cpu_over_budget_observation_not_causal_proof"]),
               "budget observation must warn without invalidity")
    report.add("contracts.generator_saturation.allocation_missing", "negative-fixture",
               c.generator_evidence_reasons({"generatorCpu": {"processCpuFraction": 1.0}}, {"kind": "memtier"}, True)
               == (["generator_cpu_evidence_missing"], ["generator_cpu_allocation_missing"]),
               "expected generator_cpu_allocation_missing")
    report.add("contracts.generator_saturation.headroom", "negative-fixture",
               c.generator_evidence_reasons(
                   {"generatorCpu": good_cpu,
                     "generatorHeadroom": {"ceilingOpsPerSecond": 10, "achievedOpsPerSecond": 9}},
                   {"kind": "memtier"}, True) == ([], ["generator_headroom_below_minimum_observation_not_causal_proof"]),
               "headroom observation must warn without invalidity")
    report.add("contracts.generator_saturation.no_client_load", "positive-control",
               c.generator_evidence_reasons({}, {"kind": "memory"}, True) == ([], []),
               "memory rows have no client load")

    # collector.client_cpu_summary ---------------------------------------------
    samples = []
    ticks = {"1": 100, "2": 100}
    base = time.monotonic()
    for index in range(5):
        previous = dict(ticks)
        ticks = {"1": 100 + (index + 1) * 50, "2": 100 + (index + 1) * 25}
        samples.append({"monotonic": base + index + 1, "wall": base + index + 1, "intervalSeconds": 1.0,
                        "previousClientCpuTicks": previous, "clientCpuTicks": dict(ticks),
                        "clockTicksPerSecond": 100})
    summary = collector.client_cpu_summary({"samples": samples}, 12, (base, base + 5))
    report.add("collector.cpu_window_required", "negative-fixture",
               collector.client_cpu_summary({"samples": samples}, 12).get("reasons") == ["generator_cpu_window_invalid"],
               "missing explicit active window must fail closed")
    report.add("contracts.qualification_missing_cpu_cannot_opt_out", "negative-fixture",
               c.generator_evidence_reasons({"performanceQualification": False}, {"kind": "memtier"}, True)
               == (["generator_cpu_evidence_missing"], []), "row flag cannot override campaign qualification")
    report.add("collector.cpu_window_exact", "positive-control",
               summary.get("activeWindow") == [base, base+5] and summary.get("windowSeconds") == 5,
               "measurement numerator and denominator use the explicit native interval")
    partial_samples = [dict(s) for s in samples]
    partial_samples[0] = dict(partial_samples[0], previousClientCpuTicks={})
    partial = collector.client_cpu_summary({"samples": partial_samples}, 12, (base, base+5))
    floor, observations = c.generator_evidence_reasons({"generatorCpu": partial}, {"kind": "memtier"}, True)
    report.add("collector.partial_cpu_is_observation", "positive-control",
               partial["status"] == "measured" and partial["partial"] and not floor and
               "generator_cpu_observation_partial" in observations, str(partial))
    floor, observations = c.generator_evidence_reasons({"generatorCpu": {"status": "invalid", "reasons": ["generator_cpu_clock_changed"]}}, {"kind": "memtier"}, True)
    report.add("collector.no_usable_cpu_floor", "negative-fixture",
               floor == ["generator_cpu_evidence_missing"] and observations == ["generator_cpu_clock_changed"], str((floor, observations)))
    report.add("collector.client_cpu_summary", "unit",
               summary["clientCpuCount"] == 12 and 0 < summary["processCpuFraction"] <= 3.0,
               f"fraction={summary['processCpuFraction']} seconds={summary['processCpuSeconds']}",
               summary)
    report.add("collector.client_cpu_summary.empty", "negative-fixture",
               collector.client_cpu_summary({"samples": []}, 12).get("processCpuFraction") is None,
               "expected no client cpu samples")

    # exact nonparametric median intervals for the planned pair count -------------
    n3 = c.median_interval([.95, .96, .97])
    report.add("contracts.median_interval.n3_exploratory", "unit",
               n3["method"] == "exploratory-range" and n3["sufficientPrecision"] is False
               and abs(n3["coverage"] - .75) < 1e-9 and n3["n"] == 3,
               f"method={n3['method']} coverage={n3['coverage']} bounds={n3['bounds']}")
    n6 = c.median_interval([.95, .96, .97, .98, .94, .93])
    report.add("contracts.median_interval.n6_exact", "unit",
               n6["method"] == "exact-binomial-median" and n6["sufficientPrecision"] is True
               and abs(n6["coverage"] - .96875) < 1e-9 and n6["n"] == 6,
               f"method={n6['method']} coverage={n6['coverage']} bounds={n6['bounds']}")
    small = c.classify("M1", [.95, .96, .97], expected_pairs=3)
    report.add("contracts.classify.small_count_inconclusive", "unit",
               small["verdict"] == "INCONCLUSIVE" and "insufficient_precision" in small["reasons"],
               f"verdict={small['verdict']} reasons={small['reasons']}")
    adversarial = c.classify("M1", [.10, .20, .30], expected_pairs=3)
    report.add("contracts.classify.adverse_small_count_fail", "unit",
               adversarial["verdict"] == "FAIL" and "valid_median_threshold_breach" in adversarial["reasons"]
               and adversarial["interval"]["sufficientPrecision"] is False,
               f"verdict={adversarial['verdict']} interval={adversarial.get('interval', {}).get('method')}")

    # pair completeness ----------------------------------------------------------
    rows = [{"scenario": "P02", "rep": rep, "server": server}
            for rep in range(3) for server in ("reference", "octomem")]
    report.add("contracts.validate_pairs.complete", "positive-control",
               c.validate_pairs(rows, "P02", 3)["valid"], "3 alternating pairs complete")
    dropped = rows[:-1]
    report.add("contracts.validate_pairs.missing", "negative-fixture",
               any("missing_pair" in r for r in c.validate_pairs(dropped, "P02", 3)["reasons"]),
               "expected missing_pair reason")
    duplicated = rows + [rows[-1]]
    report.add("contracts.validate_pairs.duplicate", "negative-fixture",
               any("duplicate_pair" in r for r in c.validate_pairs(duplicated, "P02", 3)["reasons"]),
               "expected duplicate_pair reason")


def run_soak_control(report, run_directory):
    """Bounded soak against the mock server: sampling, phases, status, no 72h claim."""
    import tempfile
    out_parent = pathlib.Path(tempfile.mkdtemp(prefix="soak-control-", dir=run_directory))
    server = MockCacheServer().start()
    out = out_parent / ("soak-" + uuid.uuid4().hex[:8])
    plan = {"plannedSeconds": 6, "intervalSeconds": 1, "phaseSeconds": 2,
            "phases": soak.DEFAULT_PHASES, "server": "mock", "collectExtra": False}
    monitor = soak.ServerMonitor("127.0.0.1", server.port, pid=None)

    def load_phase(index, name, ratio, seconds):
        time.sleep(seconds)
        return {"exitCode": 0, "artifact": None, "phase": name}

    try:
        runner = soak.SoakRunner(out, plan, load_phase, monitor, bounded=True)
        result = runner.run()
        report.add("soak.bounded_complete", "bounded-local-run",
                   result["status"] == "BOUNDED_COMPLETE" and result["bounded"] is True
                   and result["sixtyTwelveHourClaim"] is False and result["sampleCount"] > 0
                   and result["phaseCount"] >= 2,
                   f"status={result['status']} samples={result['sampleCount']} phases={result['phaseCount']}",
                   {"status": result["status"], "sampleCount": result["sampleCount"],
                    "phaseCount": result["phaseCount"], "observedSeconds": result["observedSeconds"],
                    "sixtyTwelveHourClaim": result["sixtyTwelveHourClaim"],
                    "completenessGateValid": result["completenessGate"]["valid"]})
        report.add("soak.no_72h_claim", "negative-fixture",
                   result["completenessGate"]["valid"] is False
                   and any("soak_status" in r for r in result["completenessGate"]["reasons"]),
                   f"gate reasons={result['completenessGate']['reasons']}")
        # An interrupted bounded run must not report complete.
        failed = soak.SoakRunner(out_parent / ("soak-" + uuid.uuid4().hex[:8]), plan,
                                 lambda *a: {"exitCode": 1, "artifact": None}, monitor, bounded=True)
        failed_result = failed.run()
        report.add("soak.interrupted_status", "negative-fixture",
                   failed_result["status"] == "INTERRUPTED" and failed_result["failedPhases"],
                   f"status={failed_result['status']} failedPhases={len(failed_result['failedPhases'])}")
    finally:
        server.stop()
        import shutil
        shutil.rmtree(out_parent)


def gate_sample_series(per_interval_ops):
    """Build server samples whose cmd_get delta per interval equals the given ops."""
    samples = [{"stats": {"cmd_get": "0", "cmd_set": "0"}}]
    total = 0
    for ops in per_interval_ops:
        total += ops
        samples.append({"stats": {"cmd_get": str(total), "cmd_set": "0"}})
    return samples


def run_external_gate_fixtures(report):
    """Regression cases for soak.external_traffic_gate (F2 hardening)."""
    full = soak.external_traffic_gate(gate_sample_series([100] * 20))
    report.expect_accepted("soak.external_traffic_gate.full", full)
    report.add("soak.external_traffic_gate.limits_exposed", "negative-fixture",
               full.get("maxConsecutiveZeroTrafficIntervals") == 0
               and full.get("consecutiveZeroLimit") == 5
               and full.get("maxZeroTrafficFraction") == 0.02,
               f"maxConsec={full.get('maxConsecutiveZeroTrafficIntervals')} "
               f"limit={full.get('consecutiveZeroLimit')} fraction={full.get('maxZeroTrafficFraction')}")

    zero = soak.external_traffic_gate(gate_sample_series([0] * 20))
    report.expect_rejected("soak.external_traffic_gate.zero_operations", zero,
                           "external_traffic_zero_operations")

    # Exactly 2% empty (2 of 100) is still valid; 3% is rejected.
    within = soak.external_traffic_gate(gate_sample_series([0, 0] + [100] * 98))
    report.expect_accepted("soak.external_traffic_gate.fraction_within", within)
    exceeded = soak.external_traffic_gate(gate_sample_series([0, 0, 0] + [100] * 97))
    report.expect_rejected("soak.external_traffic_gate.fraction_exceeded", exceeded,
                           "external_traffic_absent_in_intervals")

    # 5 consecutive empty intervals is within the limit; 6 is not (and is still <2%).
    ok_run = soak.external_traffic_gate(gate_sample_series([0] * 5 + [100] * 995))
    report.expect_accepted("soak.external_traffic_gate.consecutive_within", ok_run)
    report.add("soak.external_traffic_gate.consecutive_within_count", "negative-fixture",
               ok_run["maxConsecutiveZeroTrafficIntervals"] == 5,
               f"maxConsec={ok_run['maxConsecutiveZeroTrafficIntervals']}")
    over_run = soak.external_traffic_gate(gate_sample_series([0] * 6 + [100] * 994))
    report.expect_rejected("soak.external_traffic_gate.consecutive_exceeded", over_run,
                           "external_traffic_consecutive_gap")
    report.add("soak.external_traffic_gate.consecutive_only_reason", "negative-fixture",
               over_run["reasons"] == ["external_traffic_consecutive_gap"],
               f"reasons={over_run['reasons']} maxConsec={over_run['maxConsecutiveZeroTrafficIntervals']}")

    missing = soak.external_traffic_gate([{}, {}, {}])
    report.expect_rejected("soak.external_traffic_gate.counters_missing", missing,
                           "external_traffic_counters_missing")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-parent", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)
    if not args.output_parent.is_dir():
        parser.error("output parent must exist: " + str(args.output_parent))
    run_id = "verify-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    out = args.output_parent / run_id
    out.mkdir(exist_ok=False)
    report = Report()
    try:
        mixed, plan, totals = run_generator_controls(report)
        run_gate_fixtures(report, mixed, plan, totals)
        run_external_gate_fixtures(report)
        run_soak_control(report, out)
    finally:
        pass
    passed = all(case["passed"] for case in report.cases)
    result = {
        "schemaVersion": 1,
        "kind": "harness-self-verification",
        "notAcceptance": True,
        "runId": run_id,
        "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "python": sys.version,
        "platform": sys.platform,
        "generatorSha256": lg.sha256_file(pathlib.Path(lg.__file__)),
        "gatesSha256": lg.sha256_file(pathlib.Path(g.__file__)),
        "contractsSha256": lg.sha256_file(pathlib.Path(c.__file__)),
        "cases": report.cases,
        "passed": passed,
        "failedCases": [case["name"] for case in report.cases if not case["passed"]],
        "exitCode": 0 if passed else 1,
    }
    (out / "self-verification.json").write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    print(str(out))
    print(f"cases={len(report.cases)} failed={len(result['failedCases'])} exit={result['exitCode']}")
    return result["exitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
