<#
.SYNOPSIS
    Build a signed release APK and publish it as a GitHub release, so Obtainium picks it up.

.EXAMPLE
    .\release.ps1 -Version 1.1
    .\release.ps1 -Version 1.2 -Notes "Fixes the volume floor under DND."

.NOTES
    Requires GStopApp/keystore.properties and the .jks it points at. Every release must be
    signed with that same key, or the update will not install over an existing G-Stop install.

    Toolchain locations are taken from JAVA_HOME / ANDROID_HOME / GSTOP_GRADLE if set,
    otherwise from the defaults below.
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [string]$Repo = "mi3law/G-Stop",
    [switch]$SkipTests
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

if (-not (Test-Path (Join-Path $appDir "keystore.properties"))) {
    throw "keystore.properties is missing. Without it the APK is unsigned and Obtainium cannot update it."
}

# versionCode must increase monotonically or Android refuses the update.
$versionCode = [int](& git -C $projectRoot rev-list --count HEAD) + 1
Write-Host "Building G-Stop $Version (versionCode $versionCode)" -ForegroundColor Cyan

Push-Location $appDir
try {
    if (-not $SkipTests) {
        Write-Host "Running unit tests..." -ForegroundColor Cyan
        & $gradle testDebugUnitTest --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Unit tests failed. Release aborted." }
    }

    & $gradle assembleRelease --no-daemon --console=plain `
        "-PversionName=$Version" "-PversionCode=$versionCode"
    if ($LASTEXITCODE -ne 0) { throw "Release build failed." }
}
finally {
    Pop-Location
}

$apk = Join-Path $appDir "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "Expected APK not found at $apk" }

$asset = Join-Path ([System.IO.Path]::GetTempPath()) "G-Stop-$Version.apk"
Copy-Item $apk $asset -Force

$tag = "v$Version"
if ([string]::IsNullOrWhiteSpace($Notes)) { $Notes = "G-Stop $Version" }

Write-Host "Publishing $tag to $Repo ..." -ForegroundColor Cyan
& gh release create $tag $asset --repo $Repo --title "G-Stop $Version" --notes $Notes --target main
if ($LASTEXITCODE -ne 0) { throw "gh release create failed." }

Write-Host "Released $tag. Obtainium will offer the update on its next check." -ForegroundColor Green
