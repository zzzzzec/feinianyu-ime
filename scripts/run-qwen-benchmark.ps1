param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = 'adb',
    [string]$OutputDirectory = 'dist/qwen-benchmark'
)
$ErrorActionPreference = 'Stop'
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$originalIme = (& $Adb -s $Serial shell settings get secure default_input_method).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Device is unavailable or not authorized.' }
$alternatives = @(@(& $Adb -s $Serial shell ime list -s) | Where-Object { $_ -and $_ -notlike 'dev.feinianyu.ime/*' })
if (!$alternatives) { throw 'Enable another keyboard before running the isolated benchmark.' }
try {
    & $Adb -s $Serial shell ime set $alternatives[0]
    if ($LASTEXITCODE -ne 0) { throw 'Could not isolate the benchmark from the active IME.' }
    $result = @(& $Adb -s $Serial shell am instrument -w -r -e class org.fcitx.fcitx5.android.QwenOptimizationTest -e qwen_benchmark true dev.feinianyu.ime.test/androidx.test.runner.AndroidJUnitRunner)
    $result | Set-Content -LiteralPath (Join-Path $destination 'instrumentation.txt') -Encoding utf8
    if ($LASTEXITCODE -ne 0 -or (($result -join "`n") -notmatch 'OK \(3 tests\)')) { throw 'Benchmark or correctness checks failed; see instrumentation.txt.' }
    # run-as requires the signed debug APK and matching Android-test APK; no data is cleared.
    $remote = '/sdcard/Android/data/dev.feinianyu.ime/files/qwen-thread-benchmark.json'
    & $Adb -s $Serial shell run-as dev.feinianyu.ime cp files/qwen-thread-benchmark.json $remote
    if ($LASTEXITCODE -ne 0) { throw 'Could not stage the benchmark report.' }
    & $Adb -s $Serial pull $remote (Join-Path $destination 'qwen-thread-benchmark.json')
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the benchmark report.' }
} finally {
    if ($originalIme -and $originalIme -ne 'null') { & $Adb -s $Serial shell ime set $originalIme | Out-Null }
}
Write-Output "Benchmark saved to $destination"
