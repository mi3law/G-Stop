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
- Two record screens, and they are not the same thing. **History** (home screen) is the practice:
  stops that happened. **Logs** (Settings → Logs) is the app: pauses, regenerations, reboots.
  Where a row below says one of them, it means that one.
- Android's status bar shows an alarm-clock icon while a stop is armed, and some lock screens
  show the time of the next alarm. See "Known leak" at the end.

---

## 1. First run and permissions

| # | Step | Expected |
|---|---|---|
| 1.1 | Install and open the app | Main screen shows **Active** (or **Asleep**, inside a sleep window), an orange enneagram, Pause / Settings / History |
| 1.2 | Notification permission prompt appears | Grant it |
| 1.3 | Read the warning cards under the buttons | Any ungranted item shows a card with a button that opens the right system page |
| 1.4 | Tap **Grant** on "Exact alarms are not permitted" (if shown) | System page opens; after granting and returning, the card disappears |
| 1.5 | Tap **Exempt** on "Battery optimisation is active" | System dialog; allow. Card disappears on return |
| 1.6 | Tap **Allow** on "The stop screen cannot come to the front" | System page opens; allow "display over other apps". Card disappears on return |
| 1.6a | Tap **Allow** on "Full-screen alarms are not permitted" (Android 14+ only) | System page opens; allow. Card disappears on return |
| 1.6b | Tap **Allow** on "The camera is not permitted" | System dialog; allow. Card disappears on return |
| 1.7 | With everything granted | Line reads "Supersession checks passed." |
| 1.8 | Open **Settings → Logs** | Shows "Schedule regenerated" and any settings changes. No stop times are listed for stops that have not happened |
| 1.9 | Open **History** | Empty: "No stops yet." |
| 1.10 | Upgrade over an existing install rather than a clean one | Settings, sleep windows and the whole log survive the database migration |

**Fail condition for the whole app:** if the exact-alarm card cannot be cleared, stops will be
delivered inexactly. Fix this before trusting anything else.

**Second fail condition:** if "display over other apps" cannot be granted, a stop that arrives
while you are using the phone will show as a notification banner rather than taking the screen.
The sound still plays and the stop is still recorded, but the screen no longer supersedes what
you were doing, which is most of the point.

## 2. The stop event itself

| # | Step | Expected |
|---|---|---|
| 2.1 | Wait for a stop with the phone unlocked and awake | Command sound plays; screen turns pure black with the orange enneagram and the green phrase |
| 2.1a | **Be actively typing in another app** (Keep, a browser, a chat) when a stop arrives | The stop screen **takes the whole screen**. If you get a notification banner instead, "display over other apps" is not granted — this is the failure this row exists to catch |
| 2.1b | Revoke "display over other apps" and repeat 2.1a | Banner only, and the main screen shows the warning card. Confirms the card is telling the truth |
| 2.2 | During the stop, look at the screen | **No timer, no countdown, no progress bar, no buttons.** If you see any of these, the build is wrong |
| 2.3 | Do nothing | After the hidden duration, the release sound plays — clearly different from the command — and the screen closes on its own |
| 2.4 | Open **History** | One entry at the right time, reading "Stop" |
| 2.5 | Press Back during a stop | Nothing happens; the screen stays |
| 2.6 | Press Home during a stop | The screen may go to the background, but the release sound still plays on time |

## 2b. The three photographs

| # | Step | Expected |
|---|---|---|
| 2b.1 | Wait for a stop with the camera permitted | **Nothing visible changes**: no preview, no shutter, no flash. The screen stays pure black with the enneagram |
| 2b.2 | After the stop, open the observation | Three thumbnails — Beginning, Middle, End — all present |
| 2b.3 | Tap a thumbnail | It opens full size, the right way up (not rotated 90°) |
| 2b.4 | Suppress a stop within the first second or two | At most the Beginning frame exists; the others show "—" |
| 2b.5 | Deny the camera permission, wait for a stop | The stop is completely unaffected. All three slots show "—" |
| 2b.6 | Settings → Observations → turn **Photograph each stop** off, wait for a stop | No camera indicator appears at all during the stop; all three slots show "—" |
| 2b.7 | Settings → Observations | "Photos and voice notes: …" shows a plausible size, growing with each stop |
| 2b.8 | Tap **Delete all media**, confirm | Size returns to none; History rows keep their text but their photographs are gone |

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
| 5.1 | During a stop, **long-press anywhere** on the black screen | Sound stops at once; screen closes; no release sound; **no** observation screen and no observe notice |
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
| 6.5 | Toggle a window **off** with its switch | It stops applying immediately; Logs record a settings change and a regeneration |
| 6.6 | Delete all sleep windows | Whole day becomes active |

### 6b. Saying so on the home screen

| # | Step | Expected |
|---|---|---|
| 6b.1 | Open the app **during** a sleep window | Reads "**Asleep**", and underneath "A sleep window is running — no stops until 07:00" |
| 6b.2 | Check the hour named | It is the end of the *whole* stretch of sleep, not of the first window — two windows that meet at 23:00 report the later end |
| 6b.3 | Leave the app open across the end of the window | Within a minute it changes to "Active" by itself. No reopening, no pull to refresh |
| 6b.4 | Open the app inside a window that ends tomorrow (e.g. at 23:30 of a 23:00 → 07:00 window) | Reads "07:00 tomorrow" |
| 6b.5 | Stay on the screen across midnight in that window | The wording drops "tomorrow" within a minute of midnight |
| 6b.6 | Delete every sleep window while the screen is open | Returns to "Active" |
| 6b.7 | **Pause** during a sleep window | Reads "**Paused**", not "Asleep" — pausing outranks sleeping |
| 6b.8 | Add a window covering right now, from Settings | Going back to the main screen, it already reads "Asleep" |

## 7. Pause / resume and regeneration

| # | Step | Expected |
|---|---|---|
| 7.1 | Tap **Pause** | Screen reads "Paused"; Logs record "Paused" |
| 7.2 | Wait through a period when a stop would plausibly have come | No stops while paused |
| 7.3 | Tap **Resume** | Logs record "Resumed" *and* "Schedule regenerated" |
| 7.4 | Resume shortly after a stop has fired | The next stop does not come sooner than your minimum gap of active time after that stop |
| 7.5 | Resume late in the evening | Fewer stops are drawn than a morning resume would give — the count scales with remaining active time |
| 7.6 | Change any setting mid-day | Logs record "Settings changed" followed by "Schedule regenerated" |

## 7b. The home-screen widget

One cell, no words: the enneagram in orange while the practice is running, dark grey while it is
paused. **One tap pauses or resumes; two quick taps open the app.** Long-press belongs to the
launcher and always will — that gesture picks the widget up to move it, and no app can intercept
it, which is why the app-opening gesture is the double tap instead.

Because the two have to be told apart, a single tap only acts once the double-tap window has
passed: expect the colour to change a beat after your finger lifts. That pause is deliberate, not
lag.

| # | Step | Expected |
|---|---|---|
| 7b.1 | Long-press the home screen → Widgets → **G-Stop** | One 1×1 widget offered, previewing the orange enneagram on black |
| 7b.2 | Place it beside the G-Stop app icon | It looks like the icon. **Judge the size here** — the disc should sit near the size of the icons around it, not swamp them. If it is off, the padding in `widget_icon.xml` is the one number to change |
| 7b.3 | Check the shape against your launcher's icons | It is a circle. If your launcher masks icons into squircles this will read slightly differently — say so and it becomes a rounded square |
| 7b.4 | Tap it once | After a beat the enneagram goes dark grey. Open the app: it reads "Paused" |
| 7b.5 | Tap it once again, then open **Logs** | Orange again; "Resumed" and "Schedule regenerated" are recorded, exactly as a tap in the app records them |
| 7b.6 | **Double-tap it** | The app opens. **The state does not change** — it is the same colour when you come back, and the Logs show no pause or resume from that gesture. This is the row that matters most; everything below assumes it works |
| 7b.7 | Watch the home screen closely through a single tap | No flash, no dim, no window sliding in. The tap goes to an invisible activity, and it should stay invisible |
| 7b.8 | Double-tap, but leave a deliberate gap between the taps | Two separate single taps: it pauses, then resumes. The gap that separates them is the system's own double-tap timeout |
| 7b.9 | Double-tap and find the app did **not** open, or the state flipped | The second tap missed the window. Say so — the window is one constant in `WidgetTapActivity` and can be widened |
| 7b.10 | Pause **in the app**, then look at the home screen | The widget is already grey. It is not waiting for anything |
| 7b.11 | Tap it by accident (this will happen) | The change is visible at a glance, and the Logs say when. That is the only guard there is against a stray tap, so decide now whether you want it on your main home screen |
| 7b.12 | Wait for a sleep window to begin with the widget placed | It stays **orange**. Sleeping is not pausing — the practice is still running, and the app screen is where the hour it releases is named |
| 7b.13 | Double-tap while an observation window is open | The app comes forward on the observation, and nothing you have typed is lost |
| 7b.14 | **Reboot** the phone, do not open the app | The widget shows the right state once boot completes |
| 7b.15 | Reinstall the APK over the existing one | The widget survives and still works — no need to place it again |
| 7b.16 | Check the recents screen after tapping the widget | Only G-Stop is there, if you opened it. The activity that handles the tap never appears |
| 7b.17 | Look at the widget for anything it should not say | Nothing about the schedule can appear here, because nothing appears here but the mark |

## 8. Survival

| # | Step | Expected |
|---|---|---|
| 8.1 | **Reboot** the phone. Do not open the app | Logs (checked later) show "Device restarted" and "Schedule regenerated" |
| 8.2 | After a reboot, leave the phone alone | Stops still arrive that day without the app ever being opened |
| 8.3 | Change the phone's **timezone** in system settings | Logs record "Clock or timezone changed" and a regeneration |
| 8.4 | Change the timezone by several hours and check that stops fall in daytime hours of the *new* zone | The day and its sleep windows are re-read in the new zone |
| 8.5 | Leave the app closed overnight and check the Logs the next day | A regeneration is logged shortly after local midnight (day rollover) |
| 8.6 | Reinstall the APK over the existing one | Settings, logs, History and every observation survive; a regeneration is logged |

## 8b. The observation window

| # | Step | Expected |
|---|---|---|
| 8b.1 | Wait for a stop with the phone **unlocked and in hand** | The moment the release sound plays, the Observation screen opens by itself |
| 8b.2 | Read the line under the header | A countdown from about 5:00, ticking down |
| 8b.3 | Type into **Movement**, leave the other two blank, tap **Done** | Accepted; blanks are fine |
| 8b.4 | Reopen that stop from History | What you typed is there |
| 8b.5 | Wait for a stop with the phone **locked in a pocket**, then unlock | No screen was forced open; a quiet "Observe that stop" notice is waiting. Tapping it opens the window |
| 8b.6 | Leave that notice alone for five minutes | It disappears on its own |
| 8b.7 | Open an observation and let the countdown reach 0:00 while a field has focus | Nothing is snatched away; the fields go read-only and the line reads "The window closed." |
| 8b.8 | Try to type after the window has closed | Impossible — from the notice, from History, from anywhere |
| 8b.9 | Tap **Record**, allow the microphone, speak, tap **Stop recording** | A Play button appears; playback sounds right |
| 8b.10 | Tap **Play**, then **Stop** | Playback starts and stops |
| 8b.11 | Record, then tap **Delete** | The note is gone; the stop is no longer "noted" if nothing else was written |
| 8b.12 | Record and leave the screen mid-sentence without stopping | What was said up to that point is kept, not discarded |
| 8b.13 | Record for two minutes without stopping | It stops itself at 2:00 |
| 8b.14 | Deny the microphone permission | Everything else on the screen still works |

## 8c. History

| # | Step | Expected |
|---|---|---|
| 8c.1 | Open **History** from the home screen | Stops grouped under day headings, newest day first |
| 8c.2 | A stop you let run and did not note | Reads "**Stop**" |
| 8c.3 | A stop you wrote something about | Reads "**Stop, noted**", with the first line of what you wrote underneath |
| 8c.4 | A stop you suppressed | Reads "**Stop suppressed**" — even if you somehow noted it, this label wins |
| 8c.5 | Tap a "Stop, noted" row | Its observation opens: your three fields, the voice note, and the three photographs |
| 8c.6 | Tap Back | Returns to History, not to the home screen |
| 8c.7 | From the home screen, Settings → Logs → Back | Returns to Settings, not to the home screen |
| 8c.8 | Confirm the home screen buttons | **Settings** and **History**. Logs is *not* on the home screen |
| 8c.9 | A stop that was missed (Doze, phone off) | Appears in **Logs** as "Stop missed"; does **not** appear in History |

## 9. Things that must NOT happen

| # | Check | Expected |
|---|---|---|
| 9.1 | Anywhere in the app — main, settings, History, Logs — **and on the widget** | The time of a **future** stop is never shown. The end of a sleep window is not a stop time and is fine |
| 9.2 | Anywhere in the app, or on the widget | The number of stops drawn for today is never shown |
| 9.3 | During a stop | No elapsed or remaining time is shown. The observation countdown is *after* the stop and is fine |
| 9.4 | Two stops in quick succession | Never closer than the minimum gap in active time |
| 9.5 | Network | Airplane mode changes nothing; the app makes no connections |
| 9.6 | Open the phone's **Gallery / Photos** app | No stop photographs appear anywhere in it |
| 9.7 | History | No streak, no total, no daily count, no comparison between days |

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
