# Stop Exercise App — Duration, Frequency, and Interval Parameters

Context for the development session: historical evidence on how long and how often Gurdjieff's stop exercise ran, translated into concrete parameter recommendations for a personal app. All random generation runs on the **active timeline** (see design note 2); user-definable min–max thresholds bound the random draws.

## What the historical evidence supports

The evidence is anecdotal — roughly a dozen episodes from memoirists (mainly Ouspensky's *In Search of the Miraculous*, plus Fritz Peters, C.S. Nott, de Hartmann) — but it converges on usable ranges.

**Duration.** No stop on record was momentary, and none lasted hours. Three tiers appear in the accounts:

- Ordinary daily-life stops (Essentuki, 1917; the Prieuré, 1922–24): long enough for strained positions to become painful and for people to lose balance and fall — implying roughly **30 seconds to 2–3 minutes**.
- Stage demonstrations (Paris/New York, 1923–24): dancers frozen mid-movement, sometimes toppling and holding where they fell — apparently **~10 seconds to 1–2 minutes**.
- The exceptional outer bound: the Essentuki canal episode, a student held while water rose on him — **several minutes**, and remembered precisely because it was extreme.

Gurdjieff decided the release moment by watching the students' state, not by a timer. An app cannot do that, so a randomized, *hidden* duration is the proxy.

**Frequency.** It varied enormously by period, which is itself informative — there was no canonical rate:

- Intensive periods (Essentuki, 1917): in near-constant use — a stop could come at any moment, during meals, work, or conversation, day or night. Implies **several stops per day**, plausibly 3–6+.
- The Prieuré: irregular and unpredictable, tied to Gurdjieff's presence — some days none, some days several. Implies **0–3 per day** with genuinely empty days.
- Later years: confined mainly to Movements classes; the ambush-in-daily-life form faded.

**Intervals.** By design there was no schedule at all — unpredictability was the entire mechanism. The only structural constraint visible in the accounts is that stops did not cluster back-to-back; each was a discrete event with ordinary life resuming between them.

## Parameters

| Parameter | Suggested default | Allowed user range | Basis |
|---|---|---|---|
| Stop duration | 20 s – 2 min | 10 s – 5 min | Demonstration and daily-life accounts; canal episode as ceiling |
| Stops per day | 2 – 5 | 0 – 8 | Essentuki (high end) vs. Prieuré (low end); per calendar day, placed within that day's active time |
| Minimum gap between stops | 45 min | 20 min – 4 h | No clustering in accounts; measured in **active time** (sleep windows and pauses don't count toward the gap) |
| Sleep windows | One nightly window (e.g., 23:00–07:00) | Multiple recurring windows, each with own days/times; may cross midnight; overlaps merged | Whole day is implicitly active except declared sleep windows |

## Design notes

**1. The active timeline — the core scheduling model.** All random generation runs on a single virtual timeline consisting of active hours only: conceptually, snip out every sleep window (and pause — see note 3), concatenate what remains, and sample on that. Sleep windows are not interruptions the scheduler works around; they simply do not exist on the timeline the generator sees. Consequences, all deliberate:

- Nothing is ever deferred or carried over, so stops cannot pile up or cluster after a window ends.
- The daily stop count is drawn between its min and max and placed within that day's active time, whatever its length. A day with less active time gets the same count in less room; this is accepted in exchange for simplicity (no per-active-hour rate, no "I want my N stops regardless" setting — the per-day count already *is* that).
- The minimum gap is measured in active time, consistently, with no special cases at window boundaries.

**2. Distribution choice matters more than the thresholds.** If gaps are drawn *uniformly* between min and max, the exercise defeats itself: as elapsed active time approaches the max gap, an imminent stop becomes near-certain, and the user's machinery learns to anticipate — exactly the failure mode Gurdjieff identified in self-administered stops. A **(truncated) exponential distribution** for inter-stop gaps on the active timeline is memoryless: the expected wait is the same at every moment, so "it's been a while, one must be due" never becomes true. Memorylessness also means a stop landing shortly after waking is not a predictability leak — the time-to-next-stop distribution looks identical just after a sleep window as at any other point in active time. Likewise, randomize the *daily count* rather than fixing it: if exactly N stops occur per day, the time after the Nth becomes a known-safe zone.

*Implementation as decided in the PRD (`stop_app_prd.md`, §6.4): count-first placement — draw the daily count between min–max, then scatter that many points uniformly on the active timeline with the min-gap enforced (shrink/scatter/sort/re-expand). This is statistically a Poisson process conditioned on its hidden count: no maximum-gap deadline exists, so the anticipation hazard described above does not arise.*

**3. Pauses are ad-hoc sleep windows.** The general pause/resume toggle behaves exactly like a sleep window that the user opens and closes by hand: paused time does not exist on the active timeline. Because pauses are unplanned and open-ended, the implementation is: on resume, **discard any pending schedule and regenerate the sampling** from the current moment (re-draw the remaining daily count and next gap). No frozen clocks, no saved pending stops, no backlog. For an exponential process, regenerating on resume is statistically identical to continuing a suspended clock anyway — the simpler implementation costs nothing. The same regeneration applies whenever the user edits sleep windows or thresholds mid-day.

**4. Never display a countdown or timer during the stop.** Gurdjieff's students did not know when release would come; that uncertainty is part of the work. The stop ends with an external release signal, and the app shows no remaining time, elapsed time, or progress indicator during the freeze. Duration is randomized per-stop within its min–max so the body cannot learn a rhythm.

**5. The command and release must both be external signals.** The historical form is: external command → total freeze in the accidental posture → external release. The user never decides when the stop ends. Two clearly distinct sounds (command vs. release) preserve this structure.

**6. Safety exemption and the pause toggle.** Gurdjieff demanded unconditional obedience, but he was physically present and chose his moments. An app is blind: it will fire while the user is driving, carrying something hot, or crossing a street. Two controls, with distinct semantics:

- **Unsafe-context suppress (per-stop):** the user may disregard an individual stop when freezing is dangerous, without the app treating it as failure. Optional: device motion/driving detection to auto-suppress.
- **General pause/resume toggle:** the global switch described in note 3 — for travel, illness, or anything a recurring sleep window doesn't fit.

One caution on the pause toggle: it is also an anticipation escape hatch — a user who pauses whenever a stop would be inconvenient has quietly re-created the self-administered stop. Consider logging pause/resume events (visible to the user in a history view) so the practitioner can see their own pattern; the app shouldn't police it, but honest self-observation of one's use of the pause button is itself in the spirit of the exercise.

**7. Consider empty days.** The Prieuré pattern included days with no stops at all. Allowing the daily minimum to be 0 (with some probability of a zero-stop day) adds a layer of unpredictability that a guaranteed-daily schedule lacks.

**8. Implementation note.** The active-timeline model fits both scheduling styles. If the app pre-generates a day's schedule (often necessary for mobile OS notification scheduling), it samples directly within that day's active segments; a pause or settings change triggers regeneration on resume. If it schedules on-line, the exponential draw on the active timeline handles everything with no special cases.

**9. Honest caveat.** All numbers above are inferences from a handful of retrospective anecdotes, not from records. The defaults are defensible reconstructions, not doctrine — which is a good argument for making them user-tunable, exactly as planned.
