$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$jdk = Join-Path $root ".tools\jdk\jdk-25.0.4.1+1"
$javac = Join-Path $jdk "bin\javac.exe"
$jar = Join-Path $jdk "bin\jar.exe"
$out = Join-Path $here "out"

if (Test-Path -LiteralPath $out) { Remove-Item -Recurse -Force -LiteralPath $out }
New-Item -ItemType Directory -Force -Path (Join-Path $out "main") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out "agent") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out "probes") | Out-Null

$mainSources = @(Get-ChildItem -Recurse -File (Join-Path $root "src\main\java") -Filter *.java | ForEach-Object { $_.FullName })
Write-Output "compiling $($mainSources.Count) production sources"
& $javac -encoding UTF-8 --release 25 -d (Join-Path $out "main") @mainSources
if ($LASTEXITCODE -ne 0) { throw "production compile failed: $LASTEXITCODE" }

$agentSources = @(Get-ChildItem -Recurse -File (Join-Path $here "agent") -Filter *.java | ForEach-Object { $_.FullName })
Write-Output "compiling agent"
& $javac -encoding UTF-8 --release 25 -d (Join-Path $out "agent") @agentSources
if ($LASTEXITCODE -ne 0) { throw "agent compile failed: $LASTEXITCODE" }

$manifest = Join-Path $out "MANIFEST.MF"
@("Manifest-Version: 1.0", "Premain-Class: ru.octomem.instrumentation.HeapAgent", "") |
    Set-Content -LiteralPath $manifest -Encoding ascii
& $jar --create --file (Join-Path $out "heapagent.jar") --manifest $manifest -C (Join-Path $out "agent") .
if ($LASTEXITCODE -ne 0) { throw "agent jar failed: $LASTEXITCODE" }

$probeSources = @(Get-ChildItem -Recurse -File (Join-Path $here "probes") -Filter *.java | ForEach-Object { $_.FullName })
Write-Output "compiling $($probeSources.Count) probe sources"
& $javac -encoding UTF-8 --release 25 -cp "$(Join-Path $out 'main');$(Join-Path $out 'agent')" -d (Join-Path $out "probes") @probeSources
if ($LASTEXITCODE -ne 0) { throw "probe compile failed: $LASTEXITCODE" }

Write-Output "BUILD OK"
