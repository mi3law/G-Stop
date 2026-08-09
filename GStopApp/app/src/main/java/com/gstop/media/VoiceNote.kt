package com.gstop.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

private const val TAG = "GStop.Voice"

/** How long a single voice note may run before it stops itself. */
const val VOICE_NOTE_MAX_MS = 120_000

/**
 * The spoken half of an observation. One file per stop, overwritten rather than appended: an
 * observation is one utterance, not a thread.
 */
class VoiceNoteRecorder {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(context: Context, file: File): Boolean {
        if (recorder != null) return false
        if (!hasPermission(context)) return false
        file.parentFile?.mkdirs()
        file.delete()

        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setMaxDuration(VOICE_NOTE_MAX_MS)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = created
            target = file
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start recording: ${e.message}")
            runCatching { created.release() }
            file.delete()
            false
        }
    }

    /** True when a usable recording was written. */
    fun stop(): Boolean {
        val active = recorder ?: return false
        val file = target
        recorder = null
        target = null
        val ok = try {
            active.stop()
            true
        } catch (e: Exception) {
            // stop() throws when the recording was too short to produce a valid file.
            Log.w(TAG, "recording discarded: ${e.message}")
            false
        }
        runCatching { active.release() }
        if (!ok) file?.delete()
        return ok && file != null && file.isFile && file.length() > 0
    }

    fun cancel() {
        val active = recorder ?: return
        recorder = null
        runCatching { active.stop() }
        runCatching { active.release() }
        target?.delete()
        target = null
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/** Plays a voice note back on the media stream — nothing here touches the alarm stream. */
class VoiceNotePlayer {

    private var player: MediaPlayer? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun play(file: File, onFinished: () -> Unit) {
        stop()
        if (!file.isFile) return
        player = try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onFinished()
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not play voice note: ${e.message}")
            onFinished()
            null
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}
