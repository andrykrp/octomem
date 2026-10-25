"""R1/R3/R4: runtime-shaped inputs with independently fixed expectations."""
import copy
import unittest
from unittest.mock import patch

from fixtures import IDENTITY, P01, WINDOW, load, passport, row, rows

c = load("tests/platform/perf/contracts.py", "runtime_contracts")
r = load("tests/platform/perf/run.py", "runtime_collector")
f = load("tests/platform/perf/finalize.py", "runtime_finalizer")


def native_row(rep=0, server="reference", sets=False, tail=True):
    result = row(rep, server)
    native = result["native"]
    if sets:
        result["scenario"] = "P03"
        result["workload"] = dict(P01, ratio="1:0", pipeline=100)
        native["configuration"].update(ratio="1:0", pipeline=100)
        native["ALL STATS"]["Sets"] = {"Count": 1200, "Time-Serie": {
            str(i): {"Count": 100, "Bytes RX": 800} for i in range(12)}}
        native["ALL STATS"]["Gets"] = {"Count": 0, "Misses/sec": 0, "Time-Serie": {
            str(i): {"Count": 0, "Bytes RX": 0} for i in range(12)}}
        result["statsAfter"].update(cmd_get="0", get_hits="0", cmd_set="1200")
    if tail:
        stats = native["ALL STATS"]
        active, idle = ("Sets", "Gets") if sets else ("Gets", "Sets")
        stats[active]["Time-Serie"]["12"] = {"Count": 4, "Bytes RX": 32 if sets else 1144}
        stats[idle]["Time-Serie"]["12"] = {"Count": 0, "Bytes RX": 0}
        stats[active]["Count"] = 1204
        stats["Runtime"]["Finish time"] += 40
        stats["Totals"]["Ops/sec"] = 100
        if sets:
            result["statsAfter"]["cmd_set"] = "1204"
        else:
            result["statsAfter"].update(cmd_get="1204", get_hits="1204")
    return result


class StatsWireDouble:
    lines = []
    def __enter__(self):
        self.iterator = iter(self.lines)
        self.buffer = bytearray()
        return self
    def __exit__(self, *args):
        pass
    def send(self, request):
        assert request == b"stats\r\n"
    def read(self):
        return next(self.iterator)


class RuntimeFormatTests(unittest.TestCase):
    def test_product_version_with_spaces_is_valid_stats(self):
        StatsWireDouble.lines = [b"STAT version 1.0.0 (octomem)\r\n", b"STAT cmd_get 3\r\n", b"END\r\n"]
        with patch.object(r, "Wire", StatsWireDouble):
            self.assertEqual(r.stats(), {"version": "1.0.0 (octomem)", "cmd_get": "3"})

    def test_stats_duplicate_malformed_and_bad_framing(self):
        for lines in ([b"STAT x 1\r\n", b"STAT x 2\r\n", b"END\r\n"],
                      [b"STAT  1\r\n", b"END\r\n"], [b"STAT x \r\n", b"END\r\n"],
                      [b"STAT x 12xx", b"END\r\n"], [b"STAT x \xff\r\n", b"END\r\n"]):
            with self.subTest(lines=lines), patch.object(r, "Wire", StatsWireDouble):
                StatsWireDouble.lines = lines
                with self.assertRaises(ValueError):
                    r.stats()

    def test_twelve_full_buckets_plus_partial_tail_are_stable(self):
        for sets in (False, True):
            data = native_row(sets=sets)
            result = c.validate_row(data, data["workload"], IDENTITY, WINDOW)
            self.assertTrue(result["valid"], result)
            self.assertTrue(all(not reasons for reasons in result["metricReasons"].values()), result)
            self.assertEqual(result["observedWorkload"]["convergenceBucketIndices"], list(range(1, 11)))
            self.assertEqual(result["observedWorkload"]["convergenceBucketRule"],
                             "complete-interior-seconds-with-fixed-edge-trim")

    def test_real_drift_fails_with_partial_tail(self):
        data = native_row()
        buckets = data["native"]["ALL STATS"]["Gets"]["Time-Serie"]
        for i in range(12):
            count = 50 if i < 6 else 150
            buckets[str(i)] = {"Count": count, "Bytes RX": count*286}
        result = c.validate_row(data, P01, IDENTITY, WINDOW)
        for reasons in result["metricReasons"].values():
            self.assertIn("workload_not_converged", reasons)

    def test_fractional_cardinality_and_noninteger_pipeline_reject(self):
        for name, mutate in (("curr_items", lambda x: x["statsBefore"].update(curr_items=4096.9)),
                             ("budget", lambda x: x["statsBefore"].update(limit_maxbytes=268435456.9)),
                             ("pipeline_bool", lambda x: x["native"]["configuration"].update(pipeline=True)),
                             ("pipeline_float", lambda x: x["native"]["configuration"].update(pipeline=1.0)),
                             ("exit_float", lambda x: x.update(exitCode=0.0)),
                             ("preload_float", lambda x: x["preload"].update(stored=4096.0))):
            with self.subTest(name=name):
                data = rows()
                mutate(data[0])
                result = f.build_scenario(data, "P01-c1", passport())
                for metric in result["metrics"].values():
                    self.assertEqual(metric["verdict"], "INCONCLUSIVE", result)
                    self.assertTrue(metric["reasons"])
                    self.assertNotIn("medianPairedRatio", metric)

    def test_impossible_bucket_values_reject_even_when_sums_balance(self):
        for field in ("Count", "Bytes RX"):
            for bad in (-100, 99.5, True):
                with self.subTest(field=field, bad=bad):
                    data = native_row()
                    buckets = data["native"]["ALL STATS"]["Gets"]["Time-Serie"]
                    original = buckets["0"][field]
                    buckets["0"][field] = bad
                    buckets["1"][field] = 2*original-bad
                    result = c.validate_row(data, P01, IDENTITY, WINDOW)
                    self.assertFalse(result["valid"], result)
                    self.assertTrue(result["reasons"])

    def test_pipeline100_successful_set_and_actual_error_responses(self):
        good = native_row(sets=True)
        valid = c.validate_row(good, good["workload"], IDENTITY, WINDOW)
        self.assertTrue(valid["valid"], valid)
        self.assertEqual(good["native"]["ALL STATS"]["Sets"]["Count"], 1204)
        for reply in (b"ERROR\r\n", b"SERVER_ERROR synthetic\r\n", b"NOT_STORED\r\n"):
            data = copy.deepcopy(good)
            data["native"]["ALL STATS"]["Sets"]["Time-Serie"]["0"]["Bytes RX"] = 100*len(reply)
            result = c.validate_row(data, data["workload"], IDENTITY, WINDOW)
            self.assertFalse(result["valid"], result)
            self.assertTrue(any("set_response" in reason for reason in result["reasons"]), result)
