#!/usr/bin/env bash
# Normalise and compare the frozen source/test/pom hashes captured around the Linux suite,
# and record host CPU identity. Escapes nothing; writes into the owned report path.
set -euo pipefail
EV=/root/octomem-linux/rootfs/evidence
DST=/mnt/c/java/octomem.octocode.tech/tests/reports/release/linux
mkdir -p "$DST"

sed 's#/pristine/##' "$EV/source-test-pom-before.sha256" | sort -k2 > /tmp/src-before
sed 's#/build-cov/##' "$EV/source-test-pom-after.sha256" | sort -k2 > /tmp/src-after
{
  echo "# source/test/pom SHA-256 before (pristine) vs after (coverage build), path-normalised"
  echo "# before lines: $(wc -l < /tmp/src-before)  after lines: $(wc -l < /tmp/src-after)"
  if diff -u /tmp/src-before /tmp/src-after; then
    echo "IDENTICAL: no src/**, src/test/** or pom.xml content changed during the Linux build+test run"
  else
    echo "CHANGED: see diff above"
  fi
} > "$DST/source-test-pom-diff.txt"
cat "$DST/source-test-pom-diff.txt"

{
  echo "nproc=$(nproc)"
  grep -m1 'model name' /proc/cpuinfo || true
  grep -m1 'cpu MHz' /proc/cpuinfo || true
  free -h | head -2
} > "$DST/host-cpu-memory.txt"
cat "$DST/host-cpu-memory.txt"
