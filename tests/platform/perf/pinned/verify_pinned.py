#!/usr/bin/env python3
"""Verify the frozen pinned estimator copy is byte-identical to SHA256SUMS.

This script is the observable proof that `tests/platform/perf/pinned/**` still
holds byte-for-byte copies of the five campaign-pinned estimator files. It never
edits anything; it only re-hashes and compares.

Exit 0  -> every listed file matches SHA256SUMS and the campaign pin.
Exit 1  -> a digest mismatch, a missing file, or an unexpected file in the set.
"""
import hashlib
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent

# Campaign pin captured from the frozen attempt
# tests/reports/release/perf-qual/rosa-20260918-6pairs/attempt-9c62c1250095424eac29376414587680/harness/
# and cross-checked in prep-20260922/protocol-stage7b.json ("pinnedHarness").
CAMPAIGN_PIN = {
    "finalize.py": "9cc15c1ac61cfb4cada6fb77dcfd681495ee66114fab5ea0bcc4916b6654ca77",
    "contracts.py": "244be682e755242bed38c54c4fb94e3c54b2f6d783116347831f5954c5602793",
    "gates.py": "1243737576f4e3a76b1f8389b9af306d4ed12a16f190db5e69f615b4a5ce921a",
    "run.py": "07b07ea6e978572edad99ec6cda751b0ad242f30a8d78b165643d4ff8a1de7d2",
    "generator.py": "473eaf3956514519fb1ac759edb4fe22ba397b89d289f77f1a3818813da267cd",
}


def digest(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def read_sums(path):
    sums = {}
    for line in path.read_text(encoding="ascii").splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2:
            raise ValueError("malformed SHA256SUMS line: " + line)
        sums[parts[1]] = parts[0].lower()
    return sums


def main():
    sums_path = HERE / "SHA256SUMS"
    if not sums_path.is_file():
        print("FAIL missing SHA256SUMS")
        return 1
    sums = read_sums(sums_path)
    failures = []
    if set(sums) != set(CAMPAIGN_PIN):
        failures.append("SHA256SUMS set %s != campaign pin set %s" % (sorted(sums), sorted(CAMPAIGN_PIN)))
    for name, expected in sorted(CAMPAIGN_PIN.items()):
        path = HERE / name
        if not path.is_file():
            failures.append("MISSING " + name)
            continue
        actual = digest(path)
        if sums.get(name) != expected:
            failures.append("SHA256SUMS mismatch for %s: %s != %s" % (name, sums.get(name), expected))
        if actual != expected:
            failures.append("file mismatch for %s: %s != %s" % (name, actual, expected))
        print("%s  %s  %s" % ("OK " if actual == expected else "BAD", actual, name))
    # Reject unexpected payload files in the pinned directory.
    allowed = set(CAMPAIGN_PIN) | {"SHA256SUMS", "PROVENANCE.md", "verify_pinned.py"}
    for entry in sorted(HERE.iterdir()):
        if entry.is_file() and entry.name not in allowed:
            failures.append("UNEXPECTED file in pinned/: " + entry.name)
    if failures:
        print("FAIL")
        for failure in failures:
            print("  - " + failure)
        return 1
    print("PINNED_COPY_BYTE_IDENTICAL")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
