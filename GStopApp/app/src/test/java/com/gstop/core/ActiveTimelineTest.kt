package com.gstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ActiveTimelineTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    private fun dayBounds(date: LocalDate): Interval = Interval(
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    )

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `no sleep windows means the whole day is active`() {
        val date = LocalDate.of(2026, 3, 10)
        val timeline = ActiveTimeline.build(dayBounds(date), emptyList(), zone)
        assertEquals(TimeUnit.HOURS.toMillis(24), timeline.totalActiveMs)
        assertEquals(1, timeline.segments.size)
    }

    @Test
    fun `nightly window crossing midnight removes time at both ends of the day`() {
        val date = LocalDate.of(2026, 3, 10)
        val window = SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(23, 0), end = LocalTime.of(7, 0))
        val timeline = ActiveTimeline.build(dayBounds(date), listOf(window), zone)

        // 07:00 -> 23:00 is the only active stretch: 16 hours.
        assertEquals(TimeUnit.HOURS.toMillis(16), timeline.totalActiveMs)
        assertEquals(1, timeline.segments.size)
        assertEquals(at(date, LocalTime.of(7, 0)), timeline.segments.first().startMs)
        assertEquals(at(date, LocalTime.of(23, 0)), timeline.segments.first().endMs)

        assertFalse(timeline.isActiveAt(at(date, LocalTime.of(3, 0))))
        assertTrue(timeline.isActiveAt(at(date, LocalTime.of(12, 0))))
        assertFalse(timeline.isActiveAt(at(date, LocalTime.of(23, 30))))
    }

    @Test
    fun `overlapping windows merge instead of double counting`() {
        val date = LocalDate.of(2026, 3, 10)
        val a = SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(13, 0), end = LocalTime.of(15, 0))
        val b = SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(14, 0), end = LocalTime.of(16, 0))
        val timeline = ActiveTimeline.build(dayBounds(date), listOf(a, b), zone)

        // 13:00-16:00 removed once, not 13-15 and 14-16 separately.
        assertEquals(TimeUnit.HOURS.toMillis(21), timeline.totalActiveMs)
        assertEquals(2, timeline.segments.size)
        assertFalse(timeline.isActiveAt(at(date, LocalTime.of(15, 30))))
        assertTrue(timeline.isActiveAt(at(date, LocalTime.of(16, 30))))
    }

    @Test
    fun `two windows meeting exactly at a boundary coalesce into one`() {
        val date = LocalDate.of(2026, 3, 10)
        val a = SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(10, 0), end = LocalTime.of(12, 0))
        val b = SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(12, 0), end = LocalTime.of(14, 0))
        val timeline = ActiveTimeline.build(dayBounds(date), listOf(a, b), zone)

        assertEquals(TimeUnit.HOURS.toMillis(20), timeline.totalActiveMs)
        assertEquals(2, timeline.segments.size)
    }

    @Test
    fun `day of week selection is honoured, including for a window that starts the night before`() {
        // 2026-03-10 is a Tuesday. A Monday-only 23:00-07:00 window eats Tuesday morning.
        val tuesday = LocalDate.of(2026, 3, 10)
        assertEquals(DayOfWeek.TUESDAY, tuesday.dayOfWeek)

        val mondayNight = SleepWindow(
            days = setOf(DayOfWeek.MONDAY),
            start = LocalTime.of(23, 0),
            end = LocalTime.of(7, 0)
        )
        val timeline = ActiveTimeline.build(dayBounds(tuesday), listOf(mondayNight), zone)

        // Only 00:00-07:00 of Tuesday is removed; Tuesday night is not.
        assertEquals(TimeUnit.HOURS.toMillis(17), timeline.totalActiveMs)
        assertFalse(timeline.isActiveAt(at(tuesday, LocalTime.of(3, 0))))
        assertTrue(timeline.isActiveAt(at(tuesday, LocalTime.of(23, 30))))
    }

    @Test
    fun `disabled windows contribute nothing`() {
        val date = LocalDate.of(2026, 3, 10)
        val window = SleepWindow(
            days = SleepWindow.ALL_DAYS,
            start = LocalTime.of(23, 0),
            end = LocalTime.of(7, 0),
            enabled = false
        )
        assertEquals(
            TimeUnit.HOURS.toMillis(24),
            ActiveTimeline.build(dayBounds(date), listOf(window), zone).totalActiveMs
        )
    }

    @Test
    fun `offset mapping skips inactive stretches`() {
        val date = LocalDate.of(2026, 3, 10)
        val window = SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(12, 0), end = LocalTime.of(14, 0))
        val timeline = ActiveTimeline.build(dayBounds(date), listOf(window), zone)

        // 11 active hours in, we are at 11:00.
        assertEquals(
            at(date, LocalTime.of(11, 0)),
            timeline.toWallClock(TimeUnit.HOURS.toMillis(11))
        )
        // 12 active hours in, we have jumped the window and are at 14:00.
        assertEquals(
            at(date, LocalTime.of(14, 0)),
            timeline.toWallClock(TimeUnit.HOURS.toMillis(12))
        )
        // Wall-clock time inside the window contributes no active time.
        assertEquals(
            TimeUnit.HOURS.toMillis(12),
            timeline.toActiveOffset(at(date, LocalTime.of(13, 30)))
        )
    }

    @Test
    fun `round trip between wall clock and active offset is stable`() {
        val date = LocalDate.of(2026, 3, 10)
        val windows = listOf(
            SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(23, 0), end = LocalTime.of(7, 0)),
            SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(13, 0), end = LocalTime.of(14, 30))
        )
        val timeline = ActiveTimeline.build(dayBounds(date), windows, zone)

        var offset = 0L
        while (offset < timeline.totalActiveMs) {
            val wall = timeline.toWallClock(offset)
            assertEquals(offset, timeline.toActiveOffset(wall))
            offset += TimeUnit.MINUTES.toMillis(37)
        }
    }

    @Test
    fun `dropFirstActive removes active time rather than wall clock time`() {
        val date = LocalDate.of(2026, 3, 10)
        val window = SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(12, 0), end = LocalTime.of(14, 0))
        val timeline = ActiveTimeline.build(dayBounds(date), listOf(window), zone)

        val trimmed = timeline.dropFirstActive(TimeUnit.HOURS.toMillis(12))
        assertEquals(timeline.totalActiveMs - TimeUnit.HOURS.toMillis(12), trimmed.totalActiveMs)
        assertEquals(at(date, LocalTime.of(14, 0)), trimmed.segments.first().startMs)
    }

    @Test
    fun `dropping more than the whole timeline yields an empty timeline`() {
        val date = LocalDate.of(2026, 3, 10)
        val timeline = ActiveTimeline.build(dayBounds(date), emptyList(), zone)
        assertTrue(timeline.dropFirstActive(TimeUnit.DAYS.toMillis(2)).isEmpty)
    }

    @Test
    fun `a day fully covered by sleep windows has no active time`() {
        val date = LocalDate.of(2026, 3, 10)
        val windows = listOf(
            SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(0, 0), end = LocalTime.of(12, 0)),
            SleepWindow(days = SleepWindow.ALL_DAYS, start = LocalTime.of(12, 0), end = LocalTime.of(23, 59))
        )
        val timeline = ActiveTimeline.build(dayBounds(date), windows, zone)
        assertEquals(TimeUnit.MINUTES.toMillis(1), timeline.totalActiveMs)
    }

    @Test
    fun `spring forward day has 23 real hours of active time`() {
        // British Summer Time begins 2026-03-29 at 01:00.
        val date = LocalDate.of(2026, 3, 29)
        val timeline = ActiveTimeline.build(dayBounds(date), emptyList(), zone)
        assertEquals(TimeUnit.HOURS.toMillis(23), timeline.totalActiveMs)
    }

    @Test
    fun `autumn back day has 25 real hours of active time`() {
        // BST ends 2026-10-25 at 02:00.
        val date = LocalDate.of(2026, 10, 25)
        val timeline = ActiveTimeline.build(dayBounds(date), emptyList(), zone)
        assertEquals(TimeUnit.HOURS.toMillis(25), timeline.totalActiveMs)
    }
}
