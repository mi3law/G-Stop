# Installing and maintaining G-Stop with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs Android apps straight from their
source — a GitHub repository, in this case — and then watches for new releases and tells you when
one appears. It is the closest thing to a Play Store update flow for a side-loaded app, without a
store, an account, or anything phoning home about you.

It suits G-Stop exactly: the app is deliberately outside the Play Store, and this repository
publishes each version as a GitHub release with the APK attached — two APKs, in fact: the app,
and the dev build of the same commit for anyone who wants it (step 3).

## 1. Install Obtainium itself

Obtainium is not on the Play Store either. Get it from one of:

- **F-Droid** — add the [Obtainium F-Droid repo](https://apt.izzysoft.de/fdroid/index/apk/dev.imranr.obtainium)
  (IzzyOnDroid), or
- **Directly** — download the latest APK from
  <https://github.com/ImranR98/Obtainium/releases> and open it on the phone.

Android will ask whether your browser or file manager may install unknown apps. Allow it for that
one app; you can revoke it afterwards.

Obtainium can keep itself updated once installed — it adds itself as its own first source.

## 2. Add G-Stop as a source

1. Open Obtainium and tap **Add App**.
2. In **App Source URL**, paste:

   ```
   https://github.com/mi3law/G-Stop
   ```

3. Obtainium recognises it as a GitHub source and fills in the rest. Leave the defaults alone
   with two exceptions worth setting:

   | Setting | Value | Why |
   |---|---|---|
   | **Include prereleases** | off | Releases here are tagged `v1.0`, `v1.1`, … and are not prereleases |
   | **APK filter (regex)** | `^G-Stop-\d[\d.]*\.apk$` | Every release carries a dev APK as well. Anchored and digits-only so it matches `G-Stop-1.4.apk` and never `G-Stop-dev-1.4.apk` |

   The filter matters more than it looks. An unanchored `G-Stop-.*\.apk` matches the dev asset
   too, and Obtainium then asks which APK you meant on every update of the real app.

### Finding the APK filter field

There is more than one regex box on that screen, and only one of them is the right one.

Obtainium renders the GitHub-specific and the general settings as a **single list** under the one
heading *Additional options for GitHub* — there is no separate "Additional options" section to
look for. The source-specific settings come first: *Include prereleases*, *Fallback to older
releases*, *Filter release titles by regular expression*, *Filter release notes by regular
expression*, *Verify the 'latest' tag*, *Sort method*. The general ones follow further down the
same list.

The APK filter is one of the general ones. Scroll past *Sort method*, then past the *Version
detection* switch. The field you want sits **immediately above a switch reading "Invert regular
expression"**, and just before **"Attempt to filter APKs by CPU architecture if possible"**. Use
those two switches as the landmark rather than the field's own label, which is not reliably
translated across builds.

**Do not put it in *Filter release titles by regular expression*.** That field filters whole
releases by their title, and releases here are titled `G-Stop 1.5` — which no APK-filename
pattern will ever match. Every release gets filtered out and Obtainium reports *"Could not find a
suitable release"*, an error that reads as though the repository or the URL is wrong when the
regex is merely in the wrong box.

4. Tap **Add**. Obtainium fetches the release list and shows the latest version.
5. Tap **Install**. Android will ask for permission to install from Obtainium — allow it.

That's the whole setup. G-Stop now appears on Obtainium's home screen with its installed version
and the latest available one.

## 3. The dev app, if you want it

Every release also carries `G-Stop-dev-<version>.apk` — the same commit built as the dev app.
It is a separate app: `com.gstop.debug`, labelled **G-Stop dev**, ringed in orange, bannered
across the top of its screens, and with its own database. Nothing done to it can touch a real
practice log, and it can sit on the phone beside the real app indefinitely.

It exists for a phone that cannot easily be plugged into the development machine. The build is
debuggable and unminified, so a problem that only shows up on that particular phone can be
reproduced on it without a cable.

Skip this if you only want to practise. To add it, repeat step 2 with the same repository URL and
one different setting — the same field, found the same way:

| Setting | Value |
|---|---|
| **APK filter (regex)** | `^G-Stop-dev-` |

Obtainium will not object to the repeated URL: it detects duplicates by the installed package
name, and these two resolve to `com.gstop` and `com.gstop.debug`.

Obtainium will hold two entries pointing at the same repository, updating together off the same
release. Because both APKs are signed with the same key, a dev build pushed over USB with
`dev.ps1` and one installed here replace each other freely.

A dev build is not a safer real app. It is the same scheduling code, but it is the build things
get tried on, so treat its log as scratch.

## 4. Grant G-Stop its permissions

Open G-Stop once after installing. The main screen shows a card for anything the OS is
withholding, each with a button that opens the right system page. Clear all of them:

- **Alarms & reminders** (exact alarms) — the one the practice cannot do without
- **Notifications** — needed to take over a locked screen
- **Battery optimisation exemption** — so the schedule survives deep idle
- **Do Not Disturb access** *(optional)* — lets the volume floor work while DND is on

When they are all clear the screen reads *"Supersession checks passed."*

## 5. Living with it

**Update checks.** Obtainium checks on its own schedule — by default roughly every few hours in
the background. You can change the interval in Obtainium's **Settings → Background update
checker**, or pull down on its home screen to check immediately.

**Updating.** When a new release exists, Obtainium shows the app with an update badge and (if you
allow notifications) posts a notification. Tap the app, then **Install**. Android shows its usual
"update this app?" dialog.

**Your data survives updates.** Settings, sleep windows and the history log live in the app's own
database and are untouched by an update, because every release is signed with the same key. This
is why the key matters — see the warning below.

**Background installs (optional).** Obtainium can install updates silently if you grant it the
`INSTALL_PACKAGES` permission via shizuku or a rooted device. Not necessary, and for this app
probably not desirable: an update replacing the app cancels its armed alarm until the app next
regenerates, so you want to know when it happened.

**Verifying a stop still fires after an update.** After any update, the app regenerates its
schedule the first time it is opened or at the next midnight rollover. If you want to be certain,
open the app once — that alone re-arms the schedule.

## 6. If an update refuses to install

Android will reject an update signed with a different key than the installed version, usually
with *"App not installed"* or a signature-mismatch error.

**This will happen once, going from v1.4 to v1.5.** The signing key changed at v1.5 when the
project gained a second development machine. If you have v1.4 or earlier installed, uninstall it
before installing v1.5 — **which deletes that install's history log and settings**. There is no
way around it and no export; it was judged acceptable because the only install at the time was
days old. From v1.5 onwards updates install over each other normally again.

Otherwise it should never happen: `release.ps1` always signs with the same keystore.

> Back up `keystore/gstop-release-2.jks` and `GStopApp/keystore.properties` somewhere durable.
> Losing them means never being able to update an installed copy of G-Stop again.

A password manager or an encrypted archive is fine. They are the only two files in this project
that cannot be reconstructed.

## 7. Removing it

Uninstall G-Stop like any app, and remove the source from Obtainium with a long press on the app
card. If you added the dev app, it is a second uninstall and a second source to remove. Nothing
is left behind — no account, no server-side state, nothing was ever sent anywhere.
