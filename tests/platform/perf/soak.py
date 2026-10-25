"""Bounded-or-72h soak runner for TEST-09 / docs/06 Т-05 (72 hours mixed work).

It is designed to be *prepared* here and launched later on the stands, never to have
the 72-hour run executed as part of this preparation. A real run requires
`--approved-run`; a short local verification uses `--bounded-seconds` and is labelled
`bounded: true` so its report can never be mistaken for the mandatory 72 hours.

What the report distinguishes
------------------------------
* `plannedSeconds` vs `observedSeconds` and wall-clock segment boundaries;
* per-interval timestamped samples (server RSS, cgroup memory/cpu, open descriptors,
  threads, text-protocol counters, optional JVM heap/native/GC via `jcmd`);
* gaps between samples and phase boundaries;
* server restarts (reported pid/uptime discontinuity or process death);
* client phase exit codes and per-phase memtier artifacts;
* `status` = COMPLETE only for a full, gap-free, restart-free planned run;
  `BOUNDED_COMPLETE` for a local bounded verification; `INTERRUPTED` otherwise.

Two-machine (external client) mode
----------------------------------
With `--external-client-host <generator>` the server binds `--listen-host` (auto-set
to `0.0.0.0` for a non-loopback client; the effective bind is in `plan.json` and the
auto-set flag is recorded as `report.json` -> `externalClient.listenHostAutoSet`), the
local memtier phase loop is skipped, and the server host only samples for the planned
duration. The generator host runs `external-client-loop.sh`, which owns the phase cycle
and writes its own `client-report.json`. Cross-machine load is evidenced by the
`externalTrafficGate` (per-interval `cmd_get+cmd_set` deltas from the server counters):
a COMPLETE/BOUNDED_COMPLETE run is downgraded when no operations were seen, when more
than 2% of intervals show no traffic, or when more than 5 consecutive intervals show
no traffic. Local helper connections (wire_version/stats/preload/monitor) stay on
loopback. With no `--external-client-host` the behaviour is unchanged.

Durability and resume
---------------------
Every run creates a unique directory and never writes into an existing run directory.
`samples.jsonl` is opened at run start and each sample is appended and flushed as it is
taken, so the raw series survives a crash or power loss (the final file has the same
content as the previous end-of-run write). `--resume <dir>` continues the interrupted
attempt in place: it loads the retained `samples.jsonl` lines, appends the new samples,
and writes a new `report-resume-<n>.json` (plus a tagged `plan-resume-<n>.json` whose
`sampleCount`, `observedSeconds`, `gaps` and `restarts` cover the merged series).
`segments.json` and `complete.json` are rewritten with this segment and
`server-gc.log` is appended to; only the report and plan are tagged. Because a resume
is a restart, a resumed run cannot claim a clean 72 h.

The module is import-safe and exposes `SoakRunner`/`ServerMonitor` so the bounded
self-verification can drive it against an in-process mock server.
"""
import argparse
import datetime
import json
import os
import pathlib
import socket
import subprocess
import sys
import threading
import time
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import gates as g  # noqa: E402
import generator as lg  # noqa: E402

PLANNED_SECONDS_72H = 72 * 3600
DEFAULT_PHASES = [("mixed90_10", "1:9"), ("get_only", "0:1"), ("set_only", "1:0")]
DEFAULT_PORT = 11219
PHASE_PIPELINE = 1
PHASE_DATA_SIZE = 1024
PHASE_KEYS = 4096
PHASE_THREADS = 8
EXTERNAL_ZERO_TRAFFIC_FRACTION = 0.02
EXTERNAL_MAX_CONSECUTIVE_ZERO = 5
LOCAL_HOST = "127.0.0.1"
WILDCARD_HOSTS = ("0.0.0.0", "::")


# --------------------------------------------------------------------------- wire

def text_command(host, port, command, timeout=10.0, terminators=(b"END\r\n",)):
    """Send one bounded text command and read until a terminator line."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(command)
        data = bytearray()
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                raise ConnectionError("EOF before terminator")
            data.extend(chunk)
            if any(bytes(data).endswith(t) for t in terminators):
                return bytes(data)
            if len(data) > 8 << 20:
                raise ConnectionError("response exceeded 8 MiB")


def parse_stats(raw):
    result = {}
    for line in raw.decode("ascii", "replace").splitlines():
        parts = line.split(" ", 2)
        if len(parts) == 3 and parts[0] == "STAT":
            result[parts[1]] = parts[2]
    return result


def server_stats(host, port):
    return parse_stats(text_command(host, port, b"stats\r\n"))


def wire_version(host, port):
    raw = text_command(host, port, b"version\r\n", terminators=(b"\r\n",))
    return raw.decode("ascii", "replace").strip()


# --------------------------------------------------------------------------- monitor

class ServerMonitor:
    def __init__(self, host, port, pid=None, jcmd=None, start_wall=None, external=False):
        self.host, self.port, self.pid, self.jcmd = host, port, pid, jcmd
        self.start_wall = start_wall
        self.external = external
        self.lock = threading.Lock()
        self.restart_events = []
        self._last_pid = None
        self._last_uptime = None

    def sample(self, phase=None, phase_index=None):
        now_mono, now_wall = time.monotonic(), time.time()
        sample = {"monotonic": now_mono, "wall": now_wall, "phase": phase, "phaseIndex": phase_index}
        try:
            stats = server_stats(self.host, self.port)
            sample["stats"] = stats
            sample["rssBytes"] = _rss(stats.get("pid") or self.pid)
            sample["openFds"] = _open_fds(stats.get("pid") or self.pid)
            sample["threads"] = stats.get("threads")
            sample["restartDetected"] = False
            if self._last_pid is not None and stats.get("pid") and stats["pid"] != self._last_pid:
                sample["restartDetected"] = True
                self.restart_events.append({"monotonic": now_mono, "wall": now_wall, "reason": "reported pid changed",
                                            "from": self._last_pid, "to": stats["pid"]})
            if self._last_uptime is not None and stats.get("uptime") is not None:
                try:
                    if int(stats["uptime"]) < int(self._last_uptime):
                        sample["restartDetected"] = True
                        self.restart_events.append({"monotonic": now_mono, "wall": now_wall, "reason": "uptime regressed",
                                                    "from": self._last_uptime, "to": stats["uptime"]})
                except (TypeError, ValueError):
                    pass
            if stats.get("pid"):
                self._last_pid = stats["pid"]
            if stats.get("uptime") is not None:
                self._last_uptime = stats["uptime"]
        except (OSError, ConnectionError, ValueError) as exc:
            sample["statsError"] = repr(exc)
        sample["cgroup"] = lg.cgroup_evidence()
        # No local client exists in external-client mode; the generator lives on
        # another host and its RSS is sampled there, so never report a local RSS.
        sample["client"] = None if self.external else _client_rss()
        if self.jcmd and self.pid:
            sample["jvm"] = _jvm_sample(self.jcmd, self.pid)
        return sample

    def sample_stats_extra(self):
        extra = {}
        for name, command in (("items", b"stats items\r\n"), ("slabs", b"stats slabs\r\n")):
            try:
                extra[name] = text_command(self.host, self.port, command).decode("ascii", "replace")[:8192]
            except (OSError, ConnectionError) as exc:
                extra[name] = "unavailable:" + repr(exc)
        return extra


def _rss(pid):
    return lg.rss_bytes(int(pid)) if pid else None


def _open_fds(pid):
    try:
        return len(list(pathlib.Path(f"/proc/{int(pid)}/fd").iterdir()))
    except (OSError, ValueError, TypeError):
        return None


def _client_rss():
    return lg.rss_bytes()


def _jvm_sample(jcmd, pid):
    result = {}
    for label, args in (("heap", ["GC.heap_info"]), ("native", ["VM.native_memory", "summary"])):
        try:
            proc = subprocess.run([jcmd, str(pid)] + args, capture_output=True, text=True, timeout=15)
            text = (proc.stdout or "") + (proc.stderr or "")
            result[label] = {"exitCode": proc.returncode, "raw": text[-4096:]}
            if label == "heap":
                result[label]["usedKb"] = _first_int(text, "used")
                result[label]["committedKb"] = _first_int(text, "committed")
            else:
                result[label]["reservedKb"] = _first_int(text, "reserved")
                result[label]["committedKb"] = _first_int(text, "committed")
        except (OSError, subprocess.SubprocessError) as exc:
            result[label] = {"error": repr(exc)}
    return result


def _first_int(text, key):
    import re
    match = re.search(key + r"[=: ]+(\d+)", text)
    return int(match.group(1)) if match else None


# --------------------------------------------------------------------------- external gate

def _counter_delta(previous, current):
    try:
        return int(current) - int(previous)
    except (TypeError, ValueError):
        return 0


def external_traffic_gate(samples, max_zero_fraction=EXTERNAL_ZERO_TRAFFIC_FRACTION,
                          max_consecutive_zero=EXTERNAL_MAX_CONSECUTIVE_ZERO):
    """Prove an external client actually exercised the server for the whole run.

    Per-interval load is the delta of the server text counters `cmd_get + cmd_set`
    between consecutive samples. The run is inadmissible when no operations were
    observed at all, when more than `max_zero_fraction` of the intervals show no
    traffic, or when more than `max_consecutive_zero` consecutive intervals show no
    traffic. At the 72-hour plan (60 s intervals) this bounds both the total client
    silence (<= 2 %, ~1.4 h) and any single outage (<= 5 intervals, 5 min). Intervals
    whose counters are missing are counted as empty. The gate is explicit and
    machine-readable so a COMPLETE report from the external mode cannot be produced
    without sustained cross-machine load.
    """
    reasons = []
    per_interval = []
    counters_present = 0
    for previous, current in zip(samples, samples[1:]):
        before, after = previous.get("stats") or {}, current.get("stats") or {}
        if "cmd_get" not in before or "cmd_set" not in before \
                or "cmd_get" not in after or "cmd_set" not in after:
            per_interval.append(None)  # unknown counters count as an empty interval
            continue
        counters_present += 1
        per_interval.append(max(0, _counter_delta(before.get("cmd_get"), after.get("cmd_get"))
                                + _counter_delta(before.get("cmd_set"), after.get("cmd_set"))))
    intervals = len(per_interval)
    total_operations = sum(value for value in per_interval if value)
    intervals_with_traffic = sum(1 for value in per_interval if value and value > 0)
    zero_intervals = intervals - intervals_with_traffic
    max_consecutive = 0
    run = 0
    for value in per_interval:
        if value and value > 0:
            run = 0
        else:
            run += 1
            max_consecutive = max(max_consecutive, run)
    if counters_present == 0:
        reasons.append("external_traffic_counters_missing")
    if total_operations <= 0:
        reasons.append("external_traffic_zero_operations")
    if intervals > 0 and zero_intervals > intervals * max_zero_fraction:
        reasons.append("external_traffic_absent_in_intervals")
    if max_consecutive > max_consecutive_zero:
        reasons.append("external_traffic_consecutive_gap")
    return {"valid": not reasons, "reasons": sorted(set(reasons)),
            "intervalsWithTraffic": intervals_with_traffic, "totalIntervals": intervals,
            "totalOperations": total_operations, "zeroTrafficIntervals": zero_intervals,
            "maxZeroTrafficFraction": max_zero_fraction,
            "maxConsecutiveZeroTrafficIntervals": max_consecutive,
            "consecutiveZeroLimit": max_consecutive_zero}


# --------------------------------------------------------------------------- runner

class SoakRunner:
    def __init__(self, output, plan, load_phase, monitor, bounded=False, resume_from=None, external=False):
        self.output = pathlib.Path(output)
        self.plan = plan
        self.load_phase = load_phase
        self.monitor = monitor
        self.bounded = bounded
        self.external = external
        self.resume_from = pathlib.Path(resume_from) if resume_from else None
        self.stop_event = threading.Event()
        self.state = {"phase": None, "phaseIndex": None}
        self.samples = []
        self.segments = []
        self.extra = []
        # Crash-durable sample stream: opened at run start, appended and flushed per
        # interval so the raw series survives a crash or power loss. On --resume the
        # retained samples are loaded here before appending so nothing is discarded.
        self._samples_stream = None
        self.resume_loaded_samples = 0
        self.resume_skipped_lines = 0
        self.resume_boundary_gap_seconds = None

    def _load_resumed_samples(self):
        """Load retained samples.jsonl lines before appending (resume only)."""
        path = self.output / "samples.jsonl"
        if not path.is_file():
            return
        loaded = skipped = 0
        with path.open("r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    self.samples.append(json.loads(line))
                    loaded += 1
                except ValueError:
                    skipped += 1  # a truncated trailing line from an abrupt stop
        self.resume_loaded_samples = loaded
        self.resume_skipped_lines = skipped

    def _open_samples_stream(self):
        self._load_resumed_samples()
        self._samples_stream = (self.output / "samples.jsonl").open("a", encoding="utf-8")

    def _close_samples_stream(self):
        if self._samples_stream is not None:
            try:
                self._samples_stream.flush()
                self._samples_stream.close()
            finally:
                self._samples_stream = None

    def _record_sample(self, sample):
        self.samples.append(sample)
        if self._samples_stream is not None:
            self._samples_stream.write(json.dumps(sample, default=str) + "\n")
            self._samples_stream.flush()

    def _sampler(self):
        interval = self.plan["intervalSeconds"]
        while not self.stop_event.is_set():
            sample = self.monitor.sample(self.state["phase"], self.state["phaseIndex"])
            if self.plan.get("collectExtra") and self.state["phaseIndex"] is not None \
                    and self.state["phaseIndex"] % max(1, self.plan.get("extraEveryPhases", 4)) == 0:
                try:
                    sample["extra"] = self.monitor.sample_stats_extra()
                except Exception as exc:  # noqa: BLE001
                    sample["extra"] = {"error": repr(exc)}
            self._record_sample(sample)
            time.sleep(interval)

    def run(self):
        self.output.mkdir(parents=True, exist_ok=True)
        self._open_samples_stream()
        planned = self.plan["plannedSeconds"]
        interval = self.plan["intervalSeconds"]
        phase_seconds = self.plan["phaseSeconds"]
        phases = self.plan.get("phases", DEFAULT_PHASES)
        started_mono, started_wall = time.monotonic(), time.time()
        sampler = threading.Thread(target=self._sampler, daemon=True)
        sampler.start()
        phase_index = 0
        try:
            if self.external:
                # External-client mode: the generator runs on another host and owns its
                # own phase loop. Here the server host only samples for the planned
                # duration; the interval/restart/gap logic is unchanged.
                while not self.stop_event.is_set():
                    elapsed = time.monotonic() - started_mono
                    if elapsed >= planned:
                        break
                    index = int(elapsed // max(1, phase_seconds))
                    name, ratio = phases[index % len(phases)]
                    self.state.update(phase=name, phaseIndex=index)
                    time.sleep(min(1.0, max(0.05, planned - elapsed)))
            else:
                while not self.stop_event.is_set():
                    elapsed = time.monotonic() - started_mono
                    if elapsed >= planned:
                        break
                    name, ratio = phases[phase_index % len(phases)]
                    duration = min(phase_seconds, max(0.001, planned - elapsed))
                    self.state.update(phase=name, phaseIndex=phase_index)
                    t0 = time.monotonic()
                    result = self.load_phase(phase_index, name, ratio, duration)
                    t1 = time.monotonic()
                    self.segments.append({"phaseIndex": phase_index, "phase": name, "ratio": ratio,
                                          "monotonicStart": t0, "monotonicEnd": t1, "seconds": t1 - t0,
                                          "wallStart": time.time() - (time.monotonic() - t0),
                                          "wallEnd": time.time(), "load": result})
                    phase_index += 1
                    if result.get("exitCode", 0) != 0 and not result.get("ignoreFailure"):
                        break
        finally:
            self.stop_event.set()
            sampler.join(timeout=max(5.0, 2 * interval))
            self._close_samples_stream()
        observed = time.monotonic() - started_mono
        report = self._build_report(started_mono, started_wall, observed)
        stem = "report" if not self.resume_from else f"report-resume-{int(time.time())}"
        (self.output / f"{stem}.json").write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")
        (self.output / "samples.jsonl").write_text(
            "\n".join(json.dumps(s, default=str) for s in self.samples) + "\n", encoding="utf-8")
        (self.output / "segments.json").write_text(json.dumps(self.segments, indent=2, default=str), encoding="utf-8")
        return report

    def _build_report(self, started_mono, started_wall, observed):
        interval = self.plan["intervalSeconds"]
        gaps = []
        stamps = [s["monotonic"] for s in self.samples if "monotonic" in s]
        for a, b in zip(stamps, stamps[1:]):
            if b - a > 3 * interval:
                gaps.append({"atMonotonic": b, "seconds": b - a})
        phase_boundaries = [(a["monotonicEnd"], b["monotonicStart"])
                            for a, b in zip(self.segments, self.segments[1:])]
        phase_gaps = [{"atMonotonic": end, "seconds": start - end}
                      for start, end in phase_boundaries if start - end > 3 * interval]
        restarts = list(self.monitor.restart_events)
        failures = [{"phaseIndex": s["phaseIndex"], "phase": s["phase"], "load": s["load"]}
                    for s in self.segments if s["load"].get("exitCode", 0) != 0]
        processes = sorted({s["stats"].get("pid") for s in self.samples if s.get("stats", {}).get("pid")})
        observed_seconds = observed
        if self.resume_from:
            # Merge the retained segment honestly: boundary gap, a restart marker for
            # the resume itself (so a resumed run can never claim a clean 72 h), an
            # observed time that adds the retained sampling span, and the earliest
            # wall start.
            loaded = self.samples[:self.resume_loaded_samples]
            fresh = self.samples[self.resume_loaded_samples:]
            if loaded and fresh and "wall" in loaded[-1] and "wall" in fresh[0]:
                gap = fresh[0]["wall"] - loaded[-1]["wall"]
                self.resume_boundary_gap_seconds = gap
                if gap > 3 * interval:
                    gaps.append({"atWall": fresh[0]["wall"], "seconds": gap, "reason": "resume_boundary"})
            restarts.insert(0, {"reason": "resumed run", "resumedFrom": str(self.resume_from),
                                "boundaryGapSeconds": self.resume_boundary_gap_seconds})
            retained_wall = [s["wall"] for s in loaded if "wall" in s]
            if len(retained_wall) >= 2:
                observed_seconds = (retained_wall[-1] - retained_wall[0]) + observed
            if self.samples and "wall" in self.samples[0]:
                started_wall = self.samples[0]["wall"]
        if self.bounded:
            status = "BOUNDED_COMPLETE" if not failures and not gaps and not phase_gaps and not restarts else "INTERRUPTED"
        elif observed_seconds < self.plan["plannedSeconds"] * (1 - g.SOAK_COMPLETENESS_TOLERANCE):
            status = "INTERRUPTED"
        elif failures or gaps or phase_gaps or restarts:
            status = "INTERRUPTED"
        else:
            status = "COMPLETE"
        report = {
            "schemaVersion": 1,
            "kind": "soak",
            "bounded": self.bounded,
            "server": self.plan.get("server"),
            "plannedSeconds": self.plan["plannedSeconds"],
            "observedSeconds": observed_seconds,
            "intervalSeconds": interval,
            "phaseSeconds": self.plan["phaseSeconds"],
            "phases": [p[0] for p in self.plan.get("phases", DEFAULT_PHASES)],
            "startedWall": started_wall,
            "finishedWall": time.time(),
            "sampleCount": len(self.samples),
            "phaseCount": len(self.segments),
            "segments": self.segments,
            "gaps": gaps,
            "phaseBoundaryGaps": phase_gaps,
            "restartCount": len(restarts),
            "restarts": restarts,
            "observedServerPids": processes,
            "failedPhases": failures,
            "status": status,
            "resumedFrom": str(self.resume_from) if self.resume_from else None,
            "plannedSeconds72h": PLANNED_SECONDS_72H,
            "sixtyTwelveHourClaim": (not self.bounded and status == "COMPLETE"
                                     and observed_seconds >= PLANNED_SECONDS_72H * (1 - g.SOAK_COMPLETENESS_TOLERANCE)),
            "note": "BOUNDED_COMPLETE is a local verification, not the mandatory 72-hour run",
        }
        if self.resume_from:
            report["resumeLoadedSamples"] = self.resume_loaded_samples
            report["resumeSkippedLines"] = self.resume_skipped_lines
            report["resumeBoundaryGapSeconds"] = self.resume_boundary_gap_seconds
        external_gate = None
        if self.external:
            external_gate = external_traffic_gate(self.samples)
            report["localPhaseLoopSkipped"] = True
            report["externalTrafficGate"] = external_gate
            report["externalClient"] = self.plan.get("externalClient")
            if not external_gate["valid"] and report["status"] in ("COMPLETE", "BOUNDED_COMPLETE"):
                report["status"] = "INTERRUPTED"
            report["sixtyTwelveHourClaim"] = (not self.bounded and report["status"] == "COMPLETE"
                                              and observed_seconds >= PLANNED_SECONDS_72H * (1 - g.SOAK_COMPLETENESS_TOLERANCE))
        report["completenessGate"] = g.soak_completeness(report, self.plan["plannedSeconds"], interval)
        if external_gate is not None and not external_gate["valid"]:
            reasons = set(report["completenessGate"].get("reasons", []))
            reasons.update("externalTrafficGate:" + r for r in external_gate["reasons"])
            report["completenessGate"]["reasons"] = sorted(reasons)
            report["completenessGate"]["valid"] = False
        return report


# --------------------------------------------------------------------------- real server

def start_server(server, java, jar, reference, memory_mib, workers, port, output, listen_host=LOCAL_HOST):
    output = pathlib.Path(output)
    if server == "reference":
        argv = [reference, "-u", "root", "-l", listen_host, "-p", str(port), "-U", "0",
                "-m", str(memory_mib), "-I", "1m", "-c", "12000", "-t", str(workers)]
    else:
        argv = [java, "-Xms32m", f"-Xmx{memory_mib}m", f"-XX:ActiveProcessorCount={workers}",
                "-XX:NativeMemoryTracking=summary",
                f"-Xlog:gc*:file={output / 'server-gc.log'}:time,uptime:filecount=5,filesize=20M",
                "--enable-native-access=ALL-UNNAMED", "-jar", jar,
                f"--listen.address={listen_host}", f"--listen.port={port}", f"--memory.limit={memory_mib}m",
                "--item.maxSize=1m", "--connections.max=12000", f"--transport.workers={workers}",
                "--connections.idleTimeoutSeconds=0", "--log.level=error"]
    log = (output / (server + ".server.log")).open("a", encoding="utf-8")
    proc = subprocess.Popen(argv, stdout=log, stderr=log)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("soak server exited during startup")
        try:
            # Local liveness probe stays on loopback; it also works when the server
            # binds a wildcard address for an external client.
            wire_version(LOCAL_HOST, port)
            return proc, log
        except (OSError, ConnectionError):
            time.sleep(0.2)
    raise TimeoutError("soak server startup deadline")


def memtier_phase(client, port, ratio, seconds, artifact):
    argv = [client, "--server=127.0.0.1", f"--port={port}", "--protocol=memcache_text",
            f"--threads={PHASE_THREADS}", f"--clients={max(1, 32 // PHASE_THREADS)}", f"--ratio={ratio}",
            f"--pipeline={PHASE_PIPELINE}", f"--data-size={PHASE_DATA_SIZE}", "--key-minimum=1",
            f"--key-maximum={PHASE_KEYS}", "--key-prefix=memtier-", "--key-pattern=R:R",
            f"--test-time={max(1, int(seconds))}", "--print-percentiles=50,95,99", "--hide-histogram",
            "--json-out-file=" + str(artifact)]
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=seconds + 60)
    return {"argv": argv, "exitCode": proc.returncode, "artifact": pathlib.Path(artifact).name,
            "stdoutTail": (proc.stdout or "")[-2000:], "stderrTail": (proc.stderr or "")[-2000:]}


# --------------------------------------------------------------------------- CLI

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", choices=("octomem", "reference"), required=True)
    parser.add_argument("--output-parent", type=pathlib.Path, required=True)
    parser.add_argument("--java")
    parser.add_argument("--java-version")
    parser.add_argument("--jar")
    parser.add_argument("--expected-jar-sha256")
    parser.add_argument("--reference")
    parser.add_argument("--client")
    parser.add_argument("--jcmd")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--memory-mib", type=int, default=256)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--planned-seconds", type=int, default=PLANNED_SECONDS_72H)
    parser.add_argument("--interval-seconds", type=int, default=60)
    parser.add_argument("--phase-seconds", type=int, default=300)
    parser.add_argument("--data-size", type=int, default=PHASE_DATA_SIZE)
    parser.add_argument("--listen-host", default=LOCAL_HOST)
    parser.add_argument("--external-client-host", default=None)
    parser.add_argument("--resume", type=pathlib.Path, default=None)
    parser.add_argument("--bounded-seconds", type=int, default=None)
    parser.add_argument("--approved-run", action="store_true")
    args = parser.parse_args(argv)
    if not args.approved_run:
        parser.error("the soak runner requires explicit approval; no run started")
    listen_host = args.listen_host
    listen_host_auto_set = False
    if args.external_client_host and listen_host not in WILDCARD_HOSTS:
        # An external client cannot reach a loopback-only bind; auto-set the wildcard
        # and record that it was changed rather than silently rejecting the run.
        listen_host = "0.0.0.0"
        args.listen_host = listen_host
        listen_host_auto_set = True
    required = ["reference"] + (["java", "jar"] if args.server == "octomem" else [])
    if not args.external_client_host:
        # The local memtier phase loop is skipped when the client is external, so the
        # memtier path is not required for that mode.
        required.append("client")
    for name in required:
        value = getattr(args, name)
        if not value or not pathlib.Path(value).is_absolute() or not pathlib.Path(value).is_file():
            parser.error("explicit existing absolute path required: --" + name)
    if args.client and (not pathlib.Path(args.client).is_absolute() or not pathlib.Path(args.client).is_file()):
        parser.error("explicit existing absolute path required: --client")
    if args.server == "octomem" and args.expected_jar_sha256 \
            and lg.sha256_file(args.jar).upper() != args.expected_jar_sha256.upper():
        parser.error("candidate JAR pin mismatch")
    if args.bounded_seconds is None and args.planned_seconds < PLANNED_SECONDS_72H:
        parser.error("the mandatory soak is 72 hours; use --bounded-seconds for a local verification")
    if args.resume is not None:
        # Continue the interrupted attempt in place so retained samples.jsonl lines are
        # kept; never clobber the original plan.json (write a tagged resume plan).
        out = args.resume
        if not out.is_dir():
            parser.error("--resume directory does not exist: " + str(out))
        plan_file = out / f"plan-resume-{int(time.time())}.json"
    else:
        out = args.output_parent / ("soak-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
        out.mkdir(exist_ok=False)
        plan_file = out / "plan.json"
    plan_file.write_text(json.dumps({k: (str(v) if isinstance(v, pathlib.Path) else v)
                                     for k, v in vars(args).items()}, indent=2), encoding="utf-8")
    proc, log = start_server(args.server, args.java, args.jar, args.reference,
                             args.memory_mib, args.workers, args.port, out, listen_host=listen_host)
    monitor = ServerMonitor(LOCAL_HOST, args.port, pid=proc.pid, jcmd=args.jcmd, start_wall=time.time(),
                            external=bool(args.external_client_host))
    planned = args.bounded_seconds or args.planned_seconds
    plan = {"plannedSeconds": planned, "intervalSeconds": args.interval_seconds,
            "phaseSeconds": args.phase_seconds, "phases": DEFAULT_PHASES,
            "server": args.server, "collectExtra": True}
    if args.external_client_host:
        plan["externalClient"] = {
            "host": args.external_client_host, "mode": "external",
            "listenHost": listen_host, "listenHostAutoSet": listen_host_auto_set,
            "localClientRss": False,
            "note": "phase artifacts are produced on the generator host and collected separately",
        }
    lg.preload(LOCAL_HOST, args.port, PHASE_KEYS, args.data_size)
    try:
        runner = SoakRunner(out, plan,
                            lambda index, name, ratio, seconds: memtier_phase(
                                args.client, args.port, ratio, seconds, out / f"phase-{index:04d}.memtier.json"),
                            monitor, bounded=args.bounded_seconds is not None, resume_from=args.resume,
                            external=bool(args.external_client_host))
        report = runner.run()
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(15)
            except subprocess.TimeoutExpired:
                proc.kill()
        log.close()
    (out / "complete.json").write_text(json.dumps({
        "completed": report["status"] in ("COMPLETE", "BOUNDED_COMPLETE"),
        "status": report["status"], "bounded": report["bounded"],
        "observedSeconds": report["observedSeconds"], "plannedSeconds": report["plannedSeconds"],
        "sixtyTwelveHourClaim": report["sixtyTwelveHourClaim"],
        "artifacts": sorted(p.name for p in out.iterdir() if p.is_file())}, indent=2), encoding="utf-8")
    print(str(out))
    print(f"status={report['status']} observed={report['observedSeconds']:.0f}s planned={report['plannedSeconds']}s")
    return 0 if report["status"] in ("COMPLETE", "BOUNDED_COMPLETE") else 1


if __name__ == "__main__":
    raise SystemExit(main())
