#!/usr/bin/env bash
# Final cleanup and inventory.
pkill -f 'octomem-1.0.0.jar' 2>/dev/null || true
pkill -f 'reference/bin/memcached' 2>/dev/null || true
sleep 1
echo "== remaining servers =="
ps -eo pid,comm,args | grep -E 'memcached|octomem-1.0.0' | grep -v grep || echo none
echo "== report tree =="
find /mnt/c/java/octomem.octocode.tech/tests/reports/release/linux -maxdepth 1 | sort
echo "== report file count =="
find /mnt/c/java/octomem.octocode.tech/tests/reports/release/linux -type f | wc -l
echo "== platform tree =="
find /mnt/c/java/octomem.octocode.tech/tests/platform -type f | sort
