#!/usr/bin/env bash
echo "== soak log =="
ls -la /root/octomem-linux/soak-logs/ 2>&1
echo "== soak tail =="
tail -5 /root/octomem-linux/soak-logs/soak-run.log 2>&1
echo "== processes =="
ps -eo pid,etimes,rss,comm,args | grep -E 'linux_suite|java|memcached' | grep -v grep
