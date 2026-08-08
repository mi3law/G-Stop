# G-Stop

A personal Android app that plays the part of the teacher who calls "Stop!" — an external,
unpredictable command and an external release, for solo practice of Gurdjieff's stop exercise.

Fully on-device. No server, no accounts, no telemetry, no network permission at all.

Design documents live in [../docs](../docs): `stop_app_prd.md` (governing),
`stop_app_duration_frequency.md` (scheduling model), `gurdjieff_stop_exercise.md` (background).

## Installing

For normal use, install from a published release — see [../docs/obtainium.md](../docs/obtainium.md).
The rest of this file is about working on the code.

A local debug build lands at `app/build/outputs/apk/debug/app-debug.apk` and installs as
`com.gstop.debug`, so it can sit alongside an installed release copy with its own separate
database:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### After installing — grant these

The main screen shows a card for anything missing. All of them matter:

1. **Alarms & reminders** (exact alarms) — without it stops are delivered inexactly.
2. **Notifications** — needed for the full-screen intent that takes over a locked screen.
3. **Battery optimisation exemption** — so the schedule survives deep idle.
4. **Do Not Disturb access** (optional) — lets the volume floor work while DND is on.

## Building

Requires JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew assembleDebug
```

Unit tests (the sampler, the active timeline, regeneration):

```bash
./gradlew testDebugUnitTest
```

`local.properties` points at this machine's SDK; regenerate it elsewhere.

## How it is put together

```
core/       pure Kotlin, no Android — the whole scheduling model, fully unit-tested
  Interval, SleepWindow      wall-clock intervals; recurring windows, midnight-crossing, merged
  ActiveTimeline             the day minus sleep windows; maps active-time offsets <-> wall clock
  StopSampler                count-first placement (PRD 6.4): shrink, scatter, sort, re-expand
  ScheduleEngine             draws the remainder of a day; handles regeneration and the day boundary

data/       Room: settings, sleep windows, the drawn schedule, the unbounded history log

schedule/   the delivery layer
  ScheduleManager            owns the drawn schedule and the single armed alarm
  StopAlarmReceiver          the drawn moment arrives
  StopService                foreground service: command sound, hidden wait, release sound
  StopAudio                  USAGE_ALARM playback and the volume floor
  BootReceiver, TimeChangeReceiver, RolloverReceiver

ui/         Compose
  StopActivity               black screen, orange enneagram, green phrase, long-press to suppress
  MainScreen                 active/paused, permission warnings — never the schedule
  SettingsScreen, HistoryScreen
```

### Points worth knowing

- **One alarm at a time.** Only the next stop is armed; the following one is armed when it
  completes. Regeneration discards every pending stop and redraws — nothing is ever carried over.
- **The gap is measured in active time.** Two stops either side of a sleep window can be a minute
  apart on the wall clock and still be an hour apart on the active timeline. That is correct.
- **Regeneration is one code path.** Pause/resume, mid-day settings edits, reboot, timezone
  change and the midnight rollover all call `ScheduleManager.regenerate()`.
- **The count scales on regeneration.** A resume at 21:00 draws proportionally fewer stops than
  one at 08:00, because less active time remains.
- **The schedule is never displayed.** Not on the main screen, not in history, not during a stop.
  History records that a stop happened, never that one is coming.
- **The sound is the stop.** It lives in a foreground service, not the activity, so a blocked
  notification or an awkward lock screen cannot silence a stop — only make it invisible.

See [../docs/manual_test_checklist.md](../docs/manual_test_checklist.md) for the on-device
behaviour that cannot be unit-tested, and a note on the one place where Android itself may reveal
the next stop time. Release process: [../docs/releasing.md](../docs/releasing.md).
