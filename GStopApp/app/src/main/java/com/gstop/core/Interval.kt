package com.gstop.core

/**
 * A half-open wall-clock interval [startMs, endMs) in epoch milliseconds.
 */
data class Interval(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "interval end before start: $startMs..$endMs" }
    }

    val durationMs: Long get() = endMs - startMs

    fun contains(ms: Long): Boolean = ms >= startMs && ms < endMs

    fun overlaps(other: Interval): Boolean = startMs < other.endMs && other.startMs < endMs

    fun clipTo(bounds: Interval): Interval? {
        val s = maxOf(startMs, bounds.startMs)
        val e = minOf(endMs, bounds.endMs)
        return if (e > s) Interval(s, e) else null
    }

    companion object {
        /**
         * Sorts and merges intervals, coalescing any that overlap or touch.
         * Zero-length intervals are dropped.
         */
        fun merge(intervals: List<Interval>): List<Interval> {
            val sorted = intervals.filter { it.durationMs > 0 }.sortedBy { it.startMs }
            if (sorted.isEmpty()) return emptyList()
            val out = ArrayList<Interval>(sorted.size)
            var current = sorted.first()
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                current = if (next.startMs <= current.endMs) {
                    Interval(current.startMs, maxOf(current.endMs, next.endMs))
                } else {
                    out.add(current)
                    next
                }
            }
            out.add(current)
            return out
        }

        /**
         * Returns [bounds] minus the union of [holes].
         */
        fun subtract(bounds: Interval, holes: List<Interval>): List<Interval> {
            if (bounds.durationMs <= 0) return emptyList()
            val merged = merge(holes.mapNotNull { it.clipTo(bounds) })
            if (merged.isEmpty()) return listOf(bounds)
            val out = ArrayList<Interval>(merged.size + 1)
            var cursor = bounds.startMs
            for (hole in merged) {
                if (hole.startMs > cursor) out.add(Interval(cursor, hole.startMs))
                cursor = maxOf(cursor, hole.endMs)
            }
            if (cursor < bounds.endMs) out.add(Interval(cursor, bounds.endMs))
            return out
        }
    }
}
