param([switch]$CompileOnly, [switch]$FinishOnly)
$ErrorActionPreference = 'Stop'
$root = 'C:\java\octomem.octocode.tech'
$source = "$root\exp\prototypes\P001"
$report = "$root\exp\reports\P001"
$jdk = "$root\.tools\jdk\jdk-25.0.4.1+1"
if (!(Test-Path -LiteralPath $source) -or !(Test-Path -LiteralPath $report)) { throw 'P001 directories missing' }
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor
$environment = [ordered]@{
    observedAt = (Get-Date).ToUniversalTime().ToString('o')
    os = $os.Caption; version = $os.Version; build = $os.BuildNumber
    totalVisibleKiB = $os.TotalVisibleMemorySize; freePhysicalKiB = $os.FreePhysicalMemory
    totalVirtualKiB = $os.TotalVirtualMemorySize; freeVirtualKiB = $os.FreeVirtualMemory
    cpu = @($cpu | ForEach-Object { $_.Name }); logicalProcessors = @($cpu | ForEach-Object { $_.NumberOfLogicalProcessors })
    jdk = $jdk; shell = $PSVersionTable.PSVersion.ToString()
    referenceHealth = 'WSL Ubuntu uptime responds; /opt/reference/bin/memcached absent; no provisioning'
}
[IO.File]::WriteAllText("$report\environment.json", ($environment | ConvertTo-Json -Depth 4))
if ($os.FreeVirtualMemory -lt 512000) { throw 'Insufficient available host commit; no JVM started' }
& "$jdk\bin\javac.exe" '-J-Xms16m' '-J-Xmx96m' '-J-XX:ActiveProcessorCount=2' "-J-XX:ErrorFile=$report\javac-error-%p.log" --release 25 -d $source "$source\Probe.java" "$source\SizeAgent.java"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& "$jdk\bin\jar.exe" '-J-Xms16m' '-J-Xmx64m' '-J-XX:ActiveProcessorCount=2' "-J-XX:ErrorFile=$report\jar-error-%p.log" --create --file "$source\size-agent.jar" --manifest "$source\agent.mf" -C $source 'SizeAgent.class'
if ($LASTEXITCODE -ne 0) { throw 'Agent packaging failed' }
if ($CompileOnly) { return }
$base = @('-Xms16m', '-Xmx96m', '-XX:MaxDirectMemorySize=64m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC', '-XX:ReservedCodeCacheSize=64m', '-XX:MaxMetaspaceSize=96m', "-XX:ErrorFile=$report\jvm-error-%p.log", '-cp', $source)
$runs = [Collections.Generic.List[object]]::new()
if ($FinishOnly) {
    foreach ($previous in ([IO.File]::ReadAllText("$report\runs.json") | ConvertFrom-Json)) { $runs.Add($previous) }
}
function Run-Probe([string]$name, [string[]]$extra, [string[]]$probe, [bool]$record) {
    $health = Get-CimInstance Win32_OperatingSystem
    if ($health.FreeVirtualMemory -lt 512000) { throw "Host commit low before $name" }
    $jvm = @($base) + $extra
    if ($record) { $jvm += "-XX:StartFlightRecording=filename=$report\$name.jfr,settings=profile,maxsize=16m,dumponexit=true" }
    $arguments = $jvm + @('Probe') + $probe
    $samples = [Collections.Generic.List[object]]::new()
    $started = Get-Date
    $process = Start-Process -FilePath "$jdk\bin\java.exe" -ArgumentList $arguments -WorkingDirectory $source -RedirectStandardOutput "$report\$name.stdout.txt" -RedirectStandardError "$report\$name.stderr.txt" -PassThru
    $ownedHandle = $process.Handle
    do {
        Start-Sleep -Milliseconds 100
        $process.Refresh()
        if (!$process.HasExited) {
            $samples.Add([pscustomobject]@{ timestamp = (Get-Date).ToUniversalTime().ToString('o'); pid = $process.Id; workingSetBytes = $process.WorkingSet64; privateBytes = $process.PrivateMemorySize64; cpuMilliseconds = $process.TotalProcessorTime.TotalMilliseconds })
        }
        if (((Get-Date) - $started).TotalSeconds -gt 60 -and !$process.HasExited) {
            $process.Kill(); $process.WaitForExit(); throw "Killed owned P001 child after 60 seconds: $name"
        }
    } while (!$process.HasExited)
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    [IO.File]::WriteAllLines("$report\$name.rss.csv", @($samples | ConvertTo-Csv -NoTypeInformation))
    $runs.Add([pscustomobject]@{ name = $name; executable = "$jdk\bin\java.exe"; argv = $arguments; pid = $process.Id; exitCode = $exitCode; durationSeconds = ((Get-Date)-$started).TotalSeconds; freeVirtualKiBBefore = $health.FreeVirtualMemory; peakWorkingSetBytes = ($samples | Measure-Object workingSetBytes -Maximum).Maximum })
    [IO.File]::WriteAllText("$report\runs.json", ($runs | ConvertTo-Json -Depth 5))
    "${name}: exit=$exitCode"
    if ($exitCode -ne 0) { throw "Probe failed: $name" }
}
if (!$FinishOnly) {
Run-Probe 'layout-compressed' @('-XX:+UseCompressedOops', '-XX:+UseCompressedClassPointers', "-javaagent:$source\size-agent.jar") @('layout', "$report\layout-compressed.csv") $false
Run-Probe 'layout-uncompressed' @('-XX:-UseCompressedOops', '-XX:-UseCompressedClassPointers', "-javaagent:$source\size-agent.jar") @('layout', "$report\layout-uncompressed.csv") $false
Run-Probe 'lifecycle-direct' @() @('lifecycle', "$report\lifecycle-direct.csv", 'false') $true
Run-Probe 'lifecycle-ffm' @() @('lifecycle', "$report\lifecycle-ffm.csv", 'true') $true
for ($i = 1; $i -le 3; $i++) {
    # Alternate backend order to reduce a simple monotonic host-load bias.
    $backends = if ($i % 2) { @('direct','ffm') } else { @('ffm','direct') }
    foreach ($backend in $backends) {
        $name = "memory-$backend-$i"
        Run-Probe $name @() @('memory', "$report\$name.csv", ($backend -eq 'ffm').ToString().ToLowerInvariant(), "$i") $true
    }
}
}
Run-Probe 'transport-chunked' @() @('transport', "$report\transport-chunked.csv") $true
foreach ($name in @('lifecycle-direct','lifecycle-ffm','memory-direct-1','memory-ffm-1','memory-direct-2','memory-ffm-2','memory-direct-3','memory-ffm-3','transport-chunked')) {
    Run-Probe "summary-$name" @() @('jfr', "$report\$name.jfr-summary.txt", "$report\$name.jfr") $false
}
$hashes = @(Get-ChildItem -LiteralPath $source -File | Get-FileHash -Algorithm SHA256)
$hashes += Get-FileHash -LiteralPath "$jdk\bin\java.exe", "$jdk\bin\server\jvm.dll", "$root\.tools\archives\OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip", "$root\.tools\archives\memcached-1.6.45.tar.gz" -Algorithm SHA256
[IO.File]::WriteAllText("$report\hashes.json", ($hashes | Select-Object Path, Hash | ConvertTo-Json -Depth 3))
