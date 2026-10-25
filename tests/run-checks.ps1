[CmdletBinding()]
param(
    [string]$Python = 'python',
    [switch]$FullJava,
    [string]$JavaHome,
    [string]$EvidenceParent
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $EvidenceParent) { $EvidenceParent = Join-Path $PSScriptRoot 'reports/release/testing-rework/integration' }
if (-not (Test-Path -LiteralPath $EvidenceParent -PathType Container)) {
    throw "Evidence parent must already exist: $EvidenceParent"
}
$out = Join-Path (Resolve-Path -LiteralPath $EvidenceParent).Path ('checks-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out -ErrorAction Stop | Out-Null
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:PATH
$originalMavenOpts = $env:MAVEN_OPTS
$originalJavaOptions = $env:JAVA_TOOL_OPTIONS
$status = [ordered]@{ startedUtc = [DateTime]::UtcNow.ToString('o'); fullJava = [bool]$FullJava; harnessExit = $null; javaExit = $null; outcome = 'FAIL'; error = $null }
$exitCode = 1
function Invoke-BoundedCheck([string]$Executable, [string[]]$Arguments, [string]$Label, [int]$TimeoutSeconds) {
    $quoted = @($Arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' })
    if ([IO.Path]::GetFileName($Executable) -ieq 'cmd.exe') {
        # cmd /c parses one command string, not CRT-style individually quoted switches.
        # Only this fixed, repository-owned build command is accepted here.
        if (($Arguments -join ' ') -ne '/d /c mvnw.cmd -B -ntp clean verify -Pcoverage') {
            throw 'Unexpected command-shell invocation'
        }
        $quoted = '/d /s /c "mvnw.cmd -B -ntp clean verify -Pcoverage"'
    }
    $process = Start-Process -FilePath $Executable -ArgumentList $quoted -WorkingDirectory $root -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $out "$Label.stdout.log") -RedirectStandardError (Join-Path $out "$Label.stderr.log")
    $handle = $process.Handle
    $timedOut = $false
    $result = 1
    try {
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) { $timedOut = $true; $result = 124 }
        else { $result = $process.ExitCode }
    } finally {
        if (-not $process.HasExited) {
            # Terminate only the launched PID and its descendants, never a process-name sweep.
            $killer = Start-Process -FilePath "$env:SystemRoot/System32/taskkill.exe" -ArgumentList @('/PID', $process.Id, '/T', '/F') -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $out "$Label.cleanup.log") -RedirectStandardError (Join-Path $out "$Label.cleanup.stderr.log")
            try {
                if (-not $killer.WaitForExit(10000)) { $killer.Kill(); throw 'Owned process-tree termination exceeded deadline' }
                if ($killer.ExitCode -ne 0 -or -not $process.WaitForExit(5000)) { throw 'Owned check process could not be reaped' }
            } finally { $killer.Dispose() }
        }
        [ordered]@{ pid = $process.Id; timeoutSeconds = $TimeoutSeconds; timedOut = $timedOut; reaped = $process.HasExited; exitCode = $result } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out "$Label.process.json") -Encoding UTF8
        $process.Dispose()
    }
    return $result
}
Push-Location $root
try {
    $inputs = @(Get-ChildItem -LiteralPath (Join-Path $root 'src'), (Join-Path $root 'tests/platform'), (Join-Path $root 'tests/harness_tests'), (Join-Path $root '.mvn') -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' })
    $inputs += Get-Item -LiteralPath 'pom.xml', 'compatibility-manifest.json', 'mvnw.cmd', $PSCommandPath
    if (Test-Path -LiteralPath 'target/octomem-1.0.0.jar') { $inputs += Get-Item -LiteralPath 'target/octomem-1.0.0.jar' }
    $before = @($inputs | Sort-Object FullName -Unique | ForEach-Object { [ordered]@{ path = $_.FullName; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } })
    $before | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-before.json') -Encoding UTF8
    $pythonExe = @(Get-Command $Python -CommandType Application -ErrorAction Stop)[0].Source
    $pythonVersion = & $pythonExe --version
    if ($LASTEXITCODE -ne 0) { throw 'Python version probe failed' }
    [ordered]@{ executable = $pythonExe; version = "$pythonVersion"; sha256 = (Get-FileHash -LiteralPath $pythonExe -Algorithm SHA256).Hash; powershell = $PSVersionTable.PSVersion.ToString(); javaRequested = $JavaHome; inheritedJavaToolOptions = $originalJavaOptions } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'environment.json') -Encoding UTF8
    $code = "import sys,unittest; s=unittest.defaultTestLoader.discover('tests/harness_tests'); n=s.countTestCases(); print('Discovered',n,'tests'); sys.exit(2) if not n else None; r=unittest.TextTestRunner(verbosity=2).run(s); sys.exit(0 if r.wasSuccessful() and not r.skipped else 1)"
    "`"$pythonExe`" -B -c $code" | Set-Content -LiteralPath (Join-Path $out 'harness-command.txt') -Encoding UTF8
    $status.harnessExit = Invoke-BoundedCheck $pythonExe @('-B', '-c', $code) 'harness' 120
    if ($FullJava) {
        if (-not $JavaHome -or -not [IO.Path]::IsPathRooted($JavaHome)) { throw '-FullJava requires an explicit absolute -JavaHome for JDK 25' }
        $java = Join-Path $JavaHome 'bin/java.exe'
        $javac = Join-Path $JavaHome 'bin/javac.exe'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or -not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'Explicit JDK must contain java.exe and javac.exe' }
        $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $originalPath
        $env:MAVEN_OPTS = '-Xmx256m -Dfile.encoding=UTF-8'
        $env:JAVA_TOOL_OPTIONS = '-Xmx512m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8'
        $ErrorActionPreference = 'Continue'
        $version = & $java -version 2>&1
        $versionExit = $LASTEXITCODE
        $compiler = & $javac -version 2>&1
        $compilerExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        $version, $compiler | Out-String | Set-Content -LiteralPath (Join-Path $out 'jdk-version.log') -Encoding UTF8
        if ($versionExit -ne 0 -or $compilerExit -ne 0 -or "$version" -notmatch 'version "25(?:\.|\")' -or "$compiler" -notmatch 'javac 25(?:\.|\s|$)') { throw 'Explicit java and javac must both be JDK 25' }
        [ordered]@{ java = $java; javaSha256 = (Get-FileHash -LiteralPath $java).Hash; javacSha256 = (Get-FileHash -LiteralPath $javac).Hash; javaToolOptions = $env:JAVA_TOOL_OPTIONS; mavenOpts = $env:MAVEN_OPTS } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'jdk.json') -Encoding UTF8
        './mvnw.cmd -B -ntp clean verify -Pcoverage' | Set-Content -LiteralPath (Join-Path $out 'java-command.txt') -Encoding UTF8
        $status.javaExit = Invoke-BoundedCheck "$env:SystemRoot/System32/cmd.exe" @('/d', '/c', 'mvnw.cmd', '-B', '-ntp', 'clean', 'verify', '-Pcoverage') 'java' 1800
        foreach ($name in @('surefire-reports', 'failsafe-reports', 'site', 'child-coverage', 't013-wire-evidence', 'jacoco.exec', 'jacoco-merged.exec')) {
            $path = Join-Path 'target' $name
            if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination $out -Recurse -ErrorAction Stop }
        }
    }
    $after = @($before | ForEach-Object { [ordered]@{ path = $_.path; sha256 = if (Test-Path -LiteralPath $_.path) { (Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash } else { $null } } })
    $after | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-after.json') -Encoding UTF8
    $changed = @($after | Where-Object { $item = $_; ($before | Where-Object { $_.path -eq $item.path }).sha256 -ne $item.sha256 })
    if ($changed.Count) { throw 'Input or candidate identity changed during checks; inspect input manifests' }
    if ($status.harnessExit -eq 0 -and (-not $FullJava -or $status.javaExit -eq 0)) { $status.outcome = 'PASS'; $exitCode = 0 }
} catch {
    $status.error = $_.Exception.ToString()
} finally {
    $ErrorActionPreference = 'Stop'
    $env:JAVA_HOME = $originalJavaHome
    $env:PATH = $originalPath
    $env:MAVEN_OPTS = $originalMavenOpts
    $env:JAVA_TOOL_OPTIONS = $originalJavaOptions
    Pop-Location
    $status.finishedUtc = [DateTime]::UtcNow.ToString('o')
    $status | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $out 'result.json') -Encoding UTF8
    Get-ChildItem -LiteralPath $out -Recurse -File | ForEach-Object { [ordered]@{ path = $_.FullName; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'evidence-hashes.json') -Encoding UTF8
}
Write-Output "Evidence: $out"
Write-Output "Checks: $($status.outcome) (harness=$($status.harnessExit), java=$($status.javaExit))"
exit $exitCode
