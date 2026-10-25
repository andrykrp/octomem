"""V3 EXPLICITLY VERSIONED MEASUREMENT CORE - pure TEST-05 evidence contracts.

Derived from the campaign-pinned estimator `tests/platform/perf/pinned/contracts.py`
(sha256 244be682e755242bed38c54c4fb94e3c54b2f6d783116347831f5954c5602793).
Only the seven instrument defects (a)-(g) are repaired. M1/M2/M3 thresholds,
the measurement window, validity definitions, scenario definitions and workload
definitions are UNCHANGED; every existing validity check is retained verbatim and
no threshold is raised, lowered, added or removed. See `V3-DELTAS.md`.

No I/O, process creation, or import side effects.
"""
import datetime
import math
import statistics
from collections import Counter

V3_CORE_VERSION = "v3.0.0"
# Base generation this core was derived from (full 64-hex SHA-256).
V3_BASE_SHA256 = {
    "finalize.py": "9cc15c1ac61cfb4cada6fb77dcfd681495ee66114fab5ea0bcc4916b6654ca77",
    "contracts.py": "244be682e755242bed38c54c4fb94e3c54b2f6d783116347831f5954c5602793",
    "gates.py": "1243737576f4e3a76b1f8389b9af306d4ed12a16f190db5e69f615b4a5ce921a",
    "run.py": "07b07ea6e978572edad99ec6cda751b0ad242f30a8d78b165643d4ff8a1de7d2",
    "generator.py": "473eaf3956514519fb1ac759edb4fe22ba397b89d289f77f1a3818813da267cd",
}

# --------------------------------------------------------- UNCHANGED definitions
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

# Data record for the known frozen-protocol timestamp defect (defect f). This is
# recorded as data; nothing is silently corrected.
KNOWN_FROZEN_UTC_DEFECTS = {
    "2026-09-23T20:17:00Z":
        "protocol-stage7b.json frozenUtc post-dates the true freeze "
        "(true freeze 2026-09-22T20:15:54Z) and post-dates collection; "
        "recorded as data, never silently corrected.",
}

# Documented relative tolerance for reproducing the recorded per-row headroom
# ceiling from its attempt-level probe provenance (correction F5.1).
HEADROOM_MATCH_TOLERANCE = 1e-6


def positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def finite_number(value):
    """A JSON number usable by the measurement arithmetic (never a bool)."""
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except (OverflowError, ValueError, TypeError):
        return False


def discrete(value):
    """JSON discrete evidence is an integer, never a bool or integral float."""
    return type(value) is int and value >= 0


def _is_hex64(value):
    return (isinstance(value, str) and len(value) == 64
            and all(c in "0123456789abcdefABCDEF" for c in value))


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


# ------------------------------------------------- DEFECT (a)/(b) repair: CPU
def corrected_generator_cpu(samples, client_cpu_count, window=None):
    """Per-row generator CPU from real consecutive in-window samples.

    Repairs instrument defect (a): the baseline and end of a row's generator CPU
    come from samples *inside* the measurement window, accumulated per consecutive
    sample (`previousClientCpuTicks` -> `clientCpuTicks`) so a worker thread that
    appears or retires between samples contributes every delta that was actually
    observed. The frozen first/last method compared only the first `previous` to
    the last `current` and silently dropped every intermediate thread's CPU.

    Repairs instrument defect (b): an unmeasurable value is returned as an explicit
    `invalid` marker with reasons, never reported or summed as 0.

    The frozen-vs-accumulated disagreement is represented explicitly on every
    return via `firstLastSampleMismatch`, `firstLastCounterSeconds` and
    `accumulatedCounterSeconds`; a mismatch is fatal (fail closed) rather than
    silently reconciled.
    """
    reasons, warnings = [], []
    first_last = {"firstLastSampleMismatch": None, "firstLastCounterSeconds": None,
                  "accumulatedCounterSeconds": None}
    invalid = lambda: {"status": "invalid", "reasons": sorted(set(reasons)),
                       "warnings": sorted(set(warnings)), **first_last}
    try:
        if not isinstance(samples, list) or any(not isinstance(s, dict) for s in samples):
            reasons.append("generator_cpu_samples_invalid")
            return invalid()
        if not any("clientCpuTicks" in s or "previousClientCpuTicks" in s for s in samples):
            return None
        if (not isinstance(window, (tuple, list)) or len(window) != 2 or
                not all(positive(t) for t in window) or window[0] >= window[1]):
            reasons.append("generator_cpu_window_invalid")
            return invalid()
        start, end = window
        active = []
        for s in samples:
            if not positive(s.get("wall")):
                reasons.append("generator_cpu_timestamp_invalid")
                continue
            active.append(s)
        if not active:
            reasons.append("generator_cpu_active_evidence_missing")
            return invalid()
        active.sort(key=lambda s: s["wall"])
        per_thread, clocks, span = {}, set(), 0.
        last_wall, last_mono = None, None
        for s in active:
            current, previous = s.get("clientCpuTicks"), s.get("previousClientCpuTicks")
            interval, wall, mono = s.get("intervalSeconds"), s["wall"], s.get("monotonic")
            clock = s.get("clockTicksPerSecond")
            if not positive(interval) or not positive(mono):
                reasons.append("generator_cpu_interval_invalid")
                continue
            begin = wall - interval
            if wall <= start or begin >= end:
                reasons.append("generator_cpu_interval_wholly_outside_window")
                continue
            if not discrete(clock) or not clock:
                reasons.append("generator_cpu_clock_invalid")
                continue
            clocks.add(clock)
            if begin < start - .001 or wall > end + .001:
                reasons.append("generator_cpu_interval_outside_window")
                continue
            if last_wall is None:
                if abs(begin - start) > .001:
                    reasons.append("generator_cpu_coverage_gap")
            elif abs(begin - last_wall) > .001 or mono <= last_mono or abs(mono - last_mono - interval) > .001:
                # A gap between consecutive samples means part of the window has no
                # real baseline; never invent or extrapolate it.
                reasons.append("generator_cpu_coverage_gap")
            if (not isinstance(current, dict) or not current or
                    any(not discrete(v) for v in current.values())):
                reasons.append("generator_cpu_ticks_invalid")
            elif (not isinstance(previous, dict) or not previous or
                  any(not discrete(v) for v in previous.values())):
                reasons.append("generator_cpu_baseline_invalid")
            else:
                for tid in previous.keys() - current.keys():
                    # The thread vanished between samples; its remaining CPU inside
                    # this interval cannot be measured, so it is an explicit defect,
                    # never a silent zero.
                    reasons.append("generator_cpu_thread_retired_unobserved")
                for tid, after in current.items():
                    if tid not in previous:
                        reasons.append("generator_cpu_baseline_missing")
                    elif after < previous[tid]:
                        reasons.append("generator_cpu_counter_regressed")
                    else:
                        per_thread[tid] = per_thread.get(tid, 0) + after - previous[tid]
                span += interval
            last_wall, last_mono = wall, mono
        if last_wall is None or abs(end - last_wall) > .001:
            reasons.append("generator_cpu_coverage_gap")
        if len(clocks) > 1:
            reasons.append("generator_cpu_clock_changed")
        if type(client_cpu_count) is not int or client_cpu_count < 1:
            reasons.append("generator_cpu_allocation_missing")
        if len(clocks) > 1 or not per_thread or not span or type(client_cpu_count) is not int or client_cpu_count < 1:
            return invalid()
        clock = next(iter(clocks))
        total = sum(per_thread.values()) / clock
        # Frozen first/last representation for the same samples.
        first_previous = active[0].get("previousClientCpuTicks")
        last_current = active[-1].get("clientCpuTicks")
        frozen_total = None
        if isinstance(first_previous, dict) and isinstance(last_current, dict):
            keys = set(first_previous) | set(last_current)
            frozen_total = sum(max(0, last_current.get(k, 0) - first_previous.get(k, 0)) / clock for k in keys)
            first_last = {"firstLastSampleMismatch": abs(frozen_total - total) > max(1e-9, 0.001 * max(total, frozen_total)),
                          "firstLastCounterSeconds": frozen_total,
                          "accumulatedCounterSeconds": total}
            if first_last["firstLastSampleMismatch"]:
                reasons.append("generator_cpu_first_last_sample_mismatch")
        fraction = total / span
        if not math.isfinite(fraction) or not math.isfinite(total):
            reasons.append("generator_cpu_reconstruction_invalid")
            return invalid()
        if reasons:
            return invalid()
        return {"status": "measured", "reasons": [], "warnings": sorted(set(warnings)),
                "clientCpuCount": client_cpu_count, "windowSeconds": span, "activeWindow": list(window),
                "processCpuSeconds": total, "processCpuFraction": fraction,
                "maxThreadCpuSeconds": max(per_thread.values()) / clock,
                "clockTicksPerSecond": clock, "samplesUsed": len(active),
                "firstLastSampleMismatch": first_last["firstLastSampleMismatch"],
                "firstLastCounterSeconds": first_last["firstLastCounterSeconds"],
                "accumulatedCounterSeconds": first_last["accumulatedCounterSeconds"]}
    except (KeyError, TypeError, ValueError, OverflowError, AttributeError, ZeroDivisionError):
        reasons.append("generator_cpu_reconstruction_invalid")
        return invalid()


def cpu_schema_reasons(cpu):
    """Validate optional raw observation fields before arithmetic or iteration."""
    if cpu is None:
        return []
    if not isinstance(cpu, dict):
        return ["generator_cpu_evidence_invalid"]
    reasons = []
    fraction, count = cpu.get("processCpuFraction"), cpu.get("clientCpuCount")
    if fraction is not None and (not finite_number(fraction) or fraction < 0):
        reasons.append("generator_cpu_fraction_invalid")
    if count is not None and (type(count) is not int or count < 1):
        reasons.append("generator_cpu_allocation_invalid")
    if "reasons" in cpu and (not isinstance(cpu["reasons"], list) or any(not isinstance(r, str) for r in cpu["reasons"])):
        reasons.append("generator_cpu_reasons_invalid")
    if "status" in cpu and cpu["status"] not in ("measured", "invalid", "absent"):
        reasons.append("generator_cpu_status_invalid")
    for field in ("processCpuSeconds", "maxThreadCpuSeconds", "windowSeconds"):
        if field in cpu and (not finite_number(cpu[field]) or cpu[field] < 0):
            reasons.append("generator_cpu_" + field + "_invalid")
    return reasons


# ------------------------------------------------- F5.1: headroom provenance
def headroom_provenance_reasons(headroom, probes, tolerance=HEADROOM_MATCH_TOLERANCE):
    """Bind a per-row headroom claim to the attempt's recorded probe provenance.

    Correction F5.1: the self-declared `source`/`method` labels are no longer
    sufficient. The attempt must carry the probe's identity and measurement
    (method, measurement window, measured upper rate, and the probe artifact path
    with its 64-hex sha256), and the row's ceiling must reproduce the probe ceiling
    within `tolerance`. A fabricated or internally inconsistent ceiling, or missing
    probe provenance, is a failing reason.
    """
    reasons = []
    if not isinstance(probes, dict) or not probes:
        return ["generator_headroom_probe_missing"]
    ref = headroom.get("probeRef") if isinstance(headroom, dict) else None
    if not isinstance(ref, str) or not ref:
        return ["generator_headroom_probe_missing"]
    probe = probes.get(ref)
    if not isinstance(probe, dict):
        return ["generator_headroom_probe_missing"]
    if probe.get("source") != "row-measured":
        reasons.append("generator_headroom_probe_not_row_measured")
    if probe.get("method") != headroom.get("method"):
        reasons.append("generator_headroom_probe_method_mismatch")
    window = probe.get("windowSeconds")
    if not finite_number(window) or window <= 0:
        reasons.append("generator_headroom_probe_window_missing")
    artifact = probe.get("probeArtifact")
    if not isinstance(artifact, dict) or not isinstance(artifact.get("name"), str) or not artifact.get("name"):
        reasons.append("generator_headroom_probe_artifact_missing")
    elif not _is_hex64(artifact.get("sha256")):
        reasons.append("generator_headroom_probe_artifact_digest_invalid")
    ceiling = headroom.get("ceilingOpsPerSecond")
    if ceiling is None:
        ceiling = headroom.get("commandsPerSecondUpperBound")
    probe_ceiling = probe.get("ceilingOpsPerSecond")
    if probe_ceiling is None:
        probe_ceiling = probe.get("commandsPerSecondUpperBound")
    if not positive(ceiling) or not positive(probe_ceiling):
        reasons.append("generator_headroom_probe_ceiling_missing")
    elif abs(ceiling - probe_ceiling) > tolerance * max(ceiling, probe_ceiling):
        reasons.append("generator_headroom_ceiling_mismatch")
    return sorted(set(reasons))


# ------------------------------------------------- DEFECT (b)/(d)/(g) repair: gate
def generator_evidence_reasons(row, expected, qualification, provenance=None):
    """Fail-closed generator non-saturation evidence for one row.

    Repairs defect (d): per-shape headroom is a per-row measured value; a row that
    carries the discard-sink extrapolation method is rejected, and an absent
    per-row headroom is never treated as headroom-clear.
    Repairs defects (b)/(g): unknown/absent generator evidence is an explicit
    reason for every row (claimed or not), so no row can reach PASS without it.
    """
    reasons, warnings = [], []
    if expected.get("kind") not in ("memtier", "cycles"):
        return reasons, warnings
    cpu = row.get("generatorCpu")
    if cpu is None or cpu == {}:
        return ["generator_cpu_evidence_missing"], warnings
    if not isinstance(cpu, dict):
        return ["generator_cpu_evidence_missing"], ["generator_cpu_evidence_invalid"]
    warnings.extend(cpu_schema_reasons(cpu))
    if isinstance(cpu.get("reasons"), list):
        warnings.extend(r for r in cpu["reasons"] if isinstance(r, str))
    status = cpu.get("status")
    fraction, count = cpu.get("processCpuFraction"), cpu.get("clientCpuCount")
    if status == "invalid":
        reasons.append("generator_cpu_evidence_missing")
        return reasons, warnings or ["generator_cpu_reconstruction_invalid"]
    if fraction is None or not finite_number(fraction) or fraction < 0:
        reasons.append("generator_cpu_evidence_missing")
        return reasons, warnings
    if type(count) is not int or count < 1:
        reasons.append("generator_cpu_allocation_missing")
        return reasons, warnings
    if cpu.get("partial"):
        warnings.append("generator_cpu_observation_partial")
    if fraction >= GENERATOR_CPU_BUDGET * count:
        warnings.append("generator_cpu_over_budget_observation_not_causal_proof")
    headroom = row.get("generatorHeadroom")
    if headroom is None:
        reasons.append("generator_headroom_evidence_missing")
    elif not isinstance(headroom, dict):
        reasons.append("generator_headroom_evidence_missing")
        warnings.append("generator_headroom_invalid_observation")
    else:
        method = str(headroom.get("method", ""))
        source = headroom.get("source")
        ceiling = headroom.get("ceilingOpsPerSecond")
        if ceiling is None:
            ceiling = headroom.get("commandsPerSecondUpperBound")
        achieved = headroom.get("achievedOpsPerSecond")
        if source != "row-measured" or "discard-sink" in method.lower() or "extrapolat" in method.lower():
            reasons.append("generator_headroom_not_row_measured")
        elif not positive(ceiling) or not positive(achieved):
            reasons.append("generator_headroom_evidence_missing")
        elif ceiling / achieved < GENERATOR_HEADROOM_MIN:
            reasons.append("generator_headroom_below_minimum")
        if provenance is not None and source == "row-measured":
            reasons.extend(headroom_provenance_reasons(headroom, provenance.get("headroomProbes")))
    return reasons, warnings


def generator_saturation_reasons(row, expected):
    """Deprecated alias retained for callers of the pinned API.

    It now delegates to the v3 fail-closed evidence function with qualification
    assumed for rows that claim it, so a stale caller cannot re-open defect (b).
    """
    qualification = row.get("performanceQualification") is True
    return generator_evidence_reasons(row, expected, qualification)[0]


# ------------------------------------------------- DEFECT (e) repair: topology
def parse_cpu_mask(spec):
    """Parse a taskset-style cpu list ('0-1', '0,2-3') into a set of cpu numbers."""
    cpus = set()
    for part in str(spec or "").split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, _, hi = part.partition("-")
            try:
                lo, hi = int(lo), int(hi)
            except ValueError:
                return None
            if hi < lo:
                return None
            cpus.update(range(lo, hi + 1))
        elif part.isdigit():
            cpus.add(int(part))
        else:
            return None
    return cpus or None


def _cores_for_mask(mask, siblings):
    """Map a cpu mask onto physical cores using a sibling map; None if unmapped."""
    cpus = parse_cpu_mask(mask)
    if not cpus or not isinstance(siblings, dict):
        return None, []
    core_of = {}
    for cpu, group in siblings.items():
        try:
            group = [int(x) for x in group]
            if not group:
                raise ValueError
            core_of[int(cpu)] = min(group)
        except (TypeError, ValueError):
            return None, []
    if any(c not in core_of for c in cpus):
        return None, sorted({core_of[c] for c in cpus if c in core_of})
    return cpus, sorted({core_of[c] for c in cpus})


def topology_isolation(server_cpus, client_cpus, topology):
    """Reject a server/client mask pair that shares physical cores (defect e).

    Co-resident layout: `topology` is `{"siblings": {"<cpu>": [<siblings>...]}}`
    read from `/sys/devices/system/cpu/cpu*/topology/thread_siblings_list`.
    Two-machine layout: `topology` is
    `{"layout": "two-machine", "hosts": {"server": {"siblings": ...}, "client": {"siblings": ...}}}`
    because server and client are on different hosts and cannot share a physical
    core; each host's own topology must still be recorded and each mask must map
    inside its own host. The actual observed topology is always published.
    Absent topology evidence fails closed.
    """
    reasons = []
    observed = {"serverCpus": sorted(parse_cpu_mask(server_cpus) or []),
                "clientCpus": sorted(parse_cpu_mask(client_cpus) or []),
                "threadSiblings": None, "sharedCores": []}
    if not isinstance(topology, dict):
        return {"valid": False, "reasons": ["topology_evidence_missing"], "observed": observed}
    if topology.get("layout") == "two-machine":
        hosts = topology.get("hosts")
        observed["layout"] = "two-machine"
        observed["threadSiblings"] = hosts
        if (not isinstance(hosts, dict) or not isinstance(hosts.get("server"), dict)
                or not isinstance(hosts.get("client"), dict)):
            return {"valid": False, "reasons": ["topology_evidence_missing"], "observed": observed}
        mapped = {}
        for role, spec in (("server", server_cpus), ("client", client_cpus)):
            cpus, cores = _cores_for_mask(spec, hosts[role].get("siblings"))
            if cpus is None:
                reasons.append("topology_cpu_unmapped:" + role)
            mapped[role] = cores
        observed["serverCores"] = mapped.get("server", [])
        observed["clientCores"] = mapped.get("client", [])
        if not reasons:
            # Different hosts cannot share a physical core; record explicit disjointness.
            observed["crossHostIsolated"] = True
            observed["sharedCores"] = []
        return {"valid": not reasons, "reasons": sorted(set(reasons)), "observed": observed}
    siblings = topology.get("siblings")
    observed["threadSiblings"] = siblings
    if not isinstance(siblings, dict) or not siblings:
        reasons.append("topology_evidence_missing")
    server, client = parse_cpu_mask(server_cpus), parse_cpu_mask(client_cpus)
    if not server or not client:
        reasons.append("topology_mask_invalid")
    core_of = {}
    if isinstance(siblings, dict):
        for cpu, group in siblings.items():
            try:
                group = [int(x) for x in group]
                if not group:
                    raise ValueError
                core = min(group)
                core_of[int(cpu)] = core
            except (TypeError, ValueError):
                reasons.append("topology_evidence_invalid")
                break
    if not reasons and server and client:
        server_cores = {core_of[c] for c in server if c in core_of}
        client_cores = {core_of[c] for c in client if c in core_of}
        if any(c not in core_of for c in server) or any(c not in core_of for c in client):
            reasons.append("topology_cpu_unmapped")
        shared = server_cores & client_cores
        observed["serverCores"] = sorted(server_cores)
        observed["clientCores"] = sorted(client_cores)
        observed["sharedCores"] = sorted(shared)
        if shared:
            reasons.append("smt_mask_pair_not_core_isolated")
    return {"valid": not reasons, "reasons": sorted(set(reasons)), "observed": observed}


# ------------------------------------------------- DEFECT (f) repair: protocol
def _parse_iso(text):
    if not isinstance(text, str) or not text:
        return None
    try:
        return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None


def protocol_identity(protocol, collection_start_utc=None, expected_sha256=None):
    """Bind the frozen protocol to the attempt and fail closed on mis-dating.

    Repairs defect (f): the frozen protocol hash must match the attempt binding,
    the protocol must be frozen before collection, and `frozenUtc` must not
    post-date collection. The known 2026-09-23T20:17:00Z defect is recorded as
    data (`knownFrozenUtcDefect`) and never silently corrected.
    """
    reasons = []
    observed = {}
    if not isinstance(protocol, dict):
        return {"valid": False, "reasons": ["protocol_evidence_missing"], "observed": observed}
    frozen = protocol.get("frozenUtc")
    observed["frozenUtc"] = frozen
    observed["collectionStartUtc"] = collection_start_utc
    observed["expectedSha256"] = expected_sha256
    if expected_sha256 is None:
        # Correction F5.2: fail closed whenever the expected protocol hash is
        # absent, with a reason distinct from a plain mismatch.
        reasons.append("protocol_expected_hash_missing")
    elif protocol.get("sha256") != expected_sha256:
        reasons.append("protocol_attempt_binding_mismatch")
    if frozen in KNOWN_FROZEN_UTC_DEFECTS:
        observed["knownFrozenUtcDefect"] = True
        observed["knownDefectNote"] = KNOWN_FROZEN_UTC_DEFECTS[frozen]
    else:
        observed["knownFrozenUtcDefect"] = False
    frozen_dt = _parse_iso(frozen)
    start_dt = _parse_iso(collection_start_utc)
    if protocol.get("frozenBeforeCollection") is not True:
        reasons.append("protocol_not_frozen_before_collection")
    if frozen_dt is None:
        reasons.append("protocol_frozen_utc_missing_or_invalid")
    if start_dt is None:
        reasons.append("protocol_collection_time_missing")
    elif frozen_dt is not None and frozen_dt > start_dt:
        reasons.append("protocol_frozen_utc_postdates_collection")
    return {"valid": not reasons, "reasons": sorted(set(reasons)), "observed": observed}


# ------------------------------------------------- per-row validity (retained + repaired)
def _validate_row(row, expected, identity, window_config, qualification=False, provenance=None):
    """Return common and per-metric reasons; callers must combine both.

    expected is the exact workload dict recorded in the pre-run plan. Native
    memtier JSON is embedded by the reader only after digest verification.
    Every pinned validity check is retained; only the instrument defects are repaired.
    """
    reasons = list(row.get("evidenceErrors", []))
    metric_reasons = {m: [] for m in THRESHOLDS}
    warnings = []
    observed = {}
    if qualification and row.get("performanceQualification") is not True:
        reasons.append("qualification_claim_mismatch")
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
        # Defect (c): the CPU/throttling scope must be the server's own cgroup, not
        # the host root; an unidentified scope fails closed.
        for side, snapshot in (("cgroupBefore", row["cgroupBefore"]), ("cgroupAfter", row["cgroupAfter"])):
            scope = snapshot.get("scope")
            source = snapshot.get("scopeSource")
            if source != "server-pid" or not scope or scope == "/":
                reasons.append("cgroup_scope_unidentified:" + side)
    if "peakClientThreadCpu" in row:
        peak = row["peakClientThreadCpu"]
        if not finite_number(peak) or peak < 0:
            # Correction A: a present-but-malformed value is invalid (the pinned core
            # reached row_schema_invalid); a genuinely absent value stays a disclosed
            # warning and is never read as 0.
            reasons.append("client_thread_cpu_invalid")
        elif peak >= .85:
            warnings.append("client_thread_busy_ge_0.85_not_causal_proof")
    else:
        warnings.append("client_thread_cpu_absent")
    kind = expected["kind"]
    cpu_reasons, cpu_warnings = generator_evidence_reasons(row, expected, qualification, provenance)
    # Correction B: generator-CPU validity reasons are COMMON reasons applied to every
    # metric (as the pinned core did), so an absent-evidence row cannot newly PASS M3.
    reasons.extend(cpu_reasons)
    warnings.extend(cpu_warnings)
    cpu_observation = row.get("generatorCpu")
    if isinstance(cpu_observation, dict) and cpu_observation.get("firstLastSampleMismatch") is True:
        reasons.append("generator_cpu_first_last_sample_mismatch")
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
    observed["generatorCpuObservation"] = cpu_observation if isinstance(cpu_observation, dict) and cpu_observation.get("status") in ("measured", "invalid") else {"status": "supplied" if not cpu_reasons else "invalid"}
    observed["generatorCpuFirstLastSampleMismatch"] = bool(isinstance(cpu_observation, dict) and cpu_observation.get("firstLastSampleMismatch"))
    return {"valid": not reasons, "reasons": sorted(set(reasons)), "metricReasons": metric_reasons,
            "warnings": warnings, "rss": window, "statsDelta": delta["values"], "observedWorkload": observed}


def validate_row(row, expected, identity, window_config, qualification=False, provenance=None):
    """Fail closed on malformed nested evidence instead of crashing or dropping it."""
    try:
        return _validate_row(row, expected, identity, window_config, qualification, provenance)
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
        return {"valid": False, "reasons": ["row_schema_invalid"],
                "metricReasons": {m: [] for m in THRESHOLDS}, "warnings": [], "rss": {}, "statsDelta": {}, "observedWorkload": {}}


def workload(name):
    count, size, connections, ratio, pipeline, memory = SCENARIOS[name]
    return {"count": count, "size": size, "connections": connections, "ratio": ratio,
            "pipeline": pipeline, "memoryMiB": memory, "ttl": 0,
            "threads": min(connections, 8), "residency": "pressure" if name == "P06" else "resident",
            "kind": "memory" if name.startswith("M3-") else "cycles" if name == "P07" else "memtier"}
