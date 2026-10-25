param(
    [string]$OutDir = "tests\reports\release\instrumentation\raw"
)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$probes = @("P1Layout","P2Composed","P3WholeResponse","P4Cancellation","P5EpochOwnership")
$failures = @()
foreach ($probe in $probes) {
    foreach ($mode in @("compressed","uncompressed")) {
        $out = Join-Path $root "$OutDir\$probe.$mode.txt"
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here "run-probe.ps1") `
            -Probe $probe -Out $out -Mode $mode
        if ($LASTEXITCODE -ne 0) { $failures += "$probe/$mode exit $LASTEXITCODE" }
    }
}
if ($failures.Count -gt 0) {
    Write-Output ("FAILURES: " + ($failures -join ", "))
    exit 1
}
Write-Output "ALL PROBES COMPLETE"
