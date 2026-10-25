#!/usr/bin/env bash
# Obtain the matching Linux Temurin release toolchain (OpenJDK 25.0.4.1+1) for toolchain parity
# with the Windows release JDK .tools/jdk/jdk-25.0.4.1+1. Kept outside the repository.
set -euo pipefail
LX=/root/octomem-linux
mkdir -p "$LX/archives"
A="$LX/archives/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz"
URL='https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz'
if [ ! -s "$A" ]; then
  curl --fail --location --retry 3 --max-time 1200 "$URL" -o "$A"
fi
sha256sum "$A"
tar -tzf "$A" | head -3
echo "== extracting into chroot /opt =="
RS=$LX/rootfs
tar -xzf "$A" -C "$RS/opt"
ls -d "$RS"/opt/jdk-25.0.4.1+1
cat "$RS/opt/jdk-25.0.4.1+1/release" | grep -E '^(JAVA_VERSION|IMPLEMENTOR|IMPLEMENTOR_VERSION|OS_NAME|OS_ARCH|BUILD_TYPE|JVM_VARIANT)=' || true
