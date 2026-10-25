param(
    [Parameter(Mandatory=$true)][string]$JavaPath,
    [Parameter(Mandatory=$true)][string]$JavaVersion,
    [Parameter(Mandatory=$true)][string]$Image,
    [switch]$ApprovedRun
)
$ErrorActionPreference = 'Stop'
if (!$ApprovedRun) { throw 'Coordinator approval required; no Docker start performed' }
if (!$Image.Contains('@sha256:')) { throw 'Use an existing image pinned by digest; do not silently rebuild' }
if (!$JavaPath.StartsWith('/repo/') -or !$JavaVersion.StartsWith('25.')) { throw 'Explicit pinned JDK25 path and exact update required' }
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$parent = "$root/tests/reports/release/testing-rework/perf"
if (!(Test-Path -LiteralPath $parent)) { throw 'Missing output parent' }
$id = [guid]::NewGuid().ToString('N')
$out = "$parent/launch-$id"
[System.IO.Directory]::CreateDirectory($out) | Out-Null
& docker info --format '{{json .}}' | Out-File -Encoding utf8 "$out/docker-info.json"
if ($LASTEXITCODE -ne 0) { throw 'Docker info failed' }
& docker inspect $Image | Out-File -Encoding utf8 "$out/image.json"
if ($LASTEXITCODE -ne 0) { throw 'Pinned image inspection failed' }
& docker ps | Out-File -Encoding utf8 "$out/background-containers.txt"
if ($LASTEXITCODE -ne 0) { throw 'Docker inventory failed' }
# memory-swap == memory means no additional swap allowance. Actual cgroup values are captured inside.
& docker run --name "octomem-test05-$id" --cpuset-cpus 0-19 --memory 8g --memory-swap 8g --ulimit nofile=65536:65536 -v "${root}:/repo:ro" -v "${out}:/out" $Image taskset -c 16-19 python3 -B /repo/tests/platform/perf/run.py --output-parent /out --java $JavaPath --java-version $JavaVersion --reference /repo/.tools/linux/reference/bin/memcached --jar /repo/target/octomem-1.0.0.jar --client /opt/memtier/memtier_benchmark --repetitions 6 --server-cpus 0-3 --client-cpus 4-15 --expected-jar-sha256 950a8edb702e51c9979320c117257426f1109076845e80f30774ef0855ff682b --approved-run
exit $LASTEXITCODE
