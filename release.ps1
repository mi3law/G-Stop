<#
.SYNOPSIS
    Build a signed release APK and publish it as a GitHub release, so Obtainium picks it up.

.EXAMPLE
    .\release.ps1 -Version 1.1
    .\release.ps1 -Version 1.2 -Notes "Fixes the volume floor under DND."
    .\release.ps1 -Version 1.3.1 -NoPublish

.NOTES
    Requires GStopApp/keystore.properties and the .jks it points at. Every release must be
    signed with that same key, or the update will not install over an existing G-Stop install.

    -NoPublish builds and signs exactly what would be published and then stops, so a version can
    be tried on the phone before anything is tagged. Because it is signed with the same key it
    installs straight over the copy Obtainium put there, keeping the practice history — which is
    the whole point of trying it first.

    Toolchain locations are taken from JAVA_HOME / ANDROID_HOME / GSTOP_GRADLE if set,
    otherwise from the defaults below.
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [string]$Repo = "mi3law/G-Stop",
    [switch]$SkipTests,
    [switch]$NoPublish
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

if ($NoPublish) {
    $adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb" }
    Write-Host ""
    Write-Host "Built and signed. Nothing was tagged and nothing was published." -ForegroundColor Green
    Write-Host "  $asset"
    Write-Host ""
    Write-Host "With the phone plugged in and USB debugging on, install it with:" -ForegroundColor Cyan
    Write-Host "  & `"$adb`" install -r `"$asset`""
    Write-Host ""
    Write-Host "Same key as every release, so it replaces the installed copy in place and the"
    Write-Host "practice history survives. Run without -NoPublish when you are ready to ship it."
    return
}

$tag = "v$Version"
if ([string]::IsNullOrWhiteSpace($Notes)) { $Notes = "G-Stop $Version" }

# Release notes go via a file, not --notes. Windows PowerShell does not escape embedded double
# quotes when it builds a native command line, so notes containing a quoted phrase arrive at gh
# split into several arguments and it fails looking for an asset named after one of the words.
$notesFile = Join-Path ([System.IO.Path]::GetTempPath()) "G-Stop-$Version-notes.md"
Set-Content -Path $notesFile -Value $Notes -Encoding utf8

Write-Host "Publishing $tag to $Repo ..." -ForegroundColor Cyan
try {
    & gh release create $tag $asset --repo $Repo --title "G-Stop $Version" `
        --notes-file $notesFile --target main
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed." }
}
finally {
    Remove-Item $notesFile -ErrorAction SilentlyContinue
}

Write-Host "Released $tag. Obtainium will offer the update on its next check." -ForegroundColor Green
