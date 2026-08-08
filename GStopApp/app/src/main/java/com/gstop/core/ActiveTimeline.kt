package com.gstop.core

/**
 * The active timeline: the wall-clock day with every sleep window (and pause) snipped out and
 * the remainder conceptually concatenated. All random generation runs on this timeline, so the
 * minimum gap is measured in active time with no special cases at window boundaries.
 *
 * [segments] must be sorted, disjoint and non-empty-length; use [of] to build one safely.
 */
class ActiveTimeline private constructor(val segments: List<Interval>) {

    /** Total active time on this timeline, in milliseconds. */
    val totalActiveMs: Long = segments.sumOf { it.durationMs }

    val isEmpty: Boolean get() = totalActiveMs <= 0L

    /**
     * Maps an offset measured in active time to the wall-clock instant it lands on.
     * The offset is clamped into [0, totalActiveMs].
     */
    fun toWallClock(activeOffsetMs: Long): Long {
        require(segments.isNotEmpty()) { "empty timeline has no wall clock" }
        var remaining = activeOffsetMs.coerceIn(0L, totalActiveMs)
        for (segment in segments) {
            if (remaining < segment.durationMs) return segment.startMs + remaining
            remaining -= segment.durationMs
        }
        return segments.last().endMs
    }

    /**
     * Active time elapsed between the start of the timeline and [wallMs]. Wall-clock time that
     * falls inside an inactive gap contributes nothing.
     */
    fun toActiveOffset(wallMs: Long): Long {
        var acc = 0L
        for (segment in segments) {
            when {
                wallMs >= segment.endMs -> acc += segment.durationMs
                wallMs > segment.startMs -> return acc + (wallMs - segment.startMs)
                else -> return acc
            }
        }
        return acc
    }

    /** True if [wallMs] falls inside an active segment. */
    fun isActiveAt(wallMs: Long): Boolean = segments.any { it.contains(wallMs) }

    /**
     * A timeline with the first [ms] of *active* time removed. Used to honour the minimum gap
     * across a regeneration: the next stop may not land closer than min-gap in active time to
     * the stop that already fired.
     */
    fun dropFirstActive(ms: Long): ActiveTimeline {
        if (ms <= 0L) return this
        if (ms >= totalActiveMs) return EMPTY
        return of(segments, Interval(toWallClock(ms), segments.last().endMs))
    }

    override fun toString(): String = "ActiveTimeline(${segments.size} segs, ${totalActiveMs}ms)"

    companion object {
        val EMPTY = ActiveTimeline(emptyList())

        fun of(segments: List<Interval>): ActiveTimeline {
            val merged = Interval.merge(segments)
            return if (merged.isEmpty()) EMPTY else ActiveTimeline(merged)
        }

        fun of(segments: List<Interval>, bounds: Interval): ActiveTimeline =
            of(segments.mapNotNull { it.clipTo(bounds) })

        /**
         * The active timeline over [bounds] given recurring sleep [windows].
         */
        fun build(
            bounds: Interval,
            windows: List<SleepWindow>,
            zone: java.time.ZoneId
        ): ActiveTimeline {
            if (bounds.durationMs <= 0) return EMPTY
            val asleep = SleepWindowExpander.expand(windows, bounds, zone)
            return of(Interval.subtract(bounds, asleep))
        }
    }
}
