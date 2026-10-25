#!/usr/bin/env bash
# Linux second-platform acceptance (T-RELEASE-LINUX).
# One-time preparation of a Ubuntu 24.04.4 (noble) chroot on the WSL2 Ubuntu guest,
# with the pinned Linux JDK 25.0.2, pinned Maven 3.9.16, the pinned reference
# memcached 1.6.45 binary and a read-only bind of the local Maven repository.
#
# Owned path: tests/platform/**.  This script never edits tests/environment/**.
# Run as root inside WSL2 Ubuntu:  wsl -d Ubuntu -- bash tests/platform/linux/setup.sh
set -euo pipefail

REPO=/mnt/c/java/octomem.octocode.tech
LX=/root/octomem-linux
RS=$LX/rootfs
REF=$REPO/.tools/linux/reference
JDK=$REPO/.tools/linux/jdk-25.0.2
M2=/mnt/c/Users/andry/.m2/repository

mkdir -p "$LX" "$LX/evidence" "$LX/logs" "$LX/input"
LOG=$LX/logs/setup.log
exec > >(tee "$LOG") 2>&1

date -u --iso-8601=seconds
echo "== HOST =="
uname -a
head -3 /etc/os-release
nproc
free -h | head -2
echo "== ARCHIVES (sha256) =="
sha256sum \
  "$REPO/.tools/linux/archives/ubuntu-base-24.04.4-base-amd64.tar.gz" \
  "$REPO/.tools/linux/archives/openjdk-25.0.2_linux-x64_bin.tar.gz" \
  "$REPO/.tools/archives/apache-maven-3.9.16-bin.zip" \
  "$REPO/.tools/archives/memcached-1.6.45.tar.gz" \
  "$REF/bin/memcached"

# Tear down any previous mounts (idempotent).
umount -R "$RS" 2>/dev/null || true
rm -rf "$RS"
mkdir -p "$RS"

echo "== EXTRACT BASE ROOTFS =="
tar --exclude='./dev/*' -xzf "$REPO/.tools/linux/archives/ubuntu-base-24.04.4-base-amd64.tar.gz" -C "$RS"
mkdir -p "$RS/opt" "$RS/proc" "$RS/sys" "$RS/dev" "$RS/work" "$RS/cache" "$RS/evidence" "$RS/input" "$RS/tmp"
cp /etc/resolv.conf "$RS/etc/resolv.conf"

echo "== STAGE PINNED TOOLCHAIN =="
cp -a "$JDK" "$RS/opt/"
cp -a "$REF" "$RS/opt/"
cp "$REPO/.tools/archives/apache-maven-3.9.16-bin.zip" "$RS/input/"
cp "$REPO/.tools/archives/memcached-1.6.45.tar.gz" "$RS/input/"

ensure_mounts() {
  mountpoint -q "$RS/proc" || mount -t proc proc "$RS/proc"
  mountpoint -q "$RS/sys"  || mount -t sysfs sysfs "$RS/sys"
  mountpoint -q "$RS/dev"  || mount -t tmpfs -o size=64M tmpfs "$RS/dev"
  [ -e "$RS/dev/null"    ] || mknod -m 666 "$RS/dev/null"    c 1 3
  [ -e "$RS/dev/zero"    ] || mknod -m 666 "$RS/dev/zero"    c 1 5
  [ -e "$RS/dev/random"  ] || mknod -m 666 "$RS/dev/random"  c 1 8
  [ -e "$RS/dev/urandom" ] || mknod -m 666 "$RS/dev/urandom" c 1 9
  [ -e "$RS/dev/tty"     ] || mknod -m 666 "$RS/dev/tty"     c 5 0
  mountpoint -q "$RS/cache/repository" || {
    mkdir -p "$RS/cache/repository"
    mount --bind "$M2" "$RS/cache/repository"
    mount -o remount,bind,ro "$RS/cache/repository"
  }
}
ensure_mounts

echo "== INSTALL PACKAGES INSIDE CHROOT =="
chroot "$RS" /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin \
  HOME=/root DEBIAN_FRONTEND=noninteractive LC_ALL=C \
  /bin/bash -c '
    set -eux
    apt-get update
    apt-get install -y --no-install-recommends gcc libc6-dev make libevent-dev python3 unzip procps psmisc strace
    dpkg-query -W > /evidence/userspace-packages.txt
    cp /etc/os-release /evidence/userspace-os-release.txt
    unzip -q /input/apache-maven-3.9.16-bin.zip -d /opt
    chmod +x /opt/apache-maven-3.9.16/bin/mvn
    gcc --version > /evidence/reference-compiler.txt
    /opt/jdk-25.0.2/bin/java -XshowSettings:properties -version > /evidence/linux-jdk.txt 2>&1
    /opt/reference/bin/memcached --version > /evidence/reference-version.txt 2>&1
    ldd /opt/reference/bin/memcached > /evidence/reference-ldd.txt 2>&1
    sha256sum /opt/reference/bin/memcached > /evidence/reference-binary.sha256
    /opt/apache-maven-3.9.16/bin/mvn --version > /evidence/linux-maven.txt 2>&1
  '

# CPU identity inside the guest is identical to the host; record it once.
chroot "$RS" /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin \
  /bin/bash -c 'grep -m1 "model name" /proc/cpuinfo > /evidence/cpu-model.txt; getconf LONG_BIT >> /evidence/cpu-model.txt; cat /etc/os-release >> /evidence/cpu-model.txt' 2>/dev/null || true
grep -m1 "model name" /proc/cpuinfo > "$LX/evidence/cpu-model.txt" || true

echo "== SETUP COMPLETE =="
date -u --iso-8601=seconds
