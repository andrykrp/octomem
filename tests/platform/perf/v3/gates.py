"""V3 EXPLICITLY VERSIONED MEASUREMENT CORE - machine-readable validity gates.

Derived from `tests/platform/perf/pinned/gates.py`
(sha256 1243737576f4e3a76b1f8389b9af306d4ed12a16f190db5e69f615b4a5ce921a).
Only instrument defects (c)/(d)/(e)/(f) are repaired by adding gate functions and
hardening `generator_non_saturation`; every pinned gate is retained unchanged and
no threshold is changed. See `V3-DELTAS.md`.

Requirement: no ratio verdict (M1/M2/M3) may be produced before every gate passes.
An invalid observation is INCONCLUSIVE with an explicit reason and is retained, never
silently dropped. Thresholds are not defined here; this module only decides whether an
observation is admissible, and `contracts.classify` performs the threshold comparison.

Each gate returns::

    {"gate": <name>, "valid": bool, "reasons": [...], "observed": {...}}

`aggregate` merges gate outputs and produces a single admissible/inadmissible
decision usable by the matrix runner, `run.py` and the 72-hour soak reporter.
"""
import math
import pathlib
import re

try:
    from . import contracts as c
except ImportError:  # direct script execution / sys.path usage
    import sys
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import contracts as c


GENERATOR_HEADROOM_MIN = 2.0
GENERATOR_CPU_BUDGET = 0.85
SOAK_COMPLETENESS_TOLERANCE = 0.01
CLOCK_SKEW_SECONDS = 5.0


def _result(gate, reasons, valid=None, **observed):
    reasons = sorted({str(r) for r in reasons if r})
    return {"gate": gate, "valid": bool(valid if valid is not None else not reasons),
            "reasons": reasons, "observed": observed}


def _positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def _parse_cpu_stat(text):
    values = {}
    for line in (text or "").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("-").isdigit():
            values[parts[0]] = int(parts[1])
    return values


# ------------------------------------------------------------------ core gates

def unique_attempt_directory(path):
    """A fresh, empty attempt directory under an existing parent; ids are unique."""
    path = pathlib.Path(path)
    reasons = []
    if not path.parent.is_dir():
        reasons.append("attempt_parent_missing")
    if path.exists():
        if not path.is_dir():
            reasons.append("attempt_path_not_directory")
        elif any(path.iterdir()):
            reasons.append("attempt_directory_not_empty_or_reused")
    return _result("unique_attempt_directory", reasons, attemptId=path.name,
                   exists=path.exists(), parent=str(path.parent))


def stale_artifact_rejection(artifact, attempt_wall_start, attempt_wall_end, root=None, now_sha=None):
    """Reject a raw artifact produced before the attempt opened or whose digest drifted."""
    reasons = []
    artifact = artifact or {}
    name = artifact.get("name")
    digest = artifact.get("sha256")
    recorded = artifact.get("recordedWall")
    if not name or pathlib.Path(name).name != name:
        reasons.append("artifact_name_invalid")
    if not digest or not re.fullmatch(r"[0-9a-fA-F]{64}", str(digest)):
        reasons.append("artifact_digest_invalid")
    if not _positive(recorded) or not _positive(attempt_wall_start) or not _positive(attempt_wall_end):
        reasons.append("artifact_time_missing")
    elif not (attempt_wall_start - CLOCK_SKEW_SECONDS <= recorded <= attempt_wall_end + CLOCK_SKEW_SECONDS):
        reasons.append("artifact_predates_or_postdates_attempt")
    if root is not None and name:
        path = pathlib.Path(root) / name
        if not path.is_file():
            reasons.append("artifact_missing")
        else:
            import hashlib
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest and actual.lower() != str(digest).lower():
                reasons.append("artifact_digest_drift")
            elif now_sha is not None and actual.lower() != str(now_sha).lower():
                reasons.append("artifact_digest_mismatch")
    return _result("stale_artifact_rejection", reasons, name=name, recordedWall=recorded)


def identity_match(actual, expected, keys):
    """Every identity key must be present and exactly equal."""
    reasons = []
    for key in keys:
        if (actual or {}).get(key) in (None, ""):
            reasons.append("identity_missing:" + key)
        elif not (expected or {}).get(key):
            reasons.append("identity_expected_missing:" + key)
        elif actual[key] != expected[key]:
            reasons.append("identity_mismatch:" + key)
    return _result("identity_match", reasons, checked=list(keys))


def jdk_identity(version_output, required_version):
    """Explicit JDK25 update required; no silent fallback to JDK17/21/25.0.2."""
    text = version_output or ""
    match = re.search(r'(?:openjdk|java) version "([^"]+)"', text)
    reasons = []
    found = match.group(1) if match else None
    if not found:
        reasons.append("jdk_version_unparsable")
    elif not found.startswith("25."):
        reasons.append("jdk_not_25")
    elif required_version and found != required_version:
        reasons.append("jdk_update_mismatch")
    return _result("jdk_identity", reasons, found=found, required=required_version)


def harness_hashes(actual, expected):
    """Harness source must be archived and hash-identical to the frozen plan."""
    reasons = []
    if not expected:
        reasons.append("harness_plan_missing")
    for name, digest in (expected or {}).items():
        if not (actual or {}).get(name):
            reasons.append("harness_missing:" + name)
        elif str(actual[name]).lower() != str(digest).lower():
            reasons.append("harness_digest_mismatch:" + name)
    return _result("harness_hashes", reasons, files=sorted((expected or {}).keys()))


def client_exit(row):
    """Client process must have exited 0, not timed out, and not errored."""
    reasons = []
    if "error" in (row or {}):
        reasons.append("run_error")
    if (row or {}).get("exitCode") != 0:
        reasons.append("client_exit_nonzero_or_missing")
    if (row or {}).get("timedOut") is not False:
        reasons.append("client_timeout_or_missing")
    if type((row or {}).get("exitCode")) is not int:
        reasons.append("client_exit_invalid_type")
    return _result("client_exit", reasons, exitCode=(row or {}).get("exitCode"),
                   timedOut=(row or {}).get("timedOut"))


def preload_success(preload, expected_count):
    """The declared dataset must be fully resident before a timed phase starts."""
    reasons = []
    preload = preload or {}
    if preload.get("response") != "STORED":
        reasons.append("preload_response:" + str(preload.get("response")))
    if preload.get("requested") != expected_count:
        reasons.append("preload_requested_mismatch")
    if preload.get("stored") != expected_count:
        reasons.append("preload_stored_mismatch")
    for key in ("requested", "stored"):
        if type(preload.get(key)) is not int:
            reasons.append("preload_integer_invalid:" + key)
    return _result("preload_success", reasons, requested=preload.get("requested"),
                   stored=preload.get("stored"), response=preload.get("response"))


def workload_composition(observed, plan, ratio_tolerance=.03, connection_tolerance=0):
    """Achieved mix, hit/miss and connections must match the declared plan.

    A declared hit ratio of 1.0 (fully resident keyspace) is also required to be
    achieved; a plan that claims residency but misses is invalid, not a slow server.
    """
    reasons = []
    observed, plan = observed or {}, plan or {}
    if not observed:
        return _result("workload_composition", ["observed_workload_missing"])
    want_set, want_get = (int(x) for x in str(plan.get("ratioSetGet", "0:0")).split(":"))
    total_ratio = want_set + want_get
    if total_ratio <= 0:
        reasons.append("planned_ratio_invalid")
    else:
        planned_set = want_set / total_ratio
        actual = observed.get("setFraction")
        if not isinstance(actual, (int, float)) or isinstance(actual, bool) or not math.isfinite(actual):
            reasons.append("achieved_set_fraction_missing")
        elif abs(actual - planned_set) > ratio_tolerance:
            reasons.append("operation_ratio_mismatch")
    planned_connections = plan.get("connections")
    achieved = observed.get("connections")
    if type(planned_connections) is int and (not isinstance(achieved, int) or achieved < planned_connections - connection_tolerance):
        reasons.append("achieved_connections_unproven")
    declared_hit = plan.get("declaredHitRatio")
    if declared_hit is None and plan.get("residency") == "resident":
        declared_hit = 1.0
    if declared_hit is not None:
        actual_hit = observed.get("hitRatio")
        if not isinstance(actual_hit, (int, float)) or isinstance(actual_hit, bool) or not math.isfinite(actual_hit):
            reasons.append("achieved_hit_ratio_missing")
        elif declared_hit - actual_hit > 1e-9 + ratio_tolerance:
            reasons.append("hit_ratio_below_declared")
    if observed.get("ops", 0) <= 0 and (want_set + want_get) > 0:
        reasons.append("no_completed_operations")
    if observed.get("errors", 0):
        reasons.append("client_operation_errors")
    return _result("workload_composition", reasons,
                   plannedSetFraction=(want_set / total_ratio) if total_ratio else None,
                   achievedSetFraction=observed.get("setFraction"),
                   achievedHitRatio=observed.get("hitRatio"),
                   declaredHitRatio=declared_hit,
                   achievedConnections=achieved, plannedConnections=planned_connections)


def stable_measurement_window(samples, config):
    """Delegate to the frozen window contract; never search for a favorable window."""
    qualified = c.qualify_window(samples, config)
    return _result("stable_measurement_window", qualified.get("reasons", []),
                   valid=qualified.get("valid"), median=qualified.get("median"),
                   span=(qualified.get("start"), qualified.get("end")),
                   relativeDrift=qualified.get("relativeDrift"),
                   relativeRange=qualified.get("relativeRange"))


def cgroup_v2_evidence(before, after):
    """Require actual cgroup v2 limits and counters, and stable limits across the run."""
    reasons = []
    if not before or not after:
        return _result("cgroup_v2_evidence", ["cgroup_evidence_missing"])
    if before.get("version") != 2 or after.get("version") != 2:
        reasons.append("cgroup_v2_not_available")
    for key in ("cpu.max", "cpuset.cpus.effective", "memory.max", "memory.swap.max"):
        if not before.get("files", {}).get(key) or before["files"].get(key) != after.get("files", {}).get(key):
            reasons.append("cgroup_limit_missing_or_changed:" + key)
    for side_name, side in (("before", before), ("after", after)):
        if not side.get("files", {}).get("cpu.stat"):
            reasons.append("cgroup_cpu_stat_missing:" + side_name)
        if not side.get("files", {}).get("memory.events"):
            reasons.append("cgroup_memory_events_missing:" + side_name)
    def counters(side):
        return _parse_cpu_stat(side.get("files", {}).get("cpu.stat", ""))
    b, a = counters(before), counters(after)
    for key in ("nr_throttled", "throttled_usec"):
        if key not in b or key not in a:
            reasons.append("cgroup_counter_missing:" + key)
        elif a[key] - b[key] < 0:
            reasons.append("cgroup_counter_regressed:" + key)
    return _result("cgroup_v2_evidence", reasons,
                   throttleBefore={k: b.get(k) for k in ("nr_throttled", "throttled_usec")},
                   throttleAfter={k: a.get(k) for k in ("nr_throttled", "throttled_usec")})


def generator_non_saturation(cpu, headroom=None, min_headroom=GENERATOR_HEADROOM_MIN,
                             cpu_budget=GENERATOR_CPU_BUDGET, require_row_measured=True):
    """Prove the generator is not the bottleneck.

    Preferred proof is a measured capacity headroom factor (generator ceiling divided
    by achieved load) of at least `min_headroom`. Defect (d) repair: that headroom must
    be a per-row measured value (`source == "row-measured"`); a discard-sink
    extrapolation is rejected, and absent headroom is NEVER labelled headroom-clear.
    When no per-row capacity probe is available, aggregate CPU below `cpu_budget`
    together with at least one spare logical CPU is retained as weaker evidence, but
    it certifies CPU budget only (`headroomCleared=False`) and never a measured ceiling.
    """
    reasons, method = [], None
    cpu = cpu or {}
    if headroom is not None:
        if not isinstance(headroom, dict):
            return _result("generator_non_saturation", ["generator_headroom_invalid_observation"],
                           method="measured-capacity-headroom-rejected", headroomCleared=False)
        source = headroom.get("source")
        method_text = str(headroom.get("method", ""))
        ceiling = headroom.get("commandsPerSecondUpperBound") or headroom.get("ceilingOpsPerSecond")
        achieved = headroom.get("achievedOpsPerSecond")
        extrapolated = ("discard-sink" in method_text.lower() or "extrapolat" in method_text.lower()
                        or (require_row_measured and source != "row-measured"))
        if extrapolated:
            reasons.append("generator_headroom_not_row_measured")
        if not _positive(ceiling) or not _positive(achieved):
            reasons.append("generator_headroom_missing_values")
        else:
            factor = ceiling / achieved
            method = "measured-capacity-headroom"
            if factor < min_headroom:
                reasons.append("generator_headroom_below_minimum")
            return _result("generator_non_saturation", reasons, method=method,
                           headroomFactor=factor, ceilingOpsPerSecond=ceiling,
                           achievedOpsPerSecond=achieved, source=source,
                           minHeadroom=min_headroom, headroomCleared=not reasons)
        return _result("generator_non_saturation", reasons,
                       method="measured-capacity-headroom-rejected", source=source,
                       headroomCleared=False)
    fraction = cpu.get("processCpuFraction")
    cpus = cpu.get("logicalCpus")
    method = "cpu-budget-only"
    if fraction is None:
        reasons.append("generator_cpu_unavailable")
    elif fraction > cpu_budget:
        reasons.append("generator_cpu_over_budget")
    if not isinstance(cpus, int) or cpus < 2:
        reasons.append("generator_host_cpu_count_insufficient")
    return _result("generator_non_saturation", reasons, method=method,
                   processCpuFraction=fraction, maxThreadCpuSeconds=cpu.get("maxThreadCpuSeconds"),
                   logicalCpus=cpus, cpuBudget=cpu_budget, headroomCleared=False)


def cgroup_scope_identifiable(before, after):
    """Defect (c): evidence must come from the server's own cgroup scope, not the root."""
    reasons = []
    observed = {}
    for side_name, side in (("before", before), ("after", after)):
        side = side or {}
        observed[side_name] = {"scope": side.get("scope"), "scopeSource": side.get("scopeSource")}
        if side.get("scopeSource") != "server-pid":
            reasons.append("cgroup_scope_source_not_server_pid:" + side_name)
        if not side.get("scope") or side.get("scope") == "/":
            reasons.append("cgroup_scope_unidentified:" + side_name)
    return _result("cgroup_scope_identifiable", reasons, **observed)


def topology_isolation(server_cpus, client_cpus, topology):
    """Defect (e): reject a server/client mask pair that shares physical cores."""
    result = c.topology_isolation(server_cpus, client_cpus, topology)
    return _result("topology_isolation", result["reasons"], valid=result["valid"],
                   serverCpus=server_cpus, clientCpus=client_cpus,
                   **{k: v for k, v in result["observed"].items() if k != "serverCpus" and k != "clientCpus"})


def protocol_identity(protocol, collection_start_utc=None, expected_sha256=None):
    """Defect (f): bind the frozen protocol to the attempt and fail closed on mis-dating."""
    result = c.protocol_identity(protocol, collection_start_utc, expected_sha256)
    return _result("protocol_identity", result["reasons"], valid=result["valid"], **result["observed"])


def soak_completeness(report, planned_seconds, interval_seconds,
                      completeness_tolerance=SOAK_COMPLETENESS_TOLERANCE):
    """Distinguish a real completed soak from an interrupted or gapped one."""
    reasons = []
    report = report or {}
    observed = report.get("observedSeconds")
    if not _positive(planned_seconds) or not _positive(interval_seconds):
        return _result("soak_completeness", ["soak_plan_missing"])
    if not isinstance(observed, (int, float)) or isinstance(observed, bool):
        reasons.append("soak_observed_seconds_missing")
        observed = 0
    gaps = report.get("gaps") or []
    big_gaps = [g for g in gaps if isinstance(g, (int, float)) and g > 3 * interval_seconds]
    if observed < planned_seconds * (1 - completeness_tolerance):
        reasons.append("soak_duration_short")
    if big_gaps:
        reasons.append("soak_gaps_detected")
    if report.get("restartCount", 0):
        reasons.append("server_restart_detected")
    if report.get("status") != "COMPLETE":
        reasons.append("soak_status:" + str(report.get("status")))
    return _result("soak_completeness", reasons, observedSeconds=observed,
                   plannedSeconds=planned_seconds, gapCount=len(gaps), bigGapCount=len(big_gaps),
                   restartCount=report.get("restartCount", 0))


# ------------------------------------------------------------------ aggregation

def aggregate(gates_list):
    """Merge gate results; admissible only when every gate is valid."""
    reasons = []
    for gate in gates_list:
        for reason in gate.get("reasons", []):
            reasons.append(f"{gate.get('gate')}:{reason}")
    return {"gate": "aggregate", "valid": not reasons, "admissible": not reasons, "reasons": sorted(set(reasons)),
            "gates": {g.get("gate"): g.get("valid") for g in gates_list}}


def gate_matrix(row):
    """Convenience: every core gate for one paired observation row, as one object."""
    return {
        "unique_attempt_directory": unique_attempt_directory(row.get("attemptDirectory", ".")),
        "client_exit": client_exit(row),
        "preload_success": preload_success(row.get("preload"), (row.get("plan") or {}).get("count", 0)),
        "identity_match": identity_match(row.get("identity"), row.get("expectedIdentity"), c.IDENTITY_KEYS),
        "workload_composition": workload_composition(row.get("observed"), row.get("plan")),
        "stable_measurement_window": stable_measurement_window(row.get("rssWindow", []), row.get("windowConfig", {})),
        "cgroup_v2_evidence": cgroup_v2_evidence(row.get("cgroupBefore"), row.get("cgroupAfter")),
        "cgroup_scope_identifiable": cgroup_scope_identifiable(row.get("cgroupBefore"), row.get("cgroupAfter")),
        "generator_non_saturation": generator_non_saturation(row.get("cpu"), row.get("generatorHeadroom")),
        "topology_isolation": topology_isolation(row.get("serverCpus"), row.get("clientCpus"), row.get("topology")),
        "protocol_identity": protocol_identity(row.get("protocol"), row.get("collectionStartUtc"), row.get("protocolSha256")),
    }
