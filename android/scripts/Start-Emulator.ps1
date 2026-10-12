param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path (Split-Path $projectRoot -Parent) '.tools'
$sdk = Join-Path $toolsRoot 'android-sdk'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$emulator = Join-Path $sdk 'emulator/emulator.exe'
$env:ANDROID_HOME = $sdk
$env:ANDROID_AVD_HOME = Join-Path $toolsRoot 'avd'
$serial = 'emulator-5554'
if (!(Test-Path -LiteralPath $emulator) -or !(Test-Path -LiteralPath "$env:ANDROID_AVD_HOME/SchoolHelpTest.ini")) {
    throw 'The project emulator is missing. Install the Android emulator and Android 15 x86_64 system image, then create an AVD named SchoolHelpTest.'
}
$devices = & $adb devices
if ($devices -match "$serial\s+device") {
    $avdName = & $adb -s $serial emu avd name
    if ($avdName -notcontains 'SchoolHelpTest') { throw 'Port 5554 is occupied by another emulator. Close it before running this script.' }
} else {
    # This is an interactive emulator window for manual app testing.
    Start-Process -FilePath $emulator -ArgumentList '-avd SchoolHelpTest -port 5554 -gpu swiftshader -no-snapshot -no-boot-anim -memory 2048 -cores 2 -feature -Vulkan' -WorkingDirectory $projectRoot | Out-Null
}
Write-Host 'Waiting for Android to boot...'
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 2
    $booted = ''
    $ErrorActionPreference = 'Continue'
    $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
    $ErrorActionPreference = 'Stop'
    if ((Get-Date) -gt $deadline) { throw 'Emulator boot timed out. Check its window and run this script again.' }
} until ($booted -match '^1$')
if (!$SkipInstall) {
    $apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    if (!(Test-Path -LiteralPath $apk)) { throw 'Build the APK first with scripts/Build.ps1.' }
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
}
& $adb -s $serial shell input keyevent 82
& $adb -s $serial shell am start -n com.schoolhelp.app/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Host 'SchoolHelp is ready in the Android emulator.'
