package com.gstop.core

import java.time.ZoneId

/**
 * Where the practice stands with respect to sleep windows at one instant.
 *
 * [changesAtMs] is when the current state ends: the moment the running window releases, or the
 * moment the next one begins. Null means no change is in sight — no windows at all, or windows
 * that cover every hour of the [SleepClock.HORIZON_MS] ahead.
 */
data class SleepStatus(val asleep: Boolean, val changesAtMs: Long?) {
    companion object {
        val AWAKE_INDEFINITELY = SleepStatus(asleep = false, changesAtMs = null)
    }
}

/**
 * Reads the recurring sleep windows as a clock: are we inside one now, and when does that change?
 *
 * Display and bookkeeping only — nothing here places stops. It runs on the same expansion
 * [ActiveTimeline] is built from, so what the main screen says about sleep cannot disagree with
 * the timeline the sampler actually ran on.
 */
object SleepClock {

    /**
     * How far ahead a transition is looked for. A window may recur on a single day of the week,
     * so anything short of a week can miss the next one; the extra day covers a window that starts
     * late on the seventh day and crosses midnight into the eighth.
     */
    const val HORIZON_MS = 8L * 24 * 60 * 60 * 1000

    fun status(windows: List<SleepWindow>, nowMs: Long, zone: ZoneId): SleepStatus {
        val bounds = Interval(nowMs, nowMs + HORIZON_MS)
        val runs = SleepWindowExpander.expand(windows, bounds, zone)
        val next = runs.firstOrNull() ?: return SleepStatus.AWAKE_INDEFINITELY

        // expand() clips to the bounds, so a window already running when the question is asked
        // begins exactly at nowMs; windows that touch or overlap are already coalesced into one
        // run, and its end is therefore the end of the whole stretch of sleep, not of one window.
        if (!next.contains(nowMs)) return SleepStatus(asleep = false, changesAtMs = next.startMs)

        // A run that reaches the horizon has no end this side of it worth claiming.
        val endsAt = if (next.endMs >= bounds.endMs) null else next.endMs
        return SleepStatus(asleep = true, changesAtMs = endsAt)
    }
}
