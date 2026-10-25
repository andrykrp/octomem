#!/usr/bin/env bash
# Shared chroot helpers for the Linux second-platform acceptance.
# Sourced, never executed directly. Re-establishes mounts because WSL2 tears down
# the guest (and all bind mounts) when the last process exits.

REPO=/mnt/c/java/octomem.octocode.tech
LX=/root/octomem-linux
RS=$LX/rootfs
M2=/mnt/c/Users/andry/.m2/repository

ensure_mounts() {
  mountpoint -q "$RS/proc" || mount -t proc proc "$RS/proc"
  mountpoint -q "$RS/sys"  || mount -t sysfs sysfs "$RS/sys"
  mountpoint -q "$RS/dev"  || mount -t tmpfs -o size=64M tmpfs "$RS/dev"
  [ -e "$RS/dev/null"    ] || mknod -m 666 "$RS/dev/null"    c 1 3
  [ -e "$RS/dev/zero"    ] || mknod -m 666 "$RS/dev/zero"    c 1 5
  [ -e "$RS/dev/random"  ] || mknod -m 666 "$RS/dev/random"  c 1 8
  [ -e "$RS/dev/urandom" ] || mknod -m 666 "$RS/dev/urandom" c 1 9
  [ -e "$RS/dev/tty"     ] || mknod -m 666 "$RS/dev/tty"     c 5 0
  [ -e "$RS/dev/ptmx"    ] || mknod -m 666 "$RS/dev/ptmx"    c 5 2
  mkdir -p "$RS/dev/pts"
  mountpoint -q "$RS/dev/pts" || mount -t devpts devpts "$RS/dev/pts"
  mkdir -p "$RS/cache/repository"
  mountpoint -q "$RS/cache/repository" || {
    mount --bind "$M2" "$RS/cache/repository"
    mount -o remount,bind,ro "$RS/cache/repository"
  }
  mkdir -p "$RS/host-tools"
  if ! mountpoint -q "$RS/host-tools"; then
    mount --bind "$REPO/.tools" "$RS/host-tools"
    mount -o remount,bind,ro "$RS/host-tools"
  fi
  cp /etc/resolv.conf "$RS/etc/resolv.conf" 2>/dev/null || true
}

# Linux JDK under test. Since the C1/C2 re-validation this is the Linux Temurin build matching the
# Windows release toolchain (.tools/jdk/jdk-25.0.4.1+1): Eclipse Adoptium Temurin-25.0.4.1+1.
JDK=${JDK:-/opt/jdk-25.0.4.1+1}
MAVEN_BIN=/opt/apache-maven-3.9.16/bin/mvn

# Run a bash command inside the chroot with the pinned toolchain.
CHROOT() {
  chroot "$RS" /usr/bin/env -i \
    "PATH=$JDK/bin:/opt/apache-maven-3.9.16/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    "HOME=/root" \
    "JAVA_HOME=$JDK" \
    "LANG=C.UTF-8" \
    "LD_LIBRARY_PATH=$JDK/lib:$JDK/lib/server" \
    "JAVA_TOOL_OPTIONS=-Xmx384m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED" \
    "MAVEN_OPTS=-Xmx256m -Dfile.encoding=UTF-8" \
    /bin/bash -c "$1"
}

# Run a bash command inside the chroot as a plain Linux process (server under test).
CHROOT_PLAIN() {
  chroot "$RS" /usr/bin/env -i \
    "PATH=$JDK/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    "HOME=/root" \
    "JAVA_HOME=$JDK" \
    "LANG=C.UTF-8" \
    "LD_LIBRARY_PATH=$JDK/lib:$JDK/lib/server" \
    /bin/bash -c "$1"
}
