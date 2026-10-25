param([switch]$ApprovedBuild)
$ErrorActionPreference = 'Stop'
if (!$ApprovedBuild) { throw 'A separate environment-build approval is required; no build performed' }
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$parent = "$root/tests/reports/release/testing-rework/perf"
if (!(Test-Path -LiteralPath $parent)) { throw 'Missing report parent' }
$id = [guid]::NewGuid().ToString('N')
$out = "$parent/build-$id"
[System.IO.Directory]::CreateDirectory($out) | Out-Null
# This recipe is not reproducibly pinned; the resulting image and binaries must be identified before use.
& docker build -t "octomem-perf:$id" "$PSScriptRoot" 2>&1 | Out-File -Encoding utf8 "$out/build.log"
$code = $LASTEXITCODE
"exitCode=$code" | Out-File -Encoding ascii "$out/exit-code.txt"
exit $code
