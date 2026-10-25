#!/usr/bin/env bash
# Copy Linux acceptance evidence from the WSL guest to the owned report path.
set -euo pipefail
SRC=/root/octomem-linux/rootfs/evidence
DST=/mnt/c/java/octomem.octocode.tech/tests/reports/release/linux
mkdir -p "$DST"
cp -a "$SRC/." "$DST/"
find "$DST" -maxdepth 2 -type d | sort
echo "copied $(find "$DST" -type f | wc -l) files"
