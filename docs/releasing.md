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

Obtainium watches this repository's GitHub releases. Publishing a new version means: build a
signed APK, attach it to a tagged release. `release.ps1` at the repository root does both.

```powershell
.\release.ps1 -Version 1.1
.\release.ps1 -Version 1.2 -Notes "Fixes the volume floor under DND."
```

It runs the unit tests first and aborts if any fail (`-SkipTests` overrides, but don't).
`versionCode` is derived from the commit count so it always increases — Android refuses an update
whose `versionCode` is not higher than the installed one.

## Trying a version before publishing it

A published release is permanent and everyone's Obtainium sees it, which is a heavy way to find
out that a widget's back gesture is wrong. `-NoPublish` builds and signs exactly what would be
published, then stops before tagging anything:

```powershell
.\release.ps1 -Version 1.3.1 -NoPublish
```

It prints the APK's path and the `adb install -r` line for it. Because it carries the same
signature as every release, it installs **over** the copy Obtainium put on the phone and the
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
