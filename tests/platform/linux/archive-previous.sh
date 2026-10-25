#!/usr/bin/env bash
# Preserve the previous Linux acceptance revision (JDK 25.0.2) under a labelled subdirectory.
set -euo pipefail
DST=/mnt/c/java/octomem.octocode.tech/tests/reports/release/linux
PREV="$DST/previous-revision-2026-09-16-linux-jdk25.0.2"
mkdir -p "$PREV"
shopt -s dotglob nullglob
for entry in "$DST"/*; do
  base=$(basename "$entry")
  [ "$base" = "$(basename "$PREV")" ] && continue
  [ "$base" = "REVISION-NOTE.md" ] && continue
  mv "$entry" "$PREV/"
done
cat > "$PREV/README-previous-revision.txt" <<'EOF'
PREVIOUS REVISION — superseded, retained for audit.

Revision: pre-C1/C2 candidate, Linux acceptance executed 2026-09-16 (UTC).
Toolchain: Linux OpenJDK 25.0.2, Ubuntu 24.04.4 chroot, Maven 3.9.16.
Result: reproducible JAR sha256 c75b6fb1606b46cbd2e55755155ff84913ff9846c084e29a4569843df027be0c,
3011 unit + 25 IT green, all coverage gates met, 65/65 wire checks, differential with zero
unexplained differences, bounded 30.2-minute soak.

This evidence does NOT apply to the current corrected candidate; see ../README.md and
../REVISION-NOTE.md for the current revision.
EOF
echo "moved previous revision to $PREV"
ls "$DST"
ls "$PREV" | head -20
