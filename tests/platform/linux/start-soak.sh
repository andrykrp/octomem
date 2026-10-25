#!/usr/bin/env bash
# Launch the bounded 30-minute soak in the background and record its PID.
mkdir -p /root/octomem-linux/soak-logs
nohup python3 /mnt/c/java/octomem.octocode.tech/tests/platform/linux/linux_suite.py soak 1200 \
  > /root/octomem-linux/soak-logs/soak-run.log 2>&1 &
echo $! > /root/octomem-linux/soak-logs/soak.pid
disown || true
sleep 3
echo "pid=$(cat /root/octomem-linux/soak-logs/soak.pid)"
ls -la /root/octomem-linux/soak-logs/
