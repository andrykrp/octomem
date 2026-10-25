"""Pure TEST-05 evidence contracts. No I/O, process creation, or import side effects."""
import math
import statistics
from collections import Counter

THRESHOLDS = {"M1": .90, "M2": 1.25, "M3": 1.20}
GENERATOR_CPU_BUDGET = .85
GENERATOR_HEADROOM_MIN = 2.0
SCENARIOS = {
    "P01-c1": (4096, 256, 1, "0:1", 1, 256),
    "P01-c8": (4096, 256, 8, "0:1", 1, 256),
    "P01-c32": (4096, 256, 32, "0:1", 1, 256),
    "P02": (4096, 256, 1, "1:0", 1, 256),
    "P03": (4096, 256, 1, "1:0", 100, 256),
    "P04": (4096, 256, 32, "1:9", 1, 256),
    "P05-1KiB": (4096, 1024, 32, "1:9", 1, 256),
    "P05-64KiB": (128, 65536, 32, "1:9", 1, 256),
    "P05-near": (32, 1048000, 8, "1:9", 1, 256),
    "P06": (32768, 1024, 32, "1:0", 1, 16),
    "P08": (4096, 256, 10000, "1:9", 1, 256),
    "P07": (0, 0, 1, "0:0", 1, 256),
    **{f"M3-{n}": (n, 1024, 0, "0:0", 1, 256) for n in (1024, 8192, 32768)},
}
IDENTITY_KEYS = ("attemptId", "jarSha256", "referenceSha256", "memtierSha256",
                 "harnessSha256", "javaSha256", "javaVersion")
WINDOW_CONFIG = {"minSamples": 6, "minSpanSeconds": 3., "maxGapSeconds": 1.5,
                 "maxRelativeDrift": .05, "maxRelativeRange": .15,
                 "trimSeconds": 1., "ratioTolerance": .03, "measurementSeconds": 12}


def positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def discrete(value):
    """JSON discrete evidence is an integer, never a bool or integral float."""
    return type(value) is int and value >= 0


def median_interval(values, confidence=.95):
    """Exact distribution-free order-statistic CI under independent continuous draws.

    For rank k (one-based), coverage = 1 - 2 P[Bin(n,.5) <= k-1].
    Choose the narrowest symmetric interval with coverage >= confidence.
    This does not establish independence, stationarity, or an effect's cause.
    """
    if not 0 < confidence < 1 or not values or not all(positive(x) for x in values):
        raise ValueError("positive finite ratios and confidence in (0,1) required")
    ordered = sorted(values)
    n = len(ordered)
    candidates = [(k, 1 - 2 * sum(math.comb(n, j) for j in range(k)) / 2**n)
                  for k in range(1, (n + 1)//2 + 1)]
    supported = [(k, coverage) for k, coverage in candidates if coverage >= confidence]
    k, coverage = supported[-1] if supported else candidates[0]
    return {"method": "exact-binomial-median" if supported else "exploratory-range",
            "bounds": [ordered[k-1], ordered[n-k]], "coverage": coverage,
            "requestedConfidence": confidence, "sufficientPrecision": bool(supported),
            "rank": k, "n": n, "assumptions": "independent continuous stationary paired ratios"}


def classify(metric, values, reasons=(), expected_pairs=None):
    """Validity precedes threshold comparisons, including adverse medians.

    A valid median breach remains FAIL; limited precision is reported separately.
    PASS requires the exact supported interval to meet the unchanged threshold.
    CPU busy warnings are not reasons unless a distinct validity defect is proven.
    """
    threshold = THRESHOLDS[metric]
    issues = list(reasons)
    if expected_pairs is None or type(expected_pairs) is not int or expected_pairs < 2:
        issues.append("pair_plan_missing_or_invalid")
    elif len(values) != expected_pairs:
        issues.append("pair_count_mismatch")
    if not values or not all(positive(x) for x in values):
        issues.append("ratios_missing_or_invalid")
    result = {"verdict": "INCONCLUSIVE", "threshold": threshold,
              "reasons": sorted(set(issues)), "ratios": list(values)}
    if issues:
        return result
    median = statistics.median(values)
    interval = median_interval(values)
    result.update(medianPairedRatio=median, interval=interval)
    fails = median < threshold if metric == "M1" else median > threshold
    meets = interval["bounds"][0] >= threshold if metric == "M1" else interval["bounds"][1] <= threshold
    if fails:
        result.update(verdict="FAIL", reasons=["valid_median_threshold_breach"])
    elif not interval["sufficientPrecision"]:
        result["reasons"] = ["insufficient_precision"]
    elif not meets:
        result["reasons"] = ["interval_crosses_threshold"]
    else:
        result["verdict"] = "PASS"
    return result


def validate_pairs(rows, scenario, repetitions):
    """Retain all rows; explicitly reject missing, duplicate and unexpected pairs."""
    group = [r for r in rows if r.get("scenario") == scenario]
    reasons = []
    if type(repetitions) is not int or repetitions < 2:
        return {"valid": False, "reasons": ["pair_plan_missing_or_invalid"], "rows": group}
    malformed = [r for r in group if type(r.get("rep")) is not int or not isinstance(r.get("server"), str)]
    if malformed:
        reasons.append("pair_identity_invalid_type")
    counts = Counter((r.get("rep"), r.get("server")) for r in group
                     if type(r.get("rep")) is int and isinstance(r.get("server"), str))
    expected = {(rep, server) for rep in range(repetitions) for server in ("reference", "octomem")}
    for rep, server in sorted(expected):
        count = counts[(rep, server)]
        if count != 1:
            reasons.append(f"{'missing' if count == 0 else 'duplicate'}_pair:{rep}:{server}")
    for key in counts.keys() - expected:
        reasons.append(f"unexpected_pair:{key[0]}:{key[1]}")
    return {"valid": not reasons, "reasons": sorted(reasons), "rows": group}


def qualify_window(samples, config):
    """Evaluate a preselected window, never search for a favorable subwindow."""
    reasons = []
    if not isinstance(config, dict) or any(k not in config for k in WINDOW_CONFIG):
        return {"valid": False, "reasons": ["window_config_missing"]}
    if any(not positive(config[k]) for k in WINDOW_CONFIG) or type(config["minSamples"]) is not int or config["minSamples"] < 2 or not discrete(config["measurementSeconds"]):
        return {"valid": False, "reasons": ["window_config_invalid"]}
    if len(samples) < config["minSamples"]:
        reasons.append("window_too_few_samples")
    if not samples or any(not discrete(s.get("total_rss")) or not s["total_rss"] or
                          not isinstance(s.get("monotonic"), (int, float)) or
                          isinstance(s.get("monotonic"), bool) or
                          not math.isfinite(s["monotonic"]) for s in samples):
        return {"valid": False, "reasons": sorted(reasons + ["window_samples_invalid"])}
    stamps = [s["monotonic"] for s in samples]
    gaps = [b-a for a, b in zip(stamps, stamps[1:])]
    if any(g <= 0 or g > config["maxGapSeconds"] for g in gaps):
        reasons.append("window_timing_invalid")
    if stamps[-1]-stamps[0] < config["minSpanSeconds"]:
        reasons.append("window_too_short")
    values = [s["total_rss"] for s in samples]
    mid = max(1, len(values)//2)
    median = statistics.median(values)
    drift = abs(statistics.mean(values[:mid])-statistics.mean(values[mid:] or values))/median
    spread = (max(values)-min(values))/median
    if drift > config["maxRelativeDrift"] or spread > config["maxRelativeRange"]:
        reasons.append("rss_not_converged")
    return {"valid": not reasons, "reasons": sorted(reasons), "median": median,
            "mean": statistics.mean(values), "sampledPeak": max(values), "relativeDrift": drift,
            "relativeRange": spread, "start": stamps[0], "end": stamps[-1]}


def _counter(value):
    if type(value) is int and value >= 0:
        return value
    if isinstance(value, str) and value and all('0' <= char <= '9' for char in value):
        return int(value)
    raise ValueError('counter must be a nonnegative decimal integer')


def stats_delta(before, after):
    result, reasons = {}, []
    for key in ("cmd_get", "cmd_set", "get_hits", "get_misses", "evictions", "total_items"):
        try:
            delta = _counter(after[key])-_counter(before[key])
            if delta < 0:
                raise ValueError()
            result[key] = delta
        except (KeyError, TypeError, ValueError):
            reasons.append("stats_delta_invalid:" + key)
    return {"values": result, "reasons": reasons}


def generator_saturation_reasons(row, expected):
    """Validity gate: a performance-qualification row must show the generator is
    not the bottleneck.

    Backward compatibility: independent/synthetic fixtures that do not claim
    performance qualification and carry no generator evidence are exempt, so the
    gate does not silently invalidate non-qualification contracts. The qualification
    path stays fail-closed: a row that claims qualification (`performanceQualification
    is True`, set by the collector) and is missing generator CPU evidence is rejected,
    and any row that *does* carry generator evidence is validated even without the
    claim. Required evidence is the generator's own CPU over the measurement window
    against the CPU actually allocated to the client cpuset. A busy single thread is
    not automatically fatal, but a generator using more than `GENERATOR_CPU_BUDGET` of
    its allocation cannot be shown not to be limiting the comparison, so the
    observation is INCONCLUSIVE rather than silently reported as a product ratio.
    When a measured capacity headroom block is present it is additionally required to
    be at least `GENERATOR_HEADROOM_MIN`.
    """
    if expected.get("kind") not in ("memtier", "cycles"):
        return []
    claims = row.get("performanceQualification") is True
    cpu = row.get("generatorCpu")
    if not isinstance(cpu, dict) or cpu.get("processCpuFraction") is None:
        return ["generator_cpu_evidence_missing"] if claims else []
    reasons = []
    count = cpu.get("clientCpuCount")
    if type(count) is not int or count < 1:
        reasons.append("generator_cpu_allocation_missing")
    elif cpu["processCpuFraction"] > GENERATOR_CPU_BUDGET * count:
        reasons.append("generator_cpu_over_budget")
    headroom = row.get("generatorHeadroom")
    if isinstance(headroom, dict):
        ceiling = headroom.get("ceilingOpsPerSecond")
        achieved = headroom.get("achievedOpsPerSecond")
        if not positive(ceiling) or not positive(achieved) or ceiling / achieved < GENERATOR_HEADROOM_MIN:
            reasons.append("generator_headroom_below_minimum")
    return reasons


def _validate_row(row, expected, identity, window_config):
    """Return common and per-metric reasons; callers must combine both.

    expected is the exact workload dict recorded in the pre-run plan. Native
    memtier JSON is embedded by the reader only after digest verification.
    """
    reasons = list(row.get("evidenceErrors", []))
    metric_reasons = {m: [] for m in THRESHOLDS}
    warnings = []
    observed = {}
    if "error" in row:
        reasons.append("run_error")
    if row.get("exitCode") != 0:
        reasons.append("client_exit_nonzero_or_missing")
    if row.get("timedOut") is not False:
        reasons.append("client_timeout_or_missing")
    if not discrete(row.get("exitCode")):
        reasons.append("client_exit_invalid_type")
    for key in IDENTITY_KEYS:
        if not identity.get(key) or row.get("identity", {}).get(key) != identity[key]:
            reasons.append("identity_mismatch:" + key)
    if row.get("workload") != expected:
        reasons.append("workload_mismatch")
    for key, value in expected.items():
        if type(value) is int and not discrete(row.get("workload", {}).get(key)):
            reasons.append("workload_integer_invalid:" + key)
    preload = row.get("preload", {})
    if not isinstance(preload, dict) or preload.get("stored") != expected["count"] or preload.get("requested") != expected["count"] or preload.get("response") != "STORED":
        reasons.append("preload_incomplete")
    for key in ("stored", "requested"):
        if not discrete(preload.get(key)):
            reasons.append("preload_integer_invalid:" + key)
    if row.get("process", {}).get("argv") != row.get("expectedServerArgv") or not row.get("expectedServerArgv"):
        reasons.append("server_command_mismatch")
    if row.get("process", {}).get("version") != identity.get(row.get("server", "") + "Version"):
        reasons.append("server_version_mismatch")
    if not identity.get(row.get("server", "") + "Version"):
        reasons.append("server_version_missing")
    for key in ("statsBefore", "statsAfter"):
        for field, wanted in (("limit_maxbytes", expected["memoryMiB"]*2**20), ("curr_items", expected["count"])):
            try:
                value = _counter(row[key][field])
                if value != wanted and (field == "limit_maxbytes" or expected["residency"] == "resident"):
                    reasons.append(("memory_budget_mismatch:" if field == "limit_maxbytes" else "residency_mismatch:") + key)
            except (KeyError, TypeError, ValueError):
                reasons.append("stats_integer_invalid:" + key + ":" + field)
    delta = stats_delta(row.get("statsBefore", {}), row.get("statsAfter", {}))
    reasons.extend(delta["reasons"])
    window = qualify_window(row.get("rssWindow", []), window_config)
    metric_reasons["M3"].extend(window["reasons"])
    if window.get("valid") and row.get("total_rss") != window["median"]:
        metric_reasons["M3"].append("rss_summary_mismatch")
    if row.get("forcedGcDuringMeasurement") is not False:
        reasons.append("forced_gc_or_unknown")
    if row.get("windowConfig") != window_config:
        reasons.append("window_plan_mismatch")
    for key in ("minSamples", "measurementSeconds"):
        if not discrete(window_config.get(key)) or not discrete(row.get("windowConfig", {}).get(key)):
            reasons.append("window_plan_integer_invalid:" + key)
    if not row.get("cgroupBefore") or not row.get("cgroupAfter"):
        reasons.append("cgroup_evidence_missing")
    else:
        for key in ("cpu.max", "cpuset.cpus.effective", "memory.max", "memory.swap.max"):
            if not row["cgroupBefore"].get(key) or row["cgroupBefore"].get(key) != row["cgroupAfter"].get(key):
                reasons.append("cgroup_limit_missing_or_changed:" + key)
        for side in ("cgroupBefore", "cgroupAfter"):
            if not row[side].get("cpu.stat") or not row[side].get("memory.events"):
                reasons.append("cgroup_counters_missing:" + side)
        for counter_file, keys in (("cpu.stat", ("nr_throttled", "throttled_usec")), ("memory.events", ("oom", "oom_kill", "max"))):
            try:
                before = dict(line.split() for line in row["cgroupBefore"][counter_file].splitlines())
                after = dict(line.split() for line in row["cgroupAfter"][counter_file].splitlines())
                for key in keys:
                    difference = _counter(after[key])-_counter(before[key])
                    if difference < 0:
                        reasons.append("cgroup_counter_regressed:" + key)
                    elif difference:
                        if counter_file == "cpu.stat":
                            for metric in ("M1", "M2"):
                                metric_reasons[metric].append("cgroup_throttling_during_window:" + key)
                        else:
                            reasons.append("cgroup_memory_event_during_window:" + key)
            except (KeyError, TypeError, ValueError, AttributeError):
                reasons.append("cgroup_counter_invalid:" + counter_file)
    if row.get("peakClientThreadCpu", 0) >= .85:
        warnings.append("client_thread_busy_ge_0.85_not_causal_proof")
    kind = expected["kind"]
    reasons.extend(generator_saturation_reasons(row, expected))
    if kind == "memtier":
        if row.get("argv") != row.get("expectedClientArgv") or not row.get("expectedClientArgv"):
            reasons.append("client_command_mismatch")
        native = row.get("native", {})
        conf = native.get("configuration", {})
        checks = {"protocol": "memcache_text", "key_minimum": 1, "key_maximum": expected["count"],
                  "key_prefix": "memtier-", "key_pattern": "R:R", "data_size": expected["size"],
                  "ratio": expected["ratio"], "pipeline": expected["pipeline"], "expiry_range": "0:0",
                  "test_time": window_config.get("measurementSeconds"), "threads": expected["threads"],
                  "clients": expected["connections"]//expected["threads"]}
        for key, value in checks.items():
            if type(value) is int and not discrete(conf.get(key)):
                reasons.append("native_integer_invalid:configuration:" + key)
            if conf.get(key) != value:
                reasons.append("native_workload_mismatch:" + key)
        for key in ("port", "run_count", "debug", "requests", "rate_limit", "data_offset", "reconnect_interval", "multi_key_get", "select-db"):
            if key in conf and not discrete(conf[key]):
                reasons.append("native_integer_invalid:configuration:" + key)
        try:
            allstats = native["ALL STATS"]
            runtime = allstats["Runtime"]
            for key in ("Start time", "Finish time", "Total duration"):
                if key in runtime and not discrete(runtime[key]):
                    reasons.append("native_integer_invalid:Runtime:" + key)
            start, end = runtime["Start time"]/1000, runtime["Finish time"]/1000
            if runtime["Time unit"] != "MILLISECONDS" or not row["wallStart"] <= start < end <= row["wallEnd"]:
                reasons.append("native_time_outside_attempt")
            if abs((end-start)-window_config["measurementSeconds"]) > 1:
                reasons.append("native_duration_mismatch")
                raise ValueError()
            sets, gets = allstats["Sets"], allstats["Gets"]
            ns, ng = sets["Count"], gets["Count"]
            invalid_series = False
            for operation in ("Sets", "Gets", "Waits", "Totals"):
                if operation not in allstats:
                    continue
                summary = allstats[operation]
                if (operation in ("Sets", "Gets") or "Count" in summary) and not discrete(summary.get("Count")):
                    reasons.append("native_integer_invalid:" + operation + ":Count")
                    invalid_series = True
                for field in ("Bytes RX", "Bytes TX"):
                    if field in summary and not discrete(summary[field]):
                        reasons.append("native_integer_invalid:" + operation + ":" + field)
                        invalid_series = True
                for bucket, values in summary.get("Time-Serie", {}).items():
                    if not isinstance(bucket, str) or not bucket.isascii() or not bucket.isdecimal() or str(int(bucket)) != bucket:
                        reasons.append("native_bucket_index_invalid:" + operation)
                        invalid_series = True
                    for field in ("Count", "Bytes RX", "Bytes TX"):
                        if field == "Bytes TX" and field not in values:
                            continue
                        if not discrete(values.get(field)):
                            reasons.append("native_integer_invalid:" + operation + ":" + str(bucket) + ":" + field)
                            invalid_series = True
            if invalid_series or ns+ng <= 0:
                raise ValueError()
            if "Count" in allstats.get("Totals", {}) and allstats["Totals"]["Count"] != ns+ng:
                reasons.append("native_total_count_mismatch")
            want_set, want_get = map(int, expected["ratio"].split(":"))
            hit_count = delta["values"].get("get_hits", 0)
            miss_count = delta["values"].get("get_misses", 0)
            observed = {"setResponses": ns, "getResponses": ng, "setFraction": ns/(ns+ng),
                        "getHits": hit_count, "getMisses": miss_count,
                        "hitRatio": hit_count/(hit_count+miss_count) if hit_count+miss_count else None,
                        "setResponseBytes": sum(b["Bytes RX"] for b in sets["Time-Serie"].values()),
                        "getResponseBytes": sum(b["Bytes RX"] for b in gets["Time-Serie"].values()),
                        "successEvidence": "response byte accounting plus server counter reconciliation; not every-payload verification"}
            if abs(ns/(ns+ng)-want_set/(want_set+want_get)) > window_config["ratioTolerance"]:
                reasons.append("operation_ratio_mismatch")
            buckets = sets["Time-Serie"]
            if sum(b["Count"] for b in buckets.values()) != ns or sum(b["Bytes RX"] for b in buckets.values()) != 8*ns:
                reasons.append("set_response_accounting_mismatch")
            get_buckets = gets["Time-Serie"]
            if sum(b["Count"] for b in get_buckets.values()) != ng:
                reasons.append("get_response_accounting_mismatch")
            if expected["residency"] == "resident" and (gets["Misses/sec"] != 0 or delta["values"].get("get_misses") != 0):
                reasons.append("unexpected_get_misses")
            if ng:
                rx = sum(b["Bytes RX"] for b in get_buckets.values())
                minimum_hit = expected["size"]+len("memtier-1")+len(str(expected["size"]))+18
                maximum_hit = expected["size"]+len("memtier-"+str(expected["count"]))+len(str(expected["size"]))+18
                if not hit_count*minimum_hit+miss_count*5 <= rx <= hit_count*maximum_hit+miss_count*5:
                    reasons.append("get_payload_bytes_mismatch")
            if delta["values"].get("get_hits", 0)+delta["values"].get("get_misses", 0) != ng or delta["values"].get("cmd_get") != ng:
                reasons.append("get_counter_reconciliation_failed")
            # cmd_set is not a universal successful-store counter on this product.
            if delta["values"].get("cmd_set") != ns:
                reasons.append("set_admission_reconciliation_failed")
            totals = allstats["Totals"]
            if not positive(totals["Ops/sec"]) or not positive(totals["Percentile Latencies"]["p99.00"]):
                raise ValueError()
            if abs(totals["Ops/sec"]-(ns+ng)/(end-start))/totals["Ops/sec"] > .01:
                reasons.append("native_ops_count_duration_mismatch")
            active_samples = [s for s in row.get("samples", []) if start <= s.get("wall", -1) <= end]
            if not active_samples or any(not s.get("serverCpuTicks") or not s.get("clientCpuTicks") or not positive(s.get("intervalSeconds")) for s in active_samples):
                reasons.append("cpu_window_evidence_missing")
            for sample in active_samples:
                for field in ("serverCpuTicks", "clientCpuTicks", "previousServerCpuTicks", "previousClientCpuTicks"):
                    if field in sample and (not isinstance(sample[field], dict) or any(not discrete(v) for v in sample[field].values())):
                        reasons.append("cpu_counter_invalid:" + field)
                if not discrete(sample.get("connections")):
                    reasons.append("sample_integer_invalid:connections")
                if "clockTicksPerSecond" in sample and (not discrete(sample["clockTicksPerSecond"]) or not sample["clockTicksPerSecond"]):
                    reasons.append("sample_integer_invalid:clockTicksPerSecond")
            selected = row.get("rssWindow", [])
            prescribed = [s for s in row.get("samples", []) if start+window_config["trimSeconds"] <= s.get("wall", -1) <= end-window_config["trimSeconds"]]
            if selected != prescribed:
                metric_reasons["M3"].append("rss_window_selection_mismatch")
            if any(not start+window_config["trimSeconds"] <= s.get("wall", -1) <= end-window_config["trimSeconds"] for s in selected):
                metric_reasons["M3"].append("rss_outside_native_window")
            if not selected or any(not discrete(s.get("connections")) or s["connections"] < expected["connections"] for s in selected):
                reasons.append("achieved_connections_unproven")
            # Native bucket k covers [k,k+1) seconds since start. Only complete
            # buckets inside the predeclared trimmed actual runtime are comparable.
            duration = (runtime["Finish time"]-runtime["Start time"])/1000
            interior = list(range(math.ceil(window_config["trimSeconds"]),
                                  math.floor(duration-window_config["trimSeconds"])))
            for operation, summary, count in (("Sets", sets, ns), ("Gets", gets, ng)):
                series = summary["Time-Serie"]
                if any(int(k) > math.floor(duration) for k in series):
                    reasons.append("native_bucket_outside_runtime:" + operation)
                if (count or series) and any(str(k) not in series for k in interior):
                    reasons.append("native_interior_bucket_missing:" + operation)
                if (count or series) and any(str(k) not in series for k in range(math.floor(duration))):
                    reasons.append("native_complete_bucket_missing:" + operation)
            rates = [sets["Time-Serie"].get(str(k), {}).get("Count", 0)+gets["Time-Serie"].get(str(k), {}).get("Count", 0)
                     for k in interior]
            observed.update(convergenceBucketRule="complete-interior-seconds-with-fixed-edge-trim",
                            convergenceBucketIndices=interior, nativeDurationSeconds=duration)
            if len(rates) < 6 or not statistics.mean(rates) or abs(statistics.mean(rates[:len(rates)//2])-statistics.mean(rates[len(rates)//2:]))/statistics.mean(rates) > window_config["maxRelativeDrift"]:
                for m in ("M1", "M2", "M3"):
                    metric_reasons[m].append("workload_not_converged")
        except (KeyError, TypeError, ValueError, ZeroDivisionError):
            reasons.append("native_metrics_missing_or_invalid")
    elif kind == "cycles":
        cycles = row.get("cyclesSeconds", [])
        if len(cycles) != 100 or not all(positive(x) for x in cycles) or not discrete(row.get("validatedCycles")) or row.get("validatedCycles") != 100:
            reasons.append("connection_cycles_invalid")
    elif kind != "memory":
        reasons.append("unknown_workload_kind")
    return {"valid": not reasons, "reasons": sorted(set(reasons)), "metricReasons": metric_reasons,
            "warnings": warnings, "rss": window, "statsDelta": delta["values"], "observedWorkload": observed}


def validate_row(row, expected, identity, window_config):
    """Fail closed on malformed nested evidence instead of crashing or dropping it."""
    try:
        return _validate_row(row, expected, identity, window_config)
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
        return {"valid": False, "reasons": ["row_schema_invalid"],
                "metricReasons": {m: [] for m in THRESHOLDS}, "warnings": [], "rss": {}, "statsDelta": {}, "observedWorkload": {}}


def workload(name):
    count, size, connections, ratio, pipeline, memory = SCENARIOS[name]
    return {"count": count, "size": size, "connections": connections, "ratio": ratio,
            "pipeline": pipeline, "memoryMiB": memory, "ttl": 0,
            "threads": min(connections, 8), "residency": "pressure" if name == "P06" else "resident",
            "kind": "memory" if name.startswith("M3-") else "cycles" if name == "P07" else "memtier"}
