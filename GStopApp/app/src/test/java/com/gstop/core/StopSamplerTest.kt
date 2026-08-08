package com.gstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Property tests for count-first placement (PRD §6.4). Each runs over many seeds: the point is
 * that the invariants hold for *every* draw, not for one lucky one.
 */
class StopSamplerTest {

    private val seeds = 0 until 400

    private fun params(
        countMin: Int = 2,
        countMax: Int = 5,
        gapMinutes: Long = 45,
        durMinSec: Long = 20,
        durMaxSec: Long = 120
    ) = SamplingParams(
        countMin = countMin,
        countMax = countMax,
        minGapMs = TimeUnit.MINUTES.toMillis(gapMinutes),
        durationMinMs = TimeUnit.SECONDS.toMillis(durMinSec),
        durationMaxMs = TimeUnit.SECONDS.toMillis(durMaxSec)
    )

    /** A single contiguous stretch of active time starting at an arbitrary epoch. */
    private fun contiguous(hours: Long, startMs: Long = 1_800_000_000_000L) =
        ActiveTimeline.of(listOf(Interval(startMs, startMs + TimeUnit.HOURS.toMillis(hours))))

    /** Two active stretches with a four-hour inactive hole between them. */
    private fun split(firstHours: Long, secondHours: Long, startMs: Long = 1_800_000_000_000L): ActiveTimeline {
        val firstEnd = startMs + TimeUnit.HOURS.toMillis(firstHours)
        val secondStart = firstEnd + TimeUnit.HOURS.toMillis(4)
        return ActiveTimeline.of(
            listOf(
                Interval(startMs, firstEnd),
                Interval(secondStart, secondStart + TimeUnit.HOURS.toMillis(secondHours))
            )
        )
    }

    @Test
    fun `minimum gap is always respected, measured in active time`() {
        val p = params()
        val timeline = contiguous(16)
        for (seed in seeds) {
            val stops = StopSampler.sample(timeline, p, random = Random(seed))
            for (i in 1 until stops.size) {
                val activeGap = timeline.toActiveOffset(stops[i].triggerAtMs) -
                    timeline.toActiveOffset(stops[i - 1].triggerAtMs)
                assertTrue(
                    "seed $seed: active gap $activeGap < ${p.minGapMs}",
                    activeGap >= p.minGapMs
                )
            }
        }
    }

    @Test
    fun `minimum gap holds across an inactive hole, so wall-clock gaps may be shorter than it`() {
        val p = params(countMin = 4, countMax = 4, gapMinutes = 45)
        // Two 2-hour stretches separated by 4 inactive hours.
        val timeline = split(2, 2)
        var sawShortWallClockGap = false

        for (seed in seeds) {
            val stops = StopSampler.sample(timeline, p, random = Random(seed))
            for (i in 1 until stops.size) {
                val activeGap = timeline.toActiveOffset(stops[i].triggerAtMs) -
                    timeline.toActiveOffset(stops[i - 1].triggerAtMs)
                assertTrue("seed $seed: active gap $activeGap too small", activeGap >= p.minGapMs)

                val wallGap = stops[i].triggerAtMs - stops[i - 1].triggerAtMs
                if (wallGap > activeGap) sawShortWallClockGap = true
            }
        }
        // Confirms the gap really is measured on the active timeline, not on wall clock.
        assertTrue("expected at least one pair separated by the inactive hole", sawShortWallClockGap)
    }

    @Test
    fun `count stays within the user's bounds on a day with ample room`() {
        val p = params(countMin = 2, countMax = 5)
        val timeline = contiguous(16)
        val seen = mutableSetOf<Int>()
        for (seed in seeds) {
            val n = StopSampler.sample(timeline, p, random = Random(seed)).size
            assertTrue("seed $seed produced $n stops", n in p.countMin..p.countMax)
            seen.add(n)
        }
        // The count is randomised, not fixed: a fixed count would make the day after the Nth
        // stop a known-safe zone.
        assertTrue("count never varied: $seen", seen.size > 1)
    }

    @Test
    fun `a minimum of zero really does produce empty days`() {
        val p = params(countMin = 0, countMax = 3)
        val timeline = contiguous(16)
        val counts = seeds.map { StopSampler.sample(timeline, p, random = Random(it)).size }
        assertTrue("no empty day in 400 draws", counts.any { it == 0 })
        assertTrue(counts.all { it in 0..3 })
    }

    @Test
    fun `short active day clips the count to what fits at the minimum gap`() {
        // 100 minutes of active time at a 45-minute gap fits at most 3 stops:
        // (3-1) x 45 = 90 <= 100, but (4-1) x 45 = 135 > 100.
        val p = params(countMin = 6, countMax = 8, gapMinutes = 45)
        val timeline = ActiveTimeline.of(
            listOf(Interval(0L, TimeUnit.MINUTES.toMillis(100)))
        )
        for (seed in seeds) {
            val stops = StopSampler.sample(timeline, p, random = Random(seed))
            assertEquals("seed $seed", 3, stops.size)
            for (i in 1 until stops.size) {
                assertTrue(stops[i].triggerAtMs - stops[i - 1].triggerAtMs >= p.minGapMs)
            }
        }
    }

    @Test
    fun `an active timeline shorter than one gap still allows a single stop`() {
        val p = params(countMin = 3, countMax = 3, gapMinutes = 45)
        val timeline = ActiveTimeline.of(listOf(Interval(0L, TimeUnit.MINUTES.toMillis(10))))
        for (seed in seeds) {
            val stops = StopSampler.sample(timeline, p, random = Random(seed))
            assertEquals(1, stops.size)
            assertTrue(timeline.isActiveAt(stops.first().triggerAtMs))
        }
    }

    @Test
    fun `an empty timeline produces no stops`() {
        val stops = StopSampler.sample(ActiveTimeline.EMPTY, params(), random = Random(1))
        assertTrue(stops.isEmpty())
    }

    @Test
    fun `every stop lands inside active time and never in a hole`() {
        val p = params(countMin = 3, countMax = 6, gapMinutes = 30)
        val timeline = split(6, 6)
        for (seed in seeds) {
            for (stop in StopSampler.sample(timeline, p, random = Random(seed))) {
                assertTrue(
                    "seed $seed: stop at ${stop.triggerAtMs} fell in an inactive stretch",
                    timeline.isActiveAt(stop.triggerAtMs)
                )
            }
        }
    }

    @Test
    fun `stops come back in chronological order`() {
        val p = params(countMin = 4, countMax = 8, gapMinutes = 20)
        val timeline = contiguous(16)
        for (seed in seeds) {
            val stops = StopSampler.sample(timeline, p, random = Random(seed))
            for (i in 1 until stops.size) {
                assertTrue(stops[i].triggerAtMs > stops[i - 1].triggerAtMs)
            }
        }
    }

    @Test
    fun `durations stay inside the user's range`() {
        val p = params(durMinSec = 20, durMaxSec = 120)
        val timeline = contiguous(16)
        val distinct = mutableSetOf<Long>()
        for (seed in seeds) {
            for (stop in StopSampler.sample(timeline, p, random = Random(seed))) {
                assertTrue(stop.durationMs in p.durationMinMs..p.durationMaxMs)
                distinct.add(stop.durationMs)
            }
        }
        // Durations are drawn per stop, so the body cannot learn a rhythm.
        assertTrue("durations were not randomised", distinct.size > 10)
    }

    @Test
    fun `a degenerate duration range yields exactly that duration`() {
        val p = params(durMinSec = 45, durMaxSec = 45)
        val timeline = contiguous(16)
        for (stop in StopSampler.sample(timeline, p, random = Random(7))) {
            assertEquals(TimeUnit.SECONDS.toMillis(45), stop.durationMs)
        }
    }

    @Test
    fun `count scaling shrinks the draw in proportion to the remaining active time`() {
        val p = params(countMin = 4, countMax = 4)
        val timeline = contiguous(16)

        val full = seeds.map { StopSampler.sample(timeline, p, 1.0, Random(it)).size }.average()
        val quarter = seeds.map { StopSampler.sample(timeline, p, 0.25, Random(it)).size }.average()

        assertEquals(4.0, full, 0.001)
        // 4 x 0.25 = 1, resolved by an unbiased coin flip on the fractional part.
        assertEquals(1.0, quarter, 0.25)
    }

    @Test
    fun `a zero scale means no stops remain`() {
        val p = params(countMin = 4, countMax = 8)
        val timeline = contiguous(16)
        for (seed in seeds) {
            assertTrue(StopSampler.sample(timeline, p, 0.0, Random(seed)).isEmpty())
        }
    }

    @Test
    fun `placement is spread across the timeline rather than bunched at one end`() {
        val p = params(countMin = 1, countMax = 1, gapMinutes = 45)
        val timeline = contiguous(16)
        val total = timeline.totalActiveMs

        var early = 0
        var late = 0
        for (seed in 0 until 2000) {
            val stop = StopSampler.sample(timeline, p, random = Random(seed)).single()
            if (timeline.toActiveOffset(stop.triggerAtMs) < total / 2) early++ else late++
        }
        // A single uniformly placed point should land in either half roughly equally.
        assertTrue("early=$early late=$late", early > 800 && late > 800)
    }

    @Test
    fun `clipToFeasible matches the (n-1) x gap capacity rule`() {
        val gap = TimeUnit.MINUTES.toMillis(45)
        assertEquals(0, StopSampler.clipToFeasible(5, 0, gap))
        assertEquals(1, StopSampler.clipToFeasible(5, TimeUnit.MINUTES.toMillis(10), gap))
        assertEquals(2, StopSampler.clipToFeasible(5, gap, gap))
        assertEquals(3, StopSampler.clipToFeasible(5, TimeUnit.MINUTES.toMillis(100), gap))
        assertEquals(4, StopSampler.clipToFeasible(4, TimeUnit.HOURS.toMillis(16), gap))
    }
}
