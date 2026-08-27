<#
.SYNOPSIS
    Where the JDK, the Android SDK and Gradle are, on Windows or macOS.

.DESCRIPTION
    Dot-sourced by dev.ps1 and release.ps1, which differ in what they build, not in where the
    tools live. Both machines are development machines: the Windows one keeps its
    %LOCALAPPDATA%\GStopToolchain layout, the Mac uses Homebrew's.

    Explicit environment variables always win. JAVA_HOME, ANDROID_HOME and GSTOP_GRADLE are read
    if set and exported if not, so gradle and adb see the same toolchain this resolved.

.NOTES
    Requires PowerShell 7 on macOS (brew install powershell). Windows PowerShell 5.1 still works
    on Windows, where $IsWindows does not exist and absence of it means Windows.
#>

function Resolve-GStopToolchain {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $appDir = Join-Path $ProjectRoot "GStopApp"
    $onWindows = if ($null -eq $IsWindows) { $true } else { $IsWindows }

    if ($onWindows) {
        $toolchain = Join-Path $env:LOCALAPPDATA "GStopToolchain"

        if (-not $env:JAVA_HOME)        { $env:JAVA_HOME        = Join-Path $toolchain "jdk" }
        if (-not $env:ANDROID_HOME)     { $env:ANDROID_HOME     = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
        if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $toolchain "gradle_home" }

        $gradleCandidates = @(
            $env:GSTOP_GRADLE,
            (Join-Path $toolchain "gradle\bin\gradle.bat"),
            (Join-Path $appDir "gradlew.bat")
        )
        $adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    }
    else {
        # Homebrew's openjdk@17 is keg-only, so it is not on PATH and java_home does not see it
        # unless it was symlinked into /Library/Java. Name it directly, then fall back to whatever
        # java_home can find, so a JDK installed some other way still works.
        if (-not $env:JAVA_HOME) {
            $javaCandidates = @(
                "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
                "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
            )
            $env:JAVA_HOME = $javaCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
            if (-not $env:JAVA_HOME) {
                $fromJavaHome = (& /usr/libexec/java_home -v 17 2>$null)
                if ($LASTEXITCODE -eq 0 -and $fromJavaHome) { $env:JAVA_HOME = $fromJavaHome.Trim() }
            }
        }

        # ~/Library/Android/sdk is where Android Studio puts it; the Homebrew cask has its own
        # root. Either is a complete SDK once sdkmanager has installed the platform.
        if (-not $env:ANDROID_HOME) {
            $sdkCandidates = @(
                (Join-Path $HOME "Library/Android/sdk"),
                "/opt/homebrew/share/android-commandlinetools",
                "/usr/local/share/android-commandlinetools"
            )
            $env:ANDROID_HOME = $sdkCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
        }

        # Gradle comes from the wrapper here. Nothing pre-provisions it as it does on Windows.
        $gradleCandidates = @(
            $env:GSTOP_GRADLE,
            (Join-Path $appDir "gradlew")
        )
        $adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools/adb" } else { "adb" }
    }

    if ($env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }

    $gradle = $gradleCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if (-not $gradle) {
        throw "No Gradle found. Expected the wrapper at $(Join-Path $appDir 'gradlew'), or set GSTOP_GRADLE."
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
        throw "No JDK 17 found. Set JAVA_HOME, or on macOS: brew install openjdk@17. See docs/releasing.md."
    }
    if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
        throw "No Android SDK found. Set ANDROID_HOME, or on macOS: brew install --cask android-commandlinetools. See docs/releasing.md."
    }

    return [PSCustomObject]@{
        AppDir    = $appDir
        Gradle    = $gradle
        Adb       = $adb
        OnWindows = $onWindows
    }
}
