#!/usr/bin/env bash
# Linux second-platform acceptance (re-validation after C1/C2): reproducible build x2 + full coverage suite.
# Runs on the WSL2 Ubuntu guest as root against the Ubuntu 24.04.4 chroot.
#
# CLEAN-TREE FLOW: only src/**, pom.xml, mvnw, .mvn and compatibility-manifest.json are staged from
# the repository. tests/reports, design, docs and tasks are NOT copied, to confirm the C1/C2
# hermeticity fix. The only repository fixture still required is .tools/ (read by the unchanged
# CompatibilityManifestIT); it is exposed read-only through /host-tools.
set -euo pipefail

source /mnt/c/java/octomem.octocode.tech/tests/platform/linux/common.sh
ensure_mounts
EV=$RS/evidence
rm -rf "$EV"
mkdir -p "$EV"
LOG=$EV/build-run.log
exec > >(tee "$LOG") 2>&1

MAVEN_ARGS='-o -B -ntp -Dmaven.repo.local=/cache/repository'
JAVA_TOOL_OPTIONS_VALUE='-Xmx384m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED'

echo "=== ENVIRONMENT ==="
{
  echo "JDK=$JDK"
  echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS_VALUE"
  echo "MAVEN_OPTS=-Xmx256m -Dfile.encoding=UTF-8"
  echo "MAVEN_ARGS=$MAVEN_ARGS"
  echo
} > "$EV/build-environment.txt"
CHROOT "set -x; $JDK/bin/java -version; $MAVEN_BIN -version; cat /etc/os-release; uname -a; nproc" \
  >> "$EV/build-environment.txt" 2>&1 || true
CHROOT "grep -E '^(JAVA_VERSION|IMPLEMENTOR|IMPLEMENTOR_VERSION|OS_NAME|OS_ARCH|JVM_VARIANT)=' $JDK/release" \
  >> "$EV/build-environment.txt" 2>&1 || true
sha256sum /root/octomem-linux/archives/*.tar.gz > "$EV/linux-jdk-archive.sha256" 2>&1 || true
cat "$EV/build-environment.txt"

echo "=== CHROOT MAVEN/WRAPPER HOME PREP =="
CHROOT 'set -e
  mkdir -p /root/.m2
  [ -e /root/.m2/repository ] || ln -s /cache/repository /root/.m2/repository
  python3 - <<"PY"
from pathlib import Path
props = Path("/pristine/.mvn/wrapper/maven-wrapper.properties").read_text()
url = next(l.split("=",1)[1].strip() for l in props.splitlines() if l.startswith("distributionUrl="))
h = 0
for c in url:
    h = (31*h + ord(c)) % 4294967296
home = Path("/root/.m2/wrapper/dists/apache-maven-3.9.16") / format(h, "x")
home.parent.mkdir(parents=True, exist_ok=True)
if not home.exists():
    home.symlink_to("/opt/apache-maven-3.9.16", target_is_directory=True)
print("wrapper home", home, "->", home.resolve())
PY'

echo "=== STAGE CLEAN SOURCE (native fs) ==="
rm -rf "$RS/pristine"
mkdir -p "$RS/pristine"
cp -a "$REPO/src" "$REPO/pom.xml" "$REPO/mvnw" "$REPO/.mvn" "$REPO/compatibility-manifest.json" "$RS/pristine/"
ln -sfn /host-tools "$RS/pristine/.tools"
# Normalise Unix modes: files 0644, dirs 0755. Without this the JAR stores 0755 for
# META-INF/maven/ru.octomem/octomem/pom.xml (copied from drvfs) and differs from the Windows
# release JAR by exactly one central-directory attribute byte. See toolchain-parity.md.
find "$RS/pristine" -type f -exec chmod 644 {} +
find "$RS/pristine" -type d -exec chmod 755 {} +

CHROOT 'set -e
  find /pristine -type f | sort | xargs sha256sum > /evidence/build-inputs-before.sha256
  wc -l /evidence/build-inputs-before.sha256
  find /pristine/src /pristine/pom.xml -type f | sort | xargs sha256sum > /evidence/source-test-pom-before.sha256
  echo "--- staged top-level ---"; ls -a /pristine'
echo "NOTE: no tests/, design/, docs/ or tasks/ staged (clean-tree hermeticity check)." | tee -a "$EV/build-environment.txt"

build_once() {
  local n=$1 dest=/build-$1
  echo "=== REPRODUCIBILITY BUILD $n ==="
  CHROOT "rm -rf $dest; cp -a /pristine $dest"
  local start end
  start=$(date +%s)
  CHROOT "cd $dest && time $MAVEN_BIN $MAVEN_ARGS clean package -DskipTests" \
    > "$EV/repro-build-$n.log" 2>&1
  local code=$?
  end=$(date +%s)
  echo "exit=$code elapsed=$((end-start))s"
  CHROOT "cd $dest && ls -l target/*.jar && sha256sum target/*.jar" > "$EV/repro-build-$n-jar.sha256" 2>&1
  CHROOT "cd $dest && for j in target/*.jar; do echo \"== \$j ==\"; unzip -p \$j META-INF/MANIFEST.MF; done" > "$EV/repro-build-$n-manifest.txt" 2>&1 || true
  CHROOT "cd $dest && python3 -c \"
import zipfile,hashlib,json,glob
p=sorted(glob.glob('target/*.jar'))[0]
z=zipfile.ZipFile(p)
d={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
open('/evidence/repro-build-$n-payload.json','w').write(json.dumps(d,indent=2,sort_keys=True))
\"" || true
  cat "$EV/repro-build-$n-jar.sha256"
  return $code
}

set +e
build_once 1; CODE1=$?
build_once 2; CODE2=$?
set -e
echo "repro build exit codes: $CODE1 $CODE2"

echo "=== FULL TEST SUITE: clean verify -Pcoverage ==="
rm -rf "$RS/build-cov"
cp -a "$RS/pristine" "$RS/build-cov"
{
  echo './tests/platform/linux/build.sh -> CHROOT:'
  echo "$MAVEN_BIN $MAVEN_ARGS clean verify -Pcoverage"
  echo "JDK=$JDK"
  echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS_VALUE"
  echo 'MAVEN_OPTS=-Xmx256m -Dfile.encoding=UTF-8'
  echo 'wire evidence dir: target/t013-wire-evidence (StatsWireFixture default)'
} > "$EV/full-suite.command.txt"
cat "$EV/full-suite.command.txt"
start=$(date +%s)
set +e
CHROOT "cd /build-cov && time $MAVEN_BIN $MAVEN_ARGS clean verify -Pcoverage" \
  > "$EV/full-suite.log" 2>&1
SUITE_CODE=$?
set -e
end=$(date +%s)
echo "$SUITE_CODE" > "$EV/full-suite.exit-code.txt"
echo "suite exit=$SUITE_CODE elapsed=$((end-start))s"

echo "=== COLLECT REPORTS ==="
rm -rf "$EV/full-suite"
mkdir -p "$EV/full-suite"
for d in surefire-reports failsafe-reports t013-wire-evidence; do
  if [ -e "$RS/build-cov/target/$d" ]; then cp -a "$RS/build-cov/target/$d" "$EV/full-suite/"; fi
done
mkdir -p "$EV/full-suite/jacoco"
cp -a "$RS/build-cov/target/jacoco.exec" "$EV/full-suite/jacoco/" 2>/dev/null || true
if [ -e "$RS/build-cov/target/site/jacoco" ]; then cp -a "$RS/build-cov/target/site/jacoco" "$EV/full-suite/"; fi
cp "$RS/build-cov/target/"*.jar "$EV/full-candidate.jar" 2>/dev/null || true
CHROOT 'cd /build-cov && sha256sum target/*.jar' > "$EV/coverage-build-jar.sha256" 2>&1 || true

echo "=== SOURCE HASHES AFTER ==="
CHROOT 'find /build-cov -type f \( -path "*/src/*" -o -name pom.xml \) | sort | xargs sha256sum > /evidence/source-test-pom-after.sha256; echo done'

echo "=== COVERAGE GATE SUMMARY ==="
grep -E 'All coverage checks have been met|Rule violated|BUILD (SUCCESS|FAILURE)|Tests run: [0-9]+, Failures|Total time' "$EV/full-suite.log" \
  > "$EV/summary.txt" 2>&1 || true
tail -20 "$EV/summary.txt"

echo "=== DONE ==="
date -u --iso-8601=seconds
