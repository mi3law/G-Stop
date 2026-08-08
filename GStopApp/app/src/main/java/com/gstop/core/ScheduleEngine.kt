package com.gstop.core

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Turns settings + the current moment into the remainder of today's drawn schedule.
 *
 * Everything here is pure: no Android, no clock reads, no storage. The caller supplies [nowMs],
 * the zone, and the last stop that already happened today, which makes the whole regeneration
 * story (pause/resume, settings edits, reboot, timezone change) unit-testable.
 */
object ScheduleEngine {

    /**
     * Stops are never drawn in the first half-minute after a generation. Regeneration runs on a
     * broadcast; without the grace period a stop could be drawn at an instant that has already
     * passed by the time the alarm is set.
     */
    const val GENERATION_GRACE_MS = 30_000L

    /**
     * Draws the schedule for the remainder of the calendar day containing [nowMs].
     *
     * @param lastStopAtMs the most recent stop that already fired or was suppressed today, if any.
     *   The minimum gap is honoured across the regeneration boundary: the first new stop cannot
     *   land closer than [SamplingParams.minGapMs] of *active* time to it.
     */
    fun generateForRemainderOfDay(
        nowMs: Long,
        zone: ZoneId,
        windows: List<SleepWindow>,
        params: SamplingParams,
        lastStopAtMs: Long? = null,
        random: Random = Random.Default
    ): List<DrawnStop> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val from = maxOf(nowMs + GENERATION_GRACE_MS, dayStart)
        if (from >= dayEnd) return emptyList()

        val fullDay = ActiveTimeline.build(Interval(dayStart, dayEnd), windows, zone)
        if (fullDay.isEmpty) return emptyList()

        var remaining = ActiveTimeline.build(Interval(from, dayEnd), windows, zone)
        if (remaining.isEmpty) return emptyList()

        if (lastStopAtMs != null && lastStopAtMs < from) {
            val elapsedActive = activeMsBetween(lastStopAtMs, from, windows, zone)
            val deficit = params.minGapMs - elapsedActive
            if (deficit > 0) {
                remaining = remaining.dropFirstActive(deficit)
                if (remaining.isEmpty) return emptyList()
            }
        }

        val countScale = remaining.totalActiveMs.toDouble() / fullDay.totalActiveMs.toDouble()
        return StopSampler.sample(remaining, params, countScale.coerceIn(0.0, 1.0), random)
    }

    /** Active time (sleep windows excluded) between two wall-clock instants. */
    fun activeMsBetween(fromMs: Long, toMs: Long, windows: List<SleepWindow>, zone: ZoneId): Long {
        if (toMs <= fromMs) return 0L
        return ActiveTimeline.build(Interval(fromMs, toMs), windows, zone).totalActiveMs
    }

    /** Start of the next calendar day, when the next day's schedule must be drawn. */
    fun nextDayBoundaryMs(nowMs: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The local date key ("2026-08-09") a schedule belongs to. */
    fun localDateKey(nowMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
}
