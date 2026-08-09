package com.gstop.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.gstop.R
import com.gstop.data.HistoryType
import com.gstop.data.ObservationEntity
import com.gstop.data.Repository
import com.gstop.data.StopStatus
import com.gstop.media.PhotoSlot
import com.gstop.ui.ObservationActivity
import com.gstop.ui.StopActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns one stop from command to release.
 *
 * A foreground service rather than the activity, because the *sound* is the stop. If the
 * full-screen intent cannot show the screen — notifications blocked, an odd OEM lock screen —
 * the command and release must still sound.
 */
class StopService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: StopAudio
    private var wakeLock: PowerManager.WakeLock? = null

    private var stopId: Long = -1L
    private var releaseRunnable: Runnable? = null
    private var finished = false
    private var photosEnabled = false
    /** Set on release, cleared on suppression: only a stop that ran its course is observable. */
    private var releasedAtMs: Long? = null

    override fun onCreate() {
        super.onCreate()
        audio = StopAudio(this)
        ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BEGIN -> begin(
                intent.getLongExtra(EXTRA_STOP_ID, -1L),
                intent.getLongExtra(EXTRA_DURATION_MS, 30_000L)
            )
            ACTION_SUPPRESS -> suppress()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(id: Long, durationMs: Long) {
        if (stopId != -1L) return
        stopId = id
        Log.i(TAG, "stop $id beginning (duration hidden from UI)")

        startForegroundCompat()
        acquireWakeLock(durationMs)

        scope.launch {
            val repo = Repository.get(this@StopService)
            val settings = repo.settings()
            val now = System.currentTimeMillis()

            repo.setStopStatus(id, StopStatus.FIRED)
            repo.log(HistoryType.STOP_FIRED, now)

            handler.post {
                audio.applyVolumeFloor(settings.volumeFloorPercent)
                photosEnabled = settings.photosEnabled
                StopSession.begin(id, settings.photosEnabled)
                audio.playCommand(settings.commandSoundUri)
                scheduleCaptures(id, durationMs)

                val runnable = Runnable { release(settings.releaseSoundUri) }
                releaseRunnable = runnable
                handler.postDelayed(runnable, durationMs)
            }
        }
    }

    /**
     * The beginning, the middle and the end. Timed here because the service is the only party that
     * knows the duration — the stop screen, which actually holds the camera, must not learn it.
     *
     * The last frame is asked for a moment early so it is on disk before the release tears the
     * screen down; a photograph of the very last instant is not worth losing the photograph.
     */
    private fun scheduleCaptures(id: Long, durationMs: Long) {
        if (!photosEnabled) return
        handler.postDelayed(
            { StopSession.requestCapture(id, PhotoSlot.BEGIN) },
            BEGIN_CAPTURE_DELAY_MS.coerceAtMost(durationMs / 4)
        )
        handler.postDelayed({ StopSession.requestCapture(id, PhotoSlot.MIDDLE) }, durationMs / 2)
        handler.postDelayed(
            { StopSession.requestCapture(id, PhotoSlot.END) },
            (durationMs - END_CAPTURE_LEAD_MS).coerceAtLeast(durationMs * 3 / 4)
        )
    }

    /** The release signal: the stop ends from outside, never by the user's decision. */
    private fun release(releaseUri: String?) {
        if (finished) return
        finished = true
        val endedAt = System.currentTimeMillis()
        releasedAtMs = endedAt
        Log.i(TAG, "stop $stopId released")
        // Written now rather than at teardown, so the window is already open by the time the
        // stop screen hands over to it.
        val id = stopId
        scope.launch { Repository.get(this@StopService).openObservationWindow(id, endedAt) }
        audio.stop()
        audio.playRelease(releaseUri) { finish() }
        // Do not wait forever on a broken player.
        handler.postDelayed({ finish() }, RELEASE_TAIL_MS)
    }

    private fun suppress() {
        if (finished) return
        finished = true
        Log.i(TAG, "stop $stopId suppressed by user")
        releaseRunnable?.let { handler.removeCallbacks(it) }
        audio.stop()
        val id = stopId
        scope.launch {
            val repo = Repository.get(this@StopService)
            val now = System.currentTimeMillis()
            repo.setStopStatus(id, StopStatus.SUPPRESSED)
            // Recorded as suppressed, never as failure.
            repo.log(HistoryType.STOP_SUPPRESSED, now)
        }
        finish()
    }

    private var finishing = false

    private fun finish() {
        if (finishing) return
        finishing = true
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        audio.restoreVolume()
        StopSession.end(stopId)

        val appContext = applicationContext
        val id = stopId
        val endedAt = releasedAtMs
        scope.launch {
            if (endedAt != null) {
                Repository.get(appContext).openObservationWindow(id, endedAt)
                handler.post { postObserveNotice(appContext, id) }
            }
            try {
                ScheduleManager.armNext(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "failed to arm next stop after $id: ${e.message}")
            }
            handler.post {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    /**
     * The way back into a stop that ended behind a locked screen or in a pocket. Quiet by
     * channel, and it expires with the window rather than lingering as a reproach.
     */
    private fun postObserveNotice(context: Context, stopId: Long) {
        if (ObservationActivity.isShowing(stopId)) return
        val open = PendingIntent.getActivity(
            context,
            stopId.toInt(),
            ObservationActivity.intent(context, stopId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_stop)
            .setContentTitle(context.getString(R.string.observe_notification_title))
            .setContentText(context.getString(R.string.observe_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(ObservationEntity.WINDOW_MS)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(ObservationActivity.OBSERVE_NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        audio.restoreVolume()
        StopSession.end(stopId)
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // --- plumbing ---

    private fun startForegroundCompat() {
        val notification = buildStopNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock(durationMs: Long) {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gstop:stop").apply {
            setReferenceCounted(false)
            acquire(durationMs + WAKE_LOCK_SLACK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "GStop.Service"

        const val ACTION_BEGIN = "com.gstop.action.BEGIN_STOP"
        const val ACTION_SUPPRESS = "com.gstop.action.SUPPRESS_STOP"
        const val EXTRA_STOP_ID = "stop_id"
        const val EXTRA_DURATION_MS = "duration_ms"

        const val CHANNEL_STOP = "gstop_stop"
        const val CHANNEL_STATUS = "gstop_status"
        private const val NOTIF_ID = 42
        private const val RELEASE_TAIL_MS = 8_000L
        private const val WAKE_LOCK_SLACK_MS = 30_000L

        /** Long enough for the stop screen to have bound the camera, short enough to still be
         *  the beginning; capped at a quarter of the stop for the shortest durations. */
        private const val BEGIN_CAPTURE_DELAY_MS = 1_200L

        /** The end frame is asked for this much before the release, so it lands. */
        private const val END_CAPTURE_LEAD_MS = 700L

        fun beginIntent(context: Context, stopId: Long, durationMs: Long): Intent =
            Intent(context, StopService::class.java).apply {
                action = ACTION_BEGIN
                putExtra(EXTRA_STOP_ID, stopId)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }

        fun suppressIntent(context: Context): Intent =
            Intent(context, StopService::class.java).apply { action = ACTION_SUPPRESS }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)

            val stop = NotificationChannel(
                CHANNEL_STOP,
                context.getString(R.string.channel_stop_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_stop_desc)
                setSound(null, null) // the service plays the command sound itself, on the alarm stream
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                try {
                    setBypassDnd(true)
                } catch (_: SecurityException) {
                    // Needs notification-policy access; the alarm-stream audio still passes DND.
                }
            }
            nm.createNotificationChannel(stop)

            val status = NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_status_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(status)
        }

        /** Full-screen intent so a stop lights and occupies the lock screen (PRD §3). */
        fun buildStopNotification(context: Context): Notification {
            val fullScreen = PendingIntent.getActivity(
                context,
                7,
                Intent(context, StopActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(context, CHANNEL_STOP)
                .setSmallIcon(R.drawable.ic_stat_stop)
                .setContentTitle(context.getString(R.string.stop_notification_title))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .build()
        }
    }
}
