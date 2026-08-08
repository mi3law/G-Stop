package com.gstop.schedule

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.gstop.R
import kotlin.math.ceil

/**
 * Command / release playback on the alarm stream.
 *
 * `USAGE_ALARM` + `STREAM_ALARM` is the whole point (PRD §3): the alarm stream is unaffected by
 * ringer silent mode and passes Do Not Disturb under the default alarms exception. The volume
 * floor closes the remaining hole — a user who has zeroed the alarm stream.
 */
class StopAudio(private val context: Context) {

    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null

    private val audioManager: AudioManager
        get() = context.getSystemService(AudioManager::class.java)

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setLegacyStreamType(AudioManager.STREAM_ALARM)
        .build()

    /**
     * Raises the alarm stream to at least [floorPercent] of the device maximum, remembering the
     * previous level so [restoreVolume] can put it back.
     */
    fun applyVolumeFloor(floorPercent: Int) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (max <= 0) return
            val floor = ceil(max * floorPercent.coerceIn(0, 100) / 100.0).toInt().coerceIn(1, max)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current < floor) {
                savedAlarmVolume = current
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0)
                Log.i(TAG, "volume floor applied: $current -> $floor (max $max)")
            }
        } catch (e: SecurityException) {
            // Raising volume while DND is active needs notification-policy access. The alarm
            // stream still plays at whatever level the user left it.
            Log.w(TAG, "volume floor blocked by DND policy: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "volume floor failed: ${e.message}")
        }
    }

    fun restoreVolume() {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
            Log.i(TAG, "alarm volume restored to $saved")
        } catch (e: Exception) {
            Log.w(TAG, "volume restore failed: ${e.message}")
        }
    }

    /** Plays [uriString], or the bundled tone when null. [onDone] fires when playback ends. */
    fun play(uriString: String?, fallbackRes: Int, onDone: () -> Unit = {}) {
        stop()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attributes)
            if (uriString.isNullOrBlank()) {
                context.resources.openRawResourceFd(fallbackRes).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            } else {
                mp.setDataSource(context, Uri.parse(uriString))
            }
            mp.isLooping = false
            mp.setOnCompletionListener {
                onDone()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                onDone()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "playback failed, falling back to bundled tone: ${e.message}")
            if (uriString != null) play(null, fallbackRes, onDone) else onDone()
        }
    }

    fun playCommand(uriString: String?, onDone: () -> Unit = {}) =
        play(uriString, R.raw.command, onDone)

    fun playRelease(uriString: String?, onDone: () -> Unit = {}) =
        play(uriString, R.raw.release, onDone)

    fun stop() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
    }

    companion object {
        private const val TAG = "GStop.Audio"
    }
}
