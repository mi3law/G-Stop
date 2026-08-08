package com.gstop.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A recurring window of inactive time.
 *
 * [days] are the days on which the window *starts*. If [end] is not after [start] the window
 * crosses midnight and ends on the following calendar day. Overlapping windows are merged by
 * [SleepWindowExpander]; the whole day is implicitly active outside them.
 *
 * A window whose start equals its end is degenerate and contributes no inactive time.
 */
data class SleepWindow(
    val id: Long = 0L,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val enabled: Boolean = true
) {
    val crossesMidnight: Boolean get() = !end.isAfter(start)

    companion object {
        val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.values().toSet()

        /** The shipped default: one nightly window, 23:00–07:00, every day. */
        fun default(): SleepWindow =
            SleepWindow(days = ALL_DAYS, start = LocalTime.of(23, 0), end = LocalTime.of(7, 0))
    }
}

object SleepWindowExpander {

    /**
     * Expands recurring [windows] into concrete wall-clock intervals covering [bounds],
     * merged so that overlaps disappear.
     */
    fun expand(windows: List<SleepWindow>, bounds: Interval, zone: ZoneId): List<Interval> {
        if (bounds.durationMs <= 0) return emptyList()
        val active = windows.filter { it.enabled && it.days.isNotEmpty() && it.start != it.end }
        if (active.isEmpty()) return emptyList()

        // Instant.atZone(...).toLocalDate() rather than LocalDate.ofInstant: the latter is a
        // Java 9 addition and is not on every Android version this app supports.
        val firstDate = java.time.Instant.ofEpochMilli(bounds.startMs).atZone(zone).toLocalDate().minusDays(1)
        val lastDate = java.time.Instant.ofEpochMilli(bounds.endMs).atZone(zone).toLocalDate().plusDays(1)

        val raw = ArrayList<Interval>()
        var date = firstDate
        while (!date.isAfter(lastDate)) {
            for (w in active) {
                if (date.dayOfWeek !in w.days) continue
                val startMs = date.atTime(w.start).atZone(zone).toInstant().toEpochMilli()
                val endDate = if (w.crossesMidnight) date.plusDays(1) else date
                val endMs = endDate.atTime(w.end).atZone(zone).toInstant().toEpochMilli()
                if (endMs > startMs) raw.add(Interval(startMs, endMs))
            }
            date = date.plusDays(1)
        }
        return Interval.merge(raw.mapNotNull { it.clipTo(bounds) })
    }
}
