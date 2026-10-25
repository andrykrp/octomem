"""Bounded entrypoint/import and immutable fresh-artifact controls."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

from fixtures import IDENTITY, ROOT, load, row


class EntrypointTests(unittest.TestCase):
    def test_silent_blocking_peer_is_bounded_and_child_reaped(self):
        code = r'''
import importlib.util, socket, sys, time
spec = importlib.util.spec_from_file_location('wire', sys.argv[1])
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
class Silent:
    def settimeout(self, timeout): self.timeout = timeout
    def recv(self, count):
        time.sleep(self.timeout)
        raise socket.timeout('silent peer')
conn = h.Conn.__new__(h.Conn)
conn.timeout, conn.deadline, conn.buf, conn.sock = .05, None, b'', Silent()
try: conn.line()
except TimeoutError: print('TIMED_OUT_NOT_EOF')
else: raise AssertionError('silent peer accepted')
'''
        proc = subprocess.Popen([sys.executable, "-I", "-B", "-c", code,
                                 str(ROOT / "tests/platform/linux/linux_suite.py")],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            stdout, stderr = proc.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate(timeout=3)
            self.fail("silent peer escaped absolute deadline; child killed and reaped")
        self.assertEqual(proc.returncode, 0, stderr)
        self.assertEqual(stdout.strip(), b"TIMED_OUT_NOT_EOF")

    def test_imports_have_no_io_effects(self):
        # The child audit hook allows reads needed for imports but forbids effects.
        code = r'''
import sys, importlib.util, os
sys.dont_write_bytecode = True
def audit(event, args):
    if event.startswith(('socket.', 'subprocess.', 'os.mkdir', 'os.remove', 'os.rename', 'os.system')):
        raise AssertionError('import effect: ' + event)
    if event == 'open' and ((isinstance(args[1], str) and any(x in args[1] for x in 'wax+')) or
                            isinstance(args[2], int) and args[2] & (os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC)):
        raise AssertionError('import writes: ' + str(args))
sys.addaudithook(audit)
for index, path in enumerate(sys.argv[1:]):
    spec = importlib.util.spec_from_file_location('safe_import_' + str(index), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
print('IMPORTS_SAFE')
'''
        paths = [ROOT / "tests/platform" / name for name in
                 ("linux/oracle_wire.py", "linux/linux_suite.py", "perf/contracts.py",
                  "perf/run.py", "perf/finalize.py", "perf/analyze.py", "perf/supplement.py")]
        result = subprocess.run([sys.executable, "-I", "-B", "-c", code, *map(str, paths)],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "IMPORTS_SAFE")

    def test_pinned_hash_shell_predicate_positive_and_negative(self):
        source = (ROOT / "tests/platform/stand/remote-build-r2.sh").read_text()
        pin = re.search(r"^PINNED_JAR=([0-9a-fA-F]{64})$", source, re.M)
        function = re.search(r"^check_jar\(\) \{\n.*?^\}", source, re.M | re.S)
        self.assertIsNotNone(pin)
        self.assertIsNotNone(function)
        candidates = [Path("C:/Program Files/Git/bin/bash.exe"), Path("C:/Program Files/Git/usr/bin/bash.exe")]
        shell = next((str(p) for p in candidates if p.is_file()), shutil.which("bash"))
        self.assertIsNotNone(shell, "Bash required to execute actual production predicate")
        for digest, expected in ((pin[1].lower(), 0), (pin[1].upper(), 0), ("0"*64, 2)):
            script = "PINNED_JAR=" + pin[1] + "\n" + function[0] + "\nsha256sum() { printf '%s target/octomem-1.0.0.jar\\n' '" + digest + "'; }\ncheck_jar\n"
            result = subprocess.run([shell, "--noprofile", "--norc", "-s"], input=script,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, expected, result.stderr)
        self.assertGreaterEqual(source.count("check_jar || exit 2"), 3)

    def test_stale_artifact_digest_and_name_reader(self):
        f = load("tests/platform/perf/finalize.py", "independent_artifact_reader")
        with tempfile.TemporaryDirectory(prefix="independent-harness-") as directory:
            root = Path(directory) / IDENTITY["attemptId"]
            root.mkdir()
            native = json.dumps(row()["native"]).encode()
            name = "P01-c1-r0-reference.memtier.json"
            (root / name).write_bytes(native)
            for artifact, reason in (({"name": name, "sha256": "0"*64}, "artifact_digest_mismatch_or_missing"),
                                     ({"name": "previous-attempt.json", "sha256": hashlib.sha256(native).hexdigest()}, "artifact_name_mismatch")):
                data = row()
                data.pop("native")
                data["artifact"] = artifact
                (root / "passport.json").write_text(json.dumps({"schemaVersion": 2, "identity": IDENTITY}))
                (root / "runs.json").write_text(json.dumps([data]))
                _, loaded, errors = f.read_attempt(root)
                self.assertEqual(len(loaded), 1)
                self.assertIn(reason, loaded[0]["evidenceErrors"])
                self.assertNotIn("native", loaded[0])
                self.assertTrue(errors)  # Other deliberately absent passport evidence also fails closed.

    def test_cli_invalid_attempt_exits_nonzero_and_refuses_raw_overwrite(self):
        with tempfile.TemporaryDirectory(prefix="independent-cli-") as directory:
            root = Path(directory) / "attempt"
            root.mkdir()
            (root / "passport.json").write_text(json.dumps({"identity": {"attemptId": "attempt"}}))
            (root / "runs.json").write_text("[]")
            before = {p.name: p.read_bytes() for p in root.iterdir()}
            for output, expected in ((Path(directory) / "analysis", 1), (root, 2)):
                result = subprocess.run([sys.executable, "-B", str(ROOT / "tests/platform/perf/finalize.py"),
                                         "--input", str(root), "--output", str(output)],
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, expected, result.stderr)
                if expected == 1:
                    summary = json.loads((output / "summary.json").read_text())
                    self.assertTrue(all(r["verdict"] == "INCONCLUSIVE" for r in summary))
                    self.assertTrue(all(m["reasons"] for r in summary for m in r["metrics"].values()))
            self.assertEqual(before, {p.name: p.read_bytes() for p in root.iterdir()})
