param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $SkipBuild) {
    & "$PSScriptRoot/Build.ps1"
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
}
$config = Get-Content "$root/android/app/build.gradle.kts" -Raw
$code = [int]([regex]::Match($config, 'versionCode = (\d+)').Groups[1].Value)
$version = [regex]::Match($config, 'versionName = "([^"]+)"').Groups[1].Value
$tag = "v$version"
$releaseFolder = "$root/.tools/release"
New-Item -ItemType Directory -Force -Path $releaseFolder | Out-Null
Copy-Item -LiteralPath "$root/android/app/build/outputs/apk/debug/app-debug.apk" -Destination "$releaseFolder/SchoolHelp.apk"
$hash = (Get-FileHash "$releaseFolder/SchoolHelp.apk" -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  SchoolHelp.apk" | Set-Content "$releaseFolder/SchoolHelp.apk.sha256" -Encoding ascii
# Never replace an existing release. Increase versionCode and versionName for each update.
& gh release create $tag "$releaseFolder/SchoolHelp.apk" "$releaseFolder/SchoolHelp.apk.sha256" --repo just-crow/SchoolHelp --target main --title "SchoolHelp $version" --notes 'SchoolHelp update. Install over the existing app to preserve your settings and saved answers.'
if ($LASTEXITCODE -ne 0) { throw 'Release publication failed; update manifest was not changed.' }
$manifest = @{versionCode=$code;versionName=$version;sha256=$hash;url="https://github.com/just-crow/SchoolHelp/releases/download/$tag/SchoolHelp.apk"} | ConvertTo-Json
[System.IO.File]::WriteAllText("$root/web/public/android-update.json", $manifest, (New-Object System.Text.UTF8Encoding $false))
Write-Host 'Release uploaded. Commit and push web/public/android-update.json to make this update available in the app.'
