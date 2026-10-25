#!/usr/bin/env bash
# Wait for the bounded soak to finish, then copy the independent RSS monitor next to the result.
PID=$(cat /root/octomem-linux/soak-logs/soak.pid 2>/dev/null || true)
while [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; do
  sleep 20
done
echo "soak finished at $(date -u --iso-8601=seconds)"
echo "=== soak stdout ==="
cat /root/octomem-linux/soak-logs/soak-run.log
DST=/mnt/c/java/octomem.octocode.tech/tests/reports/release/linux/soak
mkdir -p "$DST"
cp /root/octomem-linux/soak-logs/rss-monitor.log "$DST/rss-monitor.log"
echo "=== soak dir ==="
ls -la "$DST"
