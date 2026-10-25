"""V3 EXPLICITLY VERSIONED MEASUREMENT CORE - bounded TEST-05 collector.

Derived from `tests/platform/perf/pinned/run.py`
(sha256 07b07ea6e978572edad99ec6cda751b0ad242f30a8d78b165643d4ff8a1de7d2).
Explicit runtime, immutable unique attempt, no builds. Only instrument defects
(a)/(c)/(d)/(e)/(f) are repaired. See `V3-DELTAS.md`."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import socket
import statistics
import subprocess
import sys
import time
import traceback
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

PORT = 11219
EXPECTED_JAR = "63764015708EDBDCB2284DF53E827FA5CF2D58E904E73D3004D3F7D0165F9B70"


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def server_argv(server, java, reference, jar, memory, server_cpus="0-3", address="127.0.0.1"):
    # `address` is the transport parameter: the co-resident default is loopback and
    # the two-machine runner passes the server host address. Only the bind address
    # changes; the binary, flags, memory, item size and worker counts are identical.
    if server == "reference":
        args = [reference, "-u", "root", "-l", address, "-p", str(PORT), "-U", "0", "-m", str(memory), "-I", "1m", "-c", "12000", "-t", "4"]
    else:
        args = [java, "-Xms32m", "-Xmx512m", "-XX:ActiveProcessorCount=4", "--enable-native-access=ALL-UNNAMED", "-jar", jar,
                f"--listen.address={address}", f"--listen.port={PORT}", f"--memory.limit={memory}m", "--item.maxSize=1m",
                "--connections.max=12000", "--transport.workers=4", "--connections.idleTimeoutSeconds=0", "--log.level=error"]
    return ["taskset", "-c", server_cpus] + args


def client_argv(client, out, label, w, seconds, client_cpus="4-15", address="127.0.0.1"):
    # Forward slashes so a Linux-recorded argv is reconstructed identically on a
    # Windows analysis host (pathlib would otherwise emit backslashes).
    json_out = str(out/(label+".memtier.json")).replace("\\", "/")
    return ["taskset", "-c", client_cpus, client, f"--server={address}", f"--port={PORT}", "--protocol=memcache_text",
            f"--threads={w['threads']}", f"--clients={w['connections']//w['threads']}", f"--ratio={w['ratio']}",
            f"--pipeline={w['pipeline']}", f"--data-size={w['size']}", "--key-minimum=1", f"--key-maximum={w['count']}",
            "--key-prefix=memtier-", "--key-pattern=R:R", f"--test-time={seconds}",
            "--print-percentiles=50,95,99", "--hide-histogram", "--json-out-file="+json_out]


def cgroup_snapshot(pid=None):
    """Read cgroup v2 controller files from the SERVER process's own cgroup scope.

    Defect (c) repair: the CPU/throttling evidence is read from `/proc/<server-pid>/cgroup`,
    never from the host root. The unified hierarchy root never exposes per-controller
    limits, so walking the server's own ancestry finds the actual scope. When the
    server scope cannot be identified the snapshot records no scope and the row fails
    closed (`scopeSource` is only `"server-pid"` when a real scope was resolved).
    """
    result = {"wall": time.time(), "monotonic": time.monotonic(), "version": None, "path": None,
              "scope": None, "scopeSource": None, "pid": pid}
    mount = pathlib.Path("/sys/fs/cgroup")
    if (mount / "cgroup.controllers").is_file():
        result["version"] = 2
    try:
        controllers = (mount / "cgroup.controllers").read_text().strip()
        result["controllers"] = controllers
    except OSError:
        result["controllers"] = None
    cgroup_file = f"/proc/{pid}/cgroup" if pid is not None else "/proc/self/cgroup"
    relative = None
    try:
        for line in pathlib.Path(cgroup_file).read_text().splitlines():
            parts = line.split(":", 2)
            if len(parts) == 3 and parts[0] == "0":
                relative = parts[2].strip()
                break
    except OSError as exc:
        result["pathError"] = str(exc)
    if relative is None:
        result["scopeError"] = "server cgroup scope could not be identified"
        return result
    result["path"] = relative
    result["scope"] = relative or "/"
    result["scopeSource"] = "server-pid" if pid is not None else "collector-pid"
    directories = []
    current = pathlib.Path(str(mount) + relative)
    while True:
        directories.append(current)
        if current == mount or current.parent == current:
            break
        current = current.parent
    if mount not in directories:
        directories.append(mount)
    for name in ("cpu.max", "cpu.stat", "cpuset.cpus.effective", "memory.max",
                 "memory.swap.max", "memory.current", "memory.events"):
        result[name] = None
        for directory in directories:
            try:
                result[name] = (directory / name).read_text().strip()
                result[name + "Source"] = str(directory)
                break
            except OSError:
                continue
        if result[name] is None:
            result[name + "Error"] = "not readable in process cgroup ancestry"
    return result


def _expand_cpu_list(text):
    """Expand a Linux cpu list ('0-1,3') into a sorted list of cpu numbers."""
    cpus = []
    for part in str(text or "").split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, _, hi = part.partition("-")
            try:
                cpus.extend(range(int(lo), int(hi) + 1))
            except ValueError:
                continue
        elif part.isdigit():
            cpus.append(int(part))
    return sorted(set(cpus))


def topology_snapshot():
    """Record the actual CPU thread_siblings topology (defect e)."""
    result = {"siblings": {}, "online": None, "errors": []}
    base = pathlib.Path("/sys/devices/system/cpu")
    try:
        result["online"] = (base / "online").read_text().strip()
    except OSError:
        pass
    for directory in sorted(base.glob("cpu[0-9]*")):
        try:
            text = (directory / "topology" / "thread_siblings_list").read_text().strip()
        except OSError:
            continue
        group = _expand_cpu_list(text)
        if group:
            result["siblings"][directory.name[3:]] = group
    if not result["siblings"]:
        result["errors"].append("thread_siblings_list unreadable")
    return result


def load_protocol(path):
    """Load and hash the frozen protocol so it can be bound to the attempt (defect f)."""
    if not path:
        return None
    path = pathlib.Path(path)
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return {"loadError": repr(exc), "sha256": None}
    document = dict(document) if isinstance(document, dict) else {}
    document["sha256"] = sha(path)
    return document


def cpu_ticks(pid):
    result = {}
    for path in pathlib.Path(f"/proc/{pid}/task").glob("*/stat"):
        try:
            fields = path.read_text().rsplit(")", 1)[1].split()
            result[path.parent.name] = int(fields[11])+int(fields[12])
        except (OSError, IndexError, ValueError):
            pass
    return result


def rss(pid):
    return int(pathlib.Path(f"/proc/{pid}/statm").read_text().split()[1])*os.sysconf("SC_PAGE_SIZE")


class Wire:
    """One fixed-deadline request, bounded lines and bodies, real EOF distinct."""
    def __enter__(self):
        self.deadline = time.monotonic()+10
        self.socket = socket.create_connection(("127.0.0.1", PORT), timeout=10)
        self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.buffer = bytearray()
        return self

    def __exit__(self, *exc):
        self.socket.close()

    def send(self, data):
        self.socket.settimeout(max(.001, self.deadline-time.monotonic()))
        self.socket.sendall(data)

    def read(self, length=None):
        while True:
            end = self.buffer.find(b"\r\n")+2 if length is None else length
            if (length is not None or end > 1) and len(self.buffer) >= end:
                value = bytes(self.buffer[:end])
                del self.buffer[:end]
                return value
            if length is None and len(self.buffer) > 8192:
                raise ValueError("response line too large")
            left = self.deadline-time.monotonic()
            if left <= 0:
                raise TimeoutError("absolute response deadline")
            self.socket.settimeout(left)
            data = self.socket.recv(min(65536, (length-len(self.buffer)) if length else 8192))
            if not data:
                raise EOFError("response EOF")
            self.buffer.extend(data)


def stats():
    result = {}
    with Wire() as wire:
        wire.send(b"stats\r\n")
        for _ in range(1000):
            line = wire.read()
            if line == b"END\r\n":
                if wire.buffer:
                    raise ValueError("trailing stats frame data")
                return result
            parts = line[:-2].decode("ascii").split(" ", 2)
            if not re.fullmatch(rb"STAT [!-~]+ [ -~]+\r\n", line) or len(parts) != 3 or parts[1] in result:
                raise ValueError("malformed/duplicate stats")
            result[parts[1]] = parts[2]
    raise ValueError("too many stats")


def store_one(key, size, flags=0, readback=False):
    data = b"x"*size
    with Wire() as wire:
        wire.send(f"set {key} {flags} 0 {size}\r\n".encode()+data+b"\r\n")
        response = wire.read().decode("ascii").strip()
        result = {"valueBytes": size, "response": response}
        if readback and response == "STORED":
            wire.send(f"get {key}\r\n".encode())
            header = wire.read()
            if header != f"VALUE {key} {flags} {size}\r\n".encode():
                raise ValueError("calibration readback header mismatch")
            body = wire.read(size+2)
            if body != data+b"\r\n" or wire.read() != b"END\r\n":
                raise ValueError("calibration readback content mismatch")
            result.update(readbackVerified=True, payloadSha256=hashlib.sha256(body[:-2]).hexdigest())
        return result


def load(count, size):
    """Fill `count` keys of `size` bytes with the bounded multi-threaded generator.

    Replaces the earlier single-connection sequential loader. The returned dict keeps
    the historical `{requested, stored, response}` contract and adds generator
    telemetry (duration, byte counts, errors) for the validity gates.
    """
    return gen.preload("127.0.0.1", PORT, count, size, workers=8,
                       connections_per_worker=4, pipeline=32, timeout=120.0)


def client_cpu_count(spec):
    """Count CPUs in a taskset-style list such as '4-15' or '0,2-3'."""
    return gen.effective_cpu_count({"files": {"cpuset.cpus.effective": spec}})


def client_cpu_summary(result, client_cpus, window=None):
    """Aggregate generator CPU over the measurement window from real samples.

    Defect (a) repair: delegates to the shared v3 consecutive per-thread
    accumulation so the collector and the offline reader cannot disagree, and a
    thread that vanished between samples is an explicit defect rather than a
    silent zero. The frozen first/last method is retained only for the mismatch
    fields the reader publishes.
    """
    summary = c.corrected_generator_cpu(result.get("samples", []), client_cpus, window)
    if summary is None:
        return {"engine": result.get("clientEngine", "memtier"), "clientCpuCount": client_cpus,
                "processCpuFraction": None, "reason": "no client cpu samples"}
    summary["engine"] = result.get("clientEngine", "memtier")
    return summary


def per_row_headroom(w, native):
    """Measure this row's OWN generator headroom for its shape (defect d).

    Returns None when the row's achieved rate or the per-row measurement is
    unavailable, so the row is never treated as headroom-clear.
    """
    try:
        achieved = native["ALL STATS"]["Totals"]["Ops/sec"]
    except (KeyError, TypeError):
        return None
    if not c.positive(achieved):
        return None
    connections = max(1, w.get("connections") or 1)
    threads = max(1, w.get("threads") or 1)
    shape = {"workers": min(8, threads), "connectionsPerWorker": max(1, connections // threads),
             "pipeline": max(1, w.get("pipeline") or 1), "ratioSetGet": w.get("ratio", "1:9"),
             "keys": max(1, w.get("count") or 1), "keyPrefix": "memtier-", "keyDistribution": "uniform",
             "valueSizeMix": f"{w.get('size') or 256}:1.0", "seed": 1}
    probe = gen.headroom_probe("127.0.0.1", PORT, shape)
    if not isinstance(probe, dict):
        return None
    probe["achievedOpsPerSecond"] = achieved
    return probe


class Collector:
    def __init__(self, args):
        self.args = args
        self.out = args.output_parent / ("attempt-"+uuid.uuid4().hex)
        if not args.output_parent.is_dir():
            raise ValueError("output parent must exist")
        for path in (args.java, args.reference, args.jar, args.client):
            if not pathlib.Path(path).is_absolute() or not pathlib.Path(path).is_file():
                raise ValueError("explicit existing absolute binary paths required: " + path)
        expected_jar = (args.expected_jar_sha256 or EXPECTED_JAR).upper()
        if sha(args.jar).upper() != expected_jar:
            raise ValueError("candidate JAR pin mismatch")
        version = subprocess.run([args.java, "-version"], capture_output=True, text=True, timeout=15, check=True)
        java_text = version.stdout+version.stderr
        match = re.search(r'(?:openjdk|java) version "([^"]+)"', java_text)
        if not match or match.group(1) != args.java_version or not args.java_version.startswith("25."):
            raise ValueError("explicit required JDK25 update mismatch")
        self.out.mkdir(exist_ok=False)
        harness = pathlib.Path(__file__).parent
        (self.out/"harness").mkdir()
        files = {}
        for source in harness.iterdir():
            if source.is_file() and source.suffix in (".py", ".ps1"):
                (self.out/"harness"/source.name).write_bytes(source.read_bytes())
                files[source.name] = sha(source)
        self.identity = {"attemptId": self.out.name, "jarSha256": sha(args.jar), "referenceSha256": sha(args.reference),
                         "memtierSha256": sha(args.client), "harnessSha256": sha(__file__), "javaSha256": sha(args.java),
                         "javaVersion": args.java_version}
        capacity = gen.capacity_probe({"workers": 8, "connectionsPerWorker": 4, "pipeline": 100,
                                       "ratioSetGet": "1:9", "keys": 4096,
                                       "valueSizeMix": "256:1.0", "seed": 1}, probe_seconds=2.0)
        capacity = gen.capacity_probe({"workers": 8, "connectionsPerWorker": 4, "pipeline": 100,
                                       "ratioSetGet": "1:9", "keys": 4096,
                                       "valueSizeMix": "256:1.0", "seed": 1}, probe_seconds=2.0)
        protocol = load_protocol(getattr(args, "protocol", None))
        self.passport = {"schemaVersion": 2, "identity": self.identity, "javaVersionOutput": java_text, "harnessFiles": files,
                         "paths": {k: getattr(args, k) for k in ("java", "reference", "jar", "client")},
                         "collectorOutput": str(self.out), "repetitions": args.repetitions, "stoppingRule": "fixed-pairs-no-extension",
                         "clientCpus": args.client_cpus, "clientCpuCount": client_cpu_count(args.client_cpus),
                         "serverCpus": args.server_cpus, "expectedJarSha256": expected_jar,
                         "generatorCapacity": capacity, "topology": topology_snapshot(),
                         "protocol": protocol, "protocolPath": getattr(args, "protocol", None),
                         "protocolSha256": protocol.get("sha256") if isinstance(protocol, dict) else None,
                         "windowConfig": dict(c.WINDOW_CONFIG), "workloads": {n: c.workload(n) for n in c.SCENARIOS},
                         "cgroup": cgroup_snapshot(), "warmupSeconds": 5,
                         "limitations": ["Host exclusivity unproven", "Closed-loop latency; no coordinated omission correction",
                                         "Preparatory warmup uses distinct connections; no automatic JVM-warm claim",
                                         "No forced GC; bounded convergence is not equilibrium",
                                         "Per-row generator headroom is measured against a responding echo, never a discard-sink extrapolation"]}
        self.save("passport.json", self.passport)

    def save(self, name, value):
        (self.out/name).write_text(json.dumps(value, indent=2, allow_nan=False), encoding="utf-8")

    def start(self, server, label, memory=256):
        for key, path in (("javaSha256", self.args.java), ("referenceSha256", self.args.reference),
                          ("jarSha256", self.args.jar), ("memtierSha256", self.args.client)):
            if sha(path) != self.identity[key]:
                raise ValueError("binary changed since attempt plan: " + key)
        try:
            existing = socket.create_connection(("127.0.0.1", PORT), timeout=.2)
        except ConnectionRefusedError:
            pass
        else:
            existing.close()
            raise RuntimeError("benchmark port already occupied; not touching another server")
        argv = server_argv(server, self.args.java, self.args.reference, self.args.jar, memory, self.args.server_cpus)
        log = (self.out/(label+".server.log")).open("x", encoding="utf-8")
        begin = time.monotonic()
        p = subprocess.Popen(argv, stdout=log, stderr=log)
        p.meta = {"argv": argv, "startedWall": time.time(), "pid": p.pid}
        p.log = log
        try:
            while time.monotonic()-begin < 10:
                if p.poll() is not None:
                    raise RuntimeError("server exited during startup")
                try:
                    with Wire() as wire:
                        wire.send(b"version\r\n")
                        version = wire.read().decode("ascii").strip()
                    if not version.startswith("VERSION "):
                        raise ValueError("bad version response")
                    p.meta.update(version=version, startupSeconds=time.monotonic()-begin, empty_rss=rss(p.pid))
                    return p
                except ConnectionRefusedError:
                    time.sleep(.05)
            raise TimeoutError("server startup deadline")
        except BaseException:
            self.stop(p)
            raise

    def stop(self, p):
        if p.poll() is None:
            p.terminate()
        try:
            p.wait(10)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait(5)
        p.meta["exitCode"] = p.returncode
        p.log.close()

    def calibration(self):
        rows = []
        for server in ("reference", "octomem"):
            p = self.start(server, "calibration-"+server)
            self.identity[server+"Version"] = p.meta["version"]
            try:
                for keylen in (1, 16, 250):
                    for flags in (0, 1, 65535, 4294967295):
                        low, high, attempts = 0, 1048577, []
                        while high-low > 1:
                            mid = (high+low)//2
                            probe = store_one("k"*keylen, mid, flags)
                            attempts.append(probe)
                            if probe["response"] == "STORED":
                                low = mid
                            elif probe["response"].startswith("SERVER_ERROR object too large"):
                                high = mid
                            else:
                                raise ValueError("unexpected boundary rejection: " + probe["response"])
                        checks = [store_one("k"*keylen, n, flags, readback=True) for n in (low, high)]
                        if not checks[0].get("readbackVerified") or checks[1]["response"] == "STORED":
                            raise ValueError("boundary adjacent checks failed")
                        rows.append({"server": server, "keyBytes": keylen, "flags": flags, "accepted": low,
                                     "rejected": high, "checks": checks, "attempts": attempts, "identity": dict(self.identity)})
                        self.save("calibration.json", rows)
            finally:
                self.stop(p)
                self.save("calibration-"+server+"-process.json", p.meta)
        self.save("passport.json", self.passport)

    def bench(self, p, label, w, seconds):
        argv = client_argv(self.args.client, self.out, label, w, seconds, self.args.client_cpus)
        path = self.out/(label+".memtier.json")
        if path.exists():
            raise FileExistsError("refusing stale client output")
        result = {"argv": argv, "expectedClientArgv": argv, "statsBefore": stats(), "cgroupBefore": cgroup_snapshot(p.pid),
                  "wallStart": time.time(), "monotonicStart": time.monotonic(), "timedOut": False, "samples": []}
        with (self.out/(label+".client.log")).open("x", encoding="utf-8") as log:
            q = subprocess.Popen(argv, stdout=log, stderr=log)
            previous = cpu_ticks(q.pid)
            previous_server = cpu_ticks(p.pid)
            tick_time = time.monotonic()
            deadline = tick_time+seconds+30
            try:
                while q.poll() is None:
                    if time.monotonic() >= deadline:
                        result["timedOut"] = True
                        q.kill()
                        break
                    time.sleep(.5)
                    now = time.monotonic()
                    current, server_ticks = cpu_ticks(q.pid), cpu_ticks(p.pid)
                    snap = stats()
                    sample = {"monotonic": now, "wall": time.time(), "intervalSeconds": now-tick_time,
                              "total_rss": rss(p.pid), "connections": int(snap["curr_connections"])-1,
                              "clientCpuTicks": current, "serverCpuTicks": server_ticks,
                              "previousClientCpuTicks": previous, "previousServerCpuTicks": previous_server,
                              "clockTicksPerSecond": os.sysconf("SC_CLK_TCK"),
                              "maxClientThreadCpu": max([(v-previous.get(k, v))/os.sysconf("SC_CLK_TCK")/(now-tick_time) for k, v in current.items()] or [0])}
                    result["samples"].append(sample)
                    previous, previous_server, tick_time = current, server_ticks, now
            finally:
                if q.poll() is None:
                    q.kill()
                q.wait(5)
        result.update(exitCode=q.returncode, wallEnd=time.time(), monotonicEnd=time.monotonic(),
                      statsAfter=stats(), cgroupAfter=cgroup_snapshot(p.pid))
        # Defect (b): never substitute 0 for an absent peak-thread observation.
        peaks = [s["maxClientThreadCpu"] for s in result["samples"] if "maxClientThreadCpu" in s]
        if peaks:
            result["peakClientThreadCpu"] = max(peaks)
            result["cpuWarning"] = result["peakClientThreadCpu"] >= .85
        # Keep all lifecycle samples; the prespecified native active interval alone qualifies RSS.
        result["rssWindow"] = []
        cpu_window, native = None, None
        if path.is_file():
            result["artifact"] = {"name": path.name, "sha256": sha(path)}
            try:
                native = json.loads(path.read_text(encoding="utf-8"))
                runtime = native["ALL STATS"]["Runtime"]
                if runtime["Time unit"] == "MILLISECONDS" and all(c.discrete(runtime[k]) for k in ("Start time", "Finish time")):
                    cpu_window = (runtime["Start time"]/1000, runtime["Finish time"]/1000)
                trim = c.WINDOW_CONFIG["trimSeconds"]
                start, end = runtime["Start time"]/1000+trim, runtime["Finish time"]/1000-trim
                result["rssWindow"] = [s for s in result["samples"] if start <= s["wall"] <= end]
            except (OSError, ValueError, KeyError, TypeError) as exc:
                result["artifactError"] = repr(exc)
        else:
            result["artifactMissing"] = True
        qualified = c.qualify_window(result["rssWindow"], c.WINDOW_CONFIG)
        result["rssQualification"] = qualified
        if "median" in qualified:
            result["total_rss"] = qualified["median"]
        result["clientEngine"] = "memtier"
        result["generatorCpu"] = client_cpu_summary(result, client_cpu_count(self.args.client_cpus), cpu_window)
        # Defect (d): a per-row measured headroom, never derived from a global probe.
        headroom = per_row_headroom(w, native)
        if headroom is not None:
            result["generatorHeadroom"] = headroom
        self.save(label+".measurement.json", result)
        return result

    def memory(self, p):
        result = {"statsBefore": stats(), "cgroupBefore": cgroup_snapshot(p.pid), "wallStart": time.time(),
                  "exitCode": 0, "timedOut": False, "rssWindow": []}
        start = time.monotonic()
        # Fixed symmetric quiescent window; no adaptive wait-until-a-favorable-RSS.
        while time.monotonic()-start < c.WINDOW_CONFIG["measurementSeconds"]:
            time.sleep(.5)
            result["rssWindow"].append({"monotonic": time.monotonic(), "wall": time.time(), "total_rss": rss(p.pid)})
        result.update(statsAfter=stats(), cgroupAfter=cgroup_snapshot(p.pid), wallEnd=time.time())
        result["rssQualification"] = c.qualify_window(result["rssWindow"], c.WINDOW_CONFIG)
        result["total_rss"] = result["rssQualification"]["median"]
        return result

    def execute(self):
        self.calibration()
        rows = []
        for rep in range(self.args.repetitions):
            for name in c.SCENARIOS:
                w = c.workload(name)
                order = ["reference", "octomem"] if rep % 2 == 0 else ["octomem", "reference"]
                for order_index, server in enumerate(order):
                    label = f"{name}-r{rep}-{server}"
                    row = {"scenario": name, "rep": rep, "server": server, "identity": dict(self.identity),
                           "workload": w, "pairOrder": order, "orderIndex": order_index,
                           "performanceQualification": True,
                           "windowConfig": dict(c.WINDOW_CONFIG), "forcedGcDuringMeasurement": False,
                           "expectedServerArgv": server_argv(server, self.args.java, self.args.reference, self.args.jar, w["memoryMiB"], self.args.server_cpus)}
                    p = None
                    try:
                        p = self.start(server, label, w["memoryMiB"])
                        row["process"] = p.meta
                        row["preload"] = load(w["count"], w["size"]) if w["count"] else {"requested": 0, "stored": 0, "response": "STORED"}
                        if row["preload"]["stored"] != w["count"] or row["preload"]["response"] != "STORED":
                            raise RuntimeError("preload incomplete; timed phase not started")
                        if w["kind"] == "memtier":
                            warmup = self.bench(p, label+"-warmup", w, 5)
                            row["warmup"] = {"exitCode": warmup["exitCode"], "timedOut": warmup["timedOut"], "qualifiedAsWarm": False}
                            if warmup["exitCode"] or warmup["timedOut"]:
                                raise RuntimeError("preparatory client failed; timed phase not started")
                            row.update(self.bench(p, label, w, c.WINDOW_CONFIG["measurementSeconds"]))
                        elif w["kind"] == "memory":
                            row.update(self.memory(p))
                        else:
                            row.update(statsBefore=stats(), cgroupBefore=cgroup_snapshot(p.pid), wallStart=time.time())
                            with Wire() as wire:
                                wire.send(b"version\r\n")
                                if wire.read().decode("ascii").strip() != p.meta["version"]:
                                    raise ValueError("cycle version mismatch")
                            churn = gen.churn("127.0.0.1", PORT, cycles=100, workers=1, handshake=True)
                            row.update(exitCode=churn.get("exitCode", 1), timedOut=False,
                                       cyclesSeconds=churn.get("cyclesSeconds", []),
                                       validatedCycles=churn.get("validatedCycles", 0),
                                       cycleErrors=churn.get("cycleErrors", []),
                                       unreapedWorkers=churn.get("unreapedWorkers", []),
                                       generatorCpu=churn.get("cpu"),
                                       clientEngine="octomem-bounded-loadgen",
                                       statsAfter=stats(), cgroupAfter=cgroup_snapshot(p.pid), wallEnd=time.time(),
                                       total_rss=rss(p.pid), rssWindow=[], rssLimitation="cold cycles have no steady RSS window")
                        row["statsDelta"] = c.stats_delta(row["statsBefore"], row["statsAfter"])
                        row["dataset_rss"] = row.get("total_rss", 0)-p.meta["empty_rss"]
                    except Exception as exc:
                        row.update(error=repr(exc), trace=traceback.format_exc())
                    finally:
                        if p is not None:
                            self.stop(p)
                        rows.append(row)
                        self.save("runs.json", rows)
        artifacts = {path.name: sha(path) for path in self.out.iterdir() if path.is_file()}
        self.save("complete.json", {"completed": True, "captureSealed": True, "planComplete": True,
                                    "identity": self.identity, "artifacts": artifacts,
                                    "runsSha256": sha(self.out/"runs.json"), "passportSha256": sha(self.out/"passport.json")})
        from finalize import read_attempt, build_scenario
        passport, validated_rows, errors = read_attempt(self.out)
        summaries = [build_scenario(validated_rows, name, passport, errors) for name in c.SCENARIOS]
        # Completion seals collected artifacts, not acceptance; invalid collection exits nonzero.
        return 0 if all(s["verdict"] == "PASS" for s in summaries) else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-parent", type=pathlib.Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--java-version", required=True)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--client", required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--server-cpus", default="0-3")
    parser.add_argument("--client-cpus", default="4-15")
    parser.add_argument("--expected-jar-sha256", default=None)
    parser.add_argument("--protocol", default=None, help="absolute path of the frozen protocol JSON to bind to this attempt")
    parser.add_argument("--approved-run", action="store_true")
    args = parser.parse_args(argv)
    if not args.approved_run:
        parser.error("runtime execution requires explicit coordinator approval and --approved-run")
    if args.repetitions < 2:
        parser.error("preplanned at least two alternating paired repetitions required; no optional stopping")
    collector = Collector(args)
    print(str(collector.out), flush=True)
    return collector.execute()


if __name__ == "__main__":
    raise SystemExit(main())

