package com.gstop.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gstop.data.HistoryType
import com.gstop.data.Repository
import com.gstop.data.StopStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "GStop.Receiver"
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs [block] while holding the broadcast open, so the work survives past onReceive.
 */
internal fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val result = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "receiver work failed: ${e.message}", e)
        } finally {
            result.finish()
        }
    }
}

/** The drawn moment has arrived. */
class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val stopId = intent.getLongExtra(EXTRA_STOP_ID, -1L)
        val appContext = context.applicationContext
        Log.i(TAG, "stop alarm fired for id=$stopId")

        async {
            val repo = Repository.get(appContext)
            val stop = repo.stopById(stopId)
            if (stop == null || stop.status != StopStatus.PENDING.name) {
                Log.w(TAG, "stop $stopId is not pending — ignoring and re-arming")
                ScheduleManager.armNext(appContext)
                return@async
            }
            // Started from an exact alarm, which exempts this from background-start restrictions.
            appContext.startForegroundService(
                StopService.beginIntent(appContext, stop.id, stop.durationMs)
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.gstop.action.STOP_ALARM"
        const val EXTRA_STOP_ID = "stop_id"
    }
}

/** Local midnight: draw the new day. */
class RolloverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ROLLOVER) return
        val appContext = context.applicationContext
        async { ScheduleManager.regenerate(appContext, "day rollover") }
    }

    companion object {
        const val ACTION_ROLLOVER = "com.gstop.action.ROLLOVER"
    }
}

/** Alarms do not survive a reboot; the schedule is redrawn (PRD §3). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appContext = context.applicationContext
        async {
            val repo = Repository.get(appContext)
            repo.log(HistoryType.BOOT, System.currentTimeMillis(), action)
            StopService.ensureChannels(appContext)
            ScheduleManager.regenerate(appContext, "boot / app update")
        }
    }
}

/** A clock or timezone change moves every wall-clock instant; redraw rather than translate. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIMEZONE_CHANGED && action != Intent.ACTION_TIME_CHANGED) return

        val appContext = context.applicationContext
        async {
            val repo = Repository.get(appContext)
            repo.log(HistoryType.TIME_CHANGED, System.currentTimeMillis(), action)
            ScheduleManager.regenerate(appContext, "clock or timezone change")
        }
    }
}
