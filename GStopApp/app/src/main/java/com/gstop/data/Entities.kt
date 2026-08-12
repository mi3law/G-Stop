package com.gstop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gstop.core.SamplingParams
import com.gstop.core.SleepWindow
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The single settings row. Defaults are the ones recommended in
 * `stop_app_duration_frequency.md`: 20 s – 2 min duration, 2–5 stops per day, 45 min minimum gap.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val durationMinSec: Int = 20,
    val durationMaxSec: Int = 120,
    val countMin: Int = 2,
    val countMax: Int = 5,
    val minGapMinutes: Int = 45,
    /** Minimum alarm-stream volume, as a percentage of the device maximum, forced at stop time. */
    val volumeFloorPercent: Int = 60,
    /** null means the bundled default tone. */
    val commandSoundUri: String? = null,
    val releaseSoundUri: String? = null,
    val paused: Boolean = false,
    val pausedAtMs: Long? = null,
    /** Set once the user has been shown the battery-optimisation prompt. */
    val batteryPromptShown: Boolean = false,
    /** Whether a stop takes the three front-camera photos. Off means no camera is ever opened. */
    val photosEnabled: Boolean = true
) {
    fun toSamplingParams(): SamplingParams = SamplingParams(
        countMin = countMin,
        countMax = countMax,
        minGapMs = TimeUnit.MINUTES.toMillis(minGapMinutes.toLong()),
        durationMinMs = TimeUnit.SECONDS.toMillis(durationMinSec.toLong()),
        durationMaxMs = TimeUnit.SECONDS.toMillis(durationMaxSec.toLong())
    )

    companion object {
        // Allowed user ranges, from the parameter table in the companion document.
        const val DURATION_FLOOR_SEC = 10
        const val DURATION_CEILING_SEC = 300
        const val COUNT_FLOOR = 0
        const val COUNT_CEILING = 8
        const val GAP_FLOOR_MIN = 20
        const val GAP_CEILING_MIN = 240
    }
}

@Entity(tableName = "sleep_windows")
data class SleepWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Bit i (0-based) set means DayOfWeek.of(i + 1) — Monday is bit 0. */
    val daysMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val enabled: Boolean = true
) {
    fun toDomain(): SleepWindow = SleepWindow(
        id = id,
        days = maskToDays(daysMask),
        start = LocalTime.ofSecondOfDay(startMinuteOfDay * 60L),
        end = LocalTime.ofSecondOfDay(endMinuteOfDay * 60L),
        enabled = enabled
    )

    companion object {
        fun maskToDays(mask: Int): Set<DayOfWeek> =
            DayOfWeek.values().filter { (mask shr (it.value - 1)) and 1 == 1 }.toSet()

        fun daysToMask(days: Set<DayOfWeek>): Int =
            days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }

        fun defaultNightly(): SleepWindowEntity = SleepWindowEntity(
            daysMask = daysToMask(SleepWindow.ALL_DAYS),
            startMinuteOfDay = 23 * 60,
            endMinuteOfDay = 7 * 60
        )
    }
}

/**
 * SUSPENDED is a pause holding the draw aside. It is deliberately not PENDING, so the alarm
 * receiver refuses it, `markMissed` cannot stamp it as a stop that got away, and History — which
 * shows only FIRED and SUPPRESSED — never sees it. A resume either makes it PENDING again or
 * throws it away; nothing else in the app looks at it.
 */
enum class StopStatus { PENDING, SUSPENDED, FIRED, SUPPRESSED, MISSED }

/**
 * A drawn stop. Never surfaced to the user before it fires — the schedule is not displayed.
 */
@Entity(tableName = "scheduled_stops")
data class ScheduledStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerAtMs: Long,
    val durationMs: Long,
    val localDate: String,
    val status: String = StopStatus.PENDING.name
)

/**
 * What the user wrote down in the five minutes after a stop released. One row per stop that ran
 * its course; the row exists from the moment the stop ends, empty, and stays empty unless the
 * user fills something in.
 *
 * A stop with anything in it is a *noted* stop — that distinction is the whole point of the
 * History screen, and it is derived here rather than stored, so it can never drift.
 */
@Entity(tableName = "observations")
data class ObservationEntity(
    @PrimaryKey val stopId: Long,
    /** When the stop ended. The observation window runs for [WINDOW_MS] from here. */
    val endedAtMs: Long,
    val movement: String? = null,
    val feeling: String? = null,
    val thinking: String? = null,
    val hasVoiceNote: Boolean = false,
    /** First moment the observation became non-empty; null while it is still empty. */
    val notedAtMs: Long? = null
) {
    val isNoted: Boolean
        get() = hasVoiceNote ||
            !movement.isNullOrBlank() || !feeling.isNullOrBlank() || !thinking.isNullOrBlank()

    fun windowClosesAtMs(): Long = endedAtMs + WINDOW_MS

    fun windowOpenAt(nowMs: Long): Boolean = nowMs < windowClosesAtMs()

    companion object {
        /** The observation window: five minutes from the release, then the stop is closed. */
        const val WINDOW_MS = 5 * 60 * 1000L
    }
}

/** A row of the History screen: a stop that actually happened, with whatever was noted about it. */
data class StopRecord(
    val stopId: Long,
    val atMs: Long,
    val status: String,
    val movement: String?,
    val feeling: String?,
    val thinking: String?,
    val hasVoiceNote: Boolean,
    val endedAtMs: Long?
) {
    val suppressed: Boolean get() = status == StopStatus.SUPPRESSED.name

    val noted: Boolean
        get() = hasVoiceNote ||
            !movement.isNullOrBlank() || !feeling.isNullOrBlank() || !thinking.isNullOrBlank()

    /** The three kinds of stop the practice recognises. */
    val label: String
        get() = when {
            suppressed -> "Stop suppressed"
            noted -> "Stop, noted"
            else -> "Stop"
        }
}

enum class HistoryType {
    STOP_FIRED,
    STOP_SUPPRESSED,
    STOP_NOTED,
    STOP_MISSED,
    PAUSED,
    RESUMED,
    SCHEDULE_REGENERATED,
    BOOT,
    TIME_CHANGED,
    SETTINGS_CHANGED,
    PERMISSION_WARNING
}

/**
 * The unbounded local history log (PRD §6.3). Pause/resume toggles are logged deliberately:
 * the pause button is the escape hatch back into self-administered stopping, and seeing one's
 * own use of it is part of the practice.
 */
@Entity(tableName = "history")
data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val type: String,
    val detail: String? = null
)
