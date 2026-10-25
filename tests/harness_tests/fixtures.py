"""Independent contract fixtures, never populated from harness output."""
import copy
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WINDOW = dict(minSamples=6, minSpanSeconds=3., maxGapSeconds=1.5,
              maxRelativeDrift=.05, maxRelativeRange=.15, trimSeconds=1.,
              ratioTolerance=.03, measurementSeconds=12)
IDENTITY = dict(attemptId="independent-attempt", jarSha256="a"*64,
                referenceSha256="b"*64, memtierSha256="c"*64,
                harnessSha256="d"*64, javaSha256="e"*64, javaVersion="25.0.2",
                referenceVersion="1.6.45", octomemVersion="1.0.0 (octomem)")
P01 = dict(count=4096, size=256, connections=1, ratio="0:1", pipeline=1,
           memoryMiB=256, ttl=0, threads=1, residency="resident", kind="memtier")
COUNTERS = ("cmd_get", "cmd_set", "get_hits", "get_misses", "evictions", "total_items")


def load(relative, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def samples(start=101., rss=1000000):
    return [dict(monotonic=start+i, wall=start+i, total_rss=rss,
                 connections=1, serverCpuTicks={"1": i+1}, clientCpuTicks={"2": i+1},
                 intervalSeconds=1.) for i in range(10)]


def row(rep=0, server="reference", rate=100, rss=1000000):
    order = ["reference", "octomem"] if rep % 2 == 0 else ["octomem", "reference"]
    start = 100 + rep*100 + order.index(server)*30
    before = dict.fromkeys(COUNTERS, "0")
    before.update(total_items="4096", curr_items="4096", limit_maxbytes="268435456")
    after = dict(before, cmd_get=str(rate*12), get_hits=str(rate*12))
    cg = {"cpu.max": "max 100000", "cpuset.cpus.effective": "0-3",
          "memory.max": "8589934592", "memory.swap.max": "0",
          "cpu.stat": "nr_throttled 0\nthrottled_usec 0\n",
          "memory.events": "oom 0\noom_kill 0\nmax 0\n"}
    native = {"configuration": dict(protocol="memcache_text", key_minimum=1,
              key_maximum=4096, key_prefix="memtier-", key_pattern="R:R", data_size=256,
              ratio="0:1", pipeline=1, expiry_range="0:0", test_time=12, threads=1, clients=1),
              "ALL STATS": {"Runtime": {"Start time": start*1000, "Finish time": (start+12)*1000,
                                       "Time unit": "MILLISECONDS"},
              "Sets": {"Count": 0, "Time-Serie": {str(i): {"Count": 0, "Bytes RX": 0} for i in range(12)}},
              "Gets": {"Count": rate*12, "Misses/sec": 0,
                       "Time-Serie": {str(i): {"Count": rate, "Bytes RX": rate*286} for i in range(12)}},
              "Totals": {"Ops/sec": rate, "Percentile Latencies": {"p99.00": 1.}}}}
    return dict(scenario="P01-c1", rep=rep, server=server, identity=copy.deepcopy(IDENTITY),
                workload=copy.deepcopy(P01), exitCode=0, timedOut=False,
                preload=dict(requested=4096, stored=4096, response="STORED"),
                process=dict(argv=["server", server], version=IDENTITY[server+"Version"], startedWall=start-1),
                expectedServerArgv=["server", server], argv=["client"], expectedClientArgv=["client"],
                statsBefore=before, statsAfter=after, windowConfig=copy.deepcopy(WINDOW),
                forcedGcDuringMeasurement=False, cgroupBefore=cg, cgroupAfter=copy.deepcopy(cg),
                rssWindow=samples(start+1, rss), samples=samples(start+1, rss), total_rss=rss,
                native=native, wallStart=start-.1, wallEnd=start+12.1,
                pairOrder=order, orderIndex=order.index(server))


def passport():
    return dict(schemaVersion=2, repetitions=6, stoppingRule="fixed-pairs-no-extension",
                identity=copy.deepcopy(IDENTITY), workloads={"P01-c1": copy.deepcopy(P01)},
                windowConfig=copy.deepcopy(WINDOW))


def rows(rate=100):
    return [row(rep, server, rate if server == "octomem" else 100)
            for rep in range(6) for server in ("reference", "octomem")]
