param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string[]]$Apk,
    [string]$Repository = 'zzzzzec/feinianyu-ime',
    [string]$NotesFile,
    [string]$OutputDirectory = 'dist/release'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$NotesFile) { $NotesFile = Join-Path $root "docs/changelog/$Version.md" }
if (!(Test-Path -LiteralPath $NotesFile)) { throw 'Write the Chinese changelog before publishing.' }
$aapt = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Filter aapt.exe -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (!$aapt) { throw 'Android build-tools aapt.exe is required.' }
$destination = Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force $destination | Out-Null
$entries = @()
$attachments = @()
foreach ($path in $Apk) {
    $source = (Resolve-Path -LiteralPath $path).Path
    $badging = & $aapt dump badging $source
    if ($LASTEXITCODE -ne 0) { throw "Invalid APK: $source" }
    $package = $badging | Where-Object { $_.StartsWith('package:') } | Select-Object -First 1
    $applicationId = [regex]::Match($package, "name='([^']+)' versionCode='").Groups[1].Value
    $code = [long][regex]::Match($package, "versionCode='([0-9]+)'").Groups[1].Value
    $name = [regex]::Match($package, "versionName='([^']+)'").Groups[1].Value
    if ($applicationId -ne 'dev.feinianyu.ime' -or $name -ne $Version) { throw 'APK package or version does not match this release.' }
    $native = $badging | Where-Object { $_.StartsWith('native-code:') } | Select-Object -First 1
    $abi = [regex]::Match($native, "'([^']+)'").Groups[1].Value
    if ($abi -notin @('arm64-v8a','x86_64')) { throw 'Unsupported release ABI.' }
    if ($entries | Where-Object { $_.abi -eq $abi }) { throw 'Duplicate ABI.' }
    $filename = "feinianyu-ime-$Version-$abi.apk"
    $target = Join-Path $destination $filename
    if ($source -ne $target) { Copy-Item -LiteralPath $source -Destination $target -Force }
    $entries += [ordered]@{name=$filename; abi=$abi; versionCode=$code; size=(Get-Item -LiteralPath $target).Length; sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()}
    $attachments += $target
}
$manifest = Join-Path $destination 'update.json'
[ordered]@{schemaVersion=1; applicationId='dev.feinianyu.ime'; versionName=$Version; configSchemaVersion=1; assets=@($entries)} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifest -Encoding utf8NoBOM
$attachments += $manifest
$commit = git -C $root rev-parse HEAD
& gh release create "v$Version" @attachments --repo $Repository --draft --target $commit --title "飞念语输入法 $Version" --notes-file $NotesFile
if ($LASTEXITCODE -ne 0) { throw 'Draft release upload failed. Existing releases were not replaced.' }
$release = & gh release view "v$Version" --repo $Repository --json assets,isDraft | ConvertFrom-Json
if (!$release.isDraft -or $release.assets.Count -ne $attachments.Count) { throw 'Verify the draft assets before publishing.' }
& gh release edit "v$Version" --repo $Repository --draft=false --latest
if ($LASTEXITCODE -ne 0) { throw 'Could not publish the verified draft.' }
& gh release view "v$Version" --repo $Repository --json url,tagName,assets
