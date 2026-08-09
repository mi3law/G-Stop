package com.gstop.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gstop.data.ObservationEntity
import com.gstop.data.Repository
import com.gstop.media.PhotoSlot
import com.gstop.media.StopMedia
import com.gstop.media.VOICE_NOTE_MAX_MS
import com.gstop.media.VoiceNotePlayer
import com.gstop.media.VoiceNoteRecorder
import com.gstop.media.decodeStopPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SAVE_DEBOUNCE_MS = 400L

/**
 * How long to wait before declaring that a stop has no observation. The row is written as the
 * stop releases, a moment before this screen can be reached from it.
 */
private const val SETTLE_MS = 2_000L

/**
 * The five minutes after a stop.
 *
 * Three words and a voice note, all optional — the exercise is the noticing, not the writing, and
 * an observation left blank is a legitimate outcome. When the window closes the fields go
 * read-only: a stop is observed then, or not at all.
 *
 * Reached twice: straight after a stop, while the window is open, and afterwards from History,
 * where it is a record to read.
 */
@Composable
fun ObservationScreen(
    stopId: Long,
    title: String,
    backLabel: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }

    val observation by remember(stopId) { repo.observationFlow(stopId) }
        .collectAsState(initial = null)

    var settled by remember(stopId) { mutableStateOf(false) }
    LaunchedEffect(stopId) {
        delay(SETTLE_MS)
        settled = true
    }

    // Ticks only while there is a window left to count down; a closed one needs no clock.
    val now by produceState(initialValue = System.currentTimeMillis(), observation?.endedAtMs) {
        val closesAt = observation?.windowClosesAtMs() ?: return@produceState
        while (System.currentTimeMillis() < closesAt) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
        value = System.currentTimeMillis()
    }
    val remainingMs = observation?.let { it.windowClosesAtMs() - now } ?: 0L
    val editable = observation != null && remainingMs > 0

    var movement by remember(stopId) { mutableStateOf("") }
    var feeling by remember(stopId) { mutableStateOf("") }
    var thinking by remember(stopId) { mutableStateOf("") }
    var seeded by remember(stopId) { mutableStateOf(false) }

    LaunchedEffect(observation) {
        val loaded = observation ?: return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        movement = loaded.movement.orEmpty()
        feeling = loaded.feeling.orEmpty()
        thinking = loaded.thinking.orEmpty()
        seeded = true
    }

    // Saved as it is written, so leaving the screen — or the window closing under a half-typed
    // line — never loses what was already noticed.
    LaunchedEffect(movement, feeling, thinking, seeded) {
        if (!seeded) return@LaunchedEffect
        val base = observation ?: return@LaunchedEffect
        val edited = base.copy(
            movement = movement.blankToNull(),
            feeling = feeling.blankToNull(),
            thinking = thinking.blankToNull()
        )
        if (edited == base) return@LaunchedEffect
        delay(SAVE_DEBOUNCE_MS)
        repo.saveObservationAsync(edited, System.currentTimeMillis())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        ScreenHeader(title = title, backLabel = backLabel, onBack = onBack)

        Text(
            text = windowLine(observation, remainingMs, editable, settled),
            style = MaterialTheme.typography.bodyMedium,
            color = if (editable) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        StopPhotoStrip(stopId)

        Spacer(Modifier.height(20.dp))

        ObservationField(
            label = "Movement",
            placeholder = "The body — posture, tension, where it was caught",
            value = movement,
            enabled = editable,
            onValueChange = { movement = it }
        )
        ObservationField(
            label = "Feeling",
            placeholder = "The emotional tone, before it was named",
            value = feeling,
            enabled = editable,
            onValueChange = { feeling = it }
        )
        ObservationField(
            label = "Thinking",
            placeholder = "What the mind was busy with",
            value = thinking,
            enabled = editable,
            onValueChange = { thinking = it }
        )

        Spacer(Modifier.height(8.dp))

        VoiceNoteSection(stopId = stopId, observation = observation, editable = editable)

        Spacer(Modifier.height(24.dp))

        if (observation == null && settled) {
            Hint(
                "This stop has no observation. Only a stop that runs to its release opens a " +
                    "window; a suppressed one does not."
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun windowLine(
    observation: ObservationEntity?,
    remainingMs: Long,
    editable: Boolean,
    settled: Boolean
): String = when {
    observation == null && !settled -> ""
    observation == null -> "Photographs only."
    editable -> {
        val seconds = (remainingMs / 1000).toInt()
        String.format(Locale.getDefault(), "%d:%02d left in the window.", seconds / 60, seconds % 60)
    }
    else -> {
        val ended = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
            .format(Date(observation.endedAtMs))
        "The window closed. Stop ended $ended."
    }
}

@Composable
private fun ObservationField(
    label: String,
    placeholder: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    // Read-only rather than disabled: a closed observation is meant to be read, and Material's
    // disabled colours are too faint for that.
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !enabled,
        label = { Text(label) },
        placeholder = {
            if (enabled) Text(placeholder, style = MaterialTheme.typography.bodySmall)
        },
        singleLine = false,
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    )
}

// --- the three photographs ---

/**
 * What the front camera saw at the beginning, the middle and the end. Missing frames are shown as
 * gaps rather than hidden: a stop that was suppressed, or one where the camera was refused, should
 * look different from one where all three were taken.
 */
@Composable
fun StopPhotoStrip(stopId: Long) {
    val context = LocalContext.current
    var enlarged by remember { mutableStateOf<File?>(null) }

    // Opened straight after a stop, the last frame may still be on its way to disk.
    var tick by remember(stopId) { mutableStateOf(0) }
    LaunchedEffect(stopId) {
        repeat(PHOTO_POLLS) {
            delay(1_500)
            tick += 1
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PhotoSlot.entries.forEach { slot ->
            val file = remember(stopId, slot) { StopMedia.photo(context, stopId, slot) }
            val exists = remember(stopId, slot, tick) { file.isFile && file.length() > 0 }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (exists) Modifier.clickable { enlarged = file } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = rememberStopPhoto(if (exists) file else null, THUMBNAIL_PX)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "${slot.label} of the stop",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (!exists) {
                        Text(
                            "—",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    slot.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    enlarged?.let { file ->
        Dialog(onDismissRequest = { enlarged = null }) {
            val bitmap = rememberStopPhoto(file, FULL_PX)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { enlarged = null },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("…", modifier = Modifier.padding(40.dp))
                }
            }
        }
    }
}

private const val THUMBNAIL_PX = 400
private const val FULL_PX = 1400
private const val PHOTO_POLLS = 6

@Composable
private fun rememberStopPhoto(file: File?, maxPx: Int): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, file, maxPx) {
        val decoded = file?.let {
            withContext(Dispatchers.IO) { decodeStopPhoto(it, maxPx)?.asImageBitmap() }
        }
        value = decoded
    }.value

// --- the voice note ---

@Composable
private fun VoiceNoteSection(
    stopId: Long,
    observation: ObservationEntity?,
    editable: Boolean
) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val file = remember(stopId) { StopMedia.voiceNote(context, stopId) }

    val recorder = remember { VoiceNoteRecorder() }
    val player = remember { VoiceNotePlayer() }
    var recording by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var hasNote by remember(stopId) { mutableStateOf(file.isFile && file.length() > 0) }
    var elapsedSec by remember { mutableStateOf(0) }

    fun markVoiceNote(present: Boolean) {
        hasNote = present
        val base = observation ?: return
        if (base.hasVoiceNote == present) return
        repo.saveObservationAsync(
            base.copy(hasVoiceNote = present),
            System.currentTimeMillis()
        )
    }

    fun stopRecording() {
        if (!recorder.isRecording) return
        val kept = recorder.stop()
        recording = false
        markVoiceNote(kept)
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) recording = recorder.start(context, file)
    }

    // Leaving the screen mid-sentence keeps what was said, rather than discarding it.
    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording) {
                val kept = recorder.stop()
                observation?.let {
                    if (it.hasVoiceNote != kept) {
                        repo.saveObservationAsync(
                            it.copy(hasVoiceNote = kept),
                            System.currentTimeMillis()
                        )
                    }
                }
            }
            player.stop()
        }
    }

    LaunchedEffect(recording) {
        elapsedSec = 0
        if (!recording) return@LaunchedEffect
        while (elapsedSec < VOICE_NOTE_MAX_MS / 1000) {
            delay(1_000)
            elapsedSec += 1
        }
        stopRecording()
    }

    // The window closing ends a recording in progress, like everything else about the stop.
    LaunchedEffect(editable) {
        if (!editable && recorder.isRecording) stopRecording()
    }

    Column {
        Text(
            "Voice note",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editable) {
                TextButton(
                    onClick = {
                        if (recording) {
                            stopRecording()
                        } else if (VoiceNoteRecorder.hasPermission(context)) {
                            player.stop()
                            playing = false
                            recording = recorder.start(context, file)
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                ) {
                    Text(
                        when {
                            recording -> "Stop recording"
                            hasNote -> "Record again"
                            else -> "Record"
                        }
                    )
                }
            }
            if (hasNote && !recording) {
                TextButton(
                    onClick = {
                        if (playing) {
                            player.stop()
                            playing = false
                        } else {
                            playing = true
                            player.play(file) { playing = false }
                        }
                    }
                ) { Text(if (playing) "Stop" else "Play") }
                if (editable) {
                    TextButton(
                        onClick = {
                            player.stop()
                            playing = false
                            file.delete()
                            markVoiceNote(false)
                        }
                    ) { Text("Delete") }
                }
            }
            if (recording) {
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        "%d:%02d",
                        elapsedSec / 60,
                        elapsedSec % 60
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (!hasNote && !editable) {
            Text(
                "No voice note.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}
