package com.gstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SleepClockTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun nightly(start: LocalTime, end: LocalTime): SleepWindow =
        SleepWindow(days = SleepWindow.ALL_DAYS, start = start, end = end)

    private val tuesday: LocalDate = LocalDate.of(2026, 3, 10)

    @Test
    fun `no windows means awake with nothing in sight`() {
        val status = SleepClock.status(emptyList(), at(tuesday, LocalTime.of(12, 0)), zone)
        assertFalse(status.asleep)
        assertNull(status.changesAtMs)
    }

    @Test
    fun `inside a nightly window, the end of that window is the answer`() {
        val windows = listOf(nightly(LocalTime.of(23, 0), LocalTime.of(7, 0)))
        val status = SleepClock.status(windows, at(tuesday, LocalTime.of(2, 30)), zone)

        assertTrue(status.asleep)
        assertEquals(at(tuesday, LocalTime.of(7, 0)), status.changesAtMs)
    }

    @Test
    fun `a window entered before midnight ends the following morning`() {
        val windows = listOf(nightly(LocalTime.of(23, 0), LocalTime.of(7, 0)))
        val status = SleepClock.status(windows, at(tuesday, LocalTime.of(23, 30)), zone)

        assertTrue(status.asleep)
        assertEquals(at(tuesday.plusDays(1), LocalTime.of(7, 0)), status.changesAtMs)
    }

    @Test
    fun `awake in the afternoon points at tonight`() {
        val windows = listOf(nightly(LocalTime.of(23, 0), LocalTime.of(7, 0)))
        val status = SleepClock.status(windows, at(tuesday, LocalTime.of(14, 0)), zone)

        assertFalse(status.asleep)
        assertEquals(at(tuesday, LocalTime.of(23, 0)), status.changesAtMs)
    }

    @Test
    fun `the boundaries are half open — asleep at the start, awake at the end`() {
        val windows = listOf(nightly(LocalTime.of(13, 0), LocalTime.of(14, 0)))

        val onTheStart = SleepClock.status(windows, at(tuesday, LocalTime.of(13, 0)), zone)
        assertTrue(onTheStart.asleep)
        assertEquals(at(tuesday, LocalTime.of(14, 0)), onTheStart.changesAtMs)

        val onTheEnd = SleepClock.status(windows, at(tuesday, LocalTime.of(14, 0)), zone)
        assertFalse(onTheEnd.asleep)
        assertEquals(at(tuesday.plusDays(1), LocalTime.of(13, 0)), onTheEnd.changesAtMs)
    }

    @Test
    fun `touching windows report the end of the whole stretch, not of the first one`() {
        // 22:00-23:00 and 23:00-06:00 are one night's sleep, and the answer must say 06:00.
        val windows = listOf(
            nightly(LocalTime.of(22, 0), LocalTime.of(23, 0)),
            nightly(LocalTime.of(23, 0), LocalTime.of(6, 0))
        )
        val status = SleepClock.status(windows, at(tuesday, LocalTime.of(22, 30)), zone)

        assertTrue(status.asleep)
        assertEquals(at(tuesday.plusDays(1), LocalTime.of(6, 0)), status.changesAtMs)
    }

    @Test
    fun `overlapping windows merge before the end is read`() {
        val windows = listOf(
            nightly(LocalTime.of(13, 0), LocalTime.of(15, 0)),
            nightly(LocalTime.of(14, 0), LocalTime.of(16, 0))
        )
        val status = SleepClock.status(windows, at(tuesday, LocalTime.of(14, 30)), zone)

        assertTrue(status.asleep)
        assertEquals(at(tuesday, LocalTime.of(16, 0)), status.changesAtMs)
    }

    @Test
    fun `a disabled window is not sleep`() {
        val windows = listOf(
            SleepWindow(
                days = SleepWindow.ALL_DAYS,
                start = LocalTime.of(23, 0),
                end = LocalTime.of(7, 0),
                enabled = false
            )
        )
        val status = SleepClock.status(windows, at(tuesday, LocalTime.of(2, 30)), zone)

        assertFalse(status.asleep)
        assertNull(status.changesAtMs)
    }

    @Test
    fun `a window on one day of the week is still found from six days away`() {
        val mondayOnly = SleepWindow(
            days = setOf(DayOfWeek.MONDAY),
            start = LocalTime.of(13, 0),
            end = LocalTime.of(14, 0)
        )
        // Tuesday afternoon: the next Monday window is nearly a week off, inside the horizon.
        val status = SleepClock.status(listOf(mondayOnly), at(tuesday, LocalTime.of(15, 0)), zone)

        assertFalse(status.asleep)
        assertEquals(at(tuesday.plusDays(6), LocalTime.of(13, 0)), status.changesAtMs)
    }

    @Test
    fun `a day of the week that is starting counts, so the window that begins tonight is seen`() {
        // 2026-03-10 is a Tuesday; a Wednesday-only window begins the next morning.
        val wednesdayOnly = SleepWindow(
            days = setOf(DayOfWeek.WEDNESDAY),
            start = LocalTime.of(9, 0),
            end = LocalTime.of(10, 0)
        )
        val status = SleepClock.status(listOf(wednesdayOnly), at(tuesday, LocalTime.of(20, 0)), zone)

        assertFalse(status.asleep)
        assertEquals(at(tuesday.plusDays(1), LocalTime.of(9, 0)), status.changesAtMs)
    }

    @Test
    fun `sleep with no end in sight is reported as asleep without a time`() {
        // A window covering the whole day, every day: there is no waking moment to name.
        val allDay = nightly(LocalTime.of(0, 0), LocalTime.of(23, 59))
        val alsoTheGap = nightly(LocalTime.of(23, 59), LocalTime.of(0, 0))
        val status = SleepClock.status(
            listOf(allDay, alsoTheGap),
            at(tuesday, LocalTime.of(12, 0)),
            zone
        )

        assertTrue(status.asleep)
        assertNull(status.changesAtMs)
    }

    @Test
    fun `the clock agrees with the timeline the sampler runs on`() {
        val windows = listOf(nightly(LocalTime.of(23, 0), LocalTime.of(7, 0)))
        var probe = at(tuesday, LocalTime.of(0, 0))
        val end = at(tuesday.plusDays(1), LocalTime.of(0, 0))
        val timeline = ActiveTimeline.build(Interval(probe, end), windows, zone)

        while (probe < end) {
            assertEquals(
                "at $probe",
                timeline.isActiveAt(probe),
                !SleepClock.status(windows, probe, zone).asleep
            )
            probe += 17 * 60 * 1000L
        }
    }
}
