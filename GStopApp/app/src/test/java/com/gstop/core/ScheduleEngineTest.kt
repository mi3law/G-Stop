package com.gstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Regeneration behaviour: pause/resume, mid-day settings edits, reboot and timezone change all
 * funnel into the same call, so these tests cover all four.
 */
class ScheduleEngineTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val date: LocalDate = LocalDate.of(2026, 3, 10)

    private val nightly = SleepWindow(
        days = SleepWindow.ALL_DAYS,
        start = LocalTime.of(23, 0),
        end = LocalTime.of(7, 0)
    )

    private fun at(time: LocalTime): Long = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun params(
        countMin: Int = 2,
        countMax: Int = 5,
        gapMinutes: Long = 45
    ) = SamplingParams(
        countMin = countMin,
        countMax = countMax,
        minGapMs = TimeUnit.MINUTES.toMillis(gapMinutes),
        durationMinMs = TimeUnit.SECONDS.toMillis(20),
        durationMaxMs = TimeUnit.SECONDS.toMillis(120)
    )

    private fun generate(
        now: LocalTime,
        params: SamplingParams = params(),
        windows: List<SleepWindow> = listOf(nightly),
        lastStopAt: Long? = null,
        seed: Int = 1
    ) = ScheduleEngine.generateForRemainderOfDay(
        nowMs = at(now),
        zone = zone,
        windows = windows,
        params = params,
        lastStopAtMs = lastStopAt,
        random = Random(seed)
    )

    @Test
    fun `stops are only ever drawn in the future and inside active time`() {
        for (seed in 0 until 200) {
            val now = at(LocalTime.of(9, 0))
            val stops = generate(LocalTime.of(9, 0), seed = seed)
            val remaining = ActiveTimeline.build(
                Interval(now, at(LocalTime.of(23, 59)) + TimeUnit.MINUTES.toMillis(1)),
                listOf(nightly),
                zone
            )
            for (stop in stops) {
                assertTrue("seed $seed: stop in the past", stop.triggerAtMs > now)
                assertTrue("seed $seed: stop outside active time", remaining.isActiveAt(stop.triggerAtMs))
            }
        }
    }

    @Test
    fun `nothing is drawn during the sleep window itself`() {
        for (seed in 0 until 200) {
            // Regenerating at 02:00 — inside the nightly window — must not place stops before 07:00.
            val stops = generate(LocalTime.of(2, 0), seed = seed)
            for (stop in stops) {
                assertTrue(
                    "seed $seed: stop at ${stop.triggerAtMs} landed before the window ended",
                    stop.triggerAtMs >= at(LocalTime.of(7, 0))
                )
                assertTrue(stop.triggerAtMs < at(LocalTime.of(23, 0)))
            }
        }
    }

    @Test
    fun `a regeneration late in the day draws fewer stops than one at the start`() {
        val morning = (0 until 300).map { generate(LocalTime.of(7, 30), seed = it).size }.average()
        val evening = (0 until 300).map { generate(LocalTime.of(21, 0), seed = it).size }.average()

        assertTrue(
            "morning=$morning evening=$evening — count should scale with remaining active time",
            evening < morning
        )
        assertTrue("morning draw looks wrong: $morning", morning in 2.0..5.0)
    }

    @Test
    fun `nothing is drawn when no active time remains in the day`() {
        // 23:30 is inside the nightly window; the rest of the calendar day is asleep.
        assertTrue(generate(LocalTime.of(23, 30)).isEmpty())
    }

    @Test
    fun `regeneration honours the minimum gap against a stop that already fired`() {
        val p = params(countMin = 5, countMax = 5, gapMinutes = 45)
        val lastStop = at(LocalTime.of(11, 50))
        // Resuming ten minutes after a stop: the next may not come for another 35 active minutes.
        val resumeAt = LocalTime.of(12, 0)

        for (seed in 0 until 300) {
            val stops = generate(resumeAt, params = p, lastStopAt = lastStop, seed = seed)
            val first = stops.firstOrNull() ?: continue
            val activeGap = ScheduleEngine.activeMsBetween(
                lastStop, first.triggerAtMs, listOf(nightly), zone
            )
            assertTrue(
                "seed $seed: only ${activeGap / 60000} active minutes since the last stop",
                activeGap >= p.minGapMs
            )
        }
    }

    @Test
    fun `paused time does not count toward the minimum gap`() {
        // A stop at 10:00, then the user is asleep 23:00-07:00 and resumes the next morning is
        // covered by the window logic; here the pause is modelled as an all-day window so that
        // no active time passes at all.
        val p = params(gapMinutes = 45)
        val allDayAsleep = SleepWindow(
            days = SleepWindow.ALL_DAYS,
            start = LocalTime.of(10, 0),
            end = LocalTime.of(20, 0)
        )
        val elapsed = ScheduleEngine.activeMsBetween(
            at(LocalTime.of(10, 30)),
            at(LocalTime.of(19, 30)),
            listOf(allDayAsleep),
            zone
        )
        assertEquals(0L, elapsed)
        assertTrue(elapsed < p.minGapMs)
    }

    @Test
    fun `no stop is drawn inside the grace period after generation`() {
        val now = at(LocalTime.of(9, 0))
        for (seed in 0 until 300) {
            for (stop in generate(LocalTime.of(9, 0), seed = seed)) {
                assertTrue(
                    "seed $seed: stop drawn within the grace period",
                    stop.triggerAtMs >= now + ScheduleEngine.GENERATION_GRACE_MS
                )
            }
        }
    }

    @Test
    fun `stops never spill past the end of the calendar day`() {
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        for (seed in 0 until 300) {
            for (stop in generate(LocalTime.of(8, 0), seed = seed)) {
                assertTrue(stop.triggerAtMs < dayEnd)
            }
        }
    }

    @Test
    fun `regenerating twice at the same moment gives different schedules`() {
        val a = generate(LocalTime.of(9, 0), seed = 1).map { it.triggerAtMs }
        val b = generate(LocalTime.of(9, 0), seed = 2).map { it.triggerAtMs }
        assertTrue("two independent draws produced identical timings", a != b)
    }

    @Test
    fun `a day with no sleep windows is fully available`() {
        for (seed in 0 until 200) {
            val stops = generate(LocalTime.of(0, 30), windows = emptyList(), seed = seed)
            for (stop in stops) {
                assertTrue(stop.triggerAtMs > at(LocalTime.of(0, 30)))
            }
        }
    }

    @Test
    fun `next day boundary is the following local midnight`() {
        assertEquals(
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            ScheduleEngine.nextDayBoundaryMs(at(LocalTime.of(15, 0)), zone)
        )
    }

    @Test
    fun `local date key follows the zone, not UTC`() {
        // 23:30 London on 2026-03-10 is already 2026-03-11 in Tokyo.
        val ms = at(LocalTime.of(23, 30))
        assertEquals("2026-03-10", ScheduleEngine.localDateKey(ms, zone))
        assertEquals("2026-03-11", ScheduleEngine.localDateKey(ms, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `moving to a new timezone redraws against that zone's day`() {
        val ms = at(LocalTime.of(9, 0))
        val tokyo = ZoneId.of("Asia/Tokyo")
        val stops = ScheduleEngine.generateForRemainderOfDay(
            nowMs = ms,
            zone = tokyo,
            windows = listOf(nightly),
            params = params(),
            random = Random(3)
        )
        val tokyoDayEnd = LocalDate.of(2026, 3, 10)
            .plusDays(1).atStartOfDay(tokyo).toInstant().toEpochMilli()
        for (stop in stops) {
            assertTrue(stop.triggerAtMs > ms)
            assertTrue(stop.triggerAtMs < tokyoDayEnd)
        }
    }
}
