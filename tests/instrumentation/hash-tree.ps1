param(
    [Parameter(Mandatory=$true)][string]$Label
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$outDir = Join-Path $root "tests\reports\release\instrumentation\hashes"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Write-Tree($treeName, $relPath) {
    $abs = Join-Path $root $relPath
    $lines = @()
    if (Test-Path -LiteralPath $abs) {
        $files = Get-ChildItem -Recurse -File -LiteralPath $abs | Sort-Object FullName
        foreach ($f in $files) {
            $rel = $f.FullName.Substring($root.Length + 1).Replace('\','/')
            $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $f.FullName).Hash.ToLower()
            $lines += "$h  $rel"
        }
    }
    $dest = Join-Path $outDir "$Label-$treeName.sha256"
    $lines | Set-Content -LiteralPath $dest -Encoding ascii
    Write-Output "wrote $($lines.Count) entries -> $dest"
}

Write-Tree "src-main" "src\main\java"
Write-Tree "src-test" "src\test\java"
$pom = Join-Path $root "pom.xml"
$pomHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $pom).Hash.ToLower()
"$pomHash  pom.xml" | Set-Content -LiteralPath (Join-Path $outDir "$Label-pom.sha256") -Encoding ascii
Write-Output "wrote pom hash -> $pomHash"
