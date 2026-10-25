#!/usr/bin/env bash
source /mnt/c/java/octomem.octocode.tech/tests/platform/linux/common.sh
ensure_mounts
CHROOT 'ls -la /build-cov/.tools; echo "--- archives ---"; ls -la /build-cov/.tools/archives/; echo "--- src ---"; ls /build-cov/.tools/memcached-src/memcached-1.6.45/; echo "--- readlink ---"; readlink -f /build-cov/.tools/archives/memcached-1.6.45.tar.gz; echo "--- size ---"; stat -c %s /build-cov/.tools/archives/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip'
