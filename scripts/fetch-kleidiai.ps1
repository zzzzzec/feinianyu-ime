param([string]$Proxy)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$expected = 'a62d13f9d091badfc286bf08cbba116f834cd330ae28ba1c0e48d0c53a7dc333'
$destination = Join-Path $root 'third_party/kleidiai'
$marker = Join-Path $destination '.source-sha256'
if ((Test-Path -LiteralPath $marker) -and (Get-Content -LiteralPath $marker -Raw).Trim() -eq $expected) {
    Write-Output 'KleidiAI v1.31.0 is ready.'
    return
}
$cache = Join-Path $root 'dist/dependencies'
New-Item -ItemType Directory -Force -Path $cache, $destination | Out-Null
$archive = Join-Path $cache 'kleidiai-v1.31.0-src.tar.gz'
if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    $download = "$archive.partial"
    $arguments = @('--fail','--location','--retry','2','--output',$download)
    if ($Proxy) { $arguments += @('--proxy',$Proxy) }
    $arguments += 'https://github.com/ARM-software/kleidiai/releases/download/v1.31.0/kleidiai-v1.31.0-src.tar.gz'
    & curl.exe @arguments
    if ($LASTEXITCODE -ne 0) { throw 'KleidiAI download failed.' }
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'KleidiAI checksum mismatch.' }
    Move-Item -LiteralPath $download -Destination $archive -Force
}
& tar.exe -xzf $archive -C $destination --strip-components=1
if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath (Join-Path $destination 'CMakeLists.txt'))) { throw 'KleidiAI extraction failed.' }
Set-Content -LiteralPath $marker -Value $expected -Encoding ascii
Write-Output 'Verified KleidiAI v1.31.0 source is ready.'
