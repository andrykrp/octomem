"""Complete six-pair artifact fixtures; row validity is not campaign completeness."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from fixtures import IDENTITY, ROOT, WINDOW, load, passport
from test_runtime_formats import native_row

f = load("tests/platform/perf/finalize.py", "roundtrip_finalizer")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_attempt(parent):
    root = Path(parent) / "independent-attempt"
    root.mkdir()
    plan = passport()
    plan.update(collectorOutput=str(root), paths=dict(java="/jdk/bin/java", reference="/bin/memcached",
                jar="/candidate.jar", client="/bin/memtier_benchmark"))
    (root / "harness").mkdir()
    archived = ROOT / "tests/platform/perf/run.py"
    (root / "harness/run.py").write_bytes(archived.read_bytes())
    plan["identity"]["harnessSha256"] = digest(archived)
    plan["harnessFiles"] = {"run.py": digest(archived)}
    data = []
    for rep in range(6):
        for server in ("reference", "octomem"):
            item = native_row(rep, server)
            item["identity"] = copy.deepcopy(plan["identity"])
            # A complete six-pair qualification artifact carries the generator
            # non-saturation evidence required by contracts.generator_saturation_reasons.
            # Synthetic but self-consistent with this fixture: one memtier thread on the
            # "4-15" client cpuset (12 CPUs) and ~100 native ops/s against a >=2x ceiling.
            item["performanceQualification"] = True
            item["generatorCpu"] = {"processCpuFraction": 0.9, "clientCpuCount": 12,
                                    "maxThreadCpuSeconds": 0.9}
            item["generatorHeadroom"] = {"ceilingOpsPerSecond": 1000, "achievedOpsPerSecond": 100}
            label = f"P01-c1-r{rep}-{server}"
            name = label + ".memtier.json"
            (root / name).write_text(json.dumps(item.pop("native")))
            item["artifact"] = {"name": name, "sha256": digest(root / name)}
            item["argv"] = ["taskset", "-c", "4-15", "/bin/memtier_benchmark", "--server=127.0.0.1",
                "--port=11219", "--protocol=memcache_text", "--threads=1", "--clients=1", "--ratio=0:1",
                "--pipeline=1", "--data-size=256", "--key-minimum=1", "--key-maximum=4096",
                "--key-prefix=memtier-", "--key-pattern=R:R", "--test-time=12", "--print-percentiles=50,95,99",
                "--hide-histogram", "--json-out-file=" + (root / name).as_posix()]
            if server == "reference":
                args = ["/bin/memcached", "-u", "root", "-l", "127.0.0.1", "-p", "11219", "-U", "0",
                        "-m", "256", "-I", "1m", "-c", "12000", "-t", "4"]
            else:
                args = ["/jdk/bin/java", "-Xms32m", "-Xmx512m", "-XX:ActiveProcessorCount=4",
                        "--enable-native-access=ALL-UNNAMED", "-jar", "/candidate.jar", "--listen.address=127.0.0.1",
                        "--listen.port=11219", "--memory.limit=256m", "--item.maxSize=1m", "--connections.max=12000",
                        "--transport.workers=4", "--connections.idleTimeoutSeconds=0", "--log.level=error"]
            item["process"]["argv"] = ["taskset", "-c", "0-3"] + args
            data.append(item)
    calibration = []
    for server in ("reference", "octomem"):
        for key in (1, 16, 250):
            for flags in (0, 1, 65535, 4294967295):
                calibration.append(dict(server=server, keyBytes=key, flags=flags, accepted=1000,
                    rejected=1001, identity=plan["identity"], checks=[dict(valueBytes=1000, response="STORED",
                    readbackVerified=True, payloadSha256=hashlib.sha256(b"x"*1000).hexdigest()),
                    dict(valueBytes=1001, response="SERVER_ERROR object too large for cache")]))
    (root / "passport.json").write_text(json.dumps(plan))
    (root / "runs.json").write_text(json.dumps(data))
    (root / "calibration.json").write_text(json.dumps(calibration))
    seal(root, plan)
    return root, plan, data


def seal(root, plan):
    manifest = {path.name: digest(path) for path in root.iterdir() if path.is_file() and path.name != "complete.json"}
    (root / "complete.json").write_text(json.dumps(dict(identity=plan["identity"], completed=True,
        captureSealed=True, planComplete=False,
        runsSha256=digest(root / "runs.json"), passportSha256=digest(root / "passport.json"), artifacts=manifest)))


class ArtifactRoundtripTests(unittest.TestCase):
    def test_complete_six_pair_reader_to_verdict_positive(self):
        with tempfile.TemporaryDirectory() as directory:
            root, _, _ = write_attempt(directory)
            plan, data, errors = f.read_attempt(root)
            self.assertEqual(errors, ["campaign_plan_incomplete"])
            self.assertEqual(plan["captureStatus"], {"sealed": True, "planComplete": False})
            self.assertEqual(len(data), 12)
            result = f.build_scenario(data, "P01-c1", plan)
            self.assertEqual(result["verdict"], "PASS", result)
            self.assertTrue(all(v["valid"] for v in result["validation"]))
            campaign = f.build_scenario(data, "P01-c1", plan, errors)
            self.assertEqual(campaign["verdict"], "INCONCLUSIVE")
            # This complete scenario does not fabricate the rest of a campaign.
            absent = f.build_scenario(data, "P03", plan, errors)
            self.assertEqual(absent["verdict"], "INCONCLUSIVE")
            self.assertTrue(any("missing_pair" in x for x in absent["pairReasons"]))

    def test_wrong_name_hash_order_duplicate_and_stale_seal(self):
        for mutation in ("name", "hash", "order", "duplicate", "stale_seal"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root, plan, data = write_attempt(directory)
                if mutation == "name":
                    data[0]["artifact"]["name"] = "P01-c1-reference.memtier.json"
                elif mutation == "hash":
                    data[0]["artifact"]["sha256"] = "0"*64
                elif mutation == "order":
                    data[0]["orderIndex"] = 1
                elif mutation == "duplicate":
                    data.append(copy.deepcopy(data[0]))
                else:
                    data[0]["exitCode"] = 7
                (root / "runs.json").write_text(json.dumps(data))
                if mutation != "stale_seal":
                    seal(root, plan)
                read_plan, loaded, errors = f.read_attempt(root)
                result = f.build_scenario(loaded, "P01-c1", read_plan, errors)
                for metric in result["metrics"].values():
                    self.assertEqual(metric["verdict"], "INCONCLUSIVE", result)
                    self.assertTrue(metric["reasons"])
                    self.assertNotIn("medianPairedRatio", metric)
                    self.assertTrue(any(reason != "campaign_plan_incomplete" for reason in metric["reasons"]))
                self.assertEqual(len(loaded), len(data))
