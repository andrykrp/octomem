$ErrorActionPreference = 'Stop'
$root = 'C:\java\octomem.octocode.tech\exp\reports\P001'
$all = foreach ($file in (Get-ChildItem -LiteralPath $root -Filter 'memory-*.csv')) {
    foreach ($line in [IO.File]::ReadAllLines($file.FullName)) {
        if ($line.StartsWith('metric,')) {
            $p = $line.Split(',')
            if ($p.Count -ne 13) { throw "Invalid invariant CSV: $file" }
            [pscustomobject]@{ backend=$p[1]; operation=$p[2]; size=[int]$p[3]; repetition=[int]$p[4]; ops=[double]::Parse($p[7],[Globalization.CultureInfo]::InvariantCulture); p50=[long]$p[8]; p99=[long]$p[9]; cpuNs=[long]$p[10] }
        }
    }
}
$rows = foreach ($group in ($all | Group-Object backend,operation,size)) {
    $values = @($group.Group | Sort-Object ops)
    if ($values.Count -ne 3) { throw 'Three forks required' }
    [pscustomobject]@{ backend=$values[0].backend; operation=$values[0].operation; size=$values[0].size; medianOps=$values[1].ops; minOps=$values[0].ops; maxOps=$values[2].ops; medianP50Ns=(@($values.p50 | Sort-Object))[1]; medianP99Ns=(@($values.p99 | Sort-Object))[1]; medianCpuNs=(@($values.cpuNs | Sort-Object))[1] }
}
[IO.File]::WriteAllLines("$root\memory-summary.csv", @($rows | Sort-Object operation,size,backend | ConvertTo-Csv -NoTypeInformation))
$rows | Sort-Object operation,size,backend | Format-Table -AutoSize
$runs = [IO.File]::ReadAllText("$root\runs.json") | ConvertFrom-Json
$runs | Select-Object name,durationSeconds,peakWorkingSetBytes,exitCode | Format-Table -AutoSize
