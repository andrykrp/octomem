#!/usr/bin/env bash
set -euo pipefail
source /mnt/c/java/octomem.octocode.tech/tests/platform/linux/common.sh
A=/root/octomem-linux/archives/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz
sha256sum "$A"
tar -xzf "$A" -C "$RS/opt"
echo "dotfiles check:"
grep -E '^(JAVA_VERSION|IMPLEMENTOR|IMPLEMENTOR_VERSION|OS_NAME|OS_ARCH|JVM_VARIANT)=' "$RS/opt/jdk-25.0.4.1+1/release"
echo "java:"
chroot "$RS" /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME=/root \
  LD_LIBRARY_PATH=/opt/jdk-25.0.4.1+1/lib:/opt/jdk-25.0.4.1+1/lib/server \
  /opt/jdk-25.0.4.1+1/bin/java -version
echo "windows release for parity:"
grep -E '^(JAVA_VERSION|IMPLEMENTOR|IMPLEMENTOR_VERSION|OS_NAME|OS_ARCH)=' /mnt/c/java/octomem.octocode.tech/.tools/jdk/jdk-25.0.4.1+1/release || true
