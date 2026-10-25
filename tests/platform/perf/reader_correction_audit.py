"""Correction-4 command recorder and immutable evidence comparison."""
import json
import pathlib
import sys
import contract_correction_audit as audit

audit.OUT = audit.ROOT / 'tests/reports/release/perf-qual/correction-cycle/contract-correction-4-evidence'


def main():
    if sys.argv[1] != 'prove':
        return audit.main()
    before = json.loads((audit.OUT / 'before-hashes.json').read_text())
    after = audit.snapshot()
    immutable = {p: after.get(p) == h for p, h in before.items() if '/attempt-' in p or
                 any('/analysis-' + str(i) + '/' in p for i in range(1, 6)) or 'tests/harness_tests/' in p}
    fresh = pathlib.Path(sys.argv[2]) / 'summary.json'
    old = audit.BASE / 'analysis-5/summary.json'
    current, prior = json.loads(fresh.read_text()), json.loads(old.read_text())
    metrics_equal = all(a['metrics'] == b['metrics'] and a['verdict'] == b['verdict']
                        for a, b in zip(current, prior)) and len(current) == len(prior)
    result = dict(immutableFiles=immutable, freshSummaryByteEqual=fresh.read_bytes() == old.read_bytes(),
                  metricsUnchanged=metrics_equal,
                  editedFiles={p: dict(before=before.get(p, 'ABSENT'), after=h)
                               for p, h in after.items() if before.get(p) != h})
    (audit.OUT / 'integrity-proof.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('immutable', len(immutable), all(immutable.values()), 'metrics unchanged', metrics_equal,
          'summary byte equal', result['freshSummaryByteEqual'])
    assert all(immutable.values()) and metrics_equal
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
