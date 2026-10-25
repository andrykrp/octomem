#!/usr/bin/env bash
# Cross-platform parity experiment: rebuild with normalised Unix file modes (0644 files / 0755 dirs)
# to determine whether the Windows/Linux JAR difference is only the stored file-mode of pom.xml.
set -euo pipefail
source /mnt/c/java/octomem.octocode.tech/tests/platform/linux/common.sh
ensure_mounts
MAVEN_ARGS='-o -B -ntp -Dmaven.repo.local=/cache/repository'
rm -rf "$RS/parity"
cp -a "$RS/pristine" "$RS/parity"
find "$RS/parity" -type f -exec chmod 644 {} +
find "$RS/parity" -type d -exec chmod 755 {} +
stat -c '%a %n' "$RS/parity/pom.xml"
CHROOT "cd /parity && $MAVEN_BIN $MAVEN_ARGS clean package -DskipTests" > /root/octomem-linux/parity-build.log 2>&1
echo "build exit=$?"
CHROOT 'cd /parity && sha256sum target/*.jar && ls -l target/*.jar'
