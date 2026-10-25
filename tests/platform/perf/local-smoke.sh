#!/usr/bin/env bash
# Bounded local smoke for the purpose-built generator against the pinned memcached
# reference. Never a performance qualification: one short preload + mixed + churn.
#
# Usage inside the pinned perf image with the repository mounted at /repo:
#   bash /repo/tests/platform/perf/local-smoke.sh /repo/.tools/linux/reference/bin/memcached
set -euo pipefail
REF="${1:-/repo/.tools/linux/reference/bin/memcached}"
PORT="${2:-11399}"
PYPATH="${PYPATH:-/repo/tests/platform/perf}"
cp -f "$REF" /tmp/memcached_ref
chmod +x /tmp/memcached_ref
/tmp/memcached_ref -u root -l 127.0.0.1 -p "$PORT" -m 64 -c 4096 -t 2 >/tmp/mc.log 2>&1 &
MC_PID=$!
trap 'kill "$MC_PID" 2>/dev/null || true' EXIT
sleep 1

python3 -B "$PYPATH/generator.py" --port "$PORT" --mode preload --keys 2000 \
  --value-mix 1024:1.0 --connections-per-worker 4 --pipeline 32 --output /tmp/preload.json
python3 -B "$PYPATH/generator.py" --port "$PORT" --mode mixed --workers 4 \
  --connections-per-worker 2 --pipeline 8 --ratio 1:9 --keys 2000 --duration 5 \
  --verify-values --capacity-probe-seconds 2 --output /tmp/mixed.json
python3 -B "$PYPATH/generator.py" --port "$PORT" --mode churn --ops 100 --workers 1 \
  --output /tmp/churn.json

python3 - <<'PY'
import json
p = json.load(open('/tmp/preload.json'))
m = json.load(open('/tmp/mixed.json'))
ch = json.load(open('/tmp/churn.json'))
print('PRELOAD stored=%s/%s errors=%s' % (p['totals']['sets'], p['plan']['totalOps'], p['totals']['errors']))
print('MIXED ops=%s sets=%s gets=%s hits=%s misses=%s errors=%s hitRatio=%.4f opsPerSecond=%.1f'
      % (m['totals']['ops'], m['totals']['sets'], m['totals']['gets'], m['totals']['hits'],
         m['totals']['misses'], m['totals']['errors'], m['totals']['hitRatio'], m['totals']['opsPerSecond']))
print('CPU fraction=%s clientCpus=%s cgroupVersion=%s p99us=%s'
      % (m['cpu']['processCpuFraction'], m['cpu']['clientCpuCount'], m['cpu']['cgroup']['version'], m['latencyUs']['p99']))
print('HEADROOM commandsPerSecondUpperBound=%.0f'
      % m['generatorHeadroom']['commandsPerSecondUpperBound'])
print('CHURN validated=%s cycleErrors=%s' % (ch['validatedCycles'], len(ch['cycleErrors'])))
PY
