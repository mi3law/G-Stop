# Releasing

Obtainium watches this repository's GitHub releases. Publishing a new version means: build a
signed APK, attach it to a tagged release. `release.ps1` at the repository root does both.

```powershell
.\release.ps1 -Version 1.1
.\release.ps1 -Version 1.2 -Notes "Fixes the volume floor under DND."
```

It runs the unit tests first and aborts if any fail (`-SkipTests` overrides, but don't).
`versionCode` is derived from the commit count so it always increases — Android refuses an update
whose `versionCode` is not higher than the installed one.

## Signing — the part that matters

Every release must be signed with the **same key**, or the update will not install over an
existing G-Stop install, and the only way forward is uninstall-and-reinstall, which deletes the
history log and settings.

Two files, both deliberately untracked and both irreplaceable:

| File | What it is |
|---|---|
| `keystore/gstop-release.jks` | The signing key (RSA 4096, valid 30 years) |
| `GStopApp/keystore.properties` | Its passwords and alias |

**Back both up somewhere durable** — a password manager, an encrypted archive, anywhere that
survives this machine. They are the only things in this project that cannot be rebuilt from
source.

`build.gradle.kts` signs release builds only when both are present. Without them the release
build produces an unsigned APK rather than silently signing with a different key.

## Toolchain

The script reads `JAVA_HOME`, `ANDROID_HOME` and `GSTOP_GRADLE` if set, and otherwise falls back
to `%LOCALAPPDATA%\GStopToolchain` and `%LOCALAPPDATA%\Android\Sdk`, then to the Gradle wrapper.
Requires JDK 17 and Android platform 35 / build-tools 35.0.0.

## Doing it by hand

```powershell
cd GStopApp
.\gradlew assembleRelease "-PversionName=1.1" "-PversionCode=7"
gh release create v1.1 app\build\outputs\apk\release\app-release.apk `
    --repo mi3law/G-Stop --title "G-Stop 1.1" --notes "..."
```

Name the asset `G-Stop-<version>.apk` so it keeps matching Obtainium's APK filter.

## Debug builds alongside a release install

The debug build type carries `applicationIdSuffix = ".debug"`, so a debug build installs as
`com.gstop.debug` and can sit next to an installed release copy without a signature clash. It
keeps its own separate database, so experimenting with a debug build will not disturb a real
practice log.
