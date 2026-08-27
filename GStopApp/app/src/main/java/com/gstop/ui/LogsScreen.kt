package com.gstop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gstop.data.HistoryEventEntity
import com.gstop.data.HistoryType
import com.gstop.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How many events survive a manual clear. */
private const val KEEP_ON_CLEAR = 10

/**
 * The unbounded local log (PRD §6.3). Pause and resume are listed alongside stops on purpose:
 * the point is honest self-observation of one's own use of the escape hatch, not a score.
 *
 * Nothing prunes the log on its own; clearing is always a deliberate act.
 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val scope = rememberCoroutineScope()
    val events by repo.historyFlow.collectAsState(initial = emptyList())
    var confirmingClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        ScreenHeader(title = "Logs", onBack = onBack) {
            TextButton(
                onClick = { confirmingClear = true },
                enabled = events.size > KEEP_ON_CLEAR
            ) { Text("Clear") }
        }

        if (events.isEmpty()) {
            Hint("Nothing logged yet.")
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(events, key = { it.id }) { event ->
                LogRow(event)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear the log?") },
            text = {
                Text(
                    "The $KEEP_ON_CLEAR most recent events are kept. Everything older is deleted " +
                        "and cannot be recovered."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.trimHistory(KEEP_ON_CLEAR) }
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun LogRow(event: HistoryEventEntity) {
    val formatter = remember {
        SimpleDateFormat("EEE d MMM  HH:mm:ss", Locale.getDefault())
    }
    val type = runCatching { HistoryType.valueOf(event.type) }.getOrNull()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label(type, event.type),
                style = MaterialTheme.typography.bodyLarge,
                color = colorFor(type)
            )
            event.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = formatter.format(Date(event.atMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun label(type: HistoryType?, raw: String): String = when (type) {
    HistoryType.STOP_FIRED -> "Stop"
    HistoryType.STOP_SUPPRESSED -> "Stop suppressed"
    HistoryType.STOP_NOTED -> "Stop, noted"
    HistoryType.STOP_MISSED -> "Stop missed"
    HistoryType.PAUSED -> "Paused"
    HistoryType.RESUMED -> "Resumed"
    HistoryType.SCHEDULE_REGENERATED -> "Schedule regenerated"
    HistoryType.BOOT -> "Device restarted"
    HistoryType.TIME_CHANGED -> "Clock or timezone changed"
    HistoryType.SETTINGS_CHANGED -> "Settings changed"
    HistoryType.PERMISSION_WARNING -> "Permission warning"
    HistoryType.BACKUP -> "Backup"
    null -> raw
}

@Composable
private fun colorFor(type: HistoryType?): Color = when (type) {
    HistoryType.STOP_FIRED -> MaterialTheme.colorScheme.secondary
    HistoryType.STOP_NOTED -> MaterialTheme.colorScheme.primary
    HistoryType.STOP_SUPPRESSED, HistoryType.STOP_MISSED -> MaterialTheme.colorScheme.primary
    HistoryType.PAUSED, HistoryType.RESUMED -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
