"""Bounded multi-threaded, pipelined memcache load generator for TEST-05 / 72h soak.

This module replaces the earlier single-threaded `Wire` preload and cycle loops.
It is self-contained (standard library only) so it can run on the ROSA and Simply
Linux stands without memtier or third-party packages.

Engines
-------
mixed
    Each worker owns `connections_per_worker` non-blocking sockets multiplexed with
    `selectors`. Every connection keeps up to `pipeline` commands in flight; the
    set:get mix is decided per command from a per-worker seeded PRNG. Responses are
    parsed and validated (SET -> STORED, GET -> VALUE/END). When a single value size
    is used, GET payloads are verified byte-for-byte because the value embeds its key.
preload
    `mixed` with all-set ratio and an explicit operation count. Fills a declared
    dataset before a timed phase and builds the P06 capacity-pressure dataset.
churn
    Repeated open / optional handshake / close cycles with an absolute deadline.
    Used for P07 (100 open/close cycles) and connection-churn evidence.
sink_probe
    Fire-and-forget mode against an in-process discard sink. It measures an upper
    bound on what the generator can push and feeds the non-saturation headroom gate.

Offered-load contract
---------------------
The offered load is closed-loop concurrency, never a fixed completion target:
`workers x connections_per_worker` sockets, each with `pipeline` commands in flight,
the declared `set:get` ratio, key distribution and value-size mix. Achieved ops/s is
measured, not asserted. Every run records the plan, achieved composition, CPU
evidence, RSS samples, latency summary and a per-second timeline. `capacity_probe`
records the generator's own ceiling so `headroomFactor = ceiling / achieved` can be
published and gated.

This module never decides acceptance. It produces raw evidence; `gates.py` and
`contracts.py` classify it, and invalid evidence is INCONCLUSIVE before any threshold.
"""
import argparse
import errno
import hashlib
import json
import os
import pathlib
import random
import selectors
import socket
import statistics
import sys
import threading
import time
from array import array

SCHEMA_VERSION = 1
GENERATOR_NAME = "octomem-bounded-loadgen"
DEFAULT_PORT = 11219
DRAIN_SECONDS = 10.0
LATENCY_CAP = 200000


class LoadgenError(Exception):
    """Generator could not execute the declared plan."""


# --------------------------------------------------------------------------- helpers

def sha256_file(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def parse_value_mix(spec):
    """'1024:0.7,65536:0.3' -> [(1024, 0.7), (65536, 0.3)] normalised to 1.0."""
    if isinstance(spec, (list, tuple)):
        items = []
        for entry in spec:
            if isinstance(entry, dict):
                items.append((int(entry["size"]), float(entry["weight"])))
            else:
                size, weight = entry
                items.append((int(size), float(weight)))
    else:
        items = []
        for part in str(spec).split(","):
            part = part.strip()
            if not part:
                continue
            size, _, weight = part.partition(":")
            items.append((int(size), float(weight or 1.0)))
    if not items or any(size <= 0 for size, _ in items) or any(w <= 0 for _, w in items):
        raise LoadgenError("value-size mix requires positive sizes and weights")
    total = sum(w for _, w in items)
    return [(size, w / total) for size, w in items]


def pick_size(mix, rng):
    roll = rng.random()
    acc = 0.0
    for size, weight in mix:
        acc += weight
        if roll <= acc:
            return size
    return mix[-1][0]


def render_value(key, size):
    """Deterministic payload embedding the key so GETs can detect wrong content."""
    marker = key.encode("ascii") + b":"
    if size <= len(marker):
        seed = marker[:max(1, size)]
        return (seed * (size // len(seed) + 1))[:size]
    return marker + b"." * (size - len(marker))


def key_for(index, prefix):
    return f"{prefix}{index}"


def choose_index(rng, distribution, keys):
    if keys <= 1 or distribution == "uniform":
        return rng.randrange(max(1, keys))
    if distribution == "zipf":
        return min(keys - 1, int(keys * (rng.random() ** 4)))
    raise LoadgenError("unknown key distribution: " + distribution)


# --------------------------------------------------------------------------- CPU / cgroup

def _proc_ticks():
    try:
        fields = pathlib.Path("/proc/self/stat").read_text().rsplit(")", 1)[1].split()
        return int(fields[11]) + int(fields[12])
    except (OSError, IndexError, ValueError):
        return None


def _task_ticks():
    result = {}
    try:
        for path in pathlib.Path("/proc/self/task").glob("*/stat"):
            try:
                fields = path.read_text().rsplit(")", 1)[1].split()
                result[path.parent.name] = int(fields[11]) + int(fields[12])
            except (OSError, IndexError, ValueError):
                continue
    except OSError:
        return {}
    return result


def rss_bytes(pid=None):
    pid = pid or os.getpid()
    try:
        page = getattr(os, "sysconf", lambda _: 4096)("SC_PAGE_SIZE")
        return int(pathlib.Path(f"/proc/{pid}/statm").read_text().split()[1]) * page
    except (OSError, IndexError, ValueError, AttributeError):
        try:
            import resource
            return int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss) * 1024
        except Exception:  # noqa: BLE001
            return None


def cgroup_evidence():
    """Read cgroup v2 files when present. v1 or missing is explicit, never assumed."""
    root = pathlib.Path("/sys/fs/cgroup")
    evidence = {"version": None, "files": {}, "errors": {}}
    if (root / "cgroup.controllers").is_file():
        evidence["version"] = 2
        for name in ("cgroup.controllers", "cpu.stat", "cpu.max", "cpuset.cpus.effective",
                     "memory.max", "memory.swap.max", "memory.current", "memory.events",
                     "memory.stat"):
            try:
                evidence["files"][name] = (root / name).read_text().strip()
            except OSError as exc:
                evidence["errors"][name] = str(exc)
    else:
        evidence["version"] = 1 if root.is_dir() else None
        evidence["errors"]["cgroup.controllers"] = "cgroup v2 unified filesystem not mounted"
    return evidence


def parse_cpu_stat(text):
    values = {}
    for line in (text or "").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("-").isdigit():
            values[parts[0]] = int(parts[1])
    return values


def effective_cpu_count(cgroup):
    """CPUs actually allocated to this process (cgroup cpuset when available)."""
    text = (cgroup or {}).get("files", {}).get("cpuset.cpus.effective")
    if text:
        count = 0
        for part in str(text).split(","):
            part = part.strip()
            if not part:
                continue
            if "-" in part:
                lo, _, hi = part.partition("-")
                try:
                    count += int(hi) - int(lo) + 1
                except ValueError:
                    pass
            elif part.isdigit():
                count += 1
        if count:
            return count
    return os.cpu_count() or 1


class CpuWindow:
    """CPU measurement between two instants, portable except the Linux extras."""

    def __init__(self):
        self.wall = time.time()
        self.monotonic = time.monotonic()
        self.ticks = _proc_ticks()
        self.tasks = _task_ticks()

    def close(self, ticks1=None, tasks1=None):
        wall = time.time()
        ticks = _proc_ticks() if ticks1 is None else ticks1
        tasks = _task_ticks() if tasks1 is None else tasks1
        clock = getattr(os, "sysconf", lambda _: 100)("SC_CLK_TCK")
        span = max(1e-9, time.monotonic() - self.monotonic)
        total = None
        if self.ticks is not None and ticks is not None:
            total = max(0, ticks - self.ticks) / clock
        per_thread = {}
        for tid, after in tasks.items():
            before = self.tasks.get(tid, after)
            per_thread[tid] = max(0, after - before) / clock
        cgroup = cgroup_evidence()
        return {
            "windowSeconds": span,
            "wallStart": self.wall,
            "wallEnd": wall,
            "processCpuSeconds": total,
            "processCpuFraction": None if total is None else total / span,
            "perThreadCpuSeconds": per_thread,
            "maxThreadCpuSeconds": max(per_thread.values()) if per_thread else None,
            "clockTicksPerSecond": clock,
            "logicalCpus": os.cpu_count(),
            "clientCpuCount": effective_cpu_count(cgroup),
            "processCpuSecondsUnavailableReason": None if total is not None else "/proc/self/stat not readable",
            "cgroup": cgroup,
        }


# --------------------------------------------------------------------------- worker

class Conn:
    __slots__ = ("sock", "out", "inb", "pending", "connected", "closed", "error")

    def __init__(self, host, port):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setblocking(False)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.out = bytearray()
        self.inb = bytearray()
        self.pending = []
        self.connected = False
        self.closed = False
        self.error = None
        err = self.sock.connect_ex((host, port))
        if err == 0:
            self.connected = True
        elif err not in (errno.EINPROGRESS, errno.EWOULDBLOCK, errno.EALREADY, 10035):
            raise LoadgenError(f"connect failed immediately: {err}")

    def finish_connect(self):
        if self.connected:
            return True
        err = self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR)
        if err in (0, errno.EISCONN, 10056):
            self.connected = True
            return True
        self.error = f"SO_ERROR={err}"
        self.closed = True
        return False

    def read(self):
        try:
            data = self.sock.recv(1 << 16)
        except (BlockingIOError, InterruptedError):
            return True
        except OSError as exc:
            self.error = repr(exc)
            self.closed = True
            return False
        if not data:
            self.closed = True
            return False
        self.inb.extend(data)
        return True

    def flush(self):
        if not self.out:
            return True
        try:
            sent = self.sock.send(self.out)
            del self.out[:sent]
            return True
        except (BlockingIOError, InterruptedError):
            return True
        except OSError as exc:
            self.error = repr(exc)
            self.closed = True
            return False

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass
        self.closed = True


def take_line(buf, start):
    idx = buf.find(b"\r\n", start)
    if idx < 0:
        if len(buf) - start > 1 << 20:
            raise LoadgenError("response line exceeded 1 MiB without CRLF")
        return None
    return idx + 2, bytes(buf[start:idx])


class Totals:
    def __init__(self):
        self.ops = self.sets = self.gets = self.hits = self.misses = 0
        self.errors = self.timeouts = 0
        self.bytes_rx = self.bytes_tx = 0
        self.connections_opened = 0
        self.commands_sent = 0
        self.latency_us = array("q")
        self.error_kinds = {}


class Claim:
    """Thread-safe shared operation budget; also used to split churn cycles."""

    def __init__(self, limit):
        self.limit = limit
        self.granted = 0
        self.lock = threading.Lock()

    def take(self, want):
        if self.limit is None:
            return want
        with self.lock:
            left = self.limit - self.granted
            if left <= 0:
                return 0
            take = min(want, left)
            self.granted += take
            return take


class Worker(threading.Thread):
    def __init__(self, engine, worker_id, stop_event, claim):
        super().__init__(name=f"loadgen-w{worker_id}", daemon=True)
        self.engine = engine
        self.worker_id = worker_id
        self.stop_event = stop_event
        self.claim = claim
        self.local = Totals()
        self.rng = random.Random(engine.seed + worker_id * 7919)
        self.conns = []
        self._pending_claim = 0
        total = max(1, engine.keys)
        self._preload_lo = total * worker_id // engine.workers
        self._preload_hi = total * (worker_id + 1) // engine.workers
        self._preload_span = max(1, self._preload_hi - self._preload_lo)
        self._preload_seq = 0

    def _make_command(self):
        keys = self.engine.keys
        if self.engine.mode == "preload":
            index = self._preload_lo + self._preload_seq + self.engine.key_offset
            self._preload_seq += 1
        else:
            index = choose_index(self.rng, self.engine.distribution, keys) + self.engine.key_offset
        key = key_for(index, self.engine.key_prefix)
        want_set = self.engine.mode == "preload" or self.rng.random() < self.engine.set_fraction
        if want_set:
            size = pick_size(self.engine.value_mix, self.rng)
            payload = render_value(key, size)
            command = f"set {key} 0 {self.engine.ttl} {size}\r\n".encode("ascii") + payload + b"\r\n"
            return ["set", key, size, command]
        command = f"get {key}\r\n".encode("ascii")
        return ["get", key, None, command]

    def _fill(self, conn, deadline):
        if self.engine.mode == "sink_probe":
            while len(conn.out) < (1 << 20):
                command = self._make_command()[3]
                conn.out.extend(command)
                self.local.commands_sent += 1
                self.local.bytes_tx += len(command)
            return
        if self.engine.mode == "preload":
            while len(conn.pending) < self.engine.pipeline and self._preload_seq < self._preload_span:
                op = self._make_command()
                self.local.commands_sent += 1
                self.local.bytes_tx += len(op[3])
                conn.out.extend(op[3])
                conn.pending.append([op[0], op[1], op[2], time.monotonic()])
            return
        while len(conn.pending) < self.engine.pipeline:
            if deadline is not None and time.monotonic() >= deadline:
                return
            if self.claim is not None and self.claim.limit is not None and not self._claim_one():
                return
            op = self._make_command()
            self.local.commands_sent += 1
            self.local.bytes_tx += len(op[3])
            command = op[3]
            conn.out.extend(command)
            conn.pending.append([op[0], op[1], op[2], time.monotonic()])

    def _claim_one(self):
        if self._pending_claim <= 0:
            self._pending_claim = self.claim.take(max(8, self.engine.pipeline))
            if self._pending_claim <= 0:
                return False
        self._pending_claim -= 1
        return True

    def _consume(self, conn):
        while conn.pending:
            kind, key, size, sent_at = conn.pending[0]
            line = take_line(conn.inb, 0)
            if line is None:
                return True
            consumed, raw = line
            now = time.monotonic()
            if kind == "set":
                if raw == b"STORED":
                    self.local.ops += 1
                    self.local.sets += 1
                    self._latency(now - sent_at)
                else:
                    self.local.error_kinds["set:" + raw[:64].decode("ascii", "replace")] = \
                        self.local.error_kinds.get("set:" + raw[:64].decode("ascii", "replace"), 0) + 1
                    self.local.errors += 1
                del conn.inb[:consumed]
                self.local.bytes_rx += consumed
                conn.pending.pop(0)
                continue
            if raw == b"END":
                self.local.misses += 1
                self.local.ops += 1
                self.local.gets += 1
                self._latency(now - sent_at)
                del conn.inb[:consumed]
                self.local.bytes_rx += consumed
                conn.pending.pop(0)
                continue
            if raw.startswith(b"VALUE "):
                parts = raw.split(b" ")
                if len(parts) != 4:
                    self._error("vget_header")
                    del conn.inb[:consumed]
                    self.local.bytes_rx += consumed
                    conn.pending.pop(0)
                    continue
                try:
                    body = int(parts[3])
                except ValueError:
                    self._error("vget_length")
                    del conn.inb[:consumed]
                    self.local.bytes_rx += consumed
                    conn.pending.pop(0)
                    continue
                total = consumed + body + 2
                if len(conn.inb) < total:
                    return True
                payload = bytes(conn.inb[consumed:consumed + body])
                end = take_line(conn.inb, total)
                if end is None or bytes(conn.inb[consumed + body:consumed + body + 2]) != b"\r\n" or end[1] != b"END":
                    self._error("vget_terminator")
                    del conn.inb[:consumed]
                    self.local.bytes_rx += consumed
                    conn.pending.pop(0)
                    continue
                if self.engine.verify_values and payload != render_value(key, body):
                    self._error("vget_content")
                self.local.hits += 1
                self.local.gets += 1
                self.local.ops += 1
                self._latency(now - sent_at)
                del conn.inb[:end[0]]
                self.local.bytes_rx += end[0]
                conn.pending.pop(0)
                continue
            self._error("get:" + raw[:64].decode("ascii", "replace"))
            del conn.inb[:consumed]
            self.local.bytes_rx += consumed
            conn.pending.pop(0)
        return True

    def _latency(self, seconds):
        if len(self.local.latency_us) < LATENCY_CAP:
            self.local.latency_us.append(max(0, int(seconds * 1e6)))

    def _error(self, kind):
        self.local.error_kinds[kind] = self.local.error_kinds.get(kind, 0) + 1
        self.local.errors += 1

    def run(self):
        deadline = time.monotonic() + self.engine.duration_seconds if self.engine.duration_seconds else None
        if self.engine.mode == "sink_probe":
            return self._run_sink(deadline)
        selector = selectors.DefaultSelector()
        try:
            self.conns = [Conn(self.engine.host, self.engine.port)
                          for _ in range(self.engine.connections_per_worker)]
            for conn in self.conns:
                events = selectors.EVENT_READ | (0 if conn.connected else selectors.EVENT_WRITE)
                selector.register(conn.sock, events, conn)
            last_activity = time.monotonic()
            while not self.stop_event.is_set():
                if deadline is not None and time.monotonic() >= deadline:
                    break
                if self.engine.mode == "preload" and self._preload_seq >= self._preload_span \
                        and not any(c.pending or c.out for c in self.conns):
                    break
                if self.claim is not None and self.claim.limit is not None and self.claim.granted >= self.claim.limit \
                        and not any(c.pending or c.out for c in self.conns):
                    break
                for key, mask in selector.select(timeout=0.05):
                    conn = key.data
                    if conn.closed:
                        continue
                    if mask & selectors.EVENT_WRITE:
                        was_connected = conn.connected
                        if not conn.finish_connect():
                            self.local.connections_opened += 1
                            self._error("connect:" + str(conn.error))
                            try:
                                selector.unregister(conn.sock)
                            except (KeyError, ValueError):
                                pass
                            continue
                        if not was_connected:
                            self.local.connections_opened += 1
                        self._fill(conn, deadline)
                        conn.flush()
                        self._rearm(selector, conn)
                        last_activity = time.monotonic()
                    if mask & selectors.EVENT_READ:
                        if not conn.read():
                            try:
                                selector.unregister(conn.sock)
                            except (KeyError, ValueError):
                                pass
                            continue
                        self._consume(conn)
                        self._fill(conn, deadline)
                        conn.flush()
                        self._rearm(selector, conn)
                        last_activity = time.monotonic()
                    if time.monotonic() - last_activity > max(5.0, self.engine.io_timeout):
                        self._error("stalled")
                        conn.closed = True
                        try:
                            selector.unregister(conn.sock)
                        except (KeyError, ValueError):
                            pass
            self._drain(selector, deadline)
        finally:
            for conn in self.conns:
                conn.close()
            selector.close()

    def _rearm(self, selector, conn):
        events = selectors.EVENT_READ | (selectors.EVENT_WRITE if conn.out else 0)
        try:
            selector.modify(conn.sock, events, conn)
        except (KeyError, ValueError, OSError):
            pass

    def _drain(self, selector, deadline):
        end = min(time.monotonic() + DRAIN_SECONDS,
                  (deadline + DRAIN_SECONDS) if deadline is not None else time.monotonic() + DRAIN_SECONDS)
        while any(c.pending for c in self.conns if not c.closed) and time.monotonic() < end:
            for key, _ in selector.select(timeout=0.05):
                conn = key.data
                if conn.closed:
                    continue
                if conn.read():
                    self._consume(conn)

    def _run_sink(self, deadline):
        conns = []
        try:
            for _ in range(self.engine.connections_per_worker):
                conn = Conn(self.engine.host, self.engine.port)
                conns.append(conn)
            sel = selectors.DefaultSelector()
            for conn in conns:
                sel.register(conn.sock, selectors.EVENT_READ | (0 if conn.connected else selectors.EVENT_WRITE), conn)
            while deadline is None or time.monotonic() < deadline:
                for key, mask in sel.select(timeout=0.05):
                    conn = key.data
                    if mask & selectors.EVENT_WRITE and not conn.connected:
                        conn.finish_connect()
                        self.local.connections_opened += 1
                    self._fill(conn, None)
                    conn.flush()
                    if len(conn.out) > (4 << 20):
                        del conn.out[:]
                    try:
                        sel.modify(conn.sock, selectors.EVENT_READ | (selectors.EVENT_WRITE if conn.out else 0), conn)
                    except (KeyError, ValueError, OSError):
                        pass
            self.conns = conns
        finally:
            for conn in conns:
                conn.close()


# --------------------------------------------------------------------------- engine

class Engine:
    """Declared-plan generator. See module docstring for the offered-load contract."""

    def __init__(self, host, port, workers, connections_per_worker, pipeline, ratio,
                 keys, key_prefix="memtier-", distribution="uniform", value_mix="256:1.0",
                 seed=1, duration_seconds=10, total_ops=None, warmup_seconds=0,
                 parse_responses=True, verify_values=False, ttl=0, mode="mixed",
                 io_timeout=10.0, sample_interval=0.25, latency_sample_cap=LATENCY_CAP, key_offset=1):
        if workers < 1 or connections_per_worker < 1 or pipeline < 1:
            raise LoadgenError("workers, connections_per_worker and pipeline must be positive")
        self.host, self.port = host, port
        self.workers, self.connections_per_worker, self.pipeline = workers, connections_per_worker, pipeline
        self.ratio = ratio
        want_set, want_get = (int(x) for x in ratio.split(":"))
        if want_set + want_get <= 0:
            raise LoadgenError("ratio must be set:get with a positive total")
        self.set_fraction = want_set / (want_set + want_get)
        self.keys, self.key_prefix, self.distribution = keys, key_prefix, distribution
        self.value_mix = parse_value_mix(value_mix)
        self.seed, self.duration_seconds, self.total_ops = seed, duration_seconds, total_ops
        self.warmup_seconds, self.parse_responses, self.verify_values = warmup_seconds, parse_responses, verify_values
        self.ttl, self.mode, self.io_timeout = ttl, mode, io_timeout
        self.sample_interval, self.latency_sample_cap = sample_interval, latency_sample_cap
        self.key_offset = key_offset

    def plan(self):
        return {
            "engine": GENERATOR_NAME, "mode": self.mode, "protocol": "memcache_text",
            "host": self.host, "port": self.port,
            "workers": self.workers, "connectionsPerWorker": self.connections_per_worker,
            "connections": self.workers * self.connections_per_worker, "pipeline": self.pipeline,
            "ratioSetGet": self.ratio, "setFraction": self.set_fraction,
            "keyPrefix": self.key_prefix, "keys": self.keys, "keyDistribution": self.distribution,
            "keyOffset": self.key_offset,
            "valueSizeMix": [{"size": s, "weight": w} for s, w in self.value_mix], "ttl": self.ttl,
            "seed": self.seed, "parseResponses": self.parse_responses, "verifyValues": self.verify_values,
            "warmupSeconds": self.warmup_seconds, "durationSeconds": self.duration_seconds,
            "totalOps": self.total_ops,
            "stoppingRule": "fixed-op-count" if self.total_ops is not None else "fixed-duration",
        }

    # -- measurement ----------------------------------------------------------
    def measure(self, duration=None, total_ops=None, collect_timeline=True, warmup=False):
        stop = threading.Event()
        claim = Claim(total_ops)
        workers = [Worker(self, i, stop, claim) for i in range(self.workers)]
        cpu = CpuWindow()
        t0_wall, t0_mono = time.time(), time.monotonic()
        for worker in workers:
            worker.start()
        timeline, rss = [], []
        last_ops, last_sample = 0, t0_mono
        effective = self.duration_seconds if duration is None else duration
        if effective is not None and effective <= 0:
            effective = None
        while any(worker.is_alive() for worker in workers):
            if not warmup and effective and (time.monotonic() - t0_mono) >= effective:
                stop.set()
            time.sleep(self.sample_interval)
            now = time.monotonic()
            ops = sum(w.local.ops for w in workers)
            if collect_timeline and now - last_sample >= 0.5:
                timeline.append({"monotonic": now, "wall": time.time(), "ops": ops - last_ops,
                                 "cumulativeOps": ops, "errors": sum(w.local.errors for w in workers),
                                 "connections": sum(1 for w in workers for c in w.conns if c.connected and not c.closed)})
                last_ops, last_sample = ops, now
            value = rss_bytes()
            if value is not None:
                rss.append({"monotonic": now, "wall": time.time(), "rssBytes": value})
        stop.set()
        for worker in workers:
            worker.join(timeout=30)
        t1_wall, t1_mono = time.time(), time.monotonic()
        return self._collect(workers, cpu, t0_wall, t0_mono, t1_wall, t1_mono, timeline, rss)

    def _collect(self, workers, cpu, t0_wall, t0_mono, t1_wall, t1_mono, timeline, rss):
        total = Totals()
        for worker in workers:
            local = worker.local
            for field in ("ops", "sets", "gets", "hits", "misses", "errors", "timeouts",
                          "bytes_rx", "bytes_tx", "connections_opened", "commands_sent"):
                setattr(total, field, getattr(total, field) + getattr(local, field))
            total.latency_us.extend(local.latency_us[: self.latency_sample_cap])
            for kind, count in local.error_kinds.items():
                total.error_kinds[kind] = total.error_kinds.get(kind, 0) + count
        seconds = max(1e-9, t1_mono - t0_mono)
        latency = latency_summary(total.latency_us)
        achieved_connections = max([entry.get("connections", 0) for entry in timeline] or [total.connections_opened])
        observed = {
            "ops": total.ops, "sets": total.sets, "gets": total.gets, "hits": total.hits,
            "misses": total.misses, "errors": total.errors, "timeouts": total.timeouts,
            "bytesRx": total.bytes_rx, "bytesTx": total.bytes_tx,
            "connectionsOpened": total.connections_opened, "commandsSent": total.commands_sent,
            "connections": achieved_connections,
            "setFraction": (total.sets / total.ops) if total.ops else None,
            "hitRatio": (total.hits / (total.hits + total.misses)) if (total.hits + total.misses) else None,
            "opsPerSecond": total.ops / seconds,
            "errorKinds": dict(sorted(total.error_kinds.items())),
        }
        return {
            "measurement": {"seconds": seconds, "wallStart": t0_wall, "wallEnd": t1_wall,
                            "monotonicStart": t0_mono, "monotonicEnd": t1_mono},
            "totals": observed, "latencyUs": latency, "timeline": timeline, "rss": rss,
            "cpu": cpu.close(),
        }

    def run(self):
        report = {"schemaVersion": SCHEMA_VERSION, "generator": GENERATOR_NAME,
                  "generatorSha256": sha256_file(__file__) if os.path.isfile(__file__) else None,
                  "plan": self.plan(), "exitCode": 1, "errors": []}
        if self.mode == "churn":
            return self._churn(report)
        if self.mode == "sink_probe":
            sink = DiscardSink(self.host).start()
            try:
                probe = Engine(sink.host if hasattr(sink, "host") else "127.0.0.1", sink.port,
                               self.workers, self.connections_per_worker, self.pipeline, self.ratio,
                               self.keys, self.key_prefix, self.distribution,
                               self.value_mix, self.seed, self.duration_seconds,
                               mode="sink_probe", parse_responses=False).measure()
            finally:
                sink.stop()
            report.update(probe)
            report["exitCode"] = 0
            return report
        if self.warmup_seconds:
            warm = Engine(self.host, self.port, self.workers, self.connections_per_worker, self.pipeline,
                          self.ratio, self.keys, self.key_prefix, self.distribution, self.value_mix,
                          self.seed, self.warmup_seconds, self.total_ops if self.mode == "preload" else None,
                          warmup_seconds=0, parse_responses=self.parse_responses,
                          verify_values=self.verify_values, ttl=self.ttl, mode=self.mode,
                          io_timeout=self.io_timeout, latency_sample_cap=self.latency_sample_cap).measure()
            report["warmup"] = {"ops": warm["totals"]["ops"], "errors": warm["totals"]["errors"],
                                "seconds": warm["measurement"]["seconds"], "separateConnections": True}
        report.update(self.measure(duration=self.duration_seconds, total_ops=self.total_ops))
        report["offeredLoad"] = self.offered_load(report)
        report["exitCode"] = 0 if report["totals"]["errors"] == 0 else 1
        return report

    def offered_load(self, report):
        totals = report.get("totals", {})
        return {
            "definition": "closed-loop bounded concurrency; achieved ops/s is measured, not a target",
            "connections": self.workers * self.connections_per_worker,
            "pipeline": self.pipeline,
            "maxInFlightCommands": self.workers * self.connections_per_worker * self.pipeline,
            "ratioSetGet": self.ratio, "keyDistribution": self.distribution,
            "valueSizeMix": report.get("plan", {}).get("valueSizeMix"),
            "achievedOpsPerSecond": totals.get("opsPerSecond"),
            "achievedSetFraction": totals.get("setFraction"),
            "achievedHitRatio": totals.get("hitRatio"),
            "stoppingRule": report.get("plan", {}).get("stoppingRule"),
        }

    def _churn(self, report):
        cycles = self.total_ops if self.total_ops is not None else 100
        claim = Claim(cycles)
        done, errors = [], []
        lock = threading.Lock()

        def worker(worker_id):
            while True:
                if claim.take(1) <= 0:
                    return
                begin = time.monotonic()
                try:
                    sock = socket.create_connection((self.host, self.port), timeout=self.io_timeout)
                    sock.settimeout(self.io_timeout)
                    if self.parse_responses:
                        sock.sendall(b"version\r\n")
                        line = b""
                        while not line.endswith(b"\r\n"):
                            chunk = sock.recv(256)
                            if not chunk:
                                raise LoadgenError("EOF during handshake")
                            line += chunk
                        if not line.startswith(b"VERSION "):
                            raise LoadgenError("bad handshake: " + line[:32].decode("ascii", "replace"))
                    sock.close()
                    with lock:
                        done.append(time.monotonic() - begin)
                except Exception as exc:  # noqa: BLE001
                    with lock:
                        errors.append(repr(exc))

        threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(self.workers)]
        cpu = CpuWindow()
        t0_wall, t0_mono = time.time(), time.monotonic()
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=max(1.0, self.io_timeout * cycles))
        unreaped = [t.name for t in threads if t.is_alive()]
        report.update({
            "cyclesSeconds": done, "validatedCycles": len(done), "cycleErrors": errors,
            "unreapedWorkers": unreaped,
            "measurement": {"seconds": time.monotonic() - t0_mono, "wallStart": t0_wall, "wallEnd": time.time()},
            "totals": {"ops": len(done), "errors": len(errors), "connectionsOpened": len(done) + len(errors)},
            "cpu": cpu.close(),
            "exitCode": 0 if len(done) == cycles and not errors and not unreaped else 1,
        })
        return report


# --------------------------------------------------------------------------- sink

class DiscardSink:
    """Loopback sink that accepts and discards bytes; bounds generator capacity."""

    def __init__(self, host="127.0.0.1"):
        self.host = host
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((host, 0))
        self.server.listen(2048)
        self.port = self.server.getsockname()[1]
        self.bytes = 0
        self.closed = False
        self._thread = threading.Thread(target=self._serve, daemon=True)

    def _serve(self):
        self.server.settimeout(0.2)
        while not self.closed:
            try:
                client, _ = self.server.accept()
            except (socket.timeout, BlockingIOError, InterruptedError):
                continue
            except OSError:
                return
            threading.Thread(target=self._drain, args=(client,), daemon=True).start()

    def _drain(self, client):
        try:
            client.settimeout(0.5)
            while not self.closed:
                try:
                    data = client.recv(1 << 16)
                except (socket.timeout, BlockingIOError, InterruptedError):
                    continue
                except OSError:
                    break
                if not data:
                    break
                self.bytes += len(data)
        finally:
            try:
                client.close()
            except OSError:
                pass

    def start(self):
        self._thread.start()
        return self

    def stop(self):
        self.closed = True
        try:
            self.server.close()
        except OSError:
            pass


def capacity_probe(plan, probe_seconds=2.0):
    """Upper bound on generator command rate: same concurrency/values, no reply wait."""
    sink = DiscardSink().start()
    try:
        engine = Engine(sink.host, sink.port, max(1, plan.get("workers", 4)),
                        max(1, plan.get("connectionsPerWorker", 1)), max(1, plan.get("pipeline", 1)),
                        plan.get("ratioSetGet", "1:9"), max(1, plan.get("keys", 1024)),
                        plan.get("keyPrefix", "memtier-"), plan.get("keyDistribution", "uniform"),
                        plan.get("valueSizeMix", "256:1.0"), plan.get("seed", 1),
                        probe_seconds, mode="sink_probe")
        result = engine.measure()
        return {"method": "discard-sink-upper-bound", "seconds": result["measurement"]["seconds"],
                "commandsPerSecondUpperBound": result["totals"]["commandsSent"] / max(1e-9, result["measurement"]["seconds"]),
                "sinkBytes": sink.bytes, "note": "discard sink never replies; this bounds generator push rate"}
    finally:
        sink.stop()


def preload(host, port, count, size, workers=8, connections_per_worker=4, pipeline=32,
            key_prefix="memtier-", ttl=0, timeout=60.0):
    """Fill `count` keys of `size` bytes and return the preload evidence dict."""
    if count <= 0:
        return {"requested": 0, "stored": 0, "response": "STORED", "durationSeconds": 0.0}
    engine = Engine(host, port, max(1, min(workers, count)), max(1, connections_per_worker),
                    max(1, pipeline), "1:0", count, key_prefix, "uniform", f"{size}:1.0",
                    seed=1, duration_seconds=0, total_ops=count, mode="preload", io_timeout=timeout)
    report = engine.run()
    totals = report["totals"]
    ok = totals["sets"] == count and totals["errors"] == 0
    return {"requested": count, "stored": totals["sets"],
            "response": "STORED" if ok else "PRELOAD_INCOMPLETE",
            "durationSeconds": report["measurement"]["seconds"],
            "errors": totals["errors"], "errorKinds": totals["errorKinds"],
            "commandsSent": totals["commandsSent"],
            "bytesTx": totals["bytesTx"], "bytesRx": totals["bytesRx"]}


def churn(host, port, cycles=100, workers=1, handshake=True, timeout=10.0):
    """Run `cycles` open/handshake/close cycles; returns the generator report."""
    engine = Engine(host, port, max(1, workers), 1, 1, "0:1", 1, seed=1, duration_seconds=0,
                    total_ops=cycles, mode="churn", io_timeout=timeout, parse_responses=handshake)
    return engine.run()


# --------------------------------------------------------------------------- report

def percentile(ordered, fraction):
    if not ordered:
        return None
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def latency_summary(values):
    if not values:
        return {"count": 0}
    ordered = sorted(values)
    return {"count": len(ordered), "p50": percentile(ordered, .50), "p95": percentile(ordered, .95),
            "p99": percentile(ordered, .99), "max": ordered[-1]}


# --------------------------------------------------------------------------- CLI

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--mode", choices=("mixed", "preload", "churn", "sink_probe"), default="mixed")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--connections-per-worker", type=int, default=1)
    parser.add_argument("--pipeline", type=int, default=1)
    parser.add_argument("--ratio", default="1:9")
    parser.add_argument("--keys", type=int, default=4096)
    parser.add_argument("--key-prefix", default="memtier-")
    parser.add_argument("--key-distribution", choices=("uniform", "zipf"), default="uniform")
    parser.add_argument("--value-mix", default="256:1.0")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--duration", type=float, default=10.0)
    parser.add_argument("--ops", type=int, default=None)
    parser.add_argument("--warmup", type=float, default=0.0)
    parser.add_argument("--verify-values", action="store_true")
    parser.add_argument("--output", type=pathlib.Path, default=None)
    parser.add_argument("--capacity-probe-seconds", type=float, default=0.0)
    args = parser.parse_args(argv)
    engine = Engine(args.host, args.port, args.workers, args.connections_per_worker, args.pipeline,
                    args.ratio, args.keys, args.key_prefix, args.key_distribution, args.value_mix,
                    args.seed, args.duration, args.ops, args.warmup, True, args.verify_values, mode=args.mode)
    report = engine.run()
    if args.capacity_probe_seconds:
        report["generatorHeadroom"] = capacity_probe(report["plan"], args.capacity_probe_seconds)
    if args.output:
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    else:
        print(json.dumps({"plan": report["plan"], "totals": report.get("totals"), "exitCode": report["exitCode"]}, indent=2))
    return report["exitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
