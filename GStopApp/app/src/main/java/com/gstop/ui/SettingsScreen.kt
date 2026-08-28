package com.gstop.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gstop.backup.BackupManager
import com.gstop.backup.CsvExport
import com.gstop.core.SleepWindow
import com.gstop.data.HistoryType
import com.gstop.data.Repository
import com.gstop.data.SettingsEntity
import com.gstop.data.SleepWindowEntity
import com.gstop.media.StopMedia
import com.gstop.schedule.ScheduleManager
import com.gstop.schedule.StopAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Edits that change the *sampling* regenerate the remainder of the day — mid-day settings changes
 * are one of the regeneration triggers (PRD §2). Edits that only change how a stop is delivered,
 * the volume floor and the two tones, leave the drawn schedule alone: re-drawing it would let the
 * user reshuffle today's stops by nudging an unrelated setting.
 *
 * Sliders commit on release rather than on every frame, so a single drag is one saved value, one
 * log line and at most one regeneration.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState(initial = SettingsEntity())
    val windows by repo.sleepWindowsFlow.collectAsState(initial = emptyList())

    fun save(
        reason: String,
        regenerate: Boolean = true,
        transform: (SettingsEntity) -> SettingsEntity
    ) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val current = repo.settings()
                repo.saveSettings(transform(current))
                repo.log(HistoryType.SETTINGS_CHANGED, System.currentTimeMillis(), reason)
                if (regenerate) {
                    ScheduleManager.regenerate(context, "settings changed: $reason")
                }
            }
        }
    }

    // Tones are delivery-only, like the volume floor: picking one leaves the schedule as drawn.
    val commandPicker = rememberSoundPicker { uri ->
        save("command sound", regenerate = false) { it.copy(commandSoundUri = uri) }
    }
    val releasePicker = rememberSoundPicker { uri ->
        save("release sound", regenerate = false) { it.copy(releaseSoundUri = uri) }
    }

    // A private player for auditioning a tone from this screen — the same alarm-stream path a
    // real stop uses, so what you hear here is what a stop will play. Released with the screen.
    val sampler = remember { StopAudio(context) }
    DisposableEffect(Unit) { onDispose { sampler.stop() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        SectionCard("Stop duration") {
            var duration by remember(settings.durationMinSec, settings.durationMaxSec) {
                mutableStateOf(
                    settings.durationMinSec.toFloat()..settings.durationMaxSec.toFloat()
                )
            }
            Text(
                "${formatSeconds(duration.start.roundToInt())} – " +
                    formatSeconds(duration.endInclusive.roundToInt()),
                style = MaterialTheme.typography.titleMedium
            )
            RangeSlider(
                value = duration,
                onValueChange = { duration = it },
                onValueChangeFinished = {
                    save("duration") {
                        it.copy(
                            durationMinSec = duration.start.roundToInt(),
                            durationMaxSec = duration.endInclusive.roundToInt()
                        )
                    }
                },
                valueRange = SettingsEntity.DURATION_FLOOR_SEC.toFloat()..
                    SettingsEntity.DURATION_CEILING_SEC.toFloat()
            )
            Hint("Each stop lasts a hidden length drawn in this range.")
        }

        SectionCard("Stops per day") {
            var count by remember(settings.countMin, settings.countMax) {
                mutableStateOf(settings.countMin.toFloat()..settings.countMax.toFloat())
            }
            Text(
                "${count.start.roundToInt()} – ${count.endInclusive.roundToInt()}",
                style = MaterialTheme.typography.titleMedium
            )
            RangeSlider(
                value = count,
                onValueChange = { count = it },
                onValueChangeFinished = {
                    save("stops per day") {
                        it.copy(
                            countMin = count.start.roundToInt(),
                            countMax = count.endInclusive.roundToInt()
                        )
                    }
                },
                valueRange = SettingsEntity.COUNT_FLOOR.toFloat()..
                    SettingsEntity.COUNT_CEILING.toFloat(),
                steps = SettingsEntity.COUNT_CEILING - SettingsEntity.COUNT_FLOOR - 1
            )
            Hint(
                if (count.start.roundToInt() == 0) {
                    "A minimum of zero allows genuinely empty days."
                } else {
                    "Set the minimum to zero to allow empty days."
                }
            )
        }

        SectionCard("Minimum gap") {
            var gap by remember(settings.minGapMinutes) {
                mutableFloatStateOf(settings.minGapMinutes.toFloat())
            }
            Text(
                formatMinutes((gap / 5f).roundToInt() * 5),
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = gap,
                onValueChange = { gap = it },
                onValueChangeFinished = {
                    save("minimum gap") { it.copy(minGapMinutes = (gap / 5f).roundToInt() * 5) }
                },
                valueRange = SettingsEntity.GAP_FLOOR_MIN.toFloat()..
                    SettingsEntity.GAP_CEILING_MIN.toFloat()
            )
            Hint("Measured in active time — sleep windows and pauses do not count toward it.")
        }

        SectionCard("Volume floor") {
            var volume by remember(settings.volumeFloorPercent) {
                mutableFloatStateOf(settings.volumeFloorPercent.toFloat())
            }
            Text("${volume.roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    // Delivery-only: the drawn schedule is left exactly as it is.
                    save("volume floor", regenerate = false) {
                        it.copy(volumeFloorPercent = volume.roundToInt())
                    }
                },
                valueRange = 0f..100f
            )
            Hint("The alarm stream is raised to at least this level for a stop, then restored.")
        }

        SectionCard("Sounds") {
            SoundRow(
                label = "Command",
                uri = settings.commandSoundUri,
                onSample = { sampler.playCommand(settings.commandSoundUri) },
                onPick = { commandPicker() },
                onReset = {
                    save("command sound reset", regenerate = false) {
                        it.copy(commandSoundUri = null)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            SoundRow(
                label = "Release",
                uri = settings.releaseSoundUri,
                onSample = { sampler.playRelease(settings.releaseSoundUri) },
                onPick = { releasePicker() },
                onReset = {
                    save("release sound reset", regenerate = false) {
                        it.copy(releaseSoundUri = null)
                    }
                }
            )
            Hint("Command and release must stay clearly distinct from each other.")
        }

        SleepWindowsSection(windows, repo, scope, context)

        ObservationsSection(
            photosEnabled = settings.photosEnabled,
            onTogglePhotos = { enabled ->
                save("stop photos ${if (enabled) "on" else "off"}", regenerate = false) {
                    it.copy(photosEnabled = enabled)
                }
            }
        )

        BackupSection()

        SectionCard("Test") {
            Hint(
                "Fires a stop right now, through the exact flow a scheduled one takes — sounds, " +
                    "screen, photos, observation window. Useful for checking that a new phone " +
                    "lets the stop screen through. It is marked Test wherever it is recorded, " +
                    "and the day's drawn schedule is not touched by it."
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { ScheduleManager.fireTestStop(context) }
                }
            }) { Text("Trigger a test stop") }
        }

        SectionCard("Logs") {
            Hint(
                "Everything the app has done — stops, pauses, regenerations, reboots. The " +
                    "History screen is the record of the practice; this is the record of the app."
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenLogs) { Text("Open logs") }
        }

        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Photographs and voice notes live only on this phone and nothing prunes them, so the size they
 * take is shown plainly and deleting them is one deliberate act. Written observations are text
 * and are never touched by this.
 */
@Composable
private fun ObservationsSection(photosEnabled: Boolean, onTogglePhotos: (Boolean) -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val bytes by produceState(initialValue = -1L, reload) {
        val measured = withContext(Dispatchers.IO) { StopMedia.totalBytes(context) }
        value = measured
    }

    SectionCard("Observations") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Photograph each stop", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Three front-camera frames: beginning, middle, end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = photosEnabled, onCheckedChange = onTogglePhotos)
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (bytes < 0) "Measuring…" else "Photos and voice notes: ${formatBytes(bytes)}",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = { confirmingDelete = true },
            enabled = bytes > 0
        ) { Text("Delete all media") }
        Hint("Written observations are kept; only the pictures and recordings are removed.")
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete every photo and voice note?") },
            text = {
                Text(
                    "Every stop photograph and every recording is deleted from this phone and " +
                        "cannot be recovered. What you wrote down stays."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            StopMedia.deleteAll(context)
                            repo.clearVoiceNoteFlags()
                        }
                        reload += 1
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep") }
            }
        )
    }
}

/**
 * Backups leave the phone only through a folder the user picked in the system picker. The folder
 * is always local — cloud providers do not offer folders to Android's tree picker — so reaching
 * Drive takes a sync app watching the folder; the hint says so rather than promising what the
 * picker cannot do. G-Stop itself still has no network permission; everything here is a local
 * file write. The CSV export exists because a spreadsheet is readable in Google Sheets and the
 * backup zip is not: one is for looking at the practice, the other for getting it back.
 */
@Composable
private fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf<String?>(null) }
    var restoreCandidate by remember { mutableStateOf<Uri?>(null) }
    var restoreResult by remember { mutableStateOf<String?>(null) }

    val status by produceState<BackupManager.Status?>(initialValue = null, reload) {
        value = withContext(Dispatchers.IO) { BackupManager.status(context) }
    }
    // Resolved separately: naming the folder queries its document provider, which can be slow.
    val folderName by produceState<String?>(initialValue = null, reload) {
        value = withContext(Dispatchers.IO) { BackupManager.folderName(context) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            BackupManager.setFolder(context, uri)
            reload += 1
        }
    }

    // Drive hands zips back as octet-stream often enough that zip alone hides real backups.
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreCandidate = uri }

    SectionCard("Backup") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Backup folder", style = MaterialTheme.typography.bodyLarge)
                Text(
                    folderName ?: "Not set",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { folderPicker.launch(null) }) {
                Text(if (folderName == null) "Choose" else "Change")
            }
        }
        Hint(
            "A snapshot is written into this folder about once a day. The folder is on this " +
                "phone — Android's picker cannot offer Google Drive folders — so to carry " +
                "snapshots to the cloud, point a sync app such as Autosync for Google Drive " +
                "at it. G-Stop itself never touches the network."
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Include photos and voice notes", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off, they never leave this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = status?.includeMedia == true,
                onCheckedChange = { enabled ->
                    BackupManager.setIncludeMedia(context, enabled)
                    reload += 1
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    working = "Backing up…"
                    scope.launch {
                        withContext(Dispatchers.IO) { BackupManager.backupNow(context) }
                        working = null
                        reload += 1
                    }
                },
                enabled = folderName != null && working == null
            ) { Text("Back up now") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val intent = withContext(Dispatchers.IO) { CsvExport.shareIntent(context) }
                        context.startActivity(intent)
                    }
                }
            ) { Text("Export CSV") }
        }

        TextButton(
            onClick = {
                restorePicker.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            enabled = working == null
        ) { Text("Restore from a backup…") }

        working?.let { Hint(it) }
        restoreResult?.let { Hint(it) }
        if (working == null) {
            status?.let { s ->
                Hint(
                    when {
                        s.lastBackupMs > 0L ->
                            "Last backup: ${formatWhen(s.lastBackupMs)}" +
                                (s.lastResult?.takeIf { it.startsWith("Failed") }
                                    ?.let { " — since then: $it" } ?: "")
                        s.lastResult != null -> s.lastResult!!
                        else -> "No backup yet."
                    }
                )
            }
        }
    }

    restoreCandidate?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Everything on this phone — settings, history, observations, photos and " +
                        "voice notes — is replaced by what the backup holds. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreCandidate = null
                    working = "Restoring…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            BackupManager.restore(context, uri)
                        }
                        restoreResult = result.fold(
                            onSuccess = { it },
                            onFailure = { "Restore failed: ${it.message}" }
                        )
                        working = null
                        reload += 1
                    }
                }) { Text("Replace everything") }
            },
            dismissButton = {
                TextButton(onClick = { restoreCandidate = null }) { Text("Cancel") }
            }
        )
    }
}

private fun formatWhen(ms: Long): String =
    SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ms))

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "none"
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun SoundRow(
    label: String,
    uri: String?,
    onSample: () -> Unit,
    onPick: () -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val name = remember(uri) {
        if (uri.isNullOrBlank()) "Bundled default"
        else runCatching { displayName(context, Uri.parse(uri)) }.getOrNull() ?: "Custom"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onSample) { Text("Sample") }
        TextButton(onClick = onPick) { Text("Change") }
        if (!uri.isNullOrBlank()) TextButton(onClick = onReset) { Text("Reset") }
    }
}

/**
 * Storage Access Framework audio picker.
 *
 * The alarm-tone picker it replaces could only reach tones the OS had registered as *alarms* —
 * a personal recording never appeared in it, and a tone backed by external media could not be
 * read at stop time without a broad storage permission, so the service fell back to the bundled
 * tone and the choice looked ignored. SAF hands back a single-file `content://` URI together with
 * a *persistable* read grant, which survives reboots and process death — so [StopService] can
 * still open it hours later with no manifest permission at all. (PRD §5.)
 */
@Composable
private fun rememberSoundPicker(onPicked: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                // Without the persisted grant the URI is readable only until this Activity dies;
                // the stop that needs it fires much later.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onPicked(uri?.toString())
        }
    }
    return {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        launcher.launch(intent)
    }
}

/** The human name behind a SAF document URI, for the settings row. */
private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

@Composable
private fun SleepWindowsSection(
    windows: List<SleepWindowEntity>,
    repo: Repository,
    scope: CoroutineScope,
    context: android.content.Context
) {
    fun persist(reason: String, block: suspend () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                block()
                repo.log(HistoryType.SETTINGS_CHANGED, System.currentTimeMillis(), reason)
                ScheduleManager.regenerate(context, "sleep windows changed")
            }
        }
    }

    SectionCard("Sleep windows") {
        if (windows.isEmpty()) {
            Hint("No sleep windows — the whole day is active.")
        }
        windows.forEachIndexed { index, window ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
            SleepWindowRow(
                window = window,
                onChange = { updated -> persist("sleep window") { repo.updateWindow(updated) } },
                onDelete = { persist("sleep window removed") { repo.deleteWindow(window) } }
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                persist("sleep window added") {
                    repo.insertWindow(
                        SleepWindowEntity(
                            daysMask = SleepWindowEntity.daysToMask(SleepWindow.ALL_DAYS),
                            startMinuteOfDay = 13 * 60,
                            endMinuteOfDay = 14 * 60
                        )
                    )
                }
            }
        ) { Text("Add window") }
        Hint("Windows may cross midnight; overlapping windows merge.")
    }
}

@Composable
private fun SleepWindowRow(
    window: SleepWindowEntity,
    onChange: (SleepWindowEntity) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current

    fun pickTime(current: Int, apply: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> apply(hour * 60 + minute) },
            current / 60,
            current % 60,
            true
        ).show()
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                pickTime(window.startMinuteOfDay) { onChange(window.copy(startMinuteOfDay = it)) }
            }) { Text(formatTimeOfDay(window.startMinuteOfDay)) }
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                pickTime(window.endMinuteOfDay) { onChange(window.copy(endMinuteOfDay = it)) }
            }) { Text(formatTimeOfDay(window.endMinuteOfDay)) }
            Spacer(Modifier.weight(1f))
            Switch(
                checked = window.enabled,
                onCheckedChange = { onChange(window.copy(enabled = it)) }
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            DayOfWeek.values().forEach { day ->
                val selected = (window.daysMask shr (day.value - 1)) and 1 == 1
                DayChip(day = day, selected = selected) {
                    val mask = window.daysMask xor (1 shl (day.value - 1))
                    onChange(window.copy(daysMask = mask))
                }
            }
        }
        TextButton(onClick = onDelete) { Text("Remove") }
    }
}

@Composable
private fun DayChip(day: DayOfWeek, selected: Boolean, onToggle: () -> Unit) {
    val label = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- shared bits ---

@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    backLabel: String = "Back",
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text(backLabel) }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

fun formatSeconds(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds % 60 == 0 -> "${seconds / 60}m"
    else -> "${seconds / 60}m ${seconds % 60}s"
}

fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

fun formatTimeOfDay(minuteOfDay: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
