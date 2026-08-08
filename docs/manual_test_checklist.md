# G-Stop — Manual On-Device Test Checklist

Everything in this list depends on real OS behaviour — silent mode, Do Not Disturb, Doze, the
lock screen, reboot — and none of it can be exercised by a unit test. Work through it on the
actual phone after sideloading.

Two settings make testing bearable. In **Settings**, temporarily set:

- **Stops per day** to `8 – 8`
- **Minimum gap** to `20 min` (the floor)
- **Stop duration** to `10s – 20s`

and remove or shorten the sleep window so the whole day is active. A regeneration happens on
every settings change, so a stop should arrive within roughly the next hour. Restore your real
parameters when you are done.

Two facts worth knowing before you start:

- The app never tells you when the next stop is due, by design. To *verify* a specific stop you
  have to wait for one. Budget time accordingly, or use the log afterwards.
- Android's status bar shows an alarm-clock icon while a stop is armed, and some lock screens
  show the time of the next alarm. See "Known leak" at the end.

---

## 1. First run and permissions

| # | Step | Expected |
|---|---|---|
| 1.1 | Install and open the app | Main screen shows **Active**, an orange enneagram, Pause / Settings / History |
| 1.2 | Notification permission prompt appears | Grant it |
| 1.3 | Read the warning cards under the buttons | Any ungranted item shows a card with a button that opens the right system page |
| 1.4 | Tap **Grant** on "Exact alarms are not permitted" (if shown) | System page opens; after granting and returning, the card disappears |
| 1.5 | Tap **Exempt** on "Battery optimisation is active" | System dialog; allow. Card disappears on return |
| 1.6 | With everything granted | Line reads "Supersession checks passed." |
| 1.7 | Open **History** | Shows "Schedule regenerated" and any settings changes. No stop times are listed for stops that have not happened |

**Fail condition for the whole app:** if the exact-alarm card cannot be cleared, stops will be
delivered inexactly. Fix this before trusting anything else.

## 2. The stop event itself

| # | Step | Expected |
|---|---|---|
| 2.1 | Wait for a stop with the phone unlocked and awake | Command sound plays; screen turns pure black with the orange enneagram and the green phrase |
| 2.2 | During the stop, look at the screen | **No timer, no countdown, no progress bar, no buttons.** If you see any of these, the build is wrong |
| 2.3 | Do nothing | After the hidden duration, the release sound plays — clearly different from the command — and the screen closes on its own |
| 2.4 | Open **History** | One "Stop" entry at the right time |
| 2.5 | Press Back during a stop | Nothing happens; the screen stays |
| 2.6 | Press Home during a stop | The screen may go to the background, but the release sound still plays on time |

## 3. Supersession — the non-negotiable part

| # | Step | Expected |
|---|---|---|
| 3.1 | Set the ringer to **silent** (not just vibrate). Wait for a stop | Command sound plays at full alarm volume |
| 3.2 | Set the ringer to **vibrate only**. Wait for a stop | Command sound plays |
| 3.3 | Turn on **Do Not Disturb** (default profile, alarms allowed). Wait for a stop | Command sound plays; stop screen appears |
| 3.4 | Set DND to **Total silence** / alarms *not* allowed | Main screen shows the "Do Not Disturb is set to total silence" warning card |
| 3.5 | With alarm volume slid to **zero**, wait for a stop | Sound still plays — the volume floor raises the alarm stream to the configured percentage |
| 3.6 | Immediately after that stop, check the alarm volume | Back at zero. The floor is applied for the stop and restored afterwards |
| 3.7 | Set **Volume floor** to 100% in Settings, zero the alarm stream, wait for a stop | Plays at maximum |
| 3.8 | Grant DND access when prompted, repeat 3.5 with DND on | Volume floor still applies (without DND access Android blocks the volume change; the alarm stream still plays at its existing level) |

## 4. Locked screen and idle device

| # | Step | Expected |
|---|---|---|
| 4.1 | Lock the phone, screen off. Wait for a stop | Screen **turns on by itself**, shows the stop screen over the lock screen, sound plays |
| 4.2 | Same, but leave the phone untouched face-down for 30+ minutes first (device enters Doze) | Stop still arrives on time. This is what `setAlarmClock` buys |
| 4.3 | Lock screen with a PIN/pattern set | Stop screen shows over the keyguard without unlocking the phone |
| 4.4 | While the stop screen is on the lock screen, wait for the release | Release sound plays, screen returns to the lock screen |
| 4.5 | Put the phone in **Airplane mode**, wait for a stop | Works identically — the app is fully on-device |

## 5. The suppress gesture

| # | Step | Expected |
|---|---|---|
| 5.1 | During a stop, **long-press anywhere** on the black screen | Sound stops at once; screen closes; no release sound |
| 5.2 | Open **History** | Entry reads "Stop suppressed", not a failure or a miss |
| 5.3 | Tap (short) during a stop | Nothing happens — only a long press suppresses |
| 5.4 | After suppressing, wait | Later stops still arrive; the day's schedule is not cancelled |

## 6. Sleep windows

| # | Step | Expected |
|---|---|---|
| 6.1 | Set a sleep window covering the next few hours. Wait | No stops during that period |
| 6.2 | Set a window that crosses midnight (e.g. 23:00 → 07:00) | No stops overnight; stops resume after 07:00 |
| 6.3 | Add two overlapping windows (13:00–15:00 and 14:00–16:00) | The union 13:00–16:00 is quiet; nothing odd at the seams |
| 6.4 | Set a window whose days exclude today | It has no effect today |
| 6.5 | Toggle a window **off** with its switch | It stops applying immediately; History logs a settings change and a regeneration |
| 6.6 | Delete all sleep windows | Whole day becomes active |

## 7. Pause / resume and regeneration

| # | Step | Expected |
|---|---|---|
| 7.1 | Tap **Pause** | Screen reads "Paused"; History logs "Paused" |
| 7.2 | Wait through a period when a stop would plausibly have come | No stops while paused |
| 7.3 | Tap **Resume** | History logs "Resumed" *and* "Schedule regenerated" |
| 7.4 | Resume shortly after a stop has fired | The next stop does not come sooner than your minimum gap of active time after that stop |
| 7.5 | Resume late in the evening | Fewer stops are drawn than a morning resume would give — the count scales with remaining active time |
| 7.6 | Change any setting mid-day | History logs "Settings changed" followed by "Schedule regenerated" |

## 8. Survival

| # | Step | Expected |
|---|---|---|
| 8.1 | **Reboot** the phone. Do not open the app | History (checked later) shows "Device restarted" and "Schedule regenerated" |
| 8.2 | After a reboot, leave the phone alone | Stops still arrive that day without the app ever being opened |
| 8.3 | Change the phone's **timezone** in system settings | History logs "Clock or timezone changed" and a regeneration |
| 8.4 | Change the timezone by several hours and check that stops fall in daytime hours of the *new* zone | The day and its sleep windows are re-read in the new zone |
| 8.5 | Leave the app closed overnight and check History the next day | A regeneration is logged shortly after local midnight (day rollover) |
| 8.6 | Reinstall the APK over the existing one | Settings and history survive; a regeneration is logged |

## 9. Things that must NOT happen

| # | Check | Expected |
|---|---|---|
| 9.1 | Anywhere in the app — main, settings, history | The time of a **future** stop is never shown |
| 9.2 | Anywhere in the app | The number of stops drawn for today is never shown |
| 9.3 | During a stop | No elapsed or remaining time is shown |
| 9.4 | Two stops in quick succession | Never closer than the minimum gap in active time |
| 9.5 | Network | Airplane mode changes nothing; the app makes no connections |

---

## Known leak: the system's next-alarm display

The PRD requires `AlarmManager.setAlarmClock()`, which is what makes a stop survive Doze without
Play-Store-only permissions. The trade-off is that Android treats it as a user-visible alarm
clock: an alarm icon appears in the status bar, and on many devices the lock screen or clock app
will show the **time of the next alarm** — which is the time of the next stop.

The app itself never displays the schedule, but the OS may. Two things worth knowing:

- Only the *next* stop is ever armed, so at most one future time is exposed at a time.
- If this bothers you in practice, the fix is a one-line change in
  `ScheduleManager.armExact()` — swapping `setAlarmClock` for `setExactAndAllowWhileIdle`
  removes the icon and the next-alarm entry entirely. That call is still exact and still fires in
  Doze, but it is rate-limited to roughly one firing per nine minutes per app and is a weaker
  guarantee than the alarm-clock path the PRD specifies. I have left the PRD behaviour in place;
  say the word and I will switch it or make it a setting.

Worth checking on your specific phone (OEM lock screens vary a lot): after a regeneration, lock
the phone and see whether a next-alarm time is displayed. If it is not, there is nothing to fix.
