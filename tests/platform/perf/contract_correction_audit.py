"""Offline correction evidence recorder; never modifies sealed inputs."""
import hashlib
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = ROOT / 'tests/reports/release/perf-qual/correction-cycle/contract-correction-2-evidence'
BASE = ROOT / 'tests/reports/release/perf-qual/rosa-20260918-6pairs'


def snapshot():
    paths = list((ROOT / 'tests/platform/perf').glob('*.py'))
    paths += list((ROOT / 'tests/harness_tests').rglob('*.py'))
    paths += list((ROOT / 'tests/reports/release/perf-qual').rglob('*'))
    paths += [ROOT / 'tests/reports/release/perf-gen/VALIDITY-GATES.md']
    return {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(set(paths)) if p.is_file() and OUT not in p.parents}


def main():
    OUT.mkdir(exist_ok=True)
    action = sys.argv[1]
    if action == 'results':
        scenarios = json.loads((BASE / 'analysis-4/summary.json').read_text())
        old = json.loads((BASE / 'analysis-3/summary.json').read_text())
        result = dict(taskId='task_a8f1a752f3c3', releaseVerdict='BLOCKED',
                      analysis='rosa-20260918-6pairs/analysis-4', qualification=True,
                      candidateJarSha256='950a8edb702e51c9979320c117257426f1109076845e80f30774ef0855ff682b',
                      thresholds=dict(M1=.90, M2=1.25, M3=1.20), thresholdsChanged=False,
                      expectationDeviation='D1 alone would yield eleven M3 FAILs; strict D3 instead invalidates all memtier CPU observations. P05-near remains INCONCLUSIVE for baseline, coverage and boundary defects, not CPU budget.',
                      measurementLimitation='Interior-bucket convergence is not the estimator: M1 and M2 use full-window native Totals Ops/sec and aggregate p99. Boundary transients can drive estimates.',
                      historicalAnalysis3=dict(qualifiedM3FailCount=10, maximumQualifiedM3Median=17.190385491538244,
                          budgetCensus='nine P08 rows plus one P05-near r5 reference row; not all P08 rows; historical reconstruction is not D3-qualified'),
                      scenarios=scenarios)
        (ROOT / 'tests/reports/release/perf-qual/RESULTS.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        (BASE / 'RESULTS.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        changes = []
        for current, prior in zip(scenarios, old):
            print(current['scenario'], {m: v['verdict'] for m, v in current['metrics'].items()})
            changes.append(dict(scenario=current['scenario'],
                before={m: v['verdict'] for m, v in prior['metrics'].items()},
                after={m: v['verdict'] for m, v in current['metrics'].items()},
                cpuEvidenceRows=[dict(rep=v['rep'], server=v['server'], reasons=[r for r in v['reasons'] if r.startswith('generator_cpu')])
                                 for v in current['validation'] if any(r.startswith('generator_cpu') for r in v['reasons'])]))
            assert not any(v['verdict'] == 'PASS' for v in current['metrics'].values())
            assert not any('generator_cpu_over_budget' in r or 'generator_headroom_below_minimum' in r
                           for v in current['metrics'].values() for r in v['reasons'])
        (OUT / 'analysis-deviations.json').write_text(json.dumps(changes, indent=2), encoding='utf-8')
        return 0
    if action == 'prove':
        before = json.loads((OUT / 'before-hashes.json').read_text())
        after = snapshot()
        immutable = {p: after[p] == h for p, h in before.items() if '/attempt-' in p or
                     any('/analysis-' + str(i) + '/' in p for i in (1, 2, 3)) or 'tests/harness_tests/' in p}
        fresh = pathlib.Path(sys.argv[2]) / 'summary.json'
        equality = fresh.read_bytes() == (BASE / 'analysis-4/summary.json').read_bytes()
        result = dict(immutableFiles=immutable, immutableCount=len(immutable), deterministic=equality,
                      editedFiles={p: dict(before=before.get(p, 'ABSENT'), after=h) for p, h in after.items() if before.get(p) != h})
        (OUT / 'integrity-proof.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('immutable', len(immutable), all(immutable.values()), 'deterministic', equality)
        assert all(immutable.values()) and equality
        return 0
    if action in ('before', 'after'):
        target = OUT / (action + '-hashes.json')
        with target.open('x', encoding='utf-8') as stream:
            json.dump(snapshot(), stream, indent=2)
        print(target)
        return 0
    label, command = sys.argv[1], sys.argv[2:]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    with (OUT / (label + '.json')).open('x', encoding='utf-8') as stream:
        json.dump(dict(command=command, exitCode=result.returncode,
                       stdout=result.stdout, stderr=result.stderr), stream, indent=2)
    print(result.stdout)
    print(result.stderr)
    print('exit=' + str(result.returncode))
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
