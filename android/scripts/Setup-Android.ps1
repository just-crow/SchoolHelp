param([switch]$SkipSdk)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$toolsRoot = Join-Path (Split-Path $projectRoot -Parent) '.tools'
New-Item -ItemType Directory -Force $toolsRoot | Out-Null

function Get-VerifiedFile([string]$Url, [string]$Destination, [string]$Sha256) {
    if ((Test-Path -LiteralPath $Destination) -and
        ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256)) { return }
    Write-Host "Downloading $(Split-Path $Destination -Leaf)..."
    & curl.exe --location --fail --retry 3 --silent --show-error --output "$Destination.part" $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    if ((Get-FileHash -LiteralPath "$Destination.part" -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum verification failed: $Destination"
    }
    Move-Item -LiteralPath "$Destination.part" -Destination $Destination -Force
}

$jdkDir = Join-Path $toolsRoot 'jdk/jdk-17.0.18+8'
if (!(Test-Path -LiteralPath "$jdkDir/bin/java.exe")) {
    Get-VerifiedFile 'https://aka.ms/download-jdk/microsoft-jdk-17.0.18-windows-x64.zip' "$toolsRoot/jdk.zip" '757815B4A0AF282188D3141B9D84B91E68F8A10D9A599301EA1380590703070A'
    Expand-Archive -LiteralPath "$toolsRoot/jdk.zip" -DestinationPath "$toolsRoot/jdk" -Force
}
$env:JAVA_HOME = $jdkDir

$sdkDir = Join-Path $toolsRoot 'android-sdk'
if (!$SkipSdk) {
    if (!(Test-Path -LiteralPath "$sdkDir/cmdline-tools/latest/bin/sdkmanager.bat")) {
        Get-VerifiedFile 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' "$toolsRoot/cmdline-tools.zip" '98B565CB657B012DAE6794CEFC0F66AE1EFB4690C699B78A614B4A6A3505B003'
        Expand-Archive -LiteralPath "$toolsRoot/cmdline-tools.zip" -DestinationPath "$sdkDir/cmdline-tools" -Force
        Rename-Item -LiteralPath "$sdkDir/cmdline-tools/cmdline-tools" -NewName 'latest'
    }
    if (!(Test-Path -LiteralPath "$sdkDir/platforms/android-36/android.jar") -or
        !(Test-Path -LiteralPath "$sdkDir/build-tools/36.0.0/aapt2.exe") -or
        !(Test-Path -LiteralPath "$sdkDir/platform-tools/adb.exe")) {
        Write-Host 'Review and accept the Android SDK licenses to install build tools.'
        & "$sdkDir/cmdline-tools/latest/bin/sdkmanager.bat" "--sdk_root=$sdkDir" --licenses
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed.' }
        & "$sdkDir/cmdline-tools/latest/bin/sdkmanager.bat" "--sdk_root=$sdkDir" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
    }
    $escapedSdk = $sdkDir.Replace('\', '/').Replace(':', '\:')
    $propertiesPath = Join-Path $projectRoot 'local.properties'
    $existing = if (Test-Path -LiteralPath $propertiesPath) { Get-Content -LiteralPath $propertiesPath } else { @() }
    $otherSettings = @($existing | Where-Object { $_ -notmatch '^sdk\.dir=' })
    [System.IO.File]::WriteAllLines($propertiesPath, @("sdk.dir=$escapedSdk") + $otherSettings)
}
New-Item -ItemType Directory -Force "$projectRoot/app/libs" | Out-Null
Get-VerifiedFile 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar' "$projectRoot/app/libs/sherpa-onnx-1.13.8.aar" '633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96'
Write-Host 'Android project dependencies are ready.'
