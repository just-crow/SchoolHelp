param([switch]$Install)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/Setup-Android.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$toolsRoot = Join-Path (Split-Path $projectRoot -Parent) '.tools'
$env:JAVA_HOME = Join-Path $toolsRoot 'jdk/jdk-17.0.18+8'
& "$projectRoot/gradlew.bat" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
if ($Install) {
    & "$toolsRoot/android-sdk/platform-tools/adb.exe" install -r "$projectRoot/app/build/outputs/apk/debug/app-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Installation failed. Connect a phone with USB debugging enabled.' }
}
