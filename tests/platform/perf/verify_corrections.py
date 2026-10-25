"""Bounded self-verification for the TEST-05 correction cycle.

Developer/harness evidence, not product acceptance and not independent acceptance.
It proves, offline and without any remote or stand run, that:

1. C1 - a *qualification* row's M3 (total-RSS) metric is no longer forced
   INCONCLUSIVE by the M1/M2 throughput-convergence reason `workload_not_converged`,
   while a non-qualification synthetic row keeps the historical metric-agnostic
   analysis-only reporting. Removing the reason can expose a valid FAIL or PASS.
2. C3 - `contracts.corrected_generator_cpu` aggregates every consecutive per-thread
   delta (including threads that appear or retire) instead of only the first/last
   thread sets, returns None when no tick evidence exists so a caller cannot
   manufacture a zero, and over-budget CPU is an observation, not invalidity.

Usage (from repository root, Windows or Linux):
    python -B tests/platform/perf/verify_corrections.py --output <new-directory>
"""
import argparse
import copy
import datetime
import hashlib
import json
import pathlib
import sys
import tempfile
import subprocess
import uuid

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(REPO / "tests" / "harness_tests"))

import contracts as c  # noqa: E402
import finalize as f  # noqa: E402
import fixtures  # noqa: E402  (read-only independent fixtures)


class Report:
    def __init__(self):
        self.cases = []

    def add(self, name, kind, passed, detail):
        self.cases.append({"name": name, "kind": kind, "passed": bool(passed),
                           "detail": str(detail)})

    def check(self, name, kind, actual, expected):
        self.add(name, kind, actual == expected, f"actual={actual!r} expected={expected!r}")


def drifted_native_rows(rate=100):
    """Independent fixtures with a real throughput ramp inside the fixed window."""
    data = fixtures.rows(rate=rate)
    for item in data:
        buckets = item["native"]["ALL STATS"]["Gets"]["Time-Serie"]
        for i in range(12):
            count = 50 if i < 6 else 150
            buckets[str(i)] = {"Count": count, "Bytes RX": count * 286}
    return data


def run_corrected_aggregation(report):
    # Thread b retires after sample 0; thread c appears in sample 2. Only a
    # consecutive per-thread delta accumulates all three correctly.
    samples = [
        {"previousClientCpuTicks": {"a": 0, "b": 0}, "clientCpuTicks": {"a": 10, "b": 10},
         "intervalSeconds": 1.0, "clockTicksPerSecond": 100},
        {"previousClientCpuTicks": {"a": 10, "b": 10}, "clientCpuTicks": {"a": 20},
         "intervalSeconds": 1.0, "clockTicksPerSecond": 100},
        {"previousClientCpuTicks": {"a": 20, "c": 0}, "clientCpuTicks": {"a": 30, "c": 5},
         "intervalSeconds": 1.0, "clockTicksPerSecond": 100},
    ]
    for i, sample in enumerate(samples):
        sample.update(wall=101.+i, monotonic=101.+i)
    summary = c.corrected_generator_cpu(samples, 1, (100., 103.))
    # a: 10+10+10=30, b: 10+0=10, c: 5 -> 45 ticks = 0.45 s over 3 s.
    report.check("corrected_generator_cpu.retiring_and_appearing_threads", "unit",
                 (round(summary["processCpuSeconds"], 6), round(summary["processCpuFraction"], 6)),
                 (0.45, 0.15))

    # Cumulative-only samples (no explicit previous ticks) are chained.
    cumulative = [{"clientCpuTicks": {"a": 0}, "intervalSeconds": 1.0, "clockTicksPerSecond": 100},
                  {"clientCpuTicks": {"a": 10}, "intervalSeconds": 1.0, "clockTicksPerSecond": 100},
                  {"clientCpuTicks": {"a": 10}, "intervalSeconds": 1.0, "clockTicksPerSecond": 100},
                  {"clientCpuTicks": {"a": 25}, "intervalSeconds": 1.0, "clockTicksPerSecond": 100}]
    cumulative[0]["previousClientCpuTicks"] = {"a": 0}
    for i, sample in enumerate(cumulative):
        sample.update(wall=101.+i, monotonic=101.+i)
    chained = c.corrected_generator_cpu(cumulative, 4, (100., 104.))
    report.check("corrected_generator_cpu.chained_cumulative_samples", "unit",
                 (round(chained["processCpuSeconds"], 6), round(chained["processCpuFraction"], 6)),
                 (0.25, 0.0625))

    report.add("corrected_generator_cpu.empty_is_none", "negative-fixture",
               c.corrected_generator_cpu([], 1) is None,
               "no samples must yield None, never a fabricated zero")
    report.add("corrected_generator_cpu.no_ticks_is_none", "negative-fixture",
               c.corrected_generator_cpu([{"monotonic": 1.0}], 1) is None,
               "samples without per-thread ticks must yield None")


def run_m3_decoupling(report):
    qualification = drifted_native_rows()
    qualification[0]["performanceQualification"] = True
    result = c.validate_row(qualification[0], fixtures.P01, fixtures.IDENTITY, fixtures.WINDOW, qualification=True)
    report.add("m3.qualification_row_keeps_throughput_reason_on_m1_m2", "unit",
               "workload_not_converged" in result["metricReasons"]["M1"]
               and "workload_not_converged" in result["metricReasons"]["M2"],
               f"M1={result['metricReasons']['M1']} M2={result['metricReasons']['M2']}")
    report.add("m3.qualification_row_drops_throughput_reason_from_m3", "unit",
               "workload_not_converged" not in result["metricReasons"]["M3"],
               f"M3={result['metricReasons']['M3']}")

    legacy = drifted_native_rows()
    legacy[0].pop("performanceQualification", None)
    legacy_result = c.validate_row(legacy[0], fixtures.P01, fixtures.IDENTITY, fixtures.WINDOW)
    report.add("m3.non_qualification_fixture_retains_legacy_all_metrics", "unit",
               all("workload_not_converged" in legacy_result["metricReasons"][m] for m in c.THRESHOLDS),
               f"metricReasons={legacy_result['metricReasons']}")

    # End-to-end: a qualification campaign where throughput ramps and octomem RSS
    # is 3x the reference must report M1/M2 INCONCLUSIVE but a valid M3 FAIL.
    campaign = [fixtures.row(rep, server, 100, 3000000 if server == "octomem" else 1000000)
                for rep in range(6) for server in ("reference", "octomem")]
    for item in campaign:
        buckets = item["native"]["ALL STATS"]["Gets"]["Time-Serie"]
        for i in range(12):
            count = 50 if i < 6 else 150
            buckets[str(i)] = {"Count": count, "Bytes RX": count * 286}
        item["performanceQualification"] = True
        item["generatorCpu"] = {"processCpuFraction": 1.0, "clientCpuCount": 12,
                                "maxThreadCpuSeconds": 1.0}
    built = f.build_scenario(campaign, "P01-c1", fixtures.passport(), qualification=True)
    report.add("m3.end_to_end_throughput_ramp_does_not_mask_rss_fail", "unit",
               built["metrics"]["M3"]["verdict"] == "FAIL"
               and built["metrics"]["M3"]["medianPairedRatio"] == 3.0
               and built["metrics"]["M1"]["verdict"] == "INCONCLUSIVE"
               and built["metrics"]["M2"]["verdict"] == "INCONCLUSIVE",
               f"verdicts={ {k: v['verdict'] for k, v in built['metrics'].items()} }")


def run_generator_gate(report):
    over = {"processCpuFraction": 0.9, "clientCpuCount": 1, "maxThreadCpuSeconds": 0.9}
    within = {"processCpuFraction": 0.5, "clientCpuCount": 1, "maxThreadCpuSeconds": 0.5}
    report.add("generator_gate.corrected_over_budget_fires", "negative-fixture",
               c.generator_evidence_reasons({"generatorCpu": over}, {"kind": "memtier"}, True)
               == ([], ["generator_cpu_over_budget_observation_not_causal_proof"]),
               "0.90 > 0.85 budget must warn without invalidity")
    report.add("generator_gate.within_budget_accepts", "positive-control",
               c.generator_evidence_reasons(
                   {"generatorCpu": within}, {"kind": "memtier"}, True) == ([], []),
               "0.50 <= 0.85 budget must not fire")


def run_fail_closed(report):
    def campaign(cpu=None):
        rows = fixtures.rows()
        for row in rows:
            row["performanceQualification"] = True
            if cpu is not None:
                row["generatorCpu"] = copy.deepcopy(cpu)
        return rows

    def rejected(label, rows, reason):
        scenario = f.build_scenario(rows, "P01-c1", fixtures.passport(), qualification=True)
        if reason == "qualification_claim_mismatch":
            passed = all(m["verdict"] == "INCONCLUSIVE" and any(reason in r for r in m["reasons"])
                         for m in scenario["metrics"].values())
        else:
            passed = scenario["metrics"]["M3"]["verdict"] == "PASS" and all(
                reason in v["warnings"] or reason in v["metricReasons"]["M1"]
                for v in scenario["validation"])
            for row, validation in zip(rows, scenario["validation"]):
                cpu = row.get("generatorCpu")
                usable = isinstance(cpu, dict) and cpu.get("status") != "invalid" and type(cpu.get("processCpuFraction")) in (int, float) and c.math.isfinite(cpu["processCpuFraction"]) and cpu["processCpuFraction"] >= 0
                passed &= ("generator_cpu_evidence_missing" in validation["metricReasons"]["M1"]) == (not usable)
                passed &= not any("cpu" in r for r in validation["reasons"] + validation["metricReasons"]["M3"])
        report.add(label, "negative-fixture", scenario["qualification"] is True and passed, str(scenario))

    for label, value in (("nan", float('nan')), ("infinity", float('inf')),
                         ("negative_infinity", -float('inf')), ("negative", -1),
                         ("boolean", False), ("string", "0.1")):
        rejected("INVALID_CPU." + label, campaign({"clientCpuCount": 1, "processCpuFraction": value}),
                 "generator_cpu_fraction_invalid")
    rejected("MISSING_CPU", campaign(), "generator_cpu_evidence_missing")
    for claim in (False, None):
        rows = campaign()
        for row in rows:
            if claim is None:
                row.pop("performanceQualification")
            else:
                row["performanceQualification"] = claim
        rejected("MISSING_CPU_OPT_OUT." + str(claim), rows, "qualification_claim_mismatch")
    baseline = [{"wall": 101.+i, "monotonic": 101.+i, "intervalSeconds": 1.,
                 "clockTicksPerSecond": 100, "previousClientCpuTicks": {"a": i*10},
                 "clientCpuTicks": {"a": (i+1)*10}} for i in range(3)]
    mutations = {
        "empty_previous": (lambda s: s[0].update(previousClientCpuTicks={}), "baseline_invalid"),
        "invalid_previous": (lambda s: s[0].update(previousClientCpuTicks={"a": "bad"}), "baseline_invalid"),
        "REGRESSED_COUNTERS_END_TO_END": (lambda s: s[0].update(previousClientCpuTicks={"a": 200}), "counter_regressed"),
        "missing_baseline": (lambda s: s[0].pop("previousClientCpuTicks"), "baseline_invalid"),
        "missing_middle": (lambda s: s.pop(1), "coverage_gap"),
        "changing_clock": (lambda s: s[1].update(clockTicksPerSecond=10000), "clock_changed"),
        "OUTSIDE_WINDOW_DILUTION": (lambda s: s[0].update(intervalSeconds=10000.), "interval_outside_window"),
        "INFINITE_TICKS_CRASH": (lambda s: s[0].update(clientCpuTicks={"a": float('inf')}), "ticks_invalid"),
        "empty_current": (lambda s: s[0].update(clientCpuTicks={}), "ticks_invalid"),
        "unbased_thread": (lambda s: s[0].update(clientCpuTicks={"b": 10}), "baseline_missing"),
        "clock_missing": (lambda s: s[0].pop("clockTicksPerSecond"), "clock_invalid"),
    }
    for label, (mutate, reason) in mutations.items():
        samples = copy.deepcopy(baseline)
        mutate(samples)
        cpu = c.corrected_generator_cpu(samples, 1, (100., 103.))
        rejected(label, campaign(cpu), "generator_cpu_" + reason)
    for value in (float('nan'), float('inf'), -1, False, '1', 0):
        samples = copy.deepcopy(baseline)
        samples[0]["intervalSeconds"] = value
        rejected("invalid_interval." + str(value), campaign(c.corrected_generator_cpu(samples, 1, (100., 103.))),
                 "generator_cpu_interval_invalid")
    extended = copy.deepcopy(baseline)
    extended.append({"wall": 10000., "clientCpuTicks": {}, "intervalSeconds": 10000.})
    extended[-1]["monotonic"] = 10000.
    extended[-1]["intervalSeconds"] = 1.
    outside = c.corrected_generator_cpu(extended, 1, (100., 103.))
    report.check("outside_lifecycle_samples_do_not_dilute", "positive-control",
                 outside["processCpuFraction"], c.corrected_generator_cpu(baseline, 1, (100., 103.))["processCpuFraction"])
    report.add("outside_lifecycle_explicit_defect", "negative-fixture", "generator_cpu_interval_wholly_outside_window" in outside["reasons"], str(outside))
    scenario = f.build_scenario(fixtures.rows(), "P01-c1", fixtures.passport())
    text = f.render([scenario], fixtures.passport(), [])
    report.add("analysis_only_explicit", "positive-control", scenario["qualification"] is False and "analysis-only" in text, text)
    report.add("interior_versus_full_window_disclosure", "contract", "interior-bucket convergence is not" in text
               and "full-window native aggregate p99" in text and "full-window native Totals Ops/sec" in text, text)


def run_reader_controls(report):
    from test_artifact_roundtrip import write_attempt, seal
    for mutation in ("valid", "missing", "regression", "infinite", "row_opt_out", "outside"):
        with tempfile.TemporaryDirectory() as directory:
            root, plan, rows = write_attempt(pathlib.Path(directory))
            plan.update(performanceQualification=True, clientCpuCount=12)
            for row in rows:
                native = json.loads((root / row["artifact"]["name"]).read_text())
                runtime = native["ALL STATS"]["Runtime"]
                start, end = runtime["Start time"]/1000, runtime["Finish time"]/1000
                stamps = [start+i for i in range(1, 13)] + [end]
                previous, tick = start, 0
                samples = []
                for stamp in stamps:
                    samples.append(dict(wall=stamp, monotonic=stamp, intervalSeconds=stamp-previous,
                                        clockTicksPerSecond=100, clientCpuTicks={"a": tick+1},
                                        previousClientCpuTicks={"a": tick}, serverCpuTicks={"s": tick+1},
                                        total_rss=1000000, connections=1))
                    previous, tick = stamp, tick+1
                row["samples"] = samples
                row["rssWindow"] = [s for s in samples if start+1 <= s["wall"] <= end-1]
                if mutation == "missing":
                    for sample in samples:
                        sample.pop("clientCpuTicks")
                        sample.pop("previousClientCpuTicks")
                elif mutation == "regression":
                    samples[0]["previousClientCpuTicks"] = {"a": 200}
                elif mutation == "infinite":
                    samples[0]["clientCpuTicks"] = {"a": float('inf')}
                elif mutation == "row_opt_out":
                    row["performanceQualification"] = False
                elif mutation == "outside":
                    samples[0]["intervalSeconds"] = 10000.
            (root / "passport.json").write_text(json.dumps(plan))
            (root / "runs.json").write_text(json.dumps(rows))
            seal(root, plan)
            loaded_plan, loaded, errors = f.read_attempt(root)
            built = f.build_scenario(loaded, "P01-c1", loaded_plan, qualification=loaded_plan["qualification"])
            reason = {"missing": "generator_cpu_evidence_missing", "regression": "generator_cpu_counter_regressed",
                      "infinite": "generator_cpu_ticks_invalid", "row_opt_out": "qualification_claim_mismatch",
                      "outside": "generator_cpu_interval_outside_window"}.get(mutation)
            if mutation == "valid":
                passed = built["qualification"] and built["verdict"] == "PASS"
            else:
                if mutation == "row_opt_out":
                    passed = built["qualification"] and all(m["verdict"] == "INCONCLUSIVE" and any(reason in r for r in m["reasons"]) for m in built["metrics"].values())
                elif mutation == "missing":
                    passed = built["metrics"]["M3"]["verdict"] == "PASS" and all(
                        built["metrics"][m]["verdict"] == "INCONCLUSIVE" and any(reason in r for r in built["metrics"][m]["reasons"]) for m in ("M1", "M2"))
                else:
                    passed = built["verdict"] == "PASS" and all(reason in v["warnings"] for v in built["validation"])
            report.add("reader." + mutation, "artifact-roundtrip", passed, str(built["metrics"]))


def run_observation_scope(report):
    base = [{"wall": 101.+i, "monotonic": 101.+i, "intervalSeconds": 1., "clockTicksPerSecond": 100,
             "previousClientCpuTicks": {"a": i*10}, "clientCpuTicks": {"a": (i+1)*10}} for i in range(3)]
    for label in ("new_thread", "first_boundary", "last_boundary", "no_usable", "clock_change", "wholly_outside", "infinite", "regression"):
        samples = copy.deepcopy(base)
        if label == "new_thread":
            samples[1]["clientCpuTicks"]["new"] = 50
        elif label == "first_boundary":
            samples[0]["intervalSeconds"] = 2.
        elif label == "last_boundary":
            samples[-1]["wall"] += .5
        elif label == "no_usable":
            for s in samples:
                s["previousClientCpuTicks"] = {}
        elif label == "clock_change":
            samples[1]["clockTicksPerSecond"] = 1000
        elif label == "wholly_outside":
            samples.append(dict(base[-1], wall=200., monotonic=200.))
        elif label == "infinite":
            samples[0]["clientCpuTicks"] = {"a": float('inf')}
        else:
            samples[0]["previousClientCpuTicks"] = {"a": 200}
        cpu = c.corrected_generator_cpu(samples, 1, (100., 103.))
        rows = fixtures.rows()
        for row in rows:
            row.update(performanceQualification=True, generatorCpu=cpu)
        built = f.build_scenario(rows, "P01-c1", fixtures.passport(), qualification=True)
        floor = label in ("no_usable", "clock_change")
        report.add("R-CPU-1." + label, "metric-scope",
                   built["metrics"]["M3"]["verdict"] == "PASS" and
                   all(built["metrics"][m]["verdict"] == ("INCONCLUSIVE" if floor else "PASS") for m in ("M1", "M2")) and
                   all(v["warnings"] and not v["reasons"] and not v["metricReasons"]["M3"] for v in built["validation"]), str(built))


def run_reader_followup(report, out):
    from test_artifact_roundtrip import write_attempt, seal
    for label, cpu, reason in (
            ("null_reasons", {"reasons": None, "processCpuFraction": .1, "clientCpuCount": 1}, "generator_cpu_reasons_invalid"),
            ("huge_fraction", {"processCpuFraction": 10**400, "clientCpuCount": 1}, "generator_cpu_fraction_invalid"),
            ("huge_count", {"processCpuFraction": .1, "clientCpuCount": 10**400}, "generator_cpu_allocation_invalid")):
        parent = out / label
        parent.mkdir()
        root, plan, rows = write_attempt(parent)
        for row in rows:
            row["generatorCpu"] = cpu
        (root / "runs.json").write_text(json.dumps(rows))
        seal(root, plan)
        command = [sys.executable, "-I", "-B", str(HERE / "finalize.py"), "--input", str(root), "--output", str(parent / "analysis")]
        execution = subprocess.run(command, capture_output=True, text=True)
        (parent / "command.json").write_text(json.dumps(dict(command=command, exitCode=execution.returncode,
                                                            stdout=execution.stdout, stderr=execution.stderr), indent=2))
        summary_path = parent / "analysis/summary.json"
        summary = json.loads(summary_path.read_text()) if summary_path.exists() else []
        scenario = next((s for s in summary if s['scenario'] == 'P01-c1'), {})
        report.add("reader.R1." + label, "subprocess-artifact", execution.returncode == 1 and
                   not execution.stderr and len(summary) == len(c.SCENARIOS) and
                   all(v["observedWorkload"]["generatorCpuObservation"]["status"] == "invalid" and reason in v["warnings"]
                       for v in scenario.get("validation", [])) and bool(scenario.get("validation")), str(parent))
    samples = [dict(wall=101.+i, monotonic=101.+i, intervalSeconds=1., clockTicksPerSecond=100,
                    previousClientCpuTicks={"a": 10*i}, clientCpuTicks={"a": 10*(i+1)}) for i in range(3)]
    samples.pop(1)
    samples[-1].pop("previousClientCpuTicks")
    corrected = c.corrected_generator_cpu(samples, 1, (100., 103.))
    report.add("R2.missing_middle_chained", "negative-fixture",
               corrected.get("processCpuSeconds") == .1 and corrected.get("windowSeconds") == 1. and
               corrected.get("processCpuFraction") == .1 and "generator_cpu_baseline_invalid" in corrected["reasons"], str(corrected))
    samples[0]["previousClientCpuTicks"] = {}
    corrected = c.corrected_generator_cpu(samples, 1, (100., 103.))
    report.add("R2.GAP_ONLY_BASELINE", "negative-fixture", corrected["status"] == "invalid" and
               "processCpuFraction" not in corrected and "generator_cpu_baseline_invalid" in corrected["reasons"], str(corrected))
    retirement = [dict(wall=101., monotonic=101., intervalSeconds=1., clockTicksPerSecond=100,
                       previousClientCpuTicks={"a": 0, "b": 0}, clientCpuTicks={"a": 10, "b": 10}),
                  dict(wall=102., monotonic=102., intervalSeconds=1., clockTicksPerSecond=100,
                       previousClientCpuTicks={"a": 10, "b": 10}, clientCpuTicks={"a": 20})]
    corrected = c.corrected_generator_cpu(retirement, 1, (100., 102.))
    floor, warnings = c.generator_evidence_reasons({"generatorCpu": corrected}, {"kind": "memtier"}, True)
    report.add("R4.retired_thread_partial", "negative-fixture", corrected["partial"] and not floor and
               "generator_cpu_thread_retired_unobserved" in warnings and corrected["processCpuFraction"] == .15, str(corrected))


def run_serialization_controls(report, out):
    from test_artifact_roundtrip import write_attempt, seal
    for label, extra in (
            ("overflow_json_seconds", {"processCpuSeconds": float('inf')}),
            ("overflow_json_nested", {"extra": {"arbitrary": [float('inf')]}}),
            ("nan_seconds_measured", {"processCpuSeconds": float('nan')}),
            ("infinite_max_thread", {"maxThreadCpuSeconds": float('inf')}),
            ("invalid_marker_nan", {"status": "invalid", "windowSeconds": float('nan')}),
            ("process_null", {})):
        parent = out / label
        parent.mkdir()
        root, plan, rows = write_attempt(parent)
        rows[0]["generatorCpu"] = dict(status="measured", processCpuFraction=.9, clientCpuCount=12)
        rows[0]["generatorCpu"].update(extra)
        if label == "process_null":
            rows[0]["process"] = None
        raw = json.dumps(rows)
        if label.startswith("overflow_json"):
            raw = raw.replace('Infinity', '1e400')
        (root / "runs.json").write_text(raw)
        seal(root, plan)
        command = [sys.executable, "-I", "-B", str(HERE / "finalize.py"), "--input", str(root), "--output", str(parent / "analysis")]
        result = subprocess.run(command, capture_output=True, text=True)
        (parent / "command.json").write_text(json.dumps(dict(command=command, exitCode=result.returncode,
                                                            stdout=result.stdout, stderr=result.stderr), indent=2))
        path = parent / "analysis/summary.json"
        summary = json.loads(path.read_text()) if path.exists() else []
        scenario = next((s for s in summary if s['scenario'] == 'P01-c1'), {})
        validation = scenario.get('validation', [{}])[0]
        if label == 'process_null':
            classified = 'row_schema_invalid:process' in validation.get('reasons', [])
        else:
            classified = validation.get('observedWorkload', {}).get('generatorCpuObservation', {}).get('status') == 'invalid' and bool(validation.get('warnings'))
        report.add('serialization.' + label, 'subprocess-artifact', result.returncode == 1 and not result.stderr and
                   len(summary) == len(c.SCENARIOS) and scenario.get('rowCount') == 12 and classified, str(parent))
    nested = {'values': [float('nan'), float('inf'), 10**400, object()]}
    for _ in range(100):
        nested = {'level': nested}
    scenario = f.build_scenario(fixtures.rows(), 'P01-c1', fixtures.passport())
    scenario['validation'][0]['observedWorkload']['generatorCpuBypass'] = nested
    scenario['validation'][0]['observedWorkload']['generatorCpuOther'] = [float('inf'), 10**400, object()]
    safe = f.safe_scenarios([scenario])
    encoded = json.dumps(safe, allow_nan=False)
    report.add('serialization.bound_bypass', 'defense-in-depth', bool(safe[0].get('serializationReasons')) and
               safe[0]['metrics']['M3']['verdict'] == 'PASS' and 'null' in encoded, str(safe[0]['serializationReasons']))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=REPO / "tests/reports/release/perf-qual/correction-cycle" / ("self-verification-" + uuid.uuid4().hex), help="new directory for this run")
    args = parser.parse_args(argv)
    out = args.output.resolve()
    if out.exists():
        parser.error("output must be a new directory")
    if not out.parent.is_dir():
        parser.error("output parent must exist")
    out.mkdir()
    report = Report()
    run_corrected_aggregation(report)
    run_m3_decoupling(report)
    run_generator_gate(report)
    run_fail_closed(report)
    run_reader_controls(report)
    run_observation_scope(report)
    run_reader_followup(report, out)
    run_serialization_controls(report, out)
    passed = all(case["passed"] for case in report.cases)
    result = {
        "schemaVersion": 1,
        "kind": "correction-self-verification",
        "notAcceptance": True,
        "runId": "corrections-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8],
        "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "python": sys.version,
        "platform": sys.platform,
        "contractsSha256": hashlib.sha256(pathlib.Path(c.__file__).read_bytes()).hexdigest(),
        "finalizeSha256": hashlib.sha256(pathlib.Path(f.__file__).read_bytes()).hexdigest(),
        "cases": report.cases,
        "passed": passed,
        "failedCases": [case["name"] for case in report.cases if not case["passed"]],
        "exitCode": 0 if passed else 1,
    }
    (out / "corrections-self-verification.json").write_text(
        json.dumps(result, indent=2, default=str), encoding="utf-8")
    print(str(out))
    print(f"cases={len(report.cases)} failed={len(result['failedCases'])} exit={result['exitCode']}")
    return result["exitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
