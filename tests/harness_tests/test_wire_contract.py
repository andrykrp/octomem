"""T1/T2 independently authored byte oracles and finite socket doubles."""
import contextlib
import io
import socket
import unittest
from unittest.mock import patch

from fixtures import load

o = load("tests/platform/linux/oracle_wire.py", "independent_wire_oracle")
h = load("tests/platform/linux/linux_suite.py", "independent_linux")
ITEMS = ("number age evicted evicted_nonzero evicted_time outofmemory tailrepairs "
         "reclaimed expired_unfetched evicted_unfetched crawler_reclaimed").split()
SLABS = ("chunk_size chunks_per_page total_pages total_chunks used_chunks free_chunks "
         "free_chunks_end get_hits cmd_set delete_hits incr_hits decr_hits cas_hits cas_badval mem_requested").split()


def inventory(items=True):
    suffixes = ITEMS if items else SLABS
    return {("items:" if items else "") + str(cls) + ":" + suffix:
            ("1" if suffix in ("number", "used_chunks") else "0")
            for cls in (1, 4) for suffix in suffixes}


def frame(fields):
    return b"".join(("STAT " + k + " " + v + "\r\n").encode("ascii") for k, v in fields.items()) + b"END\r\n"


class WireOracleTests(unittest.TestCase):
    def test_valid_seeded_inventory_and_each_missing_field(self):
        for items in (True, False):
            fields = inventory(items)
            templates = [("items:N:" if items else "N:") + s for s in (ITEMS if items else SLABS)]
            self.assertEqual(o.validate_inventory(frame(fields), templates, {1, 4}), fields)
            for key in fields:
                with self.subTest(items=items, missing=key):
                    changed = dict(fields)
                    del changed[key]
                    with self.assertRaises(ValueError):
                        o.validate_inventory(frame(changed), templates, {1, 4})
            for bad in (b"END\r\n", b"STAT 1:chunk_size 96\r\nEND\r\n"):
                with self.assertRaises(ValueError):
                    o.validate_inventory(bad, templates, {1, 4})

    def test_inventory_empty_seed_zero_malformed_and_extra_class(self):
        templates = ["items:N:"+s for s in ITEMS]
        for classes in (set(), {0}, {-1}, {"1"}, {True}):
            with self.assertRaises(ValueError):
                o.validate_inventory(frame(inventory()), templates, classes)
        for key, value in (("items:1:number", "0"), ("items:1:number", "NaN"),
                           ("items:1:age", "-1"), ("items:01:number", "1"),
                           ("items:7:number", "1")):
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError):
                    o.validate_inventory(frame(dict(inventory(), **{key: value})), templates, {1, 4})

    def test_parser_rejects_duplicate_truncated_extra_and_malformed_frames(self):
        good = b"STAT cmd_get 3\r\nEND\r\n"
        self.assertEqual(o.parse_stats(good), {"cmd_get": "3"})
        for bad in (b"", good[:-1], good+b"END\r\n", good.replace(b"STAT", b"stat"),
                    b"STAT x 1\r\nSTAT x 2\r\nEND\r\n", b"STAT x\r\nEND\r\n",
                    b"STAT x \xff\r\nEND\r\n", b"STAT x 1\nEND\n"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    o.parse_stats(bad)

    def test_known_probes_require_literal_pairs_even_if_identical(self):
        cases = [("probe_duplicate_key_get", b"VALUE dk 0 1\r\nD\r\nEND\r\n",
                  b"VALUE dk 0 1\r\nD\r\nVALUE dk 0 1\r\nD\r\nEND\r\n", "known-difference"),
                 ("probe_legacy_delete_time", b"DELETED\r\n", b"DELETED\r\n", "identical"),
                 ("probe_single_lf_terminator", b"END\r\n", b"END\r\n", "identical")]
        for name, octo, ref, expected in cases:
            self.assertEqual(o.classify_reply(name, octo, ref), expected)
            for bad in (b"ERROR\r\n", b"<EOF>", b"", b"CORRUPTED\r\n"):
                for a, b in ((bad, ref), (octo, bad), (bad, bad)):
                    self.assertEqual(o.classify_reply(name, a, b), "UNEXPLAINED", (name, a, b))
        self.assertEqual(o.classify_reply("ordinary", b"STORED\r\n", b"ERROR\r\n"), "UNEXPLAINED")

    def test_deterministic_counter_wrong_missing_and_malformed(self):
        expected = dict(cmd_get=3, cmd_set=2, get_hits=2, get_misses=1, total_items=2)
        fields = {k: str(v) for k, v in expected.items()}
        self.assertEqual(o.validate_counters(fields, expected), [])
        for key in expected:
            for value in (None, "0", "wrong", "1.5"):
                changed = dict(fields, **{key: value})
                errors = o.validate_counters(changed, expected)
                self.assertTrue(any(key in error for error in errors))


class SocketDouble:
    def __init__(self, chunks, clock=None):
        self.chunks = iter(chunks)
        self.clock = clock
        self.calls = 0
        self.timeouts = []

    def settimeout(self, timeout):
        self.timeouts.append(timeout)

    def recv(self, count):
        self.calls += 1
        if self.calls > 20:
            raise AssertionError("bounded socket double exhausted")
        if self.clock is not None:
            self.clock[0] += .4
        value = next(self.chunks, b"")
        if isinstance(value, Exception):
            raise value
        return value

    def sendall(self, data):
        pass

    def shutdown(self, how):
        pass

    def close(self):
        pass


def connection(sock):
    conn = h.Conn.__new__(h.Conn)
    conn.sock, conn.buf, conn.timeout, conn.deadline = sock, b"", 1., None
    return conn


class SocketTests(unittest.TestCase):
    def test_timeouts_are_not_eof_for_every_reader(self):
        for method, args in (("line", ()), ("read_raw", (1,)), ("read_until", ()),
                             ("read_to_eof", ()), ("read_value", ())):
            with self.subTest(method=method):
                sock = SocketDouble([socket.timeout("silent")])
                with self.assertRaises(TimeoutError):
                    getattr(connection(sock), method)(*args)
                self.assertEqual(sock.calls, 1)

    def test_real_eof_and_truncation(self):
        self.assertEqual(connection(SocketDouble([b""])).read_to_eof(), b"")
        for method, args, chunks in (("line", (), [b"STO", b""]),
                                     ("read_raw", (4,), [b"abc", b""]),
                                     ("read_until", (), [b"STAT x 1\r\n", b""]),
                                     ("read_value", (), [b"VALUE k 0 4\r\nabc", b""])):
            with self.subTest(method=method):
                with self.assertRaises(EOFError):
                    getattr(connection(SocketDouble(chunks)), method)(*args)

    def test_trickle_uses_absolute_not_per_packet_deadline(self):
        for method, args in (("line", ()), ("read_raw", (100,)), ("read_until", ()), ("read_to_eof", ())):
            clock = [0.]
            sock = SocketDouble([b"x"]*20, clock)
            with patch.object(h.time, "monotonic", side_effect=lambda: clock[0]):
                with self.assertRaises(TimeoutError):
                    getattr(connection(sock), method)(*args)
            self.assertLessEqual(sock.calls, 3)
            self.assertLessEqual(clock[0], 1.3)
            self.assertGreater(sock.timeouts[0], sock.timeouts[-1])

    def test_size_and_terminator_bounds(self):
        for raw in (b"VALUE k 0 4194304\r\n", b"VALUE k 0 -1\r\n",
                    b"VALUE k 0 1\r\nxZZEND\r\n"):
            with self.assertRaises(ValueError):
                connection(SocketDouble([raw])).read_value()
        with self.assertRaises(ValueError):
            connection(SocketDouble([b"x"*8193+b"\r\n"])).line()
        with self.assertRaises(ValueError):
            connection(SocketDouble([])).read_raw(4194305)
        self.assertEqual(connection(SocketDouble([b"VALUE k 0 1\r\nx\r\nEND\r\n"])).read_value(),
                         b"VALUE k 0 1\r\nx\r\nEND\r\n")

    def test_noreply_silence_is_not_closed_socket_and_barrier_works(self):
        sock = SocketDouble([socket.timeout(), b"VERSION 1.0.0 (octomem)\r\n"])
        conn = connection(sock)
        self.assertEqual(conn.expect_silence(), b"")
        conn.send(b"version\r\n")
        self.assertEqual(conn.line(), b"VERSION 1.0.0 (octomem)\r\n")
        with self.assertRaises(EOFError):
            connection(SocketDouble([b""])).expect_silence()
        with self.assertRaises(ValueError):
            connection(SocketDouble([b"x"])).expect_silence()


class ProtocolDouble:
    mode = "valid"
    instances = []

    def __init__(self, *args, **kwargs):
        self.values, self.request, self.closed = {}, b"", False
        self.instances.append(self)

    def send(self, request):
        self.request = request

    def line(self):
        parts = self.request.split()
        if parts[0] == b"delete":
            return b"DELETED\r\n" if self.values.pop(parts[1], None) is not None else b"NOT_FOUND\r\n"
        if parts[0] == b"set":
            if self.mode == "set_error":
                return b"SERVER_ERROR synthetic\r\n"
            self.values[parts[1]] = self.request.split(b"\r\n")[1]
            return b"STORED\r\n"
        raise AssertionError(self.request)

    def read_value(self):
        if self.mode == "get_bad":
            return b"VALUE wrong 0 1\r\nx\r\nEND\r\n"
        key = self.request.split()[1]
        value = self.values.get(key)
        return b"END\r\n" if value is None else b"VALUE " + key + b" 0 " + str(len(value)).encode() + b"\r\n" + value + b"\r\nEND\r\n"

    def close(self):
        self.closed = True


class ClientTests(unittest.TestCase):
    def test_server_timeout_termination_kills_and_reaps(self):
        import subprocess
        from unittest.mock import MagicMock
        server = h.Server("octomem", 0, ".")
        server.proc, server.log = MagicMock(), MagicMock()
        server.proc.poll.return_value = None
        server.proc.wait.side_effect = [subprocess.TimeoutExpired("server", 10), 0]
        server.stop()
        server.proc.terminate.assert_called_once()
        server.proc.kill.assert_called_once()
        self.assertEqual(server.proc.wait.call_count, 2)
        self.assertTrue(all(call.kwargs.get("timeout", 0) > 0 for call in server.proc.wait.call_args_list))
        server.log.close.assert_called_once()

    def test_valid_workers_complete_exactly(self):
        ProtocolDouble.mode, ProtocolDouble.instances = "valid", []
        with patch.object(h, "Conn", ProtocolDouble):
            result = h.perf_client(0, 30, 2, 1)
        self.assertTrue(result["valid"], result)
        self.assertEqual(result["ops"], 60)
        self.assertEqual(result["workers_completed"], 2)
        self.assertEqual(result["workers_alive"], 0)
        self.assertTrue(all(c.closed for c in ProtocolDouble.instances))

    def test_worker_loss_get_corruption_set_error_are_not_partial_success(self):
        for mode in ("get_bad", "set_error", "lost"):
            with self.subTest(mode=mode):
                ProtocolDouble.mode, ProtocolDouble.instances = mode, []
                factory = ProtocolDouble if mode != "lost" else lambda *a, **k: (_ for _ in ()).throw(OSError("connection refused"))
                with patch.object(h, "Conn", factory):
                    result = h.perf_client(0, 30, 2, 1)
                self.assertFalse(result["valid"], result)
                self.assertGreater(result["errors"], 0)
                self.assertEqual(result["workers_alive"], 0)
                self.assertLess(result["ops"], result["expected_ops"])
                self.assertTrue(all(c.closed for c in ProtocolDouble.instances))

    def test_perf_command_propagates_invalid_worker_result(self):
        from unittest.mock import MagicMock
        for valid, expected in ((True, 0), (False, 1)):
            server = MagicMock()
            server.return_value.rss_kb.return_value = 1024
            with contextlib.ExitStack() as stack:
                for name, value in (("ensure_mounts", lambda: None), ("OUT", MagicMock()),
                                    ("Server", server), ("free_port", lambda: 0),
                                    ("sha256_file", lambda p: "a"*64), ("write_json", lambda *a: None),
                                    ("perf_client", lambda *a: {"valid": valid})):
                    stack.enter_context(patch.object(h, name, value))
                stack.enter_context(patch.object(h.Path, "read_text", return_value="model name: synthetic\nMemTotal: 1000 kB"))
                stack.enter_context(patch.object(h.time, "sleep", return_value=None))
                stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
                self.assertEqual(h.cmd_perf(), expected)
