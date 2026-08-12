<#
.SYNOPSIS
    Build the dev app from the current branch and put it on the phone. Publishes nothing.

.EXAMPLE
    .\dev.ps1
    .\dev.ps1 -SkipTests
    .\dev.ps1 -NoInstall

.NOTES
    This is the loop: work on a branch, run this, try it on the phone, and merge to main once it
    has actually run there. Nothing installs from a branch and nothing installs from main —
    Obtainium reads published releases — so main is free to mean "this has been on the phone".

    The dev app is com.gstop.debug, labelled "G-Stop dev", ringed in orange and bannered. It keeps
    its own database, so nothing done to it can touch a real practice log. Reinstalling over it
    keeps that database; only uninstalling clears it.

    Signing is the machine-local debug key, not the release keystore, so builds from this script
    can never be confused with something publishable. See release.ps1 for that.
#>
param(
    [switch]$SkipTests,
    [switch]$NoInstall
)

$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$appDir      = Join-Path $projectRoot "GStopApp"
$toolchain   = Join-Path $env:LOCALAPPDATA "GStopToolchain"

if (-not $env:JAVA_HOME)        { $env:JAVA_HOME        = Join-Path $toolchain "jdk" }
if (-not $env:ANDROID_HOME)     { $env:ANDROID_HOME     = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $toolchain "gradle_home" }

$gradle = if ($env:GSTOP_GRADLE) { $env:GSTOP_GRADLE } else { Join-Path $toolchain "gradle\bin\gradle.bat" }
if (-not (Test-Path $gradle)) { $gradle = Join-Path $appDir "gradlew.bat" }

$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

# The version reads as the last release plus the commit, e.g. 1.3.1-ga1b2c3d — build.gradle.kts
# appends the sha itself, so only the base is passed here.
$lastTag = (& git -C $projectRoot describe --tags --abbrev=0 2>$null)
$baseVersion = if ($LASTEXITCODE -eq 0 -and $lastTag) { $lastTag -replace '^v', '' } else { "0.0" }
$branch = (& git -C $projectRoot rev-parse --abbrev-ref HEAD)

Write-Host "Building G-Stop dev $baseVersion from $branch" -ForegroundColor Cyan

Push-Location $appDir
try {
    if (-not $SkipTests) {
        & $gradle testDebugUnitTest --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Unit tests failed. Nothing was built." }
    }

    & $gradle assembleDebug --no-daemon --console=plain "-PversionName=$baseVersion"
    if ($LASTEXITCODE -ne 0) { throw "Debug build failed." }
}
finally {
    Pop-Location
}

$apk = Join-Path $appDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "Expected APK not found at $apk" }

if ($NoInstall) {
    Write-Host "Built, not installed: $apk" -ForegroundColor Green
    return
}

if (-not (Test-Path $adb)) { throw "adb not found at $adb. Install platform-tools, or pass -NoInstall." }

# A phone that is plugged in but not authorised reports "unauthorized"; say so rather than
# letting adb fail with something less obvious.
$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$ready = $devices | Where-Object { $_ -match '\sdevice$' }
if (-not $ready) {
    Write-Host "No phone ready for install." -ForegroundColor Yellow
    if ($devices) { $devices | ForEach-Object { Write-Host "  $_" } }
    Write-Host "Plug it in with USB debugging on, and accept the prompt on the phone."
    Write-Host "The APK is built either way: $apk"
    return
}

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed." }

Write-Host "G-Stop dev $baseVersion is on the phone." -ForegroundColor Green
