# G-Stop

An Android app that plays the part of the teacher who calls **"Stop!"** — an external,
unpredictable command and an external release, for solo practice of Gurdjieff's stop exercise.

At an unpredictable moment a sound commands the stop. The screen goes black and shows an
enneagram with the phrase اُذْكُرِ اللَّهَ. You freeze exactly as you are — posture, expression,
gaze, thought — and hold it. There is no timer and no progress indicator, because you are not
meant to know when the release is coming. After a hidden interval a second, distinct sound
releases you.

Fully on-device. No server, no account, no telemetry — the app does not declare the `INTERNET`
permission at all.

## Install

The app is distributed as a side-loaded APK, not through the Play Store. Nothing in Developer
options is involved: installing needs only the per-app *install unknown apps* permission Android
asks for at the moment you install, and you can revoke it afterwards.

**With [Obtainium](https://github.com/ImranR98/Obtainium)** (recommended — gives you update
notifications): install Obtainium, then tap
[**Add G-Stop to Obtainium**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.gstop%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fmi3law%2FG-Stop%22%2C%22author%22%3A%22mi3law%22%2C%22name%22%3A%22G-Stop%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5EG-Stop-%5C%5C%5C%5Cd%5B%5C%5C%5C%5Cd.%5D%2A%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D)
on the phone. The link carries the whole configuration — source URL, prereleases off, APK filter —
so it is one confirmation and one **Install**. [docs/obtainium.md](docs/obtainium.md) has the
manual walkthrough, the dev app, and how updates behave.

**By hand:** download the APK from [Releases](../../releases) and open it on the phone. Each
release also carries `G-Stop-dev-<version>.apk` — the same commit built as the dev app, a separate
install with its own database, for debugging on a phone without a cable. Ignore it unless you want
that.

**With adb** — the one route that does need Developer options, for USB debugging:

```bash
adb install -r G-Stop-<version>.apk
```

### Grant these after installing

The main screen shows a card for anything missing. All of them matter:

| Permission | Why |
|---|---|
| Alarms & reminders (exact alarms) | Without it stops are delivered inexactly and may be delayed |
| Notifications | Needed for the full-screen intent that takes over a locked screen |
| Battery optimisation exemption | So the schedule survives deep idle |
| Do Not Disturb access *(optional)* | Lets the volume floor work while DND is on |

## What it does

**Scheduling.** All random generation runs on the *active timeline* — the day with sleep windows
and pauses snipped out and the remainder concatenated. The day's stop count is drawn between your
min and max, then that many points are placed uniformly on the active timeline with a minimum gap
enforced (shrink, scatter, sort, re-expand). Statistically this is a Poisson process conditioned
on its hidden count: there is no maximum gap, so "it's been a while, one must be due" never
becomes true.

**Regeneration.** Pause/resume, mid-day settings edits, reboot, timezone change and the midnight
rollover all discard every pending stop and redraw on the remaining active timeline, with the
count scaled to the active time that is left. Nothing is ever deferred or carried over.

**Supersession.** A stop that can be accidentally muted is not a stop. Stops are delivered with
`AlarmManager.setAlarmClock()` (exact, wakes the device from Doze), played on the alarm stream
with `USAGE_ALARM` (unaffected by ringer silent mode, passes DND under the alarms exception),
shown over the lock screen with a full-screen intent, and backed by a configurable volume floor
that raises a zeroed alarm stream for the stop and restores it afterwards.

**The schedule is never displayed.** Not on the main screen, not in the logs, not during a stop.
The log records that a stop happened, never that one is coming.

**Controls.** A global pause/resume that behaves as an ad-hoc sleep window, and a per-stop
suppress — a long press during a stop — for moments when freezing would be dangerous. Suppression
is recorded as "suppressed", never as failure.

**Logs.** An unbounded local log of stops, suppressions and pause/resume toggles. Pause events
are logged deliberately: the pause button is the escape hatch back into self-administered
stopping, and seeing your own use of it is part of the practice. Nothing is pruned automatically;
the Logs screen has a Clear button that keeps the ten most recent events.

**Backups.** Still no `INTERNET` permission: in Settings you pick a folder once in the system
picker — one that lives in Google Drive, say — and about once a day the app writes a snapshot zip
there (every table as JSON; photos and voice notes only if you opt in). The Drive app does the
carrying. The newest seven snapshots are kept, "Back up now" forces one, and "Restore from a
backup" replaces everything on the phone with a snapshot's contents. A separate "Export CSV"
button shares the stop history as a spreadsheet that opens straight into Google Sheets.

## Parameters

| Parameter | Default | Range |
|---|---|---|
| Stop duration | 20 s – 2 min | 10 s – 5 min |
| Stops per day | 2 – 5 | 0 – 8 (a minimum of 0 allows genuinely empty days) |
| Minimum gap | 45 min | 20 min – 4 h, measured in active time |
| Sleep windows | 23:00 – 07:00 daily | Multiple windows, per-day, may cross midnight, overlaps merge |
| Volume floor | 60% | 0 – 100% of the device's maximum alarm volume |

## Building

Requires JDK 17 and the Android SDK (platform 35, build-tools 35.0.0), on Windows or macOS.
[docs/releasing.md](docs/releasing.md) has the setup for each.

```bash
cd GStopApp && ./gradlew assembleDebug
```

Unit tests — the sampler, the active timeline, regeneration, the backup codec (78 tests):

```bash
cd GStopApp && ./gradlew testDebugUnitTest
```

Release builds need `GStopApp/keystore.properties` and the keystore it points at; both are
deliberately untracked. See [docs/releasing.md](docs/releasing.md).

## Layout

```
GStopApp/app/src/main/java/com/gstop/
  core/       pure Kotlin, no Android — the whole scheduling model, fully unit-tested
  data/       Room: settings, sleep windows, the drawn schedule, the event log
  backup/     snapshots into a user-picked folder (SAF), restore, and the CSV export
  schedule/   alarms, the foreground service that owns a stop, audio and the volume floor
  ui/         Compose: the stop screen, main, settings, logs
docs/         design documents, the release process, the Obtainium walkthrough
```

## Design documents

The app was specified before it was written; the reasoning is worth more than the code.

- [docs/stop_app_prd.md](docs/stop_app_prd.md) — the governing PRD
- [docs/stop_app_duration_frequency.md](docs/stop_app_duration_frequency.md) — the scheduling
  model, and what the historical evidence actually supports
- [docs/gurdjieff_stop_exercise.md](docs/gurdjieff_stop_exercise.md) — what the exercise is
- [docs/manual_test_checklist.md](docs/manual_test_checklist.md) — the on-device behaviour no
  unit test can reach

## A note on the historical parameters

The duration and frequency defaults are inferences from roughly a dozen anecdotes in Ouspensky,
Fritz Peters, C.S. Nott and de Hartmann — not from records. They are defensible reconstructions,
not doctrine, which is exactly why every one of them is user-tunable.
