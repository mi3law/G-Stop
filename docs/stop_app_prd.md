# PRD — Stop Exercise App ("G-Stop")

Product requirements for a personal app that facilitates Gurdjieff's stop exercise for a solo practitioner, outside any group or teacher. This document governs the development session. Companion documents in this folder:

- `gurdjieff_stop_exercise.md` — what the exercise is and why its structure matters.
- `stop_app_duration_frequency.md` — historical evidence, parameter defaults, and the agreed scheduling model (the **active timeline**). The scheduling design there is normative; this PRD references it rather than restating it.

## 1. Purpose and product principles

The app substitutes, as far as an algorithm can, for the one thing a solo practitioner loses: an external, unpredictable authority who commands the stop and the release. Every design decision follows from three principles:

1. **Externality.** The command and the release both come from the app, never from the user's judgment in the moment. The user configures thresholds in advance and then submits to the output.
2. **Unpredictability.** No countdowns, no visible schedules, no learnable rhythms. Memoryless gap distributions; randomized daily counts; randomized per-stop durations.
3. **Supersession.** The stop must sound through silent mode, Do Not Disturb, and low-power states. A stop that can be accidentally muted is not a stop. This requirement drives the platform decision (§3).

Non-goals: social features, accounts, cloud sync, gamification, streaks, or any motivational apparatus. This is a tool for one person's practice.

## 2. Core functionality

**Scheduling (normative spec: `stop_app_duration_frequency.md`).** In brief: all random generation runs on the active timeline (the day minus sleep windows and pauses). Daily stop count N drawn between user min–max (min may be 0 → empty days); stops placed by **count-first placement** (see §6, decision 4), with the minimum gap measured in active time; per-stop duration drawn uniformly between user min–max. Regeneration of the remaining day's sampling occurs on: pause→resume, any mid-day edit to sleep windows or thresholds, reboot, and timezone change.

**User-definable parameters** (defaults and ranges in the companion doc):

- Stop duration min–max.
- Stops per day min–max.
- Minimum gap between stops.
- Sleep windows: multiple recurring windows, each with days-of-week and start/end times; may cross midnight; overlapping windows merge. Whole day is implicitly active outside them.
- Command and release sounds (two clearly distinct sounds; sensible defaults shipped).

**The stop event.** At the drawn moment: command sound plays at supersession priority (§3), screen lights if locked, and the stop screen is shown (see §6, decision 1): a pure black screen bearing an enneagram in bright orange stroke, with the Arabic phrase اُذْكُرِ اللَّهَ centered inside it in green — no timer, no progress indicator, no interactive elements beyond the suppress gesture. After the hidden drawn duration, the release sound plays and the event ends. The user does nothing on the device during the stop; the phone is the bell, not the interface.

**Controls.**

- **Pause/resume (global):** behaves as an ad-hoc sleep window; paused time does not exist on the active timeline; resume triggers regeneration. One tap from the app's main screen; also exposed as a home-screen widget/quick-settings tile if cheap to build.
- **Unsafe-context suppress (per-stop):** during a stop, a single deliberate gesture (e.g., long-press) silences that stop without ending the schedule. Recorded as "suppressed," never as failure.

**The record of a stop.** A stop photographs itself: three frames from the front camera, at the beginning, the middle and the end of the drawn duration. Nothing about this is visible during the stop — no preview, no shutter sound of the app's own making, no change to the black screen — and the frames never leave the app's private storage. The camera is optional at runtime: a refusal, a phone with no front camera, or a stop screen the OS declined to show costs the record, never the stop.

**Observation (post-stop, five minutes).** When a stop releases, a five-minute window opens in which the practitioner may note the stop: three short free-text fields — **Movement**, **Feeling**, **Thinking** — and a voice note, all optional. The observation page also shows the three photographs. The window is entered directly from the stop screen when the phone is unlocked, and from a quiet notice that expires with the window otherwise. When the window closes the observation becomes read-only: a stop is observed then, or not at all. A suppressed stop opens no window.

**Three kinds of stop.** The practice recognises exactly three, and History speaks in them: **Stop suppressed**, **Stop**, and **Stop, noted** — the last being a stop with at least one field or a voice note filled in.

**History.** Every stop that actually happened, newest first, in those three kinds. Opening one shows its three photographs and whatever was noted about it. A record, not a score: no streaks, no totals, no comparison between days — and still no schedule, since a stop appears only once it has occurred.

**Logs (lightweight).** A local log of app events — stops fired / suppressed / noted, pause and resume toggles, regenerations, reboots, permission warnings. Purpose is honest self-observation — particularly of pause-button usage, which is the built-in escape hatch back into self-administered stopping. No analytics, no scores. Reached from Settings; History is the screen the practice itself uses.

## 3. Platform and infrastructure

**Decision: side-loaded native Android app, fully on-device. No server.**

Rationale: Android treats alarm-clock behavior as a first-class, sanctioned capability, and the app is architecturally an alarm clock with a random schedule. Supersession of silent mode and DND requires no hacks and no network. Distribution outside the Play Store removes the one real constraint (Play policy limits on exact-alarm permissions); the user installs the APK directly. Fully on-device also satisfies the privacy preference: no accounts, no telemetry, nothing leaves the phone.

**Technical foundation (Android):**

- **Exact scheduling:** `AlarmManager.setAlarmClock()` for each upcoming stop — exact delivery, wakes the device from Doze. Declare `SCHEDULE_EXACT_ALARM` (user-granted) and/or `USE_EXACT_ALARM` (auto-granted for alarm-class apps; the associated restrictions are Google Play policy, inapplicable to a side-loaded APK). Check `canScheduleExactAlarms()` and degrade loudly (warn the user) if revoked.
- **Audio supersession:** play command/release sounds with `AudioAttributes.USAGE_ALARM` on the alarm stream — unaffected by ringer silent mode; permitted through DND under the default "alarms" exception.
- **DND hardening (optional but recommended):** request DND-policy access (`ACCESS_NOTIFICATION_POLICY`) so the app can verify that the user's DND configuration permits alarms and warn (or temporarily override) if not.
- **Volume floor:** on stop delivery, programmatically raise the alarm-stream volume to a configurable minimum if the user has zeroed it, restoring it afterward. A silent alarm stream must not silently defeat the practice.
- **Locked-screen delivery:** full-screen intent (`USE_FULL_SCREEN_INTENT`) + turn-screen-on flags so a stop lights and occupies the lock screen. On Android 14+ this is additionally gated by a per-app toggle (`canUseFullScreenIntent()`); check it and degrade loudly.
- **In-use delivery:** a full-screen intent is honoured *only* when the screen is off or the device is locked — "the system UI may choose to display a heads-up notification, instead of launching this intent, while the user is using the device." A stop that arrives as a banner over the app the practitioner is typing in is not a stop; it is a suggestion, and it fails the externality principle as surely as a muted one does. The stop screen must therefore also be started directly by the service, which is a background activity start and requires the user-granted `SYSTEM_ALERT_WINDOW` ("display over other apps"). Treat it as a supersession requirement on a par with exact alarms, not as an optional nicety.
- **Survival:** `RECEIVE_BOOT_COMPLETED` receiver to regenerate and reschedule after reboot; listen for timezone/clock changes and regenerate; prompt the user once to exempt the app from battery optimization.
- **Persistence:** all state (settings, current day's drawn schedule, log, observations) in local storage (Room/DataStore). The drawn schedule must never be displayed.
- **Stop photographs:** the camera is bound to the *stop screen*, never to the foreground service. Camera access is a while-in-use permission, and a visible activity is the one place the app reliably holds it; a service started from an alarm is not. Photographs and voice notes are written to app-private storage only — never MediaStore, never a shared directory, never a backup.

**Stack:** native Kotlin recommended for direct access to the alarm/audio APIs. A cross-platform framework is acceptable only if the above APIs are reached natively via plugins without compromise; do not trade supersession reliability for framework convenience.

**Explicitly rejected for the trigger role:** web app / PWA (web push cannot bypass silent or DND and background JS cannot reliably wake a locked phone — acceptable later as a settings surface only, though none is needed for MVP).

## 4. Fallback delivery path (documented, out of scope unless needed)

If the practitioner must be reachable on an iPhone, no clean fully-on-device option exists for a personal app: Apple's Critical Alerts entitlement (the sanctioned silent/DND bypass) is granted by manual review essentially only to health/safety/security apps, and personal signing adds re-install friction; the background-audio workaround (holding a playback audio session, which ignores the silent switch) is functional but fragile and battery-hungry.

The viable iPhone route is a **minimal server that places a phone call** at each drawn time (cron + a telephony API such as Twilio; cents per call). Calls pierce DND via the contacts exception, and iOS **Emergency Bypass** on the saved contact makes the call ring through the silent switch as well; on Android the contact exception pierces DND but not full silent mode, so this route is strictly weaker there — the caveat is inherent to the mechanism and requires one-time manual setup on the phone. Design note if ever built: the ring itself is the command and the server's hang-up is the release, so ring length = stop duration, capped by carrier voicemail timeout (~30–60 s). This path contradicts the fully-on-device preference and is retained only because it is the sole mechanism that needs no installed app.

## 5. MVP scope

MVP: the scheduler on the active timeline, all four parameter groups, sleep windows, pause/resume, unsafe suppress, command/release sounds with supersession (alarm stream + exact alarms + boot/timezone regeneration), and the local log. That is the complete practice loop.

Shipped after MVP: the three stop photographs, the five-minute observation window, and the History screen.

Deferred: quick-settings tile/widget, motion/driving auto-suppress, DND-policy override, custom sound import, any settings web surface, export of observations.

## 6. Resolved decisions

1. **Stop screen:** pure black background; an enneagram rendered in bright orange stroke; the Arabic phrase اُذْكُرِ اللَّهَ centered inside the enneagram in green. No timer, no progress indicator, no interactive elements beyond the unsafe-suppress gesture.
2. **Volume floor:** user-configurable. May later move to an advanced-settings page — both to group similar expert settings and to keep escape hatches out of casual reach.
3. **Logs:** unbounded local log, never trimmed automatically. Distinct from History, which is the record of stops themselves.
4. **Sampling scheme: count-first placement.** Draw the day's count N uniformly between the user's min–max, then place N points uniformly at random on the active timeline with the minimum gap enforced by the standard transformation: shrink the timeline by the total reserved gap time ((N−1) × min-gap), scatter N points uniformly, sort them, then re-expand by adding back the cumulative gap offsets. If the active day is too short to fit N stops at the minimum gap, clip N to the maximum feasible. Regeneration (pause→resume, mid-day settings edits, reboot, timezone change) re-runs the same procedure on the remaining active timeline, with the count redrawn in proportion to the remaining active time. No rejection loops; the drawn schedule is never displayed. This refines the exponential-gap phrasing in `stop_app_duration_frequency.md`: the anticipation hazard that motivated exponential gaps was a *maximum* gap acting as a learnable deadline, and count-first placement has no maximum gap — it is statistically a Poisson process conditioned on its (hidden) count, so unpredictability is fully preserved.
5. **Observation window: five minutes, then closed.** Long enough to write three words and say a sentence, short enough that the observation is of the stop rather than a reconstruction of it. The fields go read-only rather than the screen closing itself, so a half-typed line is never snatched away — but nothing new may be added. Editing an old observation from History is deliberately impossible.
6. **A suppressed stop is never "noted."** Suppression ends the episode; the three labels stay mutually exclusive, and History never has to arbitrate between them.
7. **Photographs are taken by the stop screen, not the service.** See §3. The consequence is honest and worth stating: if the full-screen intent does not show — notifications blocked, an OEM lock screen that refuses it — the stop still sounds and still ends, and simply goes unphotographed.
