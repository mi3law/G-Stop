package com.gstop.core

import kotlin.random.Random

/**
 * One drawn stop: when it fires (wall clock) and how long it lasts. The duration is never shown
 * to the user, and neither is [triggerAtMs] before it arrives.
 */
data class DrawnStop(
    val triggerAtMs: Long,
    val durationMs: Long
)

/**
 * Bounds for a draw. Durations and gap are in milliseconds; counts are per calendar day.
 */
data class SamplingParams(
    val countMin: Int,
    val countMax: Int,
    val minGapMs: Long,
    val durationMinMs: Long,
    val durationMaxMs: Long
) {
    init {
        require(countMin >= 0 && countMax >= countMin) { "bad count range $countMin..$countMax" }
        require(minGapMs > 0) { "min gap must be positive" }
        require(durationMinMs > 0 && durationMaxMs >= durationMinMs) { "bad duration range" }
    }
}

/**
 * Count-first placement (PRD §6.4).
 *
 * Draw the day's count N uniformly between the user's min and max, then place N points uniformly
 * at random on the active timeline with the minimum gap enforced by the standard transformation:
 * shrink the timeline by the total reserved gap time ((N-1) x min-gap), scatter N points
 * uniformly, sort them, then re-expand by adding back the cumulative gap offsets. If the active
 * timeline is too short to fit N stops at the minimum gap, N is clipped to the maximum feasible.
 * No rejection loops.
 *
 * This is statistically a Poisson process conditioned on its (hidden) count: there is no maximum
 * gap, so no learnable deadline exists.
 */
object StopSampler {

    /**
     * @param timeline the remaining active timeline to place stops on.
     * @param countScale for regeneration: the fraction of the day's active time that [timeline]
     *   represents. The drawn count is scaled by it so that a regeneration part-way through the
     *   day draws a count in proportion to the remaining active time (PRD §6.4). 1.0 for a fresh
     *   whole-day draw.
     */
    fun sample(
        timeline: ActiveTimeline,
        params: SamplingParams,
        countScale: Double = 1.0,
        random: Random = Random.Default
    ): List<DrawnStop> {
        if (timeline.isEmpty) return emptyList()

        val drawn = drawCount(params.countMin, params.countMax, countScale, random)
        val n = clipToFeasible(drawn, timeline.totalActiveMs, params.minGapMs)
        if (n <= 0) return emptyList()

        val offsets = placeOffsets(n, timeline.totalActiveMs, params.minGapMs, random)
        return offsets.map { offset ->
            DrawnStop(
                triggerAtMs = timeline.toWallClock(offset),
                durationMs = drawDuration(params, random)
            )
        }
    }

    /**
     * Uniform draw on [countMin, countMax], then scaled by [countScale]. The fractional part is
     * resolved by a coin flip so the scaling is unbiased in expectation rather than always
     * rounding the same way.
     */
    internal fun drawCount(countMin: Int, countMax: Int, countScale: Double, random: Random): Int {
        val base = if (countMax == countMin) countMin else random.nextInt(countMin, countMax + 1)
        if (countScale >= 1.0) return base
        if (countScale <= 0.0) return 0
        val scaled = base * countScale
        val floor = kotlin.math.floor(scaled)
        val extra = if (random.nextDouble() < (scaled - floor)) 1 else 0
        return floor.toInt() + extra
    }

    /**
     * The largest number of stops that fits in [totalActiveMs] at [minGapMs] separation:
     * n points need (n-1) x gap of room, so n_max = floor(T / gap) + 1.
     */
    internal fun clipToFeasible(n: Int, totalActiveMs: Long, minGapMs: Long): Int {
        if (n <= 0 || totalActiveMs <= 0) return 0
        val feasible = (totalActiveMs / minGapMs).toInt() + 1
        return minOf(n, feasible)
    }

    /**
     * Shrink / scatter / sort / re-expand. Returns n sorted offsets in active time, each at least
     * [minGapMs] apart, all within [0, totalActiveMs].
     */
    internal fun placeOffsets(n: Int, totalActiveMs: Long, minGapMs: Long, random: Random): LongArray {
        val reserved = (n - 1).toLong() * minGapMs
        val shrunk = totalActiveMs - reserved
        // clipToFeasible guarantees shrunk >= 0.
        val raw = LongArray(n) { if (shrunk <= 0L) 0L else nextLong(random, shrunk + 1) }
        raw.sort()
        for (i in 0 until n) raw[i] += i.toLong() * minGapMs
        return raw
    }

    private fun drawDuration(params: SamplingParams, random: Random): Long {
        val span = params.durationMaxMs - params.durationMinMs
        return params.durationMinMs + if (span <= 0L) 0L else nextLong(random, span + 1)
    }

    /** Uniform in [0, bound). */
    private fun nextLong(random: Random, bound: Long): Long =
        if (bound <= 1L) 0L else random.nextLong(bound)
}
