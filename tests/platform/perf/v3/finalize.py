"""V3 EXPLICITLY VERSIONED MEASUREMENT CORE - import-safe, fail-closed reader.

Derived from `tests/platform/perf/pinned/finalize.py`
(sha256 9cc15c1ac61cfb4cada6fb77dcfd681495ee66114fab5ea0bcc4916b6654ca77).
Historical evidence is read-only. Only the seven instrument defects (a)-(g) are
repaired; every pinned check is retained. See `V3-DELTAS.md`.
"""
import argparse
import datetime
import hashlib
import json
import math
import pathlib
import sys

try:
    from . import contracts as c
except ImportError:
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import contracts as c

# Distinct process status for a NOT_RUN attempt (no scenario sealed/collected).
EXIT_NOT_RUN = 2


def sha256(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def _iso_utc(epoch):
    if not isinstance(epoch, (int, float)) or isinstance(epoch, bool) or not math.isfinite(epoch):
        return None
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def collection_start_utc(rows):
    """Earliest positive observed run start in the attempt, as UTC ISO-8601."""
    stamps = []
    for row in rows:
        for value in (row.get("process", {}).get("startedWall"), row.get("wallStart")):
            if c.positive(value):
                stamps.append(value)
    return _iso_utc(min(stamps)) if stamps else None


def read_cpu_observation(row, passport, kind):
    """Recompute per-row generator CPU from the retained raw samples (defect a).

    The frozen first/last aggregation is replaced by the v3 consecutive per-thread
    accumulation. The collected raw field is retained for disclosure.
    """
    raw = row.get("generatorCpu")
    row["generatorCpuRawFraction"] = raw.get("processCpuFraction") if isinstance(raw, dict) else None
    row["generatorCpuRaw"] = raw if isinstance(raw, dict) else None
    if kind != "memtier":
        return
    window = None
    try:
        runtime = row["native"]["ALL STATS"]["Runtime"]
        if runtime["Time unit"] == "MILLISECONDS" and all(c.discrete(runtime[k]) for k in ("Start time", "Finish time")):
            window = (runtime["Start time"] / 1000, runtime["Finish time"] / 1000)
    except (KeyError, TypeError, ValueError, OverflowError):
        pass
    corrected = c.corrected_generator_cpu(row.get("samples", []), passport.get("clientCpuCount"), window)
    if corrected is not None:
        row["generatorCpu"] = corrected


def read_attempt(root):
    """Load every row, attach errors rather than substitute/drop stale artifacts."""
    passport = json.loads((root / "passport.json").read_text(encoding="utf-8"))
    rows = json.loads((root / "runs.json").read_text(encoding="utf-8"))
    errors = []
    if not isinstance(passport, dict):
        passport = {}
        errors.append("passport_schema_invalid")
    if not isinstance(rows, list):
        rows = []
        errors.append("runs_schema_invalid")
    rows = [r if isinstance(r, dict) else {"scenario": "invalid-row", "error": "row schema invalid", "rawRow": r} for r in rows]
    identity = passport.get("identity", {})
    if type(passport.get("schemaVersion")) is not int or passport.get("schemaVersion") != 2:
        errors.append("unsupported_evidence_schema")
    if root.name != identity.get("attemptId"):
        errors.append("attempt_directory_identity_mismatch")
    try:
        complete = json.loads((root / "complete.json").read_text(encoding="utf-8"))
        if complete.get("identity") != identity or complete.get("runsSha256") != sha256(root / "runs.json") or complete.get("passportSha256") != sha256(root / "passport.json"):
            errors.append("completion_identity_mismatch")
        if complete.get("completed") is not True or complete.get("captureSealed", True) is not True:
            errors.append("attempt_incomplete")
        if "planComplete" in complete and type(complete["planComplete"]) is not bool:
            errors.append("completion_plan_flag_invalid")
        pair_plans = [c.validate_pairs(rows, name, passport.get("repetitions")) for name in c.SCENARIOS]
        plan_complete = all(p["valid"] for p in pair_plans)
        passport["captureStatus"] = {"sealed": complete.get("completed") is True,
                                     "planComplete": plan_complete}
        if not plan_complete:
            errors.append("campaign_plan_incomplete")
        if complete.get("planComplete") is True and not plan_complete:
            errors.append("completion_plan_claim_mismatch")
        if complete.get("planComplete") is False:
            errors.append("campaign_plan_incomplete")
        manifest = complete.get("artifacts", {})
        if not {"runs.json", "passport.json", "calibration.json"}.issubset(manifest):
            errors.append("artifact_manifest_incomplete")
        for filename, digest in manifest.items():
            path = root / filename
            if pathlib.Path(filename).name != filename or not path.is_file() or sha256(path) != digest:
                errors.append("attempt_artifact_mismatch:" + filename)
    except (OSError, ValueError):
        errors.append("completion_missing_or_invalid")
    for filename, digest in passport.get("harnessFiles", {}).items():
        path = root / "harness" / filename
        if pathlib.Path(filename).name != filename or not path.is_file() or sha256(path) != digest:
            errors.append("harness_archive_mismatch:" + filename)
    if not passport.get("harnessFiles") or passport.get("harnessFiles", {}).get("run.py") != identity.get("harnessSha256"):
        errors.append("harness_archive_missing")
    # Defect (f): bind the frozen protocol to this attempt by digest before any row
    # is qualified. A missing or drifting protocol file fails closed.
    protocol_path = passport.get("protocolPath")
    if not protocol_path:
        errors.append("protocol_evidence_missing")
    else:
        path = root / pathlib.Path(protocol_path).name
        if not path.is_file() or passport.get("protocolSha256") != sha256(path):
            errors.append("protocol_evidence_mismatch")
    try:
        calibration = json.loads((root / "calibration.json").read_text(encoding="utf-8"))
        expected_keys = {(server, key, flags) for server in ("reference", "octomem") for key in (1, 16, 250) for flags in (0, 1, 65535, 4294967295)}
        actual_keys = [(r["server"], r["keyBytes"], r["flags"]) for r in calibration]
        if set(actual_keys) != expected_keys or len(actual_keys) != len(expected_keys):
            errors.append("calibration_inventory_mismatch")
        for row in calibration:
            checks = row["checks"]
            if any(not c.discrete(row.get(key)) for key in ("keyBytes", "flags", "accepted", "rejected")) or any(not c.discrete(check.get("valueBytes")) for check in checks):
                errors.append("calibration_integer_invalid")
                continue
            if row["rejected"] != row["accepted"]+1 or len(checks) != 2 or checks[0]["valueBytes"] != row["accepted"] or checks[1]["valueBytes"] != row["rejected"] or checks[0]["response"] != "STORED" or checks[0].get("readbackVerified") is not True or checks[1]["response"] != "SERVER_ERROR object too large for cache":
                errors.append("calibration_boundary_or_readback_invalid")
            if not 0 < row["accepted"] <= 1048576 or checks[0].get("payloadSha256") != hashlib.sha256(b"x"*row["accepted"]).hexdigest():
                errors.append("calibration_payload_digest_invalid")
            if row.get("identity", {}).get("attemptId") != identity.get("attemptId"):
                errors.append("calibration_identity_mismatch")
    except (OSError, KeyError, TypeError, ValueError):
        errors.append("calibration_missing_or_invalid")
    for row in rows:
        row["evidenceErrors"] = []
        if row.get("scenario") not in c.SCENARIOS:
            errors.append("unexpected_scenario:" + str(row.get("scenario")))
            continue
        # Reconstruct commands from the frozen plan, not row self-attestation.
        from run import server_argv, client_argv
        paths = passport.get("paths", {})
        server_cpus = passport.get("serverCpus", "0-3")
        client_cpus = passport.get("clientCpus", "4-15")
        # Transport parameter from the frozen plan: loopback for the co-resident
        # collector, the server host address for the two-machine runner. Only the
        # address token changes; no estimator/workload/threshold is involved.
        address = passport.get("serverAddress", "127.0.0.1")
        try:
            w = c.workload(row["scenario"])
            row["expectedServerArgv"] = server_argv(row["server"], paths["java"], paths["reference"], paths["jar"], w["memoryMiB"], server_cpus, address)
            label = f"{row['scenario']}-r{row['rep']}-{row['server']}"
            if w["kind"] == "memtier":
                row["expectedClientArgv"] = client_argv(paths["client"], pathlib.Path(passport["collectorOutput"]), label, w, passport["windowConfig"]["measurementSeconds"], client_cpus, address)
        except (KeyError, TypeError):
            row["evidenceErrors"].append("command_plan_missing")
        kind = c.workload(row["scenario"])["kind"]
        if kind != "memtier":
            read_cpu_observation(row, passport, kind)
            continue
        filename = f"{row.get('scenario')}-r{row.get('rep')}-{row.get('server')}.memtier.json"
        artifact = row.get("artifact", {})
        path = root / filename
        if artifact.get("name") != filename or pathlib.Path(filename).name != filename:
            row["evidenceErrors"].append("artifact_name_mismatch")
        elif not path.is_file() or not artifact.get("sha256") or sha256(path) != artifact["sha256"]:
            row["evidenceErrors"].append("artifact_digest_mismatch_or_missing")
        else:
            try:
                row["native"] = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                row["evidenceErrors"].append("artifact_json_invalid")
        read_cpu_observation(row, passport, kind)
    intervals = [(r.get("process", {}).get("startedWall"), r.get("wallEnd")) for r in rows]
    if all(c.positive(start) and c.positive(end) and start < end for start, end in intervals):
        intervals.sort()
        if any(end >= next_start for (_, end), (next_start, _) in zip(intervals, intervals[1:])):
            errors.append("campaign_runs_overlap")
    else:
        errors.append("campaign_run_intervals_invalid")
    return passport, rows, sorted(set(errors))


def build_scenario(rows, name, passport=None, evidence_errors=(), qualification=False):
    """Pure scenario construction; absent evidence is explicit NOT_RUN/INCONCLUSIVE."""
    passport = passport or {}
    repetitions = passport.get("repetitions")
    pairs = c.validate_pairs(rows, name, repetitions)
    common = list(evidence_errors) + pairs["reasons"]
    if type(qualification) is not bool:
        common.append("qualification_parameter_invalid")
        qualification = False
    if type(passport.get("schemaVersion")) is not int or passport.get("schemaVersion") != 2:
        common.append("unsupported_evidence_schema")
    if passport.get("stoppingRule") != "fixed-pairs-no-extension":
        common.append("stopping_rule_missing")
    if type(repetitions) is not int or repetitions < 2:
        common.append("unbalanced_pair_plan")
    expected = c.workload(name)
    if passport.get("workloads", {}).get(name) != expected:
        common.append("scenario_plan_mismatch")
    for key, value in expected.items():
        if type(value) is int and not c.discrete(passport.get("workloads", {}).get(name, {}).get(key)):
            common.append("scenario_plan_integer_invalid:" + key)
    # Defect (f): attempt-level protocol binding; defect (e): topology isolation.
    protocol = c.protocol_identity(passport.get("protocol"), collection_start_utc(pairs["rows"] or rows), passport.get("protocolSha256"))
    for reason in protocol["reasons"]:
        common.append("protocol:" + reason)
    topology = c.topology_isolation(passport.get("serverCpus"), passport.get("clientCpus"), passport.get("topology"))
    for reason in topology["reasons"]:
        common.append("topology:" + reason)
    # Correction F5.3 + qualification-marker repair: a PASS requires the qualification
    # marker AND an attempt seal. An explicitly recorded unsealed capture blocks even
    # when the marker is present; a missing marker with no seal also blocks. The marker
    # is a declaration only - seal, plan completeness, calibration and identity are
    # still enforced independently, so it can never qualify a bad attempt by itself.
    seal = passport.get("captureStatus")
    sealed = seal.get("sealed") if isinstance(seal, dict) else None
    seal_recorded = (sealed is True) or passport.get("performanceQualification") is True
    if isinstance(seal, dict) and sealed is not True:
        common.append("attempt_seal_missing")
    elif not seal_recorded and type(passport.get("qualification")) is not bool:
        common.append("attempt_seal_missing")
    provenance = {"headroomProbes": passport.get("generatorHeadroomProbes")}
    validations = []
    for row in pairs["rows"]:
        validation = c.validate_row(row, expected, passport.get("identity", {}), passport.get("windowConfig", {}), qualification, provenance)
        if type(row.get("rep")) is int:
            planned = ["reference", "octomem"] if row["rep"] % 2 == 0 else ["octomem", "reference"]
            if row.get("server") not in planned or row.get("pairOrder") != planned or not c.discrete(row.get("orderIndex")) or row.get("orderIndex") != planned.index(row["server"]):
                validation["reasons"].append("pair_order_mismatch")
        validations.append(dict(rep=row.get("rep"), server=row.get("server"), **validation))
        common.extend(f"r{row.get('rep')}:{row.get('server')}:{reason}" for reason in validation["reasons"])
    # Check every available interval even when a different pair is missing.
    ordered = sorted((r for r in pairs["rows"] if c.discrete(r.get("rep")) and c.discrete(r.get("orderIndex"))),
                     key=lambda r: (r["rep"], r["orderIndex"]))
    previous_end = None
    for row in ordered:
        try:
            start, end = row["process"]["startedWall"], row["wallEnd"]
            if not all(c.positive(t) for t in (start, row["wallStart"], end)) or not start < row["wallStart"] < end:
                common.append("observed_run_time_invalid")
            else:
                if previous_end is not None and start <= previous_end:
                    common.append("observed_runs_overlap_or_out_of_order")
                previous_end = end
        except (KeyError, TypeError):
            common.append("observed_run_time_missing")
    metrics, ratios = {}, {m: [] for m in c.THRESHOLDS}
    if pairs["valid"]:
        for rep in range(repetitions):
            ref = next(r for r in pairs["rows"] if r["rep"] == rep and r["server"] == "reference")
            octo = next(r for r in pairs["rows"] if r["rep"] == rep and r["server"] == "octomem")
            try:
                first, second = (ref, octo) if rep % 2 == 0 else (octo, ref)
                if not first["process"]["startedWall"] < first["wallStart"] < first["wallEnd"] < second["process"]["startedWall"] < second["wallStart"] < second["wallEnd"]:
                    common.append(f"r{rep}:observed_pair_order_or_time_invalid")
            except (KeyError, TypeError):
                common.append(f"r{rep}:observed_pair_time_missing")
            for metric in ratios:
                try:
                    if metric == "M3":
                        a, b = ref["total_rss"], octo["total_rss"]
                    elif expected["kind"] == "cycles":
                        if metric == "M1":
                            a, b = sum(octo["cyclesSeconds"]), sum(ref["cyclesSeconds"])
                        else:
                            a, b = sorted(ref["cyclesSeconds"])[98], sorted(octo["cyclesSeconds"])[98]
                    elif expected["kind"] == "memtier":
                        a, b = ref["native"]["ALL STATS"]["Totals"], octo["native"]["ALL STATS"]["Totals"]
                        a, b = (a["Ops/sec"], b["Ops/sec"]) if metric == "M1" else (a["Percentile Latencies"]["p99.00"], b["Percentile Latencies"]["p99.00"])
                    else:
                        continue
                    ratios[metric].append(b/a if c.positive(a) and c.positive(b) else None)
                except (KeyError, TypeError, IndexError, ZeroDivisionError):
                    ratios[metric].append(None)
    for metric in (("M3",) if expected["kind"] == "memory" else c.THRESHOLDS):
        reasons = common + [f"r{v['rep']}:{v['server']}:{r}" for v in validations for r in v["metricReasons"][metric]]
        metrics[metric] = c.classify(metric, ratios[metric], reasons, repetitions)
    # Correction F5.3: an analysis-only (unrecorded / unsealed) scenario never reports
    # PASS; a would-be PASS becomes NOT_RUN with an explicit reason.
    if qualification is not True:
        for metric in metrics:
            if metrics[metric]["verdict"] == "PASS":
                metrics[metric]["verdict"] = "NOT_RUN"
                metrics[metric]["reasons"] = sorted(set(metrics[metric].get("reasons", []) + ["analysis_only_not_qualified"]))
    # Correction F5.3: an attempt with no recorded qualification/seal is NOT_RUN.
    if "attempt_seal_missing" in common:
        for metric in metrics:
            metrics[metric]["verdict"] = "NOT_RUN"
            metrics[metric]["reasons"] = sorted(set(metrics[metric].get("reasons", []) + ["attempt_seal_missing"]))
    # Defect (g): a scenario that was never collected is NOT_RUN, never PASS.
    if not pairs["rows"]:
        for metric in metrics:
            metrics[metric]["verdict"] = "NOT_RUN"
            metrics[metric]["reasons"] = sorted(set(metrics[metric].get("reasons", []) + ["scenario_not_run"]))
    verdicts = [m["verdict"] for m in metrics.values()]
    verdict = ("FAIL" if "FAIL" in verdicts else
               "NOT_RUN" if verdicts and all(v == "NOT_RUN" for v in verdicts) else
               "PASS" if verdicts and all(v == "PASS" for v in verdicts) else "INCONCLUSIVE")
    return {"scenario": name, "v3CoreVersion": c.V3_CORE_VERSION, "qualification": qualification,
            "verdict": verdict, "metrics": metrics, "validation": validations,
            "rowCount": len(pairs["rows"]), "pairReasons": pairs["reasons"],
            "protocol": protocol, "topology": topology}


def render(scenarios, passport, errors):
    lines = ["# TEST-05 Evidence Analysis (v3 explicitly versioned core)", "",
             "M1 >= 0.90; M2 <= 1.25; M3 total process RSS <= 1.20. No release PASS is computed.",
             "Invalid evidence is INCONCLUSIVE or NOT_RUN before threshold comparison, including an adverse observed median.",
             "CPU >=0.85 is a busy-thread warning, not proof of client domination; P08 does not establish a cause for P03.", "",
             "| Scenario | Metric | Valid median ratio | Interval method / bounds / coverage | Verdict | Reasons |", "|---|---|---|---|---|---|"]
    for scenario in scenarios:
        lines.append(f"\n{scenario['scenario']}: " + ("qualification" if scenario.get("qualification") else "analysis-only (not a qualification verdict)") + ".\n")
        for metric, result in scenario["metrics"].items():
            interval = result.get("interval")
            detail = "not qualified" if not interval else f"{interval['method']} / {interval['bounds']} / {interval['coverage']:.5f}"
            median = result.get("medianPairedRatio")
            lines.append(f"| {scenario['scenario']} | {metric} | {median if median is not None else 'not qualified'} | {detail} | {result['verdict']} | {'; '.join(result['reasons'])} |")
    lines += ["", "## Identity", "", "Recorded attempt identity (not an assertion of current-binary equality):", "```json", json.dumps(passport.get("identity", {}), indent=2), "```",
              "Evidence errors: " + ("; ".join(errors) or "none detected by the artifact reader"), "",
              "## Method And Limits", "", f"Planned pairs: {passport.get('repetitions', 'missing')}; stopping: {passport.get('stoppingRule', 'missing')}.",
              "Order-statistic intervals use exact binomial ranks under independent continuous stationary paired ratios; n=3 min/max is exploratory with 75% coverage, not a 95% CI. n=6 min/max has 96.875% nominal coverage but may be wide. Independence is not proven by six runs.",
              "RSS is the median of a predefined timestamped window; mean and sampled peak are separate in validation. Window convergence is bounded qualification, not proof of equilibrium or JVM warmup completion. Dataset RSS is not substituted.",
              "A five-second preparatory client does not warm the new measured connection population. Time-limited operation totals may differ; offered definitions, proportions, residency policy, response bytes and counter deltas must agree with the plan.",
              "P06 is capacity pressure, not equal resident cardinality. P07 is one startup and 100 connection/version/close cycles, not 100 JVM startups; cold-cycle RSS cannot be qualified by a quiescent substitute.",
              "Client/server CPU and actual cgroup limits/counters are observations, not a causal capacity intervention. No P08-to-P03 causal inference is made. No forced GC is used in timed measurements.",
              "Timed memtier byte accounting supports successful-looking response sizes, not byte-for-byte verification of every timed value. Calibration readback is a separate bounded correctness check.",
              "Host exclusivity and stationarity require separate evidence; no release readiness, second-machine qualification, long soak or 72-hour claim is generated.",
              "v3 repairs only the seven instrument defects (a)-(g); M1/M2/M3 thresholds, window, validity, scenario and workload definitions are unchanged."]
    lines += ["", "## Observed Workload", "", "Per-row actual counts, hit ratios, response bytes and measured server deltas are in summary.json validation[].observedWorkload/statsDelta. These are not assumed equal absolute counts across servers."]
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True, help="new analysis directory, never an existing raw directory")
    args = parser.parse_args(argv)
    root, out = args.input.resolve(), args.output.resolve()
    if out == root or root in out.parents or out in root.parents or out.exists():
        parser.error("output must be a new separate directory; raw evidence is immutable")
    if not out.parent.is_dir():
        parser.error("output parent must exist")
    passport, rows, errors = read_attempt(root)
    scenarios = [build_scenario(rows, name, passport, errors, qualification=passport.get("qualification", False)) for name in c.SCENARIOS]
    out.mkdir()
    (out / "summary.json").write_text(json.dumps(scenarios, indent=2, allow_nan=False), encoding="utf-8")
    (out / "report.md").write_text(render(scenarios, passport, errors), encoding="utf-8")
    (out / "analysis-identity.json").write_text(json.dumps({"v3CoreVersion": c.V3_CORE_VERSION, "input": str(root), "runsSha256": sha256(root / "runs.json"), "passportSha256": sha256(root / "passport.json"), "analyzerSha256": sha256(__file__), "contractsSha256": sha256(c.__file__)}, indent=2), encoding="utf-8")
    # Correction F5.3: an attempt that is wholly NOT_RUN returns the NOT_RUN status.
    if scenarios and all(s["verdict"] == "NOT_RUN" for s in scenarios):
        return EXIT_NOT_RUN
    return 0 if all(s["verdict"] == "PASS" for s in scenarios) else 1


if __name__ == "__main__":
    raise SystemExit(main())
