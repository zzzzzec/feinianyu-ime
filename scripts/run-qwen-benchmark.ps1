param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = 'adb',
    [string]$OutputDirectory = 'dist/qwen-benchmark',
    [switch]$ReadReportOnly
)
$ErrorActionPreference = 'Stop'
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $destination | Out-Null
function Read-BenchmarkReport {
    $lines = @(& $Adb -s $Serial exec-out run-as dev.feinianyu.ime cat files/qwen-thread-benchmark.json)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the report. A signed debug APK is required.' }
    $payload = $lines -join "`n"
    $report = $payload | ConvertFrom-Json
    if ($report.schema -ne 1 -or !$report.metrics.Count) { throw 'Invalid benchmark report.' }
    [IO.File]::WriteAllText((Join-Path $destination 'qwen-thread-benchmark.json'), $payload, [Text.UTF8Encoding]::new($false))
}
if ($ReadReportOnly) {
    Read-BenchmarkReport
    Write-Output "Benchmark report saved to $destination"
    return
}
$originalIme = (& $Adb -s $Serial shell settings get secure default_input_method).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Device is unavailable or not authorized.' }
$alternatives = @(@(& $Adb -s $Serial shell ime list -s) | Where-Object {
    $_ -and $_ -notlike 'dev.feinianyu.ime/*' -and $_ -notmatch '(?i)voice|speech|googletts'
})
if (!$alternatives) { throw 'Enable another keyboard before running the isolated benchmark.' }
$preferred = @($alternatives | Where-Object { $_ -like 'com.google.android.inputmethod.latin/*' })
$alternateIme = if ($preferred.Count) { $preferred[0] } else { $alternatives[0] }
try {
    & $Adb -s $Serial shell ime set $alternateIme
    if ($LASTEXITCODE -ne 0) { throw 'Could not isolate the benchmark from the active IME.' }
    $result = @(& $Adb -s $Serial shell am instrument -w -r -e class org.fcitx.fcitx5.android.QwenOptimizationTest -e qwen_benchmark true dev.feinianyu.ime.test/androidx.test.runner.AndroidJUnitRunner)
    $result | Set-Content -LiteralPath (Join-Path $destination 'instrumentation.txt') -Encoding utf8
    if ($LASTEXITCODE -ne 0 -or (($result -join "`n") -notmatch 'OK \(3 tests\)')) { throw 'Benchmark or correctness checks failed; see instrumentation.txt.' }
    Read-BenchmarkReport
} finally {
    if ($originalIme -and $originalIme -ne 'null') { & $Adb -s $Serial shell ime set $originalIme | Out-Null }
}
Write-Output "Benchmark saved to $destination"
