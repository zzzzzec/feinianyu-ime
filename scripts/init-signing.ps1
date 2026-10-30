$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$directory = Join-Path $repository '.private'
$properties = Join-Path $directory 'signing.properties'
$keystore = Join-Path $directory 'personal-keyboard.p12'
if ((Test-Path -LiteralPath $properties) -and (Test-Path -LiteralPath $keystore)) {
    Write-Output 'Existing personal signing key retained.'
    return
}
if ((Test-Path -LiteralPath $properties) -or (Test-Path -LiteralPath $keystore)) {
    throw 'Signing files are incomplete. Restore the original pair; do not replace the key.'
}
New-Item -ItemType Directory -Force $directory | Out-Null
$random = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$env:PERSONAL_KEYSTORE_PASSWORD = [Convert]::ToBase64String($random)
try {
    & keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias personal-keyboard -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Personal Android Keyboard' -storepass:env PERSONAL_KEYSTORE_PASSWORD -keypass:env PERSONAL_KEYSTORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
    @(
        'storeFile=.private/personal-keyboard.p12'
        'keyAlias=personal-keyboard'
        "storePassword=$env:PERSONAL_KEYSTORE_PASSWORD"
        "keyPassword=$env:PERSONAL_KEYSTORE_PASSWORD"
    ) | Set-Content -LiteralPath $properties -Encoding ascii
    Write-Output 'Personal signing key created in .private. Back up both files together.'
} finally {
    Remove-Item Env:PERSONAL_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
