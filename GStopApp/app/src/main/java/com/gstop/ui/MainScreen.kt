package com.gstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gstop.data.HistoryType
import com.gstop.data.Repository
import com.gstop.data.SettingsEntity
import com.gstop.schedule.ScheduleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The main screen shows practice *state* — active or paused — and nothing about the schedule.
 * No next-stop time, no count, no countdown: the drawn schedule is never displayed.
 *
 * The controls sit on the bottom edge, within thumb reach; everything above them scrolls.
 */
@Composable
fun MainScreen(onOpenSettings: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { Repository.get(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState(initial = SettingsEntity())
    val system = rememberSupersessionState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "G-Stop",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(24.dp))

            Enneagram(
                modifier = Modifier.size(190.dp),
                phrase = null
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = if (settings.paused) "Paused" else "Active",
                style = MaterialTheme.typography.titleLarge,
                color = if (settings.paused) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (settings.paused) {
                    val since = settings.pausedAtMs?.let {
                        " since " +
                            SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(it))
                    } ?: ""
                    "Paused time does not exist on the active timeline$since."
                } else {
                    "Stops will come without warning."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            SupersessionWarnings(system)

            Spacer(Modifier.height(24.dp))
        }

        Button(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        val current = repo.settings()
                        val nowPaused = !current.paused
                        repo.saveSettings(
                            current.copy(
                                paused = nowPaused,
                                pausedAtMs = if (nowPaused) now else null
                            )
                        )
                        repo.log(
                            if (nowPaused) HistoryType.PAUSED else HistoryType.RESUMED,
                            now
                        )
                        ScheduleManager.regenerate(
                            context,
                            if (nowPaused) "paused" else "resumed"
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (settings.paused) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (settings.paused) MaterialTheme.colorScheme.onSecondary
                else MaterialTheme.colorScheme.onSurface
            )
        ) {
            Text(
                text = if (settings.paused) "Resume" else "Pause",
                fontSize = 18.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("Settings")
            }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) {
                Text("Logs")
            }
        }
    }
}

@Composable
private fun SupersessionWarnings(state: SupersessionState) {
    val context = LocalContext.current

    if (state.allGood) {
        Text(
            text = "Supersession checks passed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.exactAlarms) {
            WarningCard(
                title = "Exact alarms are not permitted",
                body = "Stops will be delivered inexactly and may be delayed. This is the one " +
                    "permission the practice cannot do without.",
                actionLabel = "Grant"
            ) { SystemState.exactAlarmSettings(context)?.let { context.startActivity(it) } }
        }
        if (!state.notifications) {
            WarningCard(
                title = "Notifications are blocked",
                body = "Without them the stop screen cannot take over a locked screen. The sound " +
                    "will still play.",
                actionLabel = "Allow"
            ) { context.startActivity(SystemState.appNotificationSettings(context)) }
        }
        if (!state.batteryUnrestricted) {
            WarningCard(
                title = "Battery optimisation is active",
                body = "Exempt G-Stop so the schedule survives deep idle.",
                actionLabel = "Exempt"
            ) { context.startActivity(SystemState.batteryOptimizationSettings(context)) }
        }
        if (!state.dndAllowsAlarms) {
            WarningCard(
                title = "Do Not Disturb is set to total silence",
                body = "Total silence suppresses alarms too. Allow alarms in your DND settings.",
                actionLabel = "DND access"
            ) { context.startActivity(SystemState.dndAccessSettings()) }
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
