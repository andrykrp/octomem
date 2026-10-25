"""Retain correction-3 commands and hash inventories without touching old evidence."""
import json
import pathlib
import sys
import contract_correction_audit as audit

audit.OUT = audit.ROOT / 'tests/reports/release/perf-qual/correction-cycle/contract-correction-3-evidence'


def main():
    if sys.argv[1] == 'results':
        scenarios = json.loads((audit.BASE / 'analysis-5/summary.json').read_text())
        result = dict(taskId='task_15a869aa80ab', releaseVerdict='BLOCKED', analysis='rosa-20260918-6pairs/analysis-5',
                      qualification=True, candidateJarSha256='950a8edb702e51c9979320c117257426f1109076845e80f30774ef0855ff682b',
                      thresholds=dict(M1=.9, M2=1.25, M3=1.2), thresholdsChanged=False,
                      cpuContract='R-CPU-1: partial and defective CPU observations are disclosed; only no usable observation gates M1/M2; CPU observations never gate M3',
                      measurementLimitation='Interior-bucket convergence differs from full-window native Ops/sec and aggregate p99 estimators', scenarios=scenarios)
        for path in (audit.BASE / 'RESULTS.json', audit.BASE.parent / 'RESULTS.json'):
            path.write_text(json.dumps(result, indent=2), encoding='utf-8')
        fails = []
        for s in scenarios:
            print(s['scenario'], {m: (v['verdict'], v.get('medianPairedRatio')) for m, v in s['metrics'].items()})
            fails += [s['scenario'] + ':' + m for m, v in s['metrics'].items() if v['verdict'] == 'FAIL']
            assert not any(v['verdict'] == 'PASS' for v in s['metrics'].values())
            assert not any('generator_cpu' in r for r in s['metrics']['M3']['reasons'])
        (audit.OUT / 'verdict-census.json').write_text(json.dumps(dict(fails=fails, count=len(fails)), indent=2))
        return 0
    if sys.argv[1] != 'prove':
        return audit.main()
    before = json.loads((audit.OUT / 'before-hashes.json').read_text())
    after = audit.snapshot()
    immutable = {p: after.get(p) == h for p, h in before.items() if '/attempt-' in p or
                 any('/analysis-' + str(i) + '/' in p for i in (1, 2, 3, 4)) or 'tests/harness_tests/' in p}
    fresh = pathlib.Path(sys.argv[2]) / 'summary.json'
    equal = fresh.read_bytes() == (audit.BASE / 'analysis-5/summary.json').read_bytes()
    result = dict(immutableFiles=immutable, deterministic=equal,
                  editedFiles={p: dict(before=before.get(p, 'ABSENT'), after=h)
                               for p, h in after.items() if before.get(p) != h})
    (audit.OUT / 'integrity-proof.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('immutable', len(immutable), all(immutable.values()), 'deterministic', equal)
    assert all(immutable.values()) and equal
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
