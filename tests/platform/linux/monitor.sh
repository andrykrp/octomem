#!/usr/bin/env bash
# Independent RSS sampler for the bounded soak, so evidence survives even if the soak writer fails.
LOG=/root/octomem-linux/soak-logs/rss-monitor.log
echo "epoch_utc,pid,rss_kb" > "$LOG"
end=$(( $(date +%s) + 2400 ))
while [ "$(date +%s)" -lt "$end" ]; do
  pid=$(pgrep -f 'octomem-1.0.0.jar --listen' | head -1)
  if [ -n "$pid" ]; then
    rss=$(awk '/VmRSS/{print $2}' /proc/$pid/status 2>/dev/null)
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$pid,${rss:-na}" >> "$LOG"
  else
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),none,none" >> "$LOG"
  fi
  sleep 30
done
