# Working on it, and releasing it

## The loop

One developer, one phone, no CI. The flow is therefore as short as it can be while still meaning
something:

1. Work on a branch — `git worktree` makes one free, and the repository already uses them.
2. `.\dev.ps1` — builds the dev app from that branch and installs it on the phone.
3. Try it. This is the review: for anything with a screen, using it catches what reading a diff
   does not.
4. Merge to `main` (fast-forward) and push, once it has run on the phone.
5. Release when a batch is worth publishing — see below.

**The order is the point.** `release.ps1` publishes from `main`'s tip, so `main` should mean "this
has been on the phone", not "this compiled". Nothing installs from a branch and nothing installs
from `main` either — Obtainium reads published releases — which is exactly what leaves `main` free
to carry that meaning.

A release publishes the dev app too (below), so the dev build *does* reach a phone without a
cable — but only ever as a release, never from a branch. Step 2 stays the only way a branch gets
onto a phone, and it is still the review.

No pull requests, deliberately. With no CI and no second reviewer a PR runs no checks and reviews
nothing; it is a reading surface, and for an app whose failures are things like a back gesture or
a widget that looks like the wrong app, the phone is the better one. Two PRs exist in the history
from earlier feature batches; nothing was lost by stopping.

No long-lived `dev` or `testing` branch either. It would need merging and rebasing to keep up, and
it would put daylight between what is on the phone and what is on `main` — the same shape of
confusion as two apps that look alike.

### Telling builds apart

The dev app is `com.gstop.debug`, named **G-Stop dev**, with an orange ring around its icon and
its widget and a banner across the top of its screens. It keeps its own database, so nothing done
to it touches a real practice log. Reinstalling over it keeps that database; only uninstalling
clears it.

Both apps carry their commit: the main screen's footer reads `1.3.1 · a1b2c3d`, tappable to open
that commit on GitHub, and says `modified` when the build carried uncommitted changes. A dev
build's version has the sha in it too (`1.3.1-ga1b2c3d`), so Android's own app list answers the
question without the app being opened.

## Releasing

Obtainium watches this repository's GitHub releases. Publishing a new version means: build the
signed APKs, attach them to a tagged release. `release.ps1` at the repository root does both.

```powershell
.\release.ps1 -Version 1.1
.\release.ps1 -Version 1.2 -Notes "Fixes the volume floor under DND."
```

It runs the unit tests first and aborts if any fail (`-SkipTests` overrides, but don't).
`versionCode` is derived from the commit count so it always increases — Android refuses an update
whose `versionCode` is not higher than the installed one.

### The two assets

Each release carries both builds of the same commit:

| Asset | Package | What it is |
|---|---|---|
| `G-Stop-<version>.apk` | `com.gstop` | The app |
| `G-Stop-dev-<version>.apk` | `com.gstop.debug` | The dev app — orange, bannered, own database |

The dev asset is there so a phone that is awkward to wire up can still carry a debuggable build,
tracked by its own Obtainium source with the filter `^G-Stop-dev-`. The release source's filter is
anchored (`^G-Stop-\d[\d.]*\.apk$`) so it never matches the dev asset — see
[obtainium.md](obtainium.md).

Note what this does and does not change. The dev app on a phone now follows *releases* by default,
which makes it a debug-flavoured twin of the release rather than whatever branch was last flashed
over USB. `dev.ps1` still overrides it at any moment, and that is still the loop; the published
dev APK is for the phone the cable does not reach.

## Trying a version before publishing it

A published release is permanent and everyone's Obtainium sees it, which is a heavy way to find
out that a widget's back gesture is wrong. `-NoPublish` builds and signs exactly what would be
published, then stops before tagging anything:

```powershell
.\release.ps1 -Version 1.3.1 -NoPublish
```

It prints both APKs' paths and the `adb install` line for each. Because they carry the same
signature as every release, each installs **over** the copy Obtainium put on the phone and the
practice history survives — it is the real thing, just not announced.

Two things to know:

- Publish afterwards from the same commit or a later one. `versionCode` follows the commit count,
  so publishing from an *earlier* commit than the build already on the phone is a downgrade and
  Android will refuse it.
- Obtainium keeps its own record of what it installed. A hand-installed build can leave it briefly
  out of step about the version on the phone; the next real release resets that.

For iterating on something visible — a widget, a screen — prefer the debug build below. It sits
alongside the real app with its own database, so nothing you do to it can touch a real practice
log. Version numbering: patch (`1.3.1`) for fixes, minor (`1.4`) for anything new.

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

`build.gradle.kts` signs release and debug builds only when both are present. Without them the
release build produces an unsigned APK, and a debug build falls back to Gradle's own
`CN=Android Debug` key, rather than either silently signing with something publishable.

### Checking what signed an APK

Worth doing on any release cut from a machine for the first time. Both assets must report the
same certificate digest:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs G-Stop-1.5.apk
```

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs G-Stop-dev-1.5.apk
```

On macOS `apksigner` needs a JDK on `PATH`, which the Homebrew `openjdk@17` is not by default:
prefix the command with `PATH="$JAVA_HOME/bin:$PATH"`. A digest of `CN=Android Debug` means the
keystore was not picked up and neither APK should be published.

## Toolchain

Both scripts run on Windows and macOS. `toolchain.ps1` resolves where the tools are and is the
only place that knows about either platform; `JAVA_HOME`, `ANDROID_HOME` and `GSTOP_GRADLE` always
win if set. Requires JDK 17 and Android platform 35 / build-tools 35.0.0 either way.

| | Windows | macOS |
|---|---|---|
| JDK | `%LOCALAPPDATA%\GStopToolchain\jdk` | Homebrew `openjdk@17`, else `java_home -v 17` |
| SDK | `%LOCALAPPDATA%\Android\Sdk` | `~/Library/Android/sdk`, else the Homebrew cask root |
| Gradle | `%LOCALAPPDATA%\GStopToolchain\gradle`, else the wrapper | the wrapper |

### Setting up a Mac

macOS needs PowerShell, since the scripts are PowerShell:

```bash
brew install powershell openjdk@17
brew install --cask android-commandlinetools
```

Then accept the SDK licences and install the platform — Google requires the licence agreement
before any package will install:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Run the scripts with `pwsh ./dev.ps1`, `pwsh ./release.ps1 -Version 1.5`.

**The keystore does not come with the repository.** `keystore/gstop-release.jks` and
`GStopApp/keystore.properties` are untracked, and deliberately: they are the two files that cannot
be rebuilt. A second machine needs them copied across by hand, through something that does not
leave the passwords lying around — an encrypted archive or a password manager, not email. Until
they are there, that machine can build and test but both scripts will refuse to produce anything
installable, which is the intended failure.

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

It is signed with the **release key**, not Gradle's debug key. That is what lets a build from
`dev.ps1` and one Obtainium installed from a release replace each other on the phone; with two
different keys the second install of either kind fails with a signature mismatch and the only way
out is uninstalling the dev app. Both scripts therefore refuse to build without
`keystore.properties` rather than falling back to the debug key, which would produce an APK that
looks fine and cannot be installed over. The two apps are still impossible to confuse — different
`applicationId`, different name, different icon — and nothing published as the dev app can pass as
the real one, because Android keys the identity off the package, not the signature.

`dev.ps1` passes the same commit-count `versionCode` as a release, so a branch build is not a
downgrade of the last published dev APK, and installs with `-d` so that trying something by hand
always wins over what a release left there.

Debuggability is unaffected by any of this: it comes from the build type, so `adb`, logcat and
attaching a debugger work exactly as before.
