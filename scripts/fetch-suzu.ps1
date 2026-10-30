$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot '../app/src/main/assets/suzu'
New-Item -ItemType Directory -Force $destination | Out-Null
$revision = 'b166ed00f744a3a8f68f7b1b8980a76c80ac9f25'
$files = @(
    @{ Name = 'suzu-2.4.q8_0.gguf'; Hash = 'a2ff2c46110c7ba47746871eaf8ff37cc4fab58105ddbe11e0a768e340cdb996' },
    @{ Name = 'suzu-2.4.q8_0.gguf.vocab.txt'; Hash = '21f87a1ce1e99597342ed521787a1d7cac308edb6d2eddeb6c2db06c05668e5d' }
)
foreach ($file in $files) {
    $target = Join-Path $destination $file.Name
    if (!(Test-Path -LiteralPath $target) -or ($file.Hash -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $file.Hash)) {
        Invoke-WebRequest -Uri "https://huggingface.co/IzumiThe3rd/Suzu-2.4/resolve/$revision/$($file.Name)" -OutFile "$target.download"
        if ($file.Hash -and (Get-FileHash -LiteralPath "$target.download" -Algorithm SHA256).Hash -ne $file.Hash) { throw "Checksum mismatch: $($file.Name)" }
        Move-Item -LiteralPath "$target.download" -Destination $target -Force
    }
    Get-FileHash -LiteralPath $target -Algorithm SHA256
}
