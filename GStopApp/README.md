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
3. **Display over other apps** — needed for a stop to take the screen while you are *using* the
   phone. Android honours a full-screen intent only when the screen is off or the device is
   locked; without this grant a stop arriving mid-use shows as a notification banner instead.
4. **Full-screen alarms** (Android 14+) — a separate per-app toggle for the same intent.
5. **Battery optimisation exemption** — so the schedule survives deep idle.
6. **Do Not Disturb access** (optional) — lets the volume floor work while DND is on.
7. **Camera** — for the three photographs a stop takes of itself. Refusing it costs the
   photographs and nothing else; stop photos can also be switched off in Settings.
8. **Microphone** — asked for in context, the first time a voice note is recorded.

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
  SleepClock                 whether a sleep window is running now, and when it releases

data/       Room: settings, sleep windows, the drawn schedule, the log, observations

media/      the record of a stop
  StopMedia                  where a stop's photos and voice note live (app-private only)
  SelfieCapture              CameraX, front camera, no preview — bound to the stop screen
  VoiceNote                  MediaRecorder / MediaPlayer for the spoken half of an observation
  PhotoDecoding              downscale + apply the EXIF rotation CameraX leaves behind

schedule/   the delivery layer
  ScheduleManager            owns the drawn schedule and the single armed alarm
  StopAlarmReceiver          the drawn moment arrives
  StopService                foreground service: command sound, hidden wait, release sound
  StopAudio                  USAGE_ALARM playback and the volume floor
  StopSession                the in-process handle on the running stop, and the photo requests
  BootReceiver, TimeChangeReceiver, RolloverReceiver

ui/         Compose
  StopActivity               black screen, orange enneagram, green phrase, long-press to suppress
  ObservationActivity        the five minutes after a stop
  MainScreen                 active / asleep / paused, permission warnings — never the schedule
  SleepDisplay               how sleep is said, shared by the main screen and the widget
  HistoryScreen              every stop that happened, in the three kinds
  ObservationScreen          three fields, a voice note and three photographs
  SettingsScreen, LogsScreen

widget/     the home screen
  PracticeWidget             one cell: the enneagram, lit or out. Tapping it is the pause
  WidgetTapActivity          tells one tap from two: pause, or open the app
```

### Points worth knowing

- **One alarm at a time.** Only the next stop is armed; the following one is armed when it
  completes. Regeneration discards every pending stop and redraws — nothing is ever carried over.
- **The gap is measured in active time.** Two stops either side of a sleep window can be a minute
  apart on the wall clock and still be an hour apart on the active timeline. That is correct.
- **Regeneration is one code path.** Pause/resume, mid-day settings edits, reboot, timezone
  change and the midnight rollover all call `ScheduleManager.regenerate()`. A resume is the one
  caller that can decline it — see below.
- **A pause sets the draw aside; it does not tear it up.** Pending stops go to `SUSPENDED`, which
  nothing arms, nothing can fire, and `markMissed` cannot stamp as stops that got away. A resume
  hands them back untouched *if no active time passed while they were away* — otherwise it throws
  them out and redraws. The test is `activeMsBetween(pausedAt, now) == 0`, not "was it night":
  same answer for a pause inside a sleep window, and the right one everywhere else. Without it,
  toggling the pause overnight was a costless re-roll of the day's stops — same count, same
  distribution, different moments — and nothing in the practice should be re-rollable for free.
- **The count scales on regeneration.** A resume at 21:00 draws proportionally fewer stops than
  one at 08:00, because less active time remains.
- **The schedule is never displayed.** Not on the main screen, not in History, not in the Logs,
  not during a stop, and not on the home-screen widget. A stop appears in History only once it has
  occurred.
- **Sleep is the one piece of the future that is shown.** While a sleep window is running the main
  screen reads *Asleep* and names the hour it releases. That is not a leak: a sleep window is the
  user's own standing instruction, so the hour was always theirs. Which is why `SleepClock` is a
  *reading* of the same expansion `ActiveTimeline` is built from, not a second model that could
  drift from it.
- **Pause has one implementation.** `ScheduleManager.togglePaused()` — the main screen's button and
  the widget both call it, under the same lock as regeneration, so two taps in flight cannot land
  the practice somewhere neither of them meant.
- **The widget shows one bit, and it is not sleep.** Lit means the practice is running, and during
  a sleep window it is — nothing was paused and stops resume of their own accord. Keeping sleep off
  the home screen is what lets the widget be redrawn by events alone (`updatePeriodMillis` is 0,
  every regeneration redraws it) instead of needing an alarm at every sleep boundary.
- **A widget gets one hook, and two gestures are built on it.** The launcher keeps long-press for
  picking widgets up, and `RemoteViews` offers nothing but a click — so one tap and two are told
  apart by `WidgetTapActivity`, which every tap goes to. One pauses, two open the app.
- **The single tap waits out the double-tap window before acting**, so the colour changes a beat
  after the finger lifts. It cannot act at once and undo itself on the second tap: pausing
  regenerates the remainder of the day, and a double tap would quietly redraw the schedule on its
  way to opening the app.
- **The tap lands on an activity, not a broadcast.** A receiver calling `startActivity` is a
  background activity start, which Android blocks unless "display over other apps" happens to be
  granted — the app would open on some phones and silently not on others. An activity started by
  the launcher is in the foreground, and an activity starting another activity is never in
  question. It draws nothing and finishes inside `onCreate`.
- **The sound is the stop.** It lives in a foreground service, not the activity, so a blocked
  notification or an awkward lock screen cannot silence a stop — only make it invisible.
- **The screen is raised twice over.** Android honours a full-screen intent only when the screen
  is off or the device is locked; while you are using the phone it downgrades it to a banner. So
  the service also calls `startActivity` directly. That is a background activity start, which
  needs the user-granted "display over other apps" — hence the warning card that treats it as
  seriously as exact alarms.
- **The camera is bound to the stop screen, not the service.** Camera access is a while-in-use
  permission and a visible activity is the one place the app reliably holds it. The consequence is
  the mirror image of the point above: an invisible stop still sounds and still ends, and simply
  goes unphotographed.
- **The stop screen never learns the duration.** That is why the service *requests* each of the
  three photographs rather than the screen timing them: the service knows the beginning, the
  middle and the end; the screen only knows that one of them has arrived.
- **History and Logs are different records.** History is the practice: stops that happened, in
  three kinds — *Stop suppressed*, *Stop*, *Stop, noted*. Logs is the app: pauses, regenerations,
  reboots, permission warnings. History is on the home screen; Logs lives in Settings.
- **Observations close.** Five minutes after a stop releases the fields go read-only. An old
  observation cannot be edited from History, only read.
- **Nothing leaves the phone.** Photographs and voice notes are written to app-private storage,
  never MediaStore, and the app holds no network permission at all. Settings shows what they take
  up and deletes them in one deliberate act.

See [../docs/manual_test_checklist.md](../docs/manual_test_checklist.md) for the on-device
behaviour that cannot be unit-tested, and a note on the one place where Android itself may reveal
the next stop time. Release process: [../docs/releasing.md](../docs/releasing.md).
