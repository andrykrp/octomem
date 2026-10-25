param(
    [Parameter(Mandatory=$true)][string]$Probe,
    [Parameter(Mandatory=$true)][string]$Out,
    [Parameter(Mandatory=$true)][ValidateSet("compressed","uncompressed")][string]$Mode,
    [string[]]$ExtraArgs = @()
)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$jdk = Join-Path $root ".tools\jdk\jdk-25.0.4.1+1"
$java = Join-Path $jdk "bin\java.exe"
$cp = "$(Join-Path $here 'out\main');$(Join-Path $here 'out\probes');$(Join-Path $here 'out\heapagent.jar')"
$agent = Join-Path $here "out\heapagent.jar"

$pointerFlags = if ($Mode -eq "compressed") {
    @("-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers")
} else {
    @("-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers")
}
$opens = @(
    "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.function=ALL-UNNAMED"
)
$base = @("--enable-native-access=ALL-UNNAMED") + $pointerFlags +
    @("-XX:+UseSerialGC", "-Xmx256m") + $opens +
    @("-cp", $cp, "-javaagent:$agent", "ru.octomem.instrumentation.$Probe") + $ExtraArgs

"& $java $($base -join ' ')" | Set-Content -LiteralPath "$Out.cmd.txt" -Encoding ascii
$process = Start-Process -FilePath $java -ArgumentList $base -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $Out -RedirectStandardError "$Out.stderr.txt"
"EXIT=$($process.ExitCode)" | Set-Content -LiteralPath "$Out.exit.txt" -Encoding ascii
Write-Output "$Probe/$Mode -> exit $($process.ExitCode) -> $Out"
exit $process.ExitCode
