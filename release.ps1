<#
.SYNOPSIS
    Build the signed release and dev APKs and publish both as a GitHub release, so Obtainium
    picks them up.

.EXAMPLE
    .\release.ps1 -Version 1.1
    .\release.ps1 -Version 1.2 -Notes "Fixes the volume floor under DND."
    .\release.ps1 -Version 1.3.1 -NoPublish

.NOTES
    Requires GStopApp/keystore.properties and the .jks it points at. Every release must be
    signed with that same key, or the update will not install over an existing G-Stop install.

    Two assets go up: G-Stop-<version>.apk, the app itself, and G-Stop-dev-<version>.apk, the
    same commit built as the dev app (com.gstop.debug, orange, its own database). The second is
    there so a phone that cannot easily be wired up can still carry a debuggable build, tracked
    by its own Obtainium source. Both are signed with the release key, which is what lets
    dev.ps1 and Obtainium replace each other's dev installs. See docs/obtainium.md.

    -NoPublish builds and signs exactly what would be published and then stops, so a version can
    be tried on the phone before anything is tagged. Because it is signed with the same key it
    installs straight over the copy Obtainium put there, keeping the practice history — which is
    the whole point of trying it first.

    Runs on Windows or macOS. Toolchain locations are taken from JAVA_HOME / ANDROID_HOME /
    GSTOP_GRADLE if set, otherwise from the per-platform defaults in toolchain.ps1.
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
. (Join-Path $projectRoot "toolchain.ps1")
$tools  = Resolve-GStopToolchain -ProjectRoot $projectRoot
$appDir = $tools.AppDir
$gradle = $tools.Gradle

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

    # Both variants from one invocation and one commit, so the dev APK is never a build behind
    # the release it is published beside.
    & $gradle assembleRelease assembleDebug --no-daemon --console=plain `
        "-PversionName=$Version" "-PversionCode=$versionCode"
    if ($LASTEXITCODE -ne 0) { throw "Release build failed." }
}
finally {
    Pop-Location
}

$apk = Join-Path $appDir "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "Expected APK not found at $apk" }

$devApk = Join-Path $appDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $devApk)) { throw "Expected dev APK not found at $devApk" }

# The names carry the whole contract with Obtainium: its two sources tell these apart by filter
# alone. G-Stop-dev- must not match the release source's filter — see docs/obtainium.md.
$asset = Join-Path ([System.IO.Path]::GetTempPath()) "G-Stop-$Version.apk"
$devAsset = Join-Path ([System.IO.Path]::GetTempPath()) "G-Stop-dev-$Version.apk"
Copy-Item $apk $asset -Force
Copy-Item $devApk $devAsset -Force

if ($NoPublish) {
    $adb = $tools.Adb
    Write-Host ""
    Write-Host "Built and signed. Nothing was tagged and nothing was published." -ForegroundColor Green
    Write-Host "  $asset"
    Write-Host "  $devAsset  (the dev app, com.gstop.debug)"
    Write-Host ""
    Write-Host "With the phone plugged in and USB debugging on, install it with:" -ForegroundColor Cyan
    Write-Host "  & `"$adb`" install -r `"$asset`""
    Write-Host "  & `"$adb`" install -r -d `"$devAsset`""
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
    & gh release create $tag $asset $devAsset --repo $Repo --title "G-Stop $Version" `
        --notes-file $notesFile --target main
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed." }
}
finally {
    Remove-Item $notesFile -ErrorAction SilentlyContinue
}

Write-Host "Released $tag. Obtainium will offer the update on its next check." -ForegroundColor Green
