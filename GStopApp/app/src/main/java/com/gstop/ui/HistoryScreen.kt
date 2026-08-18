package com.gstop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gstop.data.Repository
import com.gstop.data.StopRecord
import com.gstop.media.StopMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every stop that actually happened, in the vocabulary the practice uses for them: a **Stop
 * suppressed**, a **Stop**, and a **Stop, noted** — one that was observed in the five minutes
 * afterwards.
 *
 * A record, not a score: no streaks, no totals, no comparison between days. And still no
 * schedule — a stop appears here only once it has occurred.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenStop: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val records by repo.stopRecordsFlow.collectAsState(initial = emptyList())

    // One directory listing rather than a stat per row.
    val withMedia by produceState(initialValue = emptySet<Long>(), records.size) {
        val found = withContext(Dispatchers.IO) { StopMedia.stopsWithMedia(context) }
        value = found
    }

    val dayFormat = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        ScreenHeader(title = "History", onBack = onBack)

        if (records.isEmpty()) {
            Hint("No stops yet. The first one will appear here after it has happened.")
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            var lastDay: String? = null
            records.forEach { record ->
                val day = dayFormat.format(Date(record.atMs))
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { DayHeader(day) }
                }
                item(key = record.stopId) {
                    StopRow(
                        record = record,
                        time = timeFormat.format(Date(record.atMs)),
                        openable = record.noted || record.stopId in withMedia,
                        onClick = { onOpenStop(record.stopId) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = day,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Light,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun StopRow(
    record: StopRecord,
    time: String,
    openable: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor(record)
            )
            preview(record)?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (openable) {
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The first thing written, so a day's rows are distinguishable without opening any of them. */
private fun preview(record: StopRecord): String? {
    val written = listOfNotNull(
        record.movement?.takeIf { it.isNotBlank() },
        record.feeling?.takeIf { it.isNotBlank() },
        record.thinking?.takeIf { it.isNotBlank() },
        record.activity?.takeIf { it.isNotBlank() }
    )
    return when {
        written.isNotEmpty() -> written.joinToString(" · ")
        record.hasVoiceNote -> "Voice note"
        else -> null
    }
}

@Composable
private fun labelColor(record: StopRecord): Color = when {
    record.suppressed -> MaterialTheme.colorScheme.onSurfaceVariant
    record.noted -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.secondary
}
