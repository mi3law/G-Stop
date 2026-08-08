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
    val batteryPromptShown: Boolean = false
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

enum class StopStatus { PENDING, FIRED, SUPPRESSED, MISSED }

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

enum class HistoryType {
    STOP_FIRED,
    STOP_SUPPRESSED,
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
