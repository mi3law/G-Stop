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

    Runs on Windows or macOS; toolchain.ps1 resolves where the tools are.

    Signing is the release keystore, the same key release.ps1 uses. That is what lets this script
    replace a dev app that Obtainium installed from a release, and lets the next release replace
    this one. The two apps stay distinct by applicationId, name and icon, not by key.
#>
param(
    [switch]$SkipTests,
    [switch]$NoInstall
)

$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
. (Join-Path $projectRoot "toolchain.ps1")
$tools  = Resolve-GStopToolchain -ProjectRoot $projectRoot
$appDir = $tools.AppDir
$gradle = $tools.Gradle
$adb    = $tools.Adb

# Without it the debug build falls back to Gradle's own debug key, and an APK signed with that
# cannot replace — or be replaced by — the dev app published alongside a release. Failing here is
# better than finding out on the phone, where the only way out is uninstalling the dev app.
if (-not (Test-Path (Join-Path $appDir "keystore.properties"))) {
    throw "keystore.properties is missing. The dev app is signed with the release key so that USB and Obtainium installs can replace each other; see docs/releasing.md."
}

# The version reads as the last release plus the commit, e.g. 1.3.1-ga1b2c3d — build.gradle.kts
# appends the sha itself, so only the base is passed here.
$lastTag = (& git -C $projectRoot describe --tags --abbrev=0 2>$null)
$baseVersion = if ($LASTEXITCODE -eq 0 -and $lastTag) { $lastTag -replace '^v', '' } else { "0.0" }
$branch = (& git -C $projectRoot rev-parse --abbrev-ref HEAD)

# Same formula release.ps1 uses, so a build from here and a published dev APK stay in step rather
# than one being a downgrade of the other. -d on the install below covers the rest.
$versionCode = [int](& git -C $projectRoot rev-list --count HEAD) + 1

Write-Host "Building G-Stop dev $baseVersion from $branch (versionCode $versionCode)" -ForegroundColor Cyan

Push-Location $appDir
try {
    if (-not $SkipTests) {
        & $gradle testDebugUnitTest --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Unit tests failed. Nothing was built." }
    }

    & $gradle assembleDebug --no-daemon --console=plain `
        "-PversionName=$baseVersion" "-PversionCode=$versionCode"
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

# -d allows a downgrade: a branch can sit behind the last published dev APK on commit count, and
# what is being tried by hand should win over what a release left there. Permitted because the dev
# app is debuggable.
& $adb install -r -d $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed." }

Write-Host "G-Stop dev $baseVersion is on the phone." -ForegroundColor Green
