# Provenance header - pinned estimator copy

This directory is an **immutable, byte-for-byte copy** of the campaign-pinned
TEST-05 estimator. It exists so the historical estimator survives without editing
or deleting the live `tests/platform/perf/*.py` files.

- Source of the copies (inside this repository):
  `tests/reports/release/perf-qual/rosa-20260918-6pairs/attempt-9c62c1250095424eac29376414587680/harness/`
- Independent cross-reference: `tests/reports/release/perf-qual/prep-20260922/protocol-stage7b.json` -> `identities.pinnedHarness`.
- Copy method: byte copy (`Copy-Item`), never re-written through a text tool.

## Pinned files (full 64-hex SHA-256)

| File | SHA-256 | Role |
|---|---|---|
| `finalize.py` | `9cc15c1ac61cfb4cada6fb77dcfd681495ee66114fab5ea0bcc4916b6654ca77` | TEST-05 reader / scenario builder |
| `contracts.py` | `244be682e755242bed38c54c4fb94e3c54b2f6d783116347831f5954c5602793` | pure evidence contracts, thresholds, row validity |
| `gates.py` | `1243737576f4e3a76b1f8389b9af306d4ed12a16f190db5e69f615b4a5ce921a` | machine-readable validity gates |
| `run.py` | `07b07ea6e978572edad99ec6cda751b0ad242f30a8d78b165643d4ff8a1de7d2` | bounded TEST-05 collector |
| `generator.py` | `473eaf3956514519fb1ac759edb4fe22ba397b89d289f77f1a3818813da267cd` | bounded load generator |

## Verification

```
python tests/platform/perf/pinned/verify_pinned.py
```

Expected output ends with `PINNED_COPY_BYTE_IDENTICAL` and exits 0. The verifier
re-hashes every file against `SHA256SUMS` and against the campaign pin constant.
It is also re-run by `tests/harness_tests/test_v3_pinned_copy.py`.

## What this directory is authoritative for

The exact estimator generation sealed into the `rosa-20260918-6pairs` campaign
attempt. It is **not** the live estimator and **not** the new `v3` core; see
`tests/reports/release/perf-qual/HARNESS-PROVENANCE.md` for the full generation map.
