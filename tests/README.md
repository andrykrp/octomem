# Testing Entry Point

Start here for current commands. [Test index](00-index.md) lists the nine product
suites; [method version 2](TESTING-METHOD.md) defines qualification and expected
outcomes. [Testing-system reference](../docs/release/09-testing-system.md) separates
harness validation, product observations, historical evidence and release gates.

## Fast Harness Checks

From the repository root, Windows PowerShell 5.1 or newer:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tests/run-checks.ps1
```

Python 3.12 is the currently exercised interpreter; no third-party Python package
is required. Bash must also be on PATH for the shell pin/exit controls (Git Bash
or an equivalent Bash installation on Windows). Its absence is a failure, not a
skip. `-Python C:\path\to\python.exe` selects an explicit interpreter. The
runner rejects zero discovered tests and skipped tests, preserves stdout/stderr
and exit codes, hashes inputs and candidate before/after, and writes to a new
`tests/reports/release/testing-rework/integration/checks-<UTC>-<UUID>/` directory.
The parent must exist; `-EvidenceParent` selects another existing evidence parent.
Exit 0 means all selected checks passed, not release acceptance.
The Windows runner bounds harness execution to 120 seconds and Maven to 1800
seconds, records launched PID/exit/reap, and on timeout terminates only that PID's
process tree. A timeout remains exit124/non-PASS; no failed worker is discarded.

Linux/macOS use the same independently authored unittest suite:

```bash
python3 -B -m unittest discover -s tests/harness_tests -v
```

Expected: nonzero discovered count, no failures/errors/skips, exit 0. Retain the
complete output in a fresh evidence directory; unlike the PowerShell wrapper,
this direct command does not create provenance manifests or reject skips itself.
No server, Docker container, Maven build or remote stand is started by default.

## Full Java Gate

Serialize this with all other Maven/build/socket-heavy work. It includes clean,
unit and integration tests plus the unchanged coverage checks; it is not a quick
smoke. It overwrites `target`, so preserve previous evidence first.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tests/run-checks.ps1 -FullJava -JavaHome C:\java\octomem.octocode.tech\.tools\jdk\jdk-25.0.4.1+1
```

The explicit JDK must contain both Java and javac 25. No system-Java fallback is
allowed. The wrapper records bounded JVM flags and copies test XML, coverage and
child-JVM evidence. A harness failure remains a failure even if Maven succeeds.
Linux with an already provisioned, explicitly verified JDK25:

```bash
export JAVA_HOME=/absolute/approved/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS='-Xmx512m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8'
export MAVEN_OPTS='-Xmx256m -Dfile.encoding=UTF-8'
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version
./mvnw -B -ntp clean verify -Pcoverage
```

Record the exact JDK update, flags and input/output SHA-256 alongside the raw
suite reports. Do not substitute JDK21 or treat missing coverage as a pass.

## Runtime And Analysis

Runtime requires an approved command, identities, isolated numeric-loopback
network scope, output directory and cleanup plan. The retained Linux `wire`
driver and stand adapters depend on their provisioned paths, not arbitrary
system installations. Stand staging must transfer `oracle_wire.py` alongside
`linux_suite.py`. The fixed-path historical `stage-wire.sh` and `run-wire.sh`
are RETIRED and exit 2 before any SSH call or evidence write. They must not be used
as current entrypoints: stage the adapter and both helper files into a new,
explicitly approved attempt directory instead.
The old `remote-build-r2.sh` output/manifests are historical and must be restaged
under a separately approved attempt before current use. The wire adapter now
requires explicit `--jar /absolute/new-attempt/candidate.jar --output-parent
/absolute/new-attempt/wire`; it enforces the current pin. Summaries require exact
`--attempt` directories and an explicit `--expected-jar-sha256`; selecting the old
D409 hash describes historical evidence only, never current-candidate qualification.

Qualified performance collection currently requires Linux cgroup v2 with readable
actual CPU/cpuset/memory limits and counters. Cgroup v1 fails closed with explicit
missing-evidence reasons; Docker flags alone do not supply those measurements.
Verify the selected Docker CLI/daemon with `docker info`: a working Windows
Docker Desktop CLI does not imply a working Docker socket inside a WSL distro.
Do not enable WSL integration, restart services or change cgroup mode as an
implicit test prerequisite. The retained bounded smoke used Windows Docker with
a hash-verified retained JDK archive extracted only inside its isolated container.

The current performance collector starts the complete fixed campaign, not one
quick scenario. Do not invoke it for a smoke or infer approval from this recipe:

```bash
python3 -B tests/platform/perf/run.py --output-parent /new/evidence-parent --java /absolute/jdk25/bin/java --java-version 25.0.4.1 --reference /absolute/memcached --jar /absolute/current-candidate.jar --client /absolute/memtier_benchmark --repetitions 6 --approved-run
```

Offline analysis of an existing attempt is separate and never rewrites raw data:

```bash
python3 -B tests/platform/perf/finalize.py --input /existing/attempt --output /new/analysis-directory
```

Expected: JSON/prose verdicts agree; missing, malformed or unqualified evidence
is `INCONCLUSIVE` with reasons, not PASS. Non-PASS exits nonzero. Six even,
balanced pairs and valid metric-specific evidence are required for acceptance;
one real paired smoke only qualifies the collection/classification path.

Current approved post-POM candidate SHA-256:
`63764015708EDBDCB2284DF53E827FA5CF2D58E904E73D3004D3F7D0165F9B70`.
Older `D409...` artifacts remain historical; never disable equality to reuse them.
Two machines remain planned, second-machine qualification is not established,
and the 72-hour run remains `NOT_RUN`. No legal or trusted-OS conclusion follows
from a successful technical check.
