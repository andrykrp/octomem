"""Execute real scenario readers over independent protocol reply socket doubles."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fixtures import ROOT, load
from test_wire_contract import frame, inventory

h = load("tests/platform/linux/linux_suite.py", "pipeline_linux")


class Peer:
    def __init__(self, reference):
        self.reference = reference
        self.buffer = b""
        self.values = {}
        self.closed = False
        self.eof = False

    def settimeout(self, timeout):
        pass
    def setsockopt(self, *args):
        pass
    def shutdown(self, *args):
        pass
    def close(self):
        self.closed = True
    def recv(self, size):
        if not self.buffer:
            if self.eof:
                return b""
            import socket
            raise socket.timeout("no queued reply")
        result, self.buffer = self.buffer[:size], self.buffer[size:]
        return result

    def sendall(self, request):
        if request.endswith(b"\r\nquit\r\n"):
            request = request[:-6]
            self.eof = True
        words = request.split(b"\r\n", 1)[0].split()
        cmd = words[0] if words else b""
        response = b""
        if cmd == b"version":
            response = b"VERSION " + (b"1.6.45" if self.reference else b"1.0.0 (octomem)") + b"\r\n"
        elif cmd in (b"set", b"add", b"replace", b"append", b"prepend", b"cas"):
            key = words[1]
            value = request.split(b"\r\n", 1)[1][:-2]
            response = b"STORED\r\n"
            if len(value) > 1048576:
                response = b"SERVER_ERROR object too large for cache\r\n"
            elif cmd == b"cas":
                response = b"EXISTS\r\n" if key in self.values else b"NOT_FOUND\r\n"
            elif (cmd == b"add" and key in self.values) or (cmd in (b"replace", b"append", b"prepend") and key not in self.values):
                response = b"NOT_STORED\r\n"
            else:
                if cmd in (b"append", b"prepend"):
                    flags, old = self.values[key]
                    value = old + value if cmd == b"append" else value + old
                else:
                    flags = words[2]
                self.values[key] = (flags, value)
        elif cmd in (b"get", b"gets", b"gat", b"gats"):
            keys = words[2:] if cmd in (b"gat", b"gats") else words[1:]
            if any(len(k) > 250 for k in keys):
                response = b"CLIENT_ERROR bad command line format\r\n" + (b"\r\n" if self.reference else b"")
            else:
                for key in keys:
                    if key in self.values:
                        flags, value = self.values[key]
                        response += b"VALUE " + key + b" " + flags + b" " + str(len(value)).encode() + (b" 42" if cmd in (b"gets", b"gats") else b"") + b"\r\n" + value + b"\r\n"
                response += b"END\r\n"
        elif cmd in (b"incr", b"decr"):
            if words[1] not in self.values:
                response = b"NOT_FOUND\r\n"
            else:
                flags, value = self.values[words[1]]
                if not value.isdigit():
                    response = b"CLIENT_ERROR cannot increment or decrement non-numeric value\r\n"
                else:
                    number = (int(value)+int(words[2])) % 2**64 if cmd == b"incr" else max(0, int(value)-int(words[2]))
                    self.values[words[1]] = (flags, str(number).encode())
                    response = str(number).encode()+b"\r\n"
        elif cmd == b"delete":
            response = b"DELETED\r\n" if self.values.pop(words[1], None) is not None else b"NOT_FOUND\r\n"
        elif cmd == b"touch":
            response = b"TOUCHED\r\n" if words[1] in self.values else b"NOT_FOUND\r\n"
        elif cmd == b"flush_all":
            self.values.clear()
            response = b"OK\r\n"
        elif cmd == b"stats":
            if words[1:] == [b"reset"]:
                response = b"RESET\r\n"
            elif words[1:] in ([b"items"], [b"slabs"]):
                items = words[1] == b"items"
                fields = inventory(items)
                if not items:
                    for cls in (1, 4):
                        fields.update({f"{cls}:get_hits": "1", f"{cls}:cmd_set": "1"})
                    fields.update({"1:chunk_size": "96" if self.reference else "64",
                                   "4:chunk_size": "192" if self.reference else "136",
                                   "1:mem_requested": "40", "4:mem_requested": "136"})
                response = frame(fields)
            else:
                manifest = json.loads((ROOT / "compatibility-manifest.json").read_text())
                fields = dict.fromkeys(manifest["statsFields"], "0")
                fields.update(cmd_get="3", cmd_set="2", get_hits="2", get_misses="1", total_items="2",
                              curr_items="2", bytes="225" if self.reference else "176",
                              version="1.6.45" if self.reference else "1.0.0 (octomem)")
                response = frame(fields)
        elif cmd == b"quit":
            self.eof = True
        elif not cmd:
            response = b"CLIENT_ERROR bad command line format\r\n" if self.reference else b"ERROR\r\n"
        else:
            response = b"ERROR\r\n"
        if b"noreply" not in words:
            self.buffer += response


class WirePipelineTests(unittest.TestCase):
    def test_full_seeded_sequence_reference_three_key_counter_and_error_isolation(self):
        transcripts = []
        for reference in (False, True):
            shared = {}
            peers = []
            def connect(*args, **kwargs):
                peer = Peer(reference)
                peer.values = shared
                peers.append(peer)
                return peer
            with patch.object(h.socket, "create_connection", side_effect=connect), patch.object(h, "MCFG", ROOT / "compatibility-manifest.json"):
                transcript = h.run_scenario(SimpleNamespace(port=0), "reference" if reference else "octomem")
                transcripts.append(transcript)
                if not reference:
                    checks = h.read_expected_checks(transcript)
                    self.assertTrue(all(x["ok"] for x in checks), [x for x in checks if not x["ok"]])
            raw = {x["name"]: bytes.fromhex(x["recv_hex"]) for x in transcript}
            self.assertEqual(raw["oracle_cleanup"], b"END\r\n")
            self.assertEqual(raw["oracle_reset"], b"RESET\r\n")
            self.assertEqual(raw["oracle_seed_small"], b"STORED\r\n")
            self.assertEqual(raw["oracle_get"], b"VALUE a 0 1\r\nx\r\nVALUE b 7 100\r\n"+b"y"*100+b"\r\nEND\r\n")
            self.assertTrue(all(p.closed for p in peers))
        with patch.object(h, "MCFG", ROOT / "compatibility-manifest.json"):
            result = h.differential(*transcripts)
        self.assertFalse([x for x in result["steps"] if x["classification"] == "UNEXPLAINED"], result)
        self.assertFalse([x for x in result["stats"].values() if x["unexplained_octomem_only"]], result)
        import copy
        corrupted = copy.deepcopy(transcripts[1])
        for step in corrupted:
            if step["name"] == "stats":
                step["recv_hex"] = bytes.fromhex(step["recv_hex"]).replace(b"STAT cmd_get 3\r\n", b"STAT cmd_get 1\r\n").hex()
        with patch.object(h, "MCFG", ROOT / "compatibility-manifest.json"):
            rejected = h.differential(transcripts[0], corrupted)
        self.assertTrue(any("cmd_get" in x for x in rejected["stats"]["general"]["unexplained_octomem_only"]))
