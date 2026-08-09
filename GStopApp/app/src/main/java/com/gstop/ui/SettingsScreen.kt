package com.gstop.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gstop.core.SleepWindow
import com.gstop.data.HistoryType
import com.gstop.data.Repository
import com.gstop.data.SettingsEntity
import com.gstop.data.SleepWindowEntity
import com.gstop.schedule.ScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
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
fun SettingsScreen(onBack: () -> Unit) {
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
    val commandPicker = rememberRingtonePicker { uri ->
        save("command sound", regenerate = false) { it.copy(commandSoundUri = uri) }
    }
    val releasePicker = rememberRingtonePicker { uri ->
        save("release sound", regenerate = false) { it.copy(releaseSoundUri = uri) }
    }

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
                onPick = { commandPicker(settings.commandSoundUri) },
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
                onPick = { releasePicker(settings.releaseSoundUri) },
                onReset = {
                    save("release sound reset", regenerate = false) {
                        it.copy(releaseSoundUri = null)
                    }
                }
            )
            Hint("Command and release must stay clearly distinct from each other.")
        }

        SleepWindowsSection(windows, repo, scope, context)

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SoundRow(label: String, uri: String?, onPick: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val name = remember(uri) {
        if (uri.isNullOrBlank()) "Bundled default"
        else runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context)
        }.getOrNull() ?: "Custom"
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
        TextButton(onClick = onPick) { Text("Change") }
        if (!uri.isNullOrBlank()) TextButton(onClick = onReset) { Text("Reset") }
    }
}

/** System alarm-tone picker. Importing arbitrary audio files is deferred (PRD §5). */
@Composable
private fun rememberRingtonePicker(onPicked: (String?) -> Unit): (String?) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data
                ?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onPicked(uri?.toString())
        }
    }
    return { existing ->
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                existing?.let { Uri.parse(it) }
            )
        }
        launcher.launch(intent)
    }
}

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
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("Back") }
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
