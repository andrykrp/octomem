#!/usr/bin/env python3
"""V3 TWO-MACHINE MEMTIER RUNNER under a frozen predeclared contract.

Collects M1/M2/M3 rows for a NON-CO-RESIDENT client (server on one host, memtier
on another) WITHOUT changing the estimator, the workload, the scenario set, the
pairs/warmup/alternation plan, the window/validity rules or any M1/M2/M3
threshold. This runner ONLY COLLECTS rows: every verdict is computed by
`tests/platform/perf/v3/{finalize,contracts,gates}.py`, never here. There is no
runner-local gate or threshold logic; the command plan and scenario definitions
are taken verbatim from the v3 core (`run.server_argv` / `run.client_argv` /
`contracts.workload`).

The runner is intended to execute on the CLIENT host (where memtier lives) and
control the SERVER host over ssh. It is import-safe (all I/O under `main`), and
the pure helpers (`precondition_reasons`, `build_server_argv`, `build_client_argv`,
`build_row`) are unit-tested with mock transports.

See tests/reports/release/perf-qual/TWO-MACHINE-CONTRACT.md.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid

try:
    from . import contracts as c
except ImportError:
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import contracts as c

try:
    from . import generator as gen
except ImportError:
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import generator as gen

try:
    from . import run as r
except ImportError:
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import run as r

PORT = getattr(r, "PORT", 11219)

# Frozen campaign pins (identity preconditions).
PINNED_IDENTITY = {
    "jar": "950a8edb702e51c9979320c117257426f1109076845e80f30774ef0855ff682b",
    "reference": "3b3d42501043b5c6eb58c7824186409413a5d0d4fcf70addeae35f549252d525",
    "java": "7380ce48ed5013735d2c8414db54adb8f981e7933ff594bd36f3baccddaafba3",
    "memtier": "e43542837cc903953371824bc4e14da589ffb717d542f685ed1f93bbf13bdbff",
}
REQUIRED_JAVA_VERSION = "25.0.4.1"
REQUIRED_MT_VERSION = "2.2.0"
FULL_PLAN_SCENARIOS = tuple(c.SCENARIOS)


# --------------------------------------------------------------------- helpers
def sha256_file(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def effective_thresholds():
    return dict(c.THRESHOLDS)


def effective_window():
    return dict(c.WINDOW_CONFIG)


def effective_scenarios():
    return dict(c.SCENARIOS)


def qualification_claim(official_campaign, full, non_qualifying):
    """The qualification marker the frozen reader keys on (`passport.qualification`).

    It is declared True ONLY for a full plan, explicitly declared official/approved
    run that is not a non-qualifying rehearsal. This is a declaration, not a
    self-sufficient qualification: the reader still independently enforces the
    attempt seal (complete.json), the balanced pair plan, calibration and identity,
    so an unsealed, incomplete or analysis-only attempt cannot PASS on the marker
    alone. The scenario/threshold decision is unchanged.
    """
    return bool(official_campaign and full and not non_qualifying)


# ------------------------------------------------- transport: command plans
def build_server_argv(server, paths, memory, server_cpus, address):
    """v3 command plan with the declared bind address (transport only)."""
    return r.server_argv(server, paths["java"], paths["reference"], paths["jar"], memory, server_cpus, address)


def build_client_argv(client, out, label, w, seconds, client_cpus, address):
    """v3 command plan with the declared server address (transport only)."""
    return r.client_argv(client, out, label, w, seconds, client_cpus, address)


# ------------------------------------------------- fail-closed preconditions
def precondition_reasons(facts):
    """Return every unmet precondition; empty means the run may proceed."""
    reasons = []
    if not facts.get("server_host_reachable"):
        reasons.append("server_host_unreachable")
    if not facts.get("client_self_reachable"):
        reasons.append("client_host_unreachable")
    if not facts.get("library_dir_present"):
        reasons.append("library_dir_missing")
    if not facts.get("memtier_with_lib_ok"):
        reasons.append("memtier_not_usable_with_library_path")
    if facts.get("memtier_without_lib_ok"):
        reasons.append("memtier_usable_without_library_path_unexpected")
    if not facts.get("protocol_present"):
        reasons.append("protocol_missing")
    if not facts.get("protocol_frozen_before_collection"):
        reasons.append("protocol_not_frozen_before_collection")
    if facts.get("protocol_misdated"):
        reasons.append("protocol_misdated")
    if facts.get("protocol_hash_mismatch"):
        reasons.append("protocol_hash_mismatch")
    if facts.get("identity_mismatch_key"):
        reasons.append("identity_mismatch:" + facts["identity_mismatch_key"])
    if facts.get("java_version") and facts["java_version"] != REQUIRED_JAVA_VERSION:
        reasons.append("java_version_mismatch")
    if facts.get("memtier_version") and facts["memtier_version"] != REQUIRED_MT_VERSION:
        reasons.append("memtier_version_mismatch")
    if facts.get("harness_hash_mismatch"):
        reasons.append("harness_hash_mismatch:" + facts["harness_hash_mismatch"])
    if facts.get("server_port_occupied"):
        reasons.append("server_port_occupied")
    if facts.get("service_cgroup_scope_missing"):
        reasons.append("service_cgroup_scope_missing")
    if facts.get("server_port_unreachable_from_client"):
        reasons.append("server_port_unreachable_from_client")
    for kind in facts.get("unsupported_kinds", []):
        reasons.append("scenario_kind_unsupported:" + kind)
    if facts.get("plan") == "full" and not facts.get("official_campaign_declared"):
        reasons.append("official_campaign_not_declared")
    return sorted(set(reasons))


def preconditions_met(facts):
    return not precondition_reasons(facts)


# ------------------------------------------------- row construction (pure)
def build_row(scenario, rep, server, identity, passport, *, process, preload, samples,
              rss_window, total_rss, stats_before, stats_after, cgroup_before, cgroup_after,
              artifact, server_argv_list, client_argv_list, exit_code=0, timed_out=False,
              generator_cpu=None, generator_headroom=None, warmup=None, peak_client_thread_cpu=None,
              forced_gc=False, performance_qualification=False, wall_start=None, wall_end=None,
              cycles_seconds=None, validated_cycles=None, cycle_errors=None, unreaped_workers=None,
              client_engine=None, error=None):
    """Build one run row byte-compatible with the v3/pinned reader."""
    w = c.workload(scenario)
    order = ["reference", "octomem"] if rep % 2 == 0 else ["octomem", "reference"]
    row = {
        "scenario": scenario, "rep": rep, "server": server,
        "identity": dict(identity), "workload": w,
        "pairOrder": order, "orderIndex": order.index(server),
        "performanceQualification": bool(performance_qualification),
        "windowConfig": dict(c.WINDOW_CONFIG),
        "forcedGcDuringMeasurement": bool(forced_gc),
        "expectedServerArgv": list(server_argv_list),
        "process": process,
        "preload": preload,
        "argv": list(client_argv_list),
        "expectedClientArgv": list(client_argv_list),
        "statsBefore": stats_before, "statsAfter": stats_after,
        "cgroupBefore": cgroup_before, "cgroupAfter": cgroup_after,
        "samples": samples, "rssWindow": rss_window, "total_rss": total_rss,
        "exitCode": int(exit_code), "timedOut": bool(timed_out),
        "artifact": artifact,
    }
    if generator_cpu is not None:
        row["generatorCpu"] = generator_cpu
    if generator_headroom is not None:
        row["generatorHeadroom"] = generator_headroom
    if warmup is not None:
        row["warmup"] = warmup
    if peak_client_thread_cpu is not None:
        row["peakClientThreadCpu"] = peak_client_thread_cpu
    if cycles_seconds is not None:
        row["cyclesSeconds"] = cycles_seconds
        row["validatedCycles"] = validated_cycles
        row["cycleErrors"] = cycle_errors if cycle_errors is not None else []
        row["unreapedWorkers"] = unreaped_workers if unreaped_workers is not None else []
    if client_engine is not None:
        row["clientEngine"] = client_engine
    if error is not None:
        row["error"] = error
    if wall_start is not None:
        row["wallStart"] = wall_start
    if wall_end is not None:
        row["wallEnd"] = wall_end
    return row


# ------------------------------------------------- local / remote primitives
def local_run(argv, timeout=60, env=None, input_text=None):
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout,
                          env=env, input=input_text)


def ssh_run(host, key, command, timeout=60, input_text=None):
    argv = ["ssh", "-i", key, "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "StrictHostKeyChecking=accept-new", host, command]
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout, input=input_text)


def ssh_write(host, key, remote_path, content):
    return ssh_run(host, key, "cat > " + remote_path, input_text=content)


def ssh_sha(host, key, remote_path):
    result = ssh_run(host, key, "sha256sum " + remote_path)
    if result.returncode != 0:
        return None
    return result.stdout.strip().split()[0].lower()


def _mc_request(host, port, payload, timeout=5.0):
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(payload)
        data = b""
        while True:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            data += chunk
            if payload.startswith(b"stats") and data.endswith(b"END\r\n"):
                break
            if payload.startswith(b"version") and data.endswith(b"\r\n"):
                break
        return data


def read_stats(host, port):
    raw = _mc_request(host, port, b"stats\r\n")
    out = {}
    for line in raw.split(b"\r\n"):
        if line.startswith(b"STAT "):
            parts = line.split(b" ", 2)
            if len(parts) == 3:
                out[parts[1].decode("ascii")] = parts[2].decode("ascii")
    return out


def read_version(host, port):
    return _mc_request(host, port, b"version\r\n").decode("ascii", "replace").strip()


def _store_one(host, port, key, size, flags=0, readback=False):
    """Two-machine equivalent of run.store_one: set a key against the server host.

    Same evidence contract: {valueBytes, response} plus readbackVerified and
    payloadSha256 when readback is requested. Uses a bounded buffered reader so a
    frame read never over-reads into the next frame's body.
    """
    data = b"x" * size
    with socket.create_connection((host, port), timeout=10) as sock:
        sock.settimeout(10)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        buf = bytearray()

        def read_until_crlf():
            while b"\r\n" not in buf:
                chunk = sock.recv(65536)
                if not chunk:
                    raise EOFError("response EOF")
                buf.extend(chunk)
            end = buf.index(b"\r\n") + 2
            frame = bytes(buf[:end])
            del buf[:end]
            return frame

        def read_exact(length):
            while len(buf) < length:
                chunk = sock.recv(65536)
                if not chunk:
                    raise EOFError("response EOF")
                buf.extend(chunk)
            frame = bytes(buf[:length])
            del buf[:length]
            return frame

        sock.sendall(f"set {key} {flags} 0 {size}\r\n".encode() + data + b"\r\n")
        response = read_until_crlf().decode("ascii").strip()
        result = {"valueBytes": size, "response": response}
        if readback and response == "STORED":
            sock.sendall(f"get {key}\r\n".encode())
            header = read_until_crlf()
            if header != f"VALUE {key} {flags} {size}\r\n".encode():
                raise ValueError("calibration readback header mismatch: got %r expected %r"
                                 % (header, f"VALUE {key} {flags} {size}\r\n".encode()))
            body = read_exact(size + 2)
            if body != data + b"\r\n" or read_until_crlf() != b"END\r\n":
                raise ValueError("calibration readback content mismatch")
            result.update(readbackVerified=True, payloadSha256=hashlib.sha256(body[:-2]).hexdigest())
        return result


def local_ticks(pid):
    result = {}
    try:
        for path in pathlib.Path(f"/proc/{pid}/task").glob("*/stat"):
            try:
                fields = path.read_text().rsplit(")", 1)[1].split()
                result[path.parent.name] = int(fields[11]) + int(fields[12])
            except (OSError, IndexError, ValueError):
                continue
    except OSError:
        return {}
    return result


def local_rss(pid):
    return int(pathlib.Path(f"/proc/{pid}/statm").read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")


SERVER_PROBE = r'''
import json, os, sys
sys.path.insert(0, sys.argv[1])
import run as r
pid = int(sys.argv[2])
with_cgroup = (len(sys.argv) > 3 and sys.argv[3] == "cgroup")
out = {"clock": os.sysconf("SC_CLK_TCK")}
try:
    out["ticks"] = r.cpu_ticks(pid)
except Exception as exc:  # noqa: BLE001
    out["ticksError"] = repr(exc)
try:
    out["rss"] = r.rss(pid)
except Exception as exc:  # noqa: BLE001
    out["rssError"] = repr(exc)
if with_cgroup:
    try:
        out["cgroup"] = r.cgroup_snapshot(pid)
    except Exception as exc:  # noqa: BLE001
        out["cgroupError"] = repr(exc)
print(json.dumps(out))
'''

SERVER_TOPOLOGY = r'''
import json, sys
sys.path.insert(0, sys.argv[1])
import run as r
print(json.dumps(r.topology_snapshot()))
'''

SERVER_LAUNCH = r'''
import json, subprocess, sys
argv = json.load(open(sys.argv[1]))
with open(sys.argv[2], "ab", 0) as log:
    p = subprocess.Popen(argv, stdout=log, stderr=log, stdin=subprocess.DEVNULL,
                         start_new_session=True)
print(p.pid)
'''

# Remote processes on these stands are reaped when their ssh session closes, so the
# server is started from a session we HOLD OPEN for the whole row: the session prints
# the pid, then blocks on stdin; closing our stdin stops the server.
SERVER_SESSION = r'''#!/bin/bash
set -u
PID=$(python3 "$STAGE/server_launch.py" "$ARGV" "$LOG")
echo "PID=$PID"
cat > /dev/null
kill "$PID" 2>/dev/null
sleep 1
kill -9 "$PID" 2>/dev/null
'''


def remote_probe(host, key, stage, pid, with_cgroup=False):
    cmd = f"python3 {stage}/server_probe.py {stage} {pid}" + (" cgroup" if with_cgroup else "")
    result = ssh_run(host, key, cmd, timeout=30)
    if result.returncode != 0:
        return {"error": result.stderr.strip() or "probe_failed"}
    try:
        return json.loads(result.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {"error": "probe_unparsable"}


def start_server_session(host, key, stage, server_argv_list, log_path, argv_name):
    """Start the server inside a HELD-OPEN ssh session and return (proc, pid)."""
    argv_path = f"{stage}/{argv_name}.argv.json"
    ssh_write(host, key, argv_path, json.dumps(server_argv_list))
    command = f"STAGE={stage} ARGV={argv_path} LOG={log_path} bash {stage}/server_session.sh"
    proc = subprocess.Popen(["ssh", "-i", key, "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
                             "-o", "StrictHostKeyChecking=accept-new", host, command],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    pid = None
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        line = proc.stdout.readline()
        if not line:
            break
        line = line.strip()
        if line.startswith("PID="):
            try:
                pid = int(line.split("=", 1)[1])
            except ValueError:
                pid = None
            break
    return proc, pid


def stop_server_session(host, key, proc, pid):
    try:
        proc.stdin.close()
    except (OSError, ValueError):
        pass
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    if pid:
        ssh_run(host, key, f"kill -9 {pid} 2>/dev/null; true", timeout=20)


def remote_stage(host, key, stage, files):
    ssh_run(host, key, f"mkdir -p {stage}")
    for name, content in files.items():
        ssh_write(host, key, f"{stage}/{name}", content)
    ssh_write(host, key, f"{stage}/server_probe.py", SERVER_PROBE)
    ssh_write(host, key, f"{stage}/server_topology.py", SERVER_TOPOLOGY)
    ssh_write(host, key, f"{stage}/server_launch.py", SERVER_LAUNCH)
    ssh_write(host, key, f"{stage}/server_session.sh", SERVER_SESSION)


# ------------------------------------------------- headroom (client host)
def _headroom_argv(client, tmpdir, label, w, seconds, client_cpus, echo_port):
    argv = r.client_argv(client, pathlib.Path(tmpdir), label, w, seconds, client_cpus, "127.0.0.1")
    return [f"--port={echo_port}" if item.startswith("--port=") else item for item in argv]


def measure_headroom(client, lib_dir, client_cpus, w, seconds, tmpdir, achieved_ops):
    """Measure the memtier generator ceiling for this shape on the client host.

    Uses the v3 EchoSink (a bounded RESPONDING loopback sink) so the measurement
    exercises real request/response round trips. `source` is `row-measured`, never
    a discard-sink extrapolation (v3 defect (d)).
    """
    sink = gen.EchoSink().start()
    seconds = max(1, int(seconds))  # memtier requires an integer --test-time
    try:
        label = "headroom-" + uuid.uuid4().hex[:8]
        argv = _headroom_argv(client, tmpdir, label, w, seconds, client_cpus, sink.port)
        env = dict(os.environ)
        env["LD_LIBRARY_PATH"] = lib_dir
        result = local_run(argv, timeout=int(seconds) + 60, env=env)
        out_path = pathlib.Path(tmpdir) / (label + ".memtier.json")
        if result.returncode != 0 or not out_path.is_file():
            return None
        data = json.loads(out_path.read_text(encoding="utf-8"))
        ceiling = data["ALL STATS"]["Totals"]["Ops/sec"]
        if not c.positive(ceiling) or not c.positive(achieved_ops):
            return None
        return {"source": "row-measured", "method": "memtier-responding-echo",
                "ceilingOpsPerSecond": ceiling, "achievedOpsPerSecond": achieved_ops,
                "windowSeconds": float(seconds), "echoPort": sink.port,
                "probeArtifact": {"name": out_path.name, "sha256": sha256_file(out_path)},
                "note": "memtier ceiling measured against a responding loopback echo on the client host"}
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        return None
    finally:
        sink.stop()


# --------------------------------------------------------------------- main
def _probe_facts(args):
    facts = {"plan": "rehearsal" if args.non_qualifying else "full"}
    facts["client_self_reachable"] = sys.platform.startswith("linux")
    facts["library_dir_present"] = args.lib_dir and pathlib.Path(args.lib_dir).is_dir()
    # memtier must run ONLY with the library path.
    if pathlib.Path(args.client).is_file():
        ok_env = dict(os.environ)
        ok_env["LD_LIBRARY_PATH"] = args.lib_dir or ""
        with_lib = local_run([args.client, "--version"], timeout=20, env=ok_env)
        facts["memtier_with_lib_ok"] = with_lib.returncode == 0
        facts["memtier_version"] = (REQUIRED_MT_VERSION if REQUIRED_MT_VERSION in (with_lib.stdout + with_lib.stderr) else None)
        no_env = dict(os.environ)
        no_env.pop("LD_LIBRARY_PATH", None)
        without_lib = local_run([args.client, "--version"], timeout=20, env=no_env)
        facts["memtier_without_lib_ok"] = without_lib.returncode == 0
    else:
        facts["memtier_with_lib_ok"] = False
    reach = ssh_run(args.server_host, args.ssh_key, "true", timeout=20)
    facts["server_host_reachable"] = reach.returncode == 0
    if facts["server_host_reachable"]:
        j = ssh_sha(args.server_host, args.ssh_key, args.jar)
        ref = ssh_sha(args.server_host, args.ssh_key, args.reference)
        java = ssh_sha(args.server_host, args.ssh_key, args.java)
        if j and j != PINNED_IDENTITY["jar"]:
            facts["identity_mismatch_key"] = "jar"
        if ref and ref != PINNED_IDENTITY["reference"]:
            facts["identity_mismatch_key"] = facts.get("identity_mismatch_key") or "reference"
        if java and java != PINNED_IDENTITY["java"]:
            facts["identity_mismatch_key"] = facts.get("identity_mismatch_key") or "java"
        if j is None:
            facts["identity_mismatch_key"] = facts.get("identity_mismatch_key") or "jar"
        ver = ssh_run(args.server_host, args.ssh_key, args.java + " -version 2>&1", timeout=20)
        for line in (ver.stdout + ver.stderr).splitlines():
            if "version" in line and '"' in line:
                facts["java_version"] = line.split('"')[1]
                break
    mt = sha256_file(args.client) if pathlib.Path(args.client).is_file() else None
    if mt and mt != PINNED_IDENTITY["memtier"]:
        facts["identity_mismatch_key"] = facts.get("identity_mismatch_key") or "memtier"
    # protocol
    if args.protocol and pathlib.Path(args.protocol).is_file():
        facts["protocol_present"] = True
        doc = json.loads(pathlib.Path(args.protocol).read_text(encoding="utf-8"))
        # F5.2: protocol_identity now fails closed without an expected hash, so the
        # preflight supplies the declared hash for the timing check.
        probe_doc = dict(doc)
        if args.expected_protocol_sha256:
            probe_doc["sha256"] = args.expected_protocol_sha256
        facts["protocol_misdated"] = not c.protocol_identity(probe_doc, utc_now(), args.expected_protocol_sha256)["valid"]
        facts["protocol_frozen_before_collection"] = doc.get("frozenBeforeCollection") is True
        if args.expected_protocol_sha256 and sha256_file(args.protocol) != args.expected_protocol_sha256:
            facts["protocol_hash_mismatch"] = True
    else:
        facts["protocol_present"] = False
    # harness hashes
    if args.expected_harness_json:
        expected = json.loads(pathlib.Path(args.expected_harness_json).read_text(encoding="utf-8"))
        here = pathlib.Path(__file__).resolve().parent
        for name, digest in expected.items():
            path = here / name
            if not path.is_file() or sha256_file(path) != digest:
                facts["harness_hash_mismatch"] = name
                break
    kinds = {c.workload(s)["kind"] for s in args.scenarios}
    # memtier, memory (M3-*) and cycles (P07) are implemented; anything else refuses.
    facts["unsupported_kinds"] = sorted(k for k in kinds if k not in ("memtier", "memory", "cycles"))
    # port check from client host
    try:
        _mc_request(args.server_ip, args.port, b"version\r\n", timeout=1.0)
        facts["server_port_occupied"] = True
    except OSError:
        facts["server_port_occupied"] = False
    return facts


def preflight_reachability(args, server_argv_list, stage):
    """Start a throwaway reference server and confirm the CLIENT can reach its port.

    Fail-closed: an unreachable server port refuses the whole attempt fast instead
    of hanging every row.
    """
    proc, pid = start_server_session(args.server_host, args.ssh_key, stage, server_argv_list,
                                     f"{stage}/preflight.server.log", "preflight")
    try:
        if pid is None:
            return False
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                if read_version(args.server_ip, args.port):
                    return True
            except OSError:
                pass
            time.sleep(0.3)
        return False
    finally:
        stop_server_session(args.server_host, args.ssh_key, proc, pid)


def _write_not_run(output_parent, reasons, facts):
    pathlib.Path(output_parent).mkdir(parents=True, exist_ok=True)
    payload = {"outcome": "NOT_RUN", "utc": utc_now(), "reasons": reasons,
               "facts": {k: v for k, v in facts.items() if k != "identity"}}
    path = pathlib.Path(output_parent) / ("NOT_RUN-" + uuid.uuid4().hex + ".json")
    path.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    print("NOT_RUN " + ",".join(reasons))
    print(str(path))
    return 2


def _collect_memtier_row(args, passport, identity, scenario, rep, server, stage, tmpdir):
    w = c.workload(scenario)
    label = f"{scenario}-r{rep}-{server}"
    attempt_out = pathlib.Path(passport["collectorOutput"])
    server_argv_list = build_server_argv(server, passport["paths"], w["memoryMiB"], args.server_cpus, args.server_ip)
    client_argv_list = build_client_argv(args.client, attempt_out, label, w,
                                         c.WINDOW_CONFIG["measurementSeconds"], args.client_cpus, args.server_ip)
    log_path = f"{stage}/{label}.server.log"
    server_proc, pid = start_server_session(args.server_host, args.ssh_key, stage, server_argv_list, log_path, label)
    row = {"scenario": scenario, "rep": rep, "server": server}
    try:
        if pid is None:
            raise RuntimeError("server session pid missing")
        # wait for version
        version = None
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                version = read_version(args.server_ip, args.port)
                if version:
                    break
            except OSError:
                time.sleep(0.2)
        if not version:
            raise RuntimeError("server version deadline")
        print(f"  {label} version={version}", flush=True)
        identity[server + "Version"] = version
        process = {"argv": server_argv_list, "startedWall": time.time(), "pid": pid, "version": version}
        # preload on the client host against the server
        if w["count"]:
            preload = gen.preload(args.server_ip, args.port, w["count"], w["size"], workers=8,
                                  connections_per_worker=4, pipeline=32, timeout=120.0)
        else:
            preload = {"requested": 0, "stored": 0, "response": "STORED"}
        print(f"  {label} preload={preload.get('stored')}/{preload.get('requested')} {preload.get('response')}", flush=True)
        # warmup (5s, not sampled)
        warm_argv = build_client_argv(args.client, attempt_out, label + "-warmup", w, 5,
                                      args.client_cpus, args.server_ip)
        env = dict(os.environ); env["LD_LIBRARY_PATH"] = args.lib_dir
        warm = local_run(warm_argv, timeout=90, env=env)
        warmup = {"exitCode": warm.returncode, "timedOut": False, "qualifiedAsWarm": False}
        if warm.returncode:
            raise RuntimeError("warmup client failed")
        print(f"  {label} warmup_exit={warm.returncode}", flush=True)
        # measured run with synchronous sampling
        stats_before = read_stats(args.server_ip, args.port)
        cg_before = remote_probe(args.server_host, args.ssh_key, stage, pid, with_cgroup=True).get("cgroup")
        wall_start = time.time()
        proc = subprocess.Popen(client_argv_list, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=env, text=True)
        samples = []
        clk = os.sysconf("SC_CLK_TCK")
        # Seed the client baseline once its thread set is stable, so the baseline
        # contains every thread the measured samples will see (no ssh round-trip in
        # between, and no fabricated ticks - only a wait). The server baseline is
        # seeded on the first in-loop probe instead.
        prev_client = _stable_client_ticks(proc.pid)
        prev_server = None
        tick_time = time.monotonic()
        peak = 0.0
        deadline = tick_time + c.WINDOW_CONFIG["measurementSeconds"] + 30
        while proc.poll() is None and time.monotonic() < deadline:
            time.sleep(0.5)
            if proc.poll() is not None:
                # Never take a sample after the client exits: a post-exit read sees
                # no task threads and would inject a spurious zero-tick sample that
                # the fail-closed CPU rule must reject.
                break
            now = time.monotonic()
            wall = time.time()
            cur_client = local_ticks(proc.pid)
            probe = remote_probe(args.server_host, args.ssh_key, stage, pid)
            try:
                snap = read_stats(args.server_ip, args.port)
                connections = int(snap.get("curr_connections", "0")) - 1
            except (OSError, ValueError):
                connections = 0
            interval = now - tick_time
            if cur_client:
                peak = max(peak, max((v - prev_client.get(k, v)) / clk / interval for k, v in cur_client.items()) if prev_client else 0.0)
            sample = {
                "monotonic": now, "wall": wall, "intervalSeconds": interval,
                "total_rss": probe.get("rss"), "connections": connections,
                "clientCpuTicks": cur_client, "serverCpuTicks": probe.get("ticks"),
                "previousClientCpuTicks": prev_client,
                "clockTicksPerSecond": probe.get("clock", clk),
                "maxClientThreadCpu": peak,
            }
            if prev_server is not None:
                # Omit a null baseline rather than emit a non-dict counter, which the
                # unchanged reader would (correctly) treat as invalid evidence.
                sample["previousServerCpuTicks"] = prev_server
            samples.append(sample)
            prev_client, prev_server, tick_time = cur_client, probe.get("ticks"), now
        if proc.poll() is None:
            proc.kill()
        proc.wait(5)
        # artifact
        artifact_path = attempt_out / (label + ".memtier.json")
        artifact = None
        rss_window = []
        total_rss = None
        native = None
        if artifact_path.is_file():
            artifact = {"name": artifact_path.name, "sha256": sha256_file(artifact_path)}
            native = json.loads(artifact_path.read_text(encoding="utf-8"))
            runtime = native["ALL STATS"]["Runtime"]
            # Align the recorded samples to the SAME measured interval the unchanged
            # reader uses for the CPU window (native Start/Finish, no trim): drop any
            # non-alive sample, snap the first interval to the measured start and the
            # last interval to the measured end. This is a sampling-window repair; it
            # changes no rule, bound or tolerance.
            cpu_start, cpu_end = runtime["Start time"] / 1000, runtime["Finish time"] / 1000
            if c.positive(cpu_start) and c.positive(cpu_end) and cpu_start < cpu_end:
                samples = _align_cpu_samples(samples, cpu_start, cpu_end)
            trim = c.WINDOW_CONFIG["trimSeconds"]
            start, end = cpu_start + trim, cpu_end - trim
            rss_window = [s for s in samples if start <= s["wall"] <= end]
            qualified = c.qualify_window(rss_window, c.WINDOW_CONFIG)
            if "median" in qualified:
                total_rss = qualified["median"]
        stats_after = read_stats(args.server_ip, args.port)
        cg_after = remote_probe(args.server_host, args.ssh_key, stage, pid, with_cgroup=True).get("cgroup")
        wall_end = time.time()
        headroom = None
        if native is not None:
            try:
                achieved = native["ALL STATS"]["Totals"]["Ops/sec"]
                headroom = measure_headroom(args.client, args.lib_dir, args.client_cpus, w, 2.0, tmpdir, achieved)
            except (KeyError, TypeError):
                headroom = None
        # F5.1: bind each row's claim to its OWN attempt-level probe provenance.
        _register_headroom_probe(passport, headroom, label)
        row = build_row(scenario, rep, server, identity, passport,
                        process=process, preload=preload, samples=samples, rss_window=rss_window,
                        total_rss=total_rss, stats_before=stats_before, stats_after=stats_after,
                        cgroup_before=cg_before, cgroup_after=cg_after, artifact=artifact,
                        server_argv_list=server_argv_list, client_argv_list=client_argv_list,
                        exit_code=proc.returncode or 0, timed_out=False, generator_headroom=headroom,
                        warmup=warmup, peak_client_thread_cpu=peak if samples else None,
                        performance_qualification=passport.get("performanceQualification", False),
                        wall_start=wall_start, wall_end=wall_end)
        return row
    finally:
        stop_server_session(args.server_host, args.ssh_key, server_proc, pid)


def _wait_for_version(args, deadline_seconds=15):
    deadline = time.monotonic() + deadline_seconds
    while time.monotonic() < deadline:
        try:
            version = read_version(args.server_ip, args.port)
            if version:
                return version
        except OSError:
            pass
        time.sleep(0.2)
    return None


def measure_cycles_headroom(tmpdir, probe_cycles=200):
    """Measure the churn generator's cycles/second ceiling on the client host.

    Uses the v3 EchoSink (a bounded RESPONDING loopback sink) so the churn
    handshake is exercised end to end. `source` is `row-measured`; the probe
    report is written inside the attempt and its 64-hex digest published.
    """
    sink = gen.EchoSink().start()
    try:
        report = gen.churn("127.0.0.1", sink.port, cycles=probe_cycles, workers=1, handshake=True, timeout=10.0)
        seconds = (report.get("measurement") or {}).get("seconds")
        cycles = report.get("validatedCycles", 0)
        if not c.positive(seconds) or not cycles:
            return None
        name = "cycles-headroom-" + uuid.uuid4().hex[:8] + ".json"
        out_path = pathlib.Path(tmpdir) / name
        out_path.write_text(json.dumps({"probeCycles": probe_cycles, "report": report}, indent=2), encoding="utf-8")
        return {"source": "row-measured", "method": "churn-responding-echo",
                "ceilingOpsPerSecond": cycles / seconds, "windowSeconds": float(seconds),
                "echoPort": sink.port,
                "probeArtifact": {"name": name, "sha256": sha256_file(out_path)},
                "note": "churn cycles/second ceiling measured against a responding loopback echo on the client host"}
    except (OSError, ValueError, TypeError, subprocess.SubprocessError):
        return None
    finally:
        sink.stop()


def _sample_self_until(samples, stop_event, interval=0.2):
    """Sample THIS client-host process's task CPU ticks until asked to stop."""
    clk = os.sysconf("SC_CLK_TCK")
    previous = local_ticks(os.getpid())
    tick_time = time.monotonic()
    while not stop_event.is_set():
        time.sleep(interval)
        now = time.monotonic()
        current = local_ticks(os.getpid())
        samples.append({"monotonic": now, "wall": time.time(), "intervalSeconds": now - tick_time,
                        "clientCpuTicks": current, "previousClientCpuTicks": previous,
                        "clockTicksPerSecond": clk, "clientRss": None})
        previous, tick_time = current, now


def _register_headroom_probe(passport, headroom, probe_ref):
    if not isinstance(headroom, dict):
        return
    headroom["probeRef"] = probe_ref
    probes = passport.setdefault("generatorHeadroomProbes", {})
    probes[probe_ref] = {"source": headroom["source"], "method": headroom["method"],
                         "windowSeconds": headroom.get("windowSeconds"),
                         "ceilingOpsPerSecond": headroom["ceilingOpsPerSecond"],
                         "probeArtifact": headroom.get("probeArtifact")}


def _stable_client_ticks(pid, stable_reads=3, delay=0.03, max_tries=100):
    """Return the client's per-thread CPU ticks once its THREAD SET is stable.

    The baseline must contain every thread the measured samples will see, or the
    unchanged rule (correctly) reports `baseline_missing`. memtier creates its worker
    threads during startup, so wait until the task-id set stops changing (only the set
    is compared - tick values always increase). This never fabricates ticks; it only
    waits, and is bounded so it cannot consume the measurement window.
    """
    last_keys, equal = None, 0
    for _ in range(max_tries):
        current = local_ticks(pid)
        keys = frozenset(current)
        if keys and keys == last_keys:
            equal += 1
            if equal >= stable_reads - 1:
                return current
        else:
            equal = 0
        last_keys = keys
        time.sleep(delay)
    return local_ticks(pid)


def _align_cpu_samples(samples, start, end):
    """Repair the generator-CPU sampling window (sampling only; no rule change).

    Keeps only samples taken while the client was alive and inside the measured
    interval, snaps the first interval's begin to the measured start and the last
    interval's end to the measured end, preserving exact consecutive contiguity
    (wall and monotonic deltas equal the recorded interval). The unchanged
    fail-closed rule still decides: a genuinely vanished thread, a regressed counter
    or a gap still reaches it untouched and is reported as an explicit reason.
    """
    alive = []
    for s in samples:
        if not isinstance(s, dict):
            continue
        if (not s.get("clientCpuTicks") or not c.positive(s.get("wall"))
                or not c.positive(s.get("intervalSeconds")) or not c.positive(s.get("monotonic"))):
            continue
        if s["wall"] <= start or s["wall"] > end:
            continue
        alive.append(s)
    alive.sort(key=lambda s: s["wall"])
    if not alive:
        return []
    first = alive[0]
    first_interval = first["wall"] - start
    if c.positive(first_interval):
        first["intervalSeconds"] = first_interval
    if len(alive) >= 2:
        last, previous = alive[-1], alive[-2]
        last_interval = end - previous["wall"]
        if c.positive(last_interval):
            last["intervalSeconds"] = last_interval
            last["wall"] = end
            last["monotonic"] = previous["monotonic"] + last_interval
    return alive


def _write_evidence(attempt_out, label, kind, payload):
    """Persist a per-row evidence record and return its {name, sha256} identity."""
    document = {"schemaVersion": 1, "kind": kind, "record": label}
    document.update(payload)
    path = pathlib.Path(attempt_out) / f"{label}.{kind}-evidence.json"
    path.write_text(json.dumps(document, indent=2), encoding="utf-8")
    return {"name": path.name, "sha256": sha256_file(path)}


def _run_calibration(args, paths, identity, stage, attempt_out, memory=256):
    """Emit calibration.json with the pinned/v3 single-host schema, two-machine.

    Runs ONCE before the matrix. Each server is started on the server host and the
    boundary/readback probes are driven from the client host; the evidence rows and
    schema are identical to `run.py:calibration()`.
    """
    rows = []
    for server in ("reference", "octomem"):
        argv = build_server_argv(server, paths, memory, args.server_cpus, args.server_ip)
        log_path = f"{stage}/calibration-{server}.server.log"
        server_proc, pid = start_server_session(args.server_host, args.ssh_key, stage, argv, log_path, "calibration-" + server)
        try:
            if pid is None:
                raise RuntimeError("calibration server pid missing")
            version = _wait_for_version(args)
            if not version:
                raise RuntimeError("calibration server version deadline")
            identity[server + "Version"] = version
            for keylen in (1, 16, 250):
                for flags in (0, 1, 65535, 4294967295):
                    low, high, attempts = 0, 1048577, []
                    while high - low > 1:
                        mid = (high + low) // 2
                        probe = _store_one(args.server_ip, args.port, "k" * keylen, mid, flags)
                        attempts.append(probe)
                        if probe["response"] == "STORED":
                            low = mid
                        elif probe["response"].startswith("SERVER_ERROR object too large"):
                            high = mid
                        else:
                            raise ValueError("unexpected boundary rejection: " + probe["response"])
                    checks = [_store_one(args.server_ip, args.port, "k" * keylen, n, flags, readback=True) for n in (low, high)]
                    if not checks[0].get("readbackVerified") or checks[1]["response"] == "STORED":
                        raise ValueError("boundary adjacent checks failed")
                    rows.append({"server": server, "keyBytes": keylen, "flags": flags, "accepted": low,
                                 "rejected": high, "checks": checks, "attempts": attempts, "identity": dict(identity)})
                    (pathlib.Path(attempt_out) / "calibration.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
        finally:
            stop_server_session(args.server_host, args.ssh_key, server_proc, pid)
            (pathlib.Path(attempt_out) / f"calibration-{server}-process.json").write_text(
                json.dumps({"server": server, "argv": argv, "version": identity.get(server + "Version")}, indent=2), encoding="utf-8")
    return rows


def _collect_memory_row(args, passport, identity, scenario, rep, server, stage):
    """Memory kind (M3-*): server on the server host, RSS sampled ONLY from it."""
    w = c.workload(scenario)
    label = f"{scenario}-r{rep}-{server}"
    server_argv_list = build_server_argv(server, passport["paths"], w["memoryMiB"], args.server_cpus, args.server_ip)
    log_path = f"{stage}/{label}.server.log"
    server_proc, pid = start_server_session(args.server_host, args.ssh_key, stage, server_argv_list, log_path, label)
    try:
        if pid is None:
            raise RuntimeError("server session pid missing")
        version = _wait_for_version(args)
        if not version:
            raise RuntimeError("server version deadline")
        print(f"  {label} version={version}", flush=True)
        identity[server + "Version"] = version
        process = {"argv": server_argv_list, "startedWall": time.time(), "pid": pid, "version": version}
        # Preload over the network from the client host with the pinned semantics.
        if w["count"]:
            preload = gen.preload(args.server_ip, args.port, w["count"], w["size"], workers=8,
                                  connections_per_worker=4, pipeline=32, timeout=120.0)
        else:
            preload = {"requested": 0, "stored": 0, "response": "STORED"}
        print(f"  {label} preload={preload.get('stored')}/{preload.get('requested')} {preload.get('response')}", flush=True)
        if (preload.get("requested") != w["count"] or preload.get("stored") != w["count"]
                or preload.get("response") != "STORED"):
            evidence = _write_evidence(passport["collectorOutput"], label, "memory",
                                       {"preload": preload, "process": process, "serverArgv": server_argv_list,
                                        "rssWindow": [], "totalRss": None,
                                        "note": "preload incomplete; timed phase not started"})
            row = build_row(scenario, rep, server, identity, passport, process=process, preload=preload,
                            samples=[], rss_window=[], total_rss=None, stats_before={}, stats_after={},
                            cgroup_before=None, cgroup_after=None, artifact=evidence,
                            server_argv_list=server_argv_list, client_argv_list=[],
                            error="memory_preload_incomplete")
            row["evidenceIdentity"] = {"record": label, "artifact": evidence["name"], "sha256": evidence["sha256"],
                                       "rssWindowSamples": 0, "error": "memory_preload_incomplete"}
            return row
        stats_before = read_stats(args.server_ip, args.port)
        cg_before = remote_probe(args.server_host, args.ssh_key, stage, pid, with_cgroup=True).get("cgroup")
        wall_start = time.time()
        rss_window = []
        deadline = time.monotonic() + c.WINDOW_CONFIG["measurementSeconds"]
        while time.monotonic() < deadline:
            time.sleep(0.5)
            probe = remote_probe(args.server_host, args.ssh_key, stage, pid)
            if probe.get("rss") is None:
                continue
            rss_window.append({"monotonic": time.monotonic(), "wall": time.time(), "total_rss": probe["rss"]})
        wall_end = time.time()
        stats_after = read_stats(args.server_ip, args.port)
        cg_after = remote_probe(args.server_host, args.ssh_key, stage, pid, with_cgroup=True).get("cgroup")
        qualified = c.qualify_window(rss_window, c.WINDOW_CONFIG)
        total_rss = qualified.get("median")
        error = None
        if not rss_window:
            error = "memory_server_rss_evidence_missing"
        elif cg_before is None or cg_after is None:
            error = "memory_server_cgroup_evidence_missing"
        evidence = _write_evidence(passport["collectorOutput"], label, "memory",
                                   {"preload": preload, "process": process, "serverArgv": server_argv_list,
                                    "rssWindow": rss_window, "totalRss": total_rss, "rssQualification": qualified,
                                    "statsBefore": stats_before, "statsAfter": stats_after,
                                    "cgroupBefore": cg_before, "cgroupAfter": cg_after,
                                    "measurementSeconds": c.WINDOW_CONFIG["measurementSeconds"]})
        row = build_row(scenario, rep, server, identity, passport, process=process, preload=preload,
                        samples=[], rss_window=rss_window, total_rss=total_rss,
                        stats_before=stats_before, stats_after=stats_after,
                        cgroup_before=cg_before, cgroup_after=cg_after, artifact=evidence,
                        server_argv_list=server_argv_list, client_argv_list=[],
                        performance_qualification=passport.get("performanceQualification", False),
                        wall_start=wall_start, wall_end=wall_end, error=error)
        row["evidenceIdentity"] = {"record": label, "artifact": evidence["name"], "sha256": evidence["sha256"],
                                   "rssWindowSamples": len(rss_window),
                                   "windowStart": (rss_window[0]["monotonic"] if rss_window else None),
                                   "windowEnd": (rss_window[-1]["monotonic"] if rss_window else None),
                                   "error": error}
        return row
    finally:
        stop_server_session(args.server_host, args.ssh_key, server_proc, pid)


def _collect_cycles_row(args, passport, identity, scenario, rep, server, stage, tmpdir):
    """Cycles kind (P07): in-process churn on the client host against the server."""
    w = c.workload(scenario)
    label = f"{scenario}-r{rep}-{server}"
    server_argv_list = build_server_argv(server, passport["paths"], w["memoryMiB"], args.server_cpus, args.server_ip)
    log_path = f"{stage}/{label}.server.log"
    server_proc, pid = start_server_session(args.server_host, args.ssh_key, stage, server_argv_list, log_path, label)
    try:
        if pid is None:
            raise RuntimeError("server session pid missing")
        version = _wait_for_version(args)
        if not version:
            raise RuntimeError("server version deadline")
        print(f"  {label} version={version}", flush=True)
        identity[server + "Version"] = version
        process = {"argv": server_argv_list, "startedWall": time.time(), "pid": pid, "version": version}
        preload = {"requested": 0, "stored": 0, "response": "STORED"} if not w["count"] else gen.preload(
            args.server_ip, args.port, w["count"], w["size"], workers=8, connections_per_worker=4,
            pipeline=32, timeout=120.0)
        stats_before = read_stats(args.server_ip, args.port)
        cg_before = remote_probe(args.server_host, args.ssh_key, stage, pid, with_cgroup=True).get("cgroup")
        wall_start = time.time()
        samples = []
        stop_event = threading.Event()
        sampler = threading.Thread(target=_sample_self_until, args=(samples, stop_event), daemon=True)
        sampler.start()
        # Pinned P07 semantics: ONE start, then exactly 100 connect/version/close cycles.
        report = gen.churn(args.server_ip, args.port, cycles=100, workers=1, handshake=True, timeout=10.0)
        stop_event.set()
        sampler.join(timeout=3)
        wall_end = time.time()
        stats_after = read_stats(args.server_ip, args.port)
        cg_after = remote_probe(args.server_host, args.ssh_key, stage, pid, with_cgroup=True).get("cgroup")
        cycles_seconds = report.get("cyclesSeconds", [])
        validated = report.get("validatedCycles", 0)
        cycle_errors = report.get("cycleErrors", [])
        unreaped = report.get("unreapedWorkers", [])
        # Real per-row generator CPU summary from measured in-window samples on the client host.
        measurement = report.get("measurement") or {}
        window = None
        if c.positive(measurement.get("wallStart")) and c.positive(measurement.get("wallEnd")):
            window = (measurement["wallStart"], measurement["wallEnd"])
        generator_cpu = None
        corrected = c.corrected_generator_cpu(samples, r.client_cpu_count(args.client_cpus), window) if samples else None
        if isinstance(corrected, dict) and corrected.get("status") == "measured":
            generator_cpu = corrected
        else:
            cpu = report.get("cpu") or {}
            if c.finite_number(cpu.get("processCpuFraction")) and type(cpu.get("clientCpuCount")) is int and cpu.get("clientCpuCount") >= 1:
                generator_cpu = dict(cpu, status="measured")
        # Per-row headroom measured on the client host; bind to attempt-level provenance.
        headroom = measure_cycles_headroom(tmpdir)
        achieved = (validated / sum(cycles_seconds)) if (validated and cycles_seconds and sum(cycles_seconds) > 0) else None
        if isinstance(headroom, dict) and c.positive(achieved):
            headroom["achievedOpsPerSecond"] = achieved
        else:
            headroom = None
        _register_headroom_probe(passport, headroom, label)
        error = None
        if len(cycles_seconds) != 100 or validated != 100 or cycle_errors or unreaped:
            error = "cycles_incomplete"
        elif generator_cpu is None:
            error = "cycles_generator_cpu_evidence_missing"
        elif headroom is None:
            error = "cycles_headroom_probe_missing"
        total_rss = remote_probe(args.server_host, args.ssh_key, stage, pid).get("rss")
        evidence = _write_evidence(passport["collectorOutput"], label, "cycles",
                                   {"preload": preload, "process": process, "serverArgv": server_argv_list,
                                    "cyclesSeconds": cycles_seconds, "validatedCycles": validated,
                                    "cycleErrors": cycle_errors, "unreapedWorkers": unreaped,
                                    "generatorCpu": generator_cpu,
                                    "generatorHeadroomProbeRef": (headroom or {}).get("probeRef"),
                                    "generatorEngine": "octomem-bounded-loadgen",
                                    "measurement": measurement, "statsBefore": stats_before,
                                    "statsAfter": stats_after, "cgroupBefore": cg_before, "cgroupAfter": cg_after})
        row = build_row(scenario, rep, server, identity, passport, process=process, preload=preload,
                        samples=samples, rss_window=[], total_rss=total_rss,
                        stats_before=stats_before, stats_after=stats_after,
                        cgroup_before=cg_before, cgroup_after=cg_after, artifact=evidence,
                        server_argv_list=server_argv_list, client_argv_list=[],
                        exit_code=report.get("exitCode", 1), timed_out=False,
                        generator_cpu=generator_cpu, generator_headroom=headroom,
                        peak_client_thread_cpu=None, client_engine="octomem-bounded-loadgen",
                        performance_qualification=passport.get("performanceQualification", False),
                        wall_start=wall_start, wall_end=wall_end,
                        cycles_seconds=cycles_seconds, validated_cycles=validated,
                        cycle_errors=cycle_errors, unreaped_workers=unreaped, error=error)
        row["evidenceIdentity"] = {"record": label, "artifact": evidence["name"], "sha256": evidence["sha256"],
                                   "cyclesValidated": validated, "cyclesRecorded": len(cycles_seconds),
                                   "generatorHeadroomProbeRef": (headroom or {}).get("probeRef"), "error": error}
        return row
    finally:
        stop_server_session(args.server_host, args.ssh_key, server_proc, pid)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-host", required=True, help="user@host of the server stand")
    parser.add_argument("--server-ip", required=True, help="IP the client uses to reach the server")
    parser.add_argument("--ssh-key", required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--java-version", required=True)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--client", required=True, help="memtier_benchmark path on the client host")
    parser.add_argument("--lib-dir", required=True, help="LD_LIBRARY_PATH dir for memtier")
    parser.add_argument("--protocol", required=True, help="frozen protocol JSON")
    parser.add_argument("--expected-protocol-sha256", default=None)
    parser.add_argument("--expected-harness-json", default=None)
    parser.add_argument("--output-parent", type=pathlib.Path, required=True)
    parser.add_argument("--stage", default="/var/tmp/octomem-perf-20260922/two-machine/harness")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--server-cpus", default="0-3")
    parser.add_argument("--client-cpus", default="0-1")
    parser.add_argument("--repetitions", type=int, default=6)
    parser.add_argument("--scenarios", nargs="*", default=None)
    parser.add_argument("--expected-jar-sha256", default=PINNED_IDENTITY["jar"])
    parser.add_argument("--non-qualifying", action="store_true")
    parser.add_argument("--official-campaign", action="store_true")
    parser.add_argument("--approved-run", action="store_true")
    args = parser.parse_args(argv)

    if not args.approved_run:
        parser.error("collection requires explicit approval and --approved-run")
    args.scenarios = args.scenarios or list(FULL_PLAN_SCENARIOS)
    full = set(args.scenarios) == set(FULL_PLAN_SCENARIOS) and args.repetitions == 6

    facts = _probe_facts(args)
    facts["official_campaign_declared"] = args.official_campaign
    facts["plan"] = "full" if full else "rehearsal"
    reasons = precondition_reasons(facts)
    if reasons:
        return _write_not_run(args.output_parent, reasons, facts)

    # stage v3 harness on the server host
    here = pathlib.Path(__file__).resolve().parent
    stage_files = {}
    for name in ("contracts.py", "generator.py", "run.py", "run_two_machine.py", "finalize.py", "gates.py"):
        path = here / name
        if path.is_file():
            stage_files[name] = path.read_text(encoding="utf-8")
    remote_stage(args.server_host, args.ssh_key, args.stage, stage_files)
    topology = json.loads(ssh_run(args.server_host, args.ssh_key,
                                  f"python3 {args.stage}/server_topology.py {args.stage}").stdout.strip().splitlines()[-1])
    client_topology = r.topology_snapshot()
    paths = {k: getattr(args, k) for k in ("java", "reference", "jar", "client")}
    print("PREFLIGHT reachability", flush=True)
    if not preflight_reachability(args, build_server_argv("reference", paths, 16, args.server_cpus, args.server_ip), args.stage):
        print("PREFLIGHT_UNREACHABLE", flush=True)
        facts["server_port_unreachable_from_client"] = True
        return _write_not_run(args.output_parent, ["server_port_unreachable_from_client"], facts)
    print("PREFLIGHT_OK", flush=True)

    attempt = args.output_parent / ("attempt-" + uuid.uuid4().hex)
    attempt.mkdir(parents=True, exist_ok=False)
    harness_dir = attempt / "harness"
    harness_dir.mkdir()
    harness_files = {}
    for name in stage_files:
        source = here / name
        if source.is_file():
            data = source.read_bytes()
            (harness_dir / name).write_bytes(data)
            harness_files[name] = sha256_bytes(data)
    # Bind the frozen protocol to the attempt by copying it inside the attempt dir.
    shutil.copyfile(args.protocol, attempt / "protocol.json")

    protocol_doc = json.loads(pathlib.Path(args.protocol).read_text(encoding="utf-8"))
    protocol_doc["sha256"] = sha256_file(args.protocol)
    identity = {"attemptId": attempt.name,
                "jarSha256": PINNED_IDENTITY["jar"], "referenceSha256": PINNED_IDENTITY["reference"],
                "memtierSha256": PINNED_IDENTITY["memtier"], "harnessSha256": harness_files["run.py"],
                "javaSha256": PINNED_IDENTITY["java"], "javaVersion": args.java_version}
    passport = {
        "schemaVersion": 2,
        "performanceQualification": bool(not args.non_qualifying and full),
        # The frozen reader keys its PASS gate on `qualification`; record the marker
        # the runner is declared to be (see qualification_claim).
        "qualification": qualification_claim(args.official_campaign, full, args.non_qualifying),
        "nonQualifying": bool(args.non_qualifying),
        "identity": identity,
        "harnessFiles": harness_files,
        "paths": {k: getattr(args, k) for k in ("java", "reference", "jar", "client")},
        "collectorOutput": str(attempt),
        "repetitions": args.repetitions,
        "stoppingRule": "fixed-pairs-no-extension",
        "clientCpus": args.client_cpus, "clientCpuCount": r.client_cpu_count(args.client_cpus),
        "serverCpus": args.server_cpus, "serverAddress": args.server_ip,
        "expectedJarSha256": args.expected_jar_sha256,
        "windowConfig": dict(c.WINDOW_CONFIG),
        "workloads": {n: c.workload(n) for n in c.SCENARIOS},
        "topology": {"layout": "two-machine", "hosts": {"server": topology, "client": client_topology}},
        "protocol": protocol_doc, "protocolPath": "protocol.json",
        "protocolSha256": sha256_file(args.protocol),
        "warmupSeconds": 5,
        "limitations": ["Two-machine layout; hypotheses about core isolation and host exclusivity are separate evidence",
                        "Non-qualifying unless declared for the official campaign"],
    }
    (attempt / "passport.json").write_text(json.dumps(passport, indent=2), encoding="utf-8")

    # Calibration is a REQUIRED pre-collection step (pinned/v3 schema, two-machine).
    print("CALIBRATION start", flush=True)
    try:
        _run_calibration(args, paths, identity, args.stage, attempt)
    except Exception as exc:  # noqa: BLE001
        print(f"CALIBRATION_FAILED {exc!r}", flush=True)
        facts2 = dict(facts)
        facts2["calibration_error"] = repr(exc)
        return _write_not_run(args.output_parent, ["calibration_failed"], facts2)
    print("CALIBRATION done", flush=True)

    tmpdir = attempt / "headroom"
    tmpdir.mkdir()
    rows = []
    for rep in range(args.repetitions):
        for scenario in args.scenarios:
            order = ["reference", "octomem"] if rep % 2 == 0 else ["octomem", "reference"]
            for server in order:
                print(f"ROW_START {scenario} r{rep} {server}", flush=True)
                try:
                    kind = c.workload(scenario)["kind"]
                    if kind == "memtier":
                        row = _collect_memtier_row(args, passport, identity, scenario, rep, server, args.stage, tmpdir)
                    elif kind == "memory":
                        row = _collect_memory_row(args, passport, identity, scenario, rep, server, args.stage)
                    elif kind == "cycles":
                        row = _collect_cycles_row(args, passport, identity, scenario, rep, server, args.stage, tmpdir)
                    else:
                        row = {"scenario": scenario, "rep": rep, "server": server, "error": "unsupported_kind:" + kind}
                    print(f"ROW_DONE {scenario} r{rep} {server}", flush=True)
                except Exception as exc:  # noqa: BLE001
                    print(f"ROW_ERROR {scenario} r{rep} {server} {exc!r}", flush=True)
                    rows.append({"scenario": scenario, "rep": rep, "server": server, "error": repr(exc)})
                    (attempt / "runs.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
                    facts2 = dict(facts)
                    facts2["row_error"] = repr(exc)
                    return _write_not_run(args.output_parent, ["row_transport_error"], facts2)
                rows.append(row)
                (attempt / "runs.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    passport["identity"] = dict(identity)
    (attempt / "passport.json").write_text(json.dumps(passport, indent=2), encoding="utf-8")
    artifacts = {p.name: sha256_file(p) for p in attempt.iterdir() if p.is_file()}
    (attempt / "complete.json").write_text(json.dumps({
        "completed": True, "captureSealed": True, "planComplete": not args.non_qualifying,
        "identity": identity, "artifacts": artifacts,
        "runsSha256": sha256_file(attempt / "runs.json"),
        "passportSha256": sha256_file(attempt / "passport.json")}, indent=2), encoding="utf-8")
    print(str(attempt))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
