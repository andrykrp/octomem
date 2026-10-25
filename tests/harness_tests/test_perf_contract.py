"""T3 acceptance: invalidate evidence BEFORE interpreting adverse ratios."""
import copy
import itertools
import unittest

from fixtures import COUNTERS, IDENTITY, P01, WINDOW, load, passport, row, rows, samples

c = load("tests/platform/perf/contracts.py", "independent_perf_contracts")
f = load("tests/platform/perf/finalize.py", "independent_finalizer")


class IntervalTests(unittest.TestCase):
    def test_coverage_by_exhaustive_sign_assignments(self):
        # Every sign vector is equiprobable for continuous independent draws.
        # Enumerate events directly; no binomial coefficient/rank implementation reuse.
        for n, coverage, method in ((3, .75, "exploratory-range"), (6, .96875, "exact-binomial-median")):
            signs = list(itertools.product((-1, 1), repeat=n))
            covered = sum(min(outcome) < 0 < max(outcome) for outcome in signs)
            self.assertEqual(covered / len(signs), coverage)
            result = c.median_interval(list(range(n, 0, -1)))
            self.assertEqual(result["coverage"], coverage)
            self.assertEqual(result["bounds"], [1, n])
            self.assertEqual(result["rank"], 1)
            self.assertEqual(result["n"], n)
            self.assertEqual(result["method"], method)
            self.assertEqual(result["sufficientPrecision"], n == 6)

    def test_invalid_ratios_never_reach_threshold(self):
        for values in ([], [0]*6, [-1]*6, [float("nan")]*6, [float("inf")]*6, [True]*6):
            with self.subTest(values=values):
                with self.assertRaises(ValueError):
                    c.median_interval(values)
                result = c.classify("M1", values, expected_pairs=6)
                self.assertEqual(result["verdict"], "INCONCLUSIVE")
                self.assertNotIn("medianPairedRatio", result)
                self.assertTrue(result["reasons"])

    def test_thresholds_and_interval_crossing(self):
        for metric, boundary, bad in (("M1", .9, .89), ("M2", 1.25, 1.26), ("M3", 1.2, 1.21)):
            with self.subTest(metric=metric):
                self.assertEqual(c.classify(metric, [boundary]*6, expected_pairs=6)["verdict"], "PASS")
                self.assertEqual(c.classify(metric, [bad]*6, expected_pairs=6)["verdict"], "FAIL")
                result = c.classify(metric, [bad]*6, ["synthetic_invalid_exit"], 6)
                self.assertEqual(result["verdict"], "INCONCLUSIVE")
                self.assertNotIn("medianPairedRatio", result)
                self.assertIn("synthetic_invalid_exit", result["reasons"])
        self.assertEqual(c.classify("M1", [.8, 1, 1, 1, 1, 1], expected_pairs=6)["reasons"], ["interval_crosses_threshold"])
        self.assertEqual(c.classify("M1", [1]*3, expected_pairs=3)["reasons"], ["insufficient_precision"])

    def test_missing_invalid_and_incomplete_plan(self):
        for plan in (None, 0, 1, True, "6", 8):
            result = c.classify("M1", [.1]*6, expected_pairs=plan)
            self.assertEqual(result["verdict"], "INCONCLUSIVE")
            self.assertNotIn("medianPairedRatio", result)


class EvidenceTests(unittest.TestCase):
    def test_valid_six_pairs_pass_without_equal_completed_counts(self):
        result = f.build_scenario(rows(rate=110), "P01-c1", passport())
        self.assertEqual(result["verdict"], "PASS", result)
        self.assertEqual(result["rowCount"], 12)
        self.assertEqual(result["metrics"]["M1"]["medianPairedRatio"], 1.1)

    def test_valid_adverse_measurements_fail(self):
        result = f.build_scenario(rows(rate=80), "P01-c1", passport())
        self.assertEqual(result["metrics"]["M1"]["verdict"], "FAIL", result)

    def test_pair_inventory_retains_duplicates_errors_and_unexpected(self):
        base = rows()
        for changed, reason in ((base[:-1], "missing_pair:"),
                                (base + [dict(base[0], error="lost worker")], "duplicate_pair:"),
                                (base + [dict(base[0], rep=6)], "unexpected_pair:")):
            with self.subTest(reason=reason):
                result = c.validate_pairs(changed, "P01-c1", 6)
                self.assertFalse(result["valid"])
                self.assertEqual(result["rows"], changed)
                self.assertTrue(any(x.startswith(reason) for x in result["reasons"]))
                final = f.build_scenario(changed, "P01-c1", passport())
                for metric in final["metrics"].values():
                    self.assertEqual(metric["verdict"], "INCONCLUSIVE")
                    self.assertNotIn("medianPairedRatio", metric)

    def test_common_invalid_evidence_beats_adverse_median(self):
        mutations = [
            ("exit", lambda r: r.update(exitCode=2), "client_exit"),
            ("timeout", lambda r: r.update(timedOut=True), "client_timeout"),
            ("preload", lambda r: r["preload"].update(stored=4095), "preload_incomplete"),
            ("preload_response", lambda r: r["preload"].update(response="SERVER_ERROR broken"), "preload_incomplete"),
            ("identity", lambda r: r["identity"].update(jarSha256="f"*64), "identity_mismatch"),
            ("workload", lambda r: r["workload"].update(ttl=1), "workload_mismatch"),
            ("native_keys", lambda r: r["native"]["configuration"].update(key_minimum=0), "native_workload_mismatch"),
            ("regression", lambda r: r["statsAfter"].update(total_items="4095"), "stats_delta_invalid"),
            ("resident", lambda r: r["statsAfter"].update(curr_items="4095"), "residency_mismatch"),
            ("stale_time", lambda r: r["native"]["ALL STATS"]["Runtime"].update(**{"Start time": 0}), "native_time_outside_attempt"),
            ("get_bytes", lambda r: r["native"]["ALL STATS"]["Gets"]["Time-Serie"]["0"].update(**{"Bytes RX": 1}), "get_payload_bytes_mismatch"),
            ("set_error", lambda r: r["native"]["ALL STATS"]["Sets"]["Time-Serie"]["0"].update(**{"Bytes RX": 10}), "set_response_accounting_mismatch"),
            ("connections", lambda r: r["rssWindow"][0].update(connections=0), "achieved_connections_unproven"),
            ("artifact", lambda r: r.update(evidenceErrors=["artifact_digest_mismatch_or_missing"]), "artifact_digest"),
            ("gc", lambda r: r.update(forcedGcDuringMeasurement=True), "forced_gc"),
            ("order", lambda r: r.update(orderIndex=9), "pair_order_mismatch"),
        ]
        for name, mutate, reason in mutations:
            with self.subTest(name=name):
                data = rows(rate=50)
                mutate(data[1])
                result = f.build_scenario(data, "P01-c1", passport())
                for metric in result["metrics"].values():
                    self.assertEqual(metric["verdict"], "INCONCLUSIVE", result)
                    self.assertNotIn("medianPairedRatio", metric)
                    self.assertTrue(any(reason in x for x in metric["reasons"]), metric)

    def test_rss_gap_and_instability_only_invalidate_memory(self):
        for mutation in (lambda r: r["rssWindow"][4].update(monotonic=109),
                         lambda r: r["rssWindow"][4].update(total_rss=2000000),
                         lambda r: r.update(rssWindow=r["rssWindow"][:2])):
            data = rows()
            mutation(data[0])
            result = f.build_scenario(data, "P01-c1", passport())
            self.assertEqual(result["metrics"]["M3"]["verdict"], "INCONCLUSIVE")
            self.assertTrue(result["metrics"]["M3"]["reasons"])
            self.assertEqual(result["metrics"]["M1"]["verdict"], "PASS")
            self.assertEqual(result["metrics"]["M2"]["verdict"], "PASS")

    def test_unstable_operation_window_invalidates_all_metrics(self):
        data = rows()
        buckets = data[0]["native"]["ALL STATS"]["Gets"]["Time-Serie"]
        for i in range(12):
            count = 50 if i < 6 else 150
            buckets[str(i)] = {"Count": count, "Bytes RX": count*286}
        result = f.build_scenario(data, "P01-c1", passport())
        for metric in result["metrics"].values():
            self.assertEqual(metric["verdict"], "INCONCLUSIVE")
            self.assertTrue(any("workload_not_converged" in r for r in metric["reasons"]))

    def test_busy_cpu_warning_does_not_hide_failure_or_assert_cause(self):
        data = rows(rate=80)
        for r in data:
            r["peakClientThreadCpu"] = .95
        result = f.build_scenario(data, "P01-c1", passport())
        self.assertEqual(result["metrics"]["M1"]["verdict"], "FAIL")
        self.assertTrue(all("client_thread_busy_ge_0.85_not_causal_proof" in v["warnings"] for v in result["validation"]))

    def test_window_statistics_and_bad_timing(self):
        good = samples()[:6]
        result = c.qualify_window(good, WINDOW)
        self.assertTrue(result["valid"])
        for key in ("median", "mean", "sampledPeak"):
            self.assertEqual(result[key], 1000000)
        for times in ([1, 1, 1, 1, 1, 1], [1, 2, 3, 9, 10, 11], [6, 5, 4, 3, 2, 1]):
            altered = copy.deepcopy(good)
            for s, t in zip(altered, times):
                s["monotonic"] = t
            self.assertFalse(c.qualify_window(altered, WINDOW)["valid"])

    def test_exact_stat_deltas_and_missing_malformed_regressing(self):
        before = dict.fromkeys(COUNTERS, "10")
        after = dict.fromkeys(COUNTERS, "12")
        result = c.stats_delta(before, after)
        self.assertEqual(result, {"values": dict.fromkeys(COUNTERS, 2), "reasons": []})
        for bad in (None, "nope", "9", "1.5"):
            changed = dict(after, cmd_get=bad)
            self.assertIn("stats_delta_invalid:cmd_get", c.stats_delta(before, changed)["reasons"])
        del after["cmd_get"]
        self.assertIn("stats_delta_invalid:cmd_get", c.stats_delta(before, after)["reasons"])

    def test_fractional_and_boolean_stat_counters_are_malformed(self):
        before = dict.fromkeys(COUNTERS, "0")
        for bad in (1.5, True):
            with self.subTest(bad=bad):
                after = dict(before, cmd_get=bad)
                self.assertIn("stats_delta_invalid:cmd_get", c.stats_delta(before, after)["reasons"])

    def test_workload_contract_literal(self):
        self.assertEqual(c.workload("P01-c1"), P01)
        self.assertEqual(c.workload("P03"), dict(P01, ratio="1:0", pipeline=100))
        self.assertEqual(c.workload("P06"), dict(P01, count=32768, size=1024, connections=32,
                                               ratio="1:0", memoryMiB=16, threads=8, residency="pressure"))
        self.assertEqual(c.workload("M3-1024"), dict(P01, count=1024, size=1024, connections=0,
                                                   ratio="0:0", threads=0, kind="memory"))
        self.assertEqual(c.WINDOW_CONFIG, WINDOW)
