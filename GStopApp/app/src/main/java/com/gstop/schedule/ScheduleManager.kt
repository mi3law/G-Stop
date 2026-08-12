package com.gstop.schedule

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.gstop.core.ScheduleEngine
import com.gstop.data.HistoryType
import com.gstop.data.Repository
import com.gstop.data.ScheduledStopEntity
import com.gstop.data.StopStatus
import com.gstop.ui.MainActivity
import com.gstop.widget.PracticeWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.random.Random

/**
 * Owns the drawn schedule and the single armed alarm.
 *
 * Exactly one stop is armed at a time (`AlarmManager.setAlarmClock`, PRD §3): when it fires the
 * next one is armed. A separate day-rollover alarm redraws at local midnight.
 *
 * Regeneration (pause→resume, mid-day settings edits, reboot, timezone change) always discards
 * every pending stop and re-runs count-first placement on the *remaining* active timeline. Nothing
 * is deferred or carried over.
 */
object ScheduleManager {

    private const val TAG = "GStop.Schedule"

    private const val REQ_STOP = 1001
    private const val REQ_ROLLOVER = 1002

    private val mutex = Mutex()

    /** Serialised so a reboot broadcast and a UI edit cannot interleave two draws. */
    suspend fun regenerate(context: Context, reason: String, nowMs: Long = System.currentTimeMillis()) {
        mutex.withLock { regenerateLocked(context, reason, nowMs) }
    }

    /**
     * The one way the practice is paused or resumed, whichever surface the tap came from — the
     * main screen or the home-screen widget. Paused time does not exist on the active timeline
     * (PRD §2), so the remainder of the day is redrawn either way.
     */
    suspend fun setPaused(
        context: Context,
        paused: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        mutex.withLock { setPausedLocked(context, paused, nowMs) }
    }

    /**
     * Flips the pause and reports the state it settled on. Reading and writing under the one lock
     * matters here: two taps in quick succession from the widget must end somewhere definite.
     */
    suspend fun togglePaused(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        mutex.withLock {
            val paused = !Repository.get(context).settings().paused
            setPausedLocked(context, paused, nowMs)
            paused
        }

    private suspend fun setPausedLocked(context: Context, paused: Boolean, nowMs: Long) {
        val repo = Repository.get(context)
        val current = repo.settings()
        repo.saveSettings(
            current.copy(paused = paused, pausedAtMs = if (paused) nowMs else null)
        )
        repo.log(if (paused) HistoryType.PAUSED else HistoryType.RESUMED, nowMs)
        regenerateLocked(context, if (paused) "paused" else "resumed", nowMs)
    }

    private suspend fun regenerateLocked(context: Context, reason: String, nowMs: Long) {
        val repo = Repository.get(context)
        val settings = repo.settings()
        val zone = ZoneId.systemDefault()

        cancelStopAlarm(context)
        repo.markMissedBefore(nowMs)
        repo.clearPendingStops()

        if (settings.paused) {
            cancelRolloverAlarm(context)
            repo.log(HistoryType.SCHEDULE_REGENERATED, nowMs, "paused — no stops scheduled ($reason)")
            Log.i(TAG, "regenerate: paused, nothing scheduled ($reason)")
            PracticeWidget.refresh(context)
            return
        }

        val params = settings.toSamplingParams()
        val todayKey = ScheduleEngine.localDateKey(nowMs, zone)
        val lastStop = repo.lastActualStopOn(todayKey)

        val drawn = ScheduleEngine.generateForRemainderOfDay(
            nowMs = nowMs,
            zone = zone,
            windows = repo.sleepWindows(),
            params = params,
            lastStopAtMs = lastStop,
            random = Random.Default
        )

        repo.replacePendingStops(
            drawn.map {
                ScheduledStopEntity(
                    triggerAtMs = it.triggerAtMs,
                    durationMs = it.durationMs,
                    localDate = ScheduleEngine.localDateKey(it.triggerAtMs, zone),
                    status = StopStatus.PENDING.name
                )
            }
        )

        // The count is deliberately not written to the log: the drawn schedule is never displayed.
        repo.log(HistoryType.SCHEDULE_REGENERATED, nowMs, reason)
        Log.i(TAG, "regenerate($reason): drew ${drawn.size} stop(s) — count not surfaced to UI")

        armNextLocked(context, nowMs)
        armRolloverAlarm(context, nowMs, zone)
        PracticeWidget.refresh(context)
    }

    /** Arms the next pending stop. Called after each stop completes. */
    suspend fun armNext(context: Context, nowMs: Long = System.currentTimeMillis()) {
        mutex.withLock { armNextLocked(context, nowMs) }
    }

    private suspend fun armNextLocked(context: Context, nowMs: Long) {
        val repo = Repository.get(context)
        val next = repo.nextPendingStop(nowMs) ?: run {
            Log.i(TAG, "armNext: no pending stops remain today")
            cancelStopAlarm(context)
            return
        }
        armExact(context, next.id, next.triggerAtMs)
    }

    @SuppressLint("MissingPermission")
    private fun armExact(context: Context, stopId: Long, triggerAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val operation = stopPendingIntent(context, stopId)

        if (!canScheduleExactAlarms(context)) {
            // Degrade loudly (PRD §3): still schedule, but the user is warned on the main screen.
            Log.w(TAG, "exact alarms not permitted — falling back to inexact delivery")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            return
        }

        // setAlarmClock: exact, wakes the device from Doze, and is not subject to the
        // background-alarm restrictions that apply to ordinary exact alarms.
        val show = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMs, show), operation)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun stopPendingIntent(context: Context, stopId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_STOP,
            Intent(context, StopAlarmReceiver::class.java).apply {
                action = StopAlarmReceiver.ACTION_STOP
                putExtra(StopAlarmReceiver.EXTRA_STOP_ID, stopId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun cancelStopAlarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(stopPendingIntent(context, -1L))
    }

    // --- day rollover ---

    private fun rolloverPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_ROLLOVER,
            Intent(context, RolloverReceiver::class.java).apply {
                action = RolloverReceiver.ACTION_ROLLOVER
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun armRolloverAlarm(context: Context, nowMs: Long, zone: ZoneId) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = ScheduleEngine.nextDayBoundaryMs(nowMs, zone)
        // Deliberately not setAlarmClock: the rollover is bookkeeping, and an alarm-clock entry
        // for it would sit in the system's "next alarm" slot. Exact where the OS allows it —
        // unguarded, this throws if the exact-alarm permission is ever revoked.
        val operation = rolloverPendingIntent(context)
        if (canScheduleExactAlarms(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun cancelRolloverAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(rolloverPendingIntent(context))
    }
}
