param([string]$Proxy)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $taskRoot 'app/libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar'
$expected = '07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575'
if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expected) { Write-Output 'Speech runtime verified.'; exit 0 }
New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
$temporary = $target + '.download'
try {
    $request = @{ Uri='https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar'; OutFile=$temporary }
    if ($Proxy) { $request.Proxy = $Proxy }
    Invoke-WebRequest @request
    if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Speech runtime checksum mismatch.' }
    Move-Item -LiteralPath $temporary -Destination $target -Force
    Write-Output 'Speech runtime installed and verified.'
} finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
