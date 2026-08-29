package com.gstop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gstop.BuildConfig
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
 * The main screen shows practice *state* — active, asleep or paused — and nothing about the
 * schedule. No next-stop time, no count, no countdown: the drawn schedule is never displayed.
 *
 * Sleep is the one piece of the future it does name, and it is not a leak: a sleep window is the
 * user's own standing instruction, so the hour it releases was always theirs.
 *
 * The controls sit on the bottom edge, within thumb reach; everything above them scrolls.
 */
@Composable
fun MainScreen(onOpenSettings: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { Repository.get(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState(initial = SettingsEntity())
    val windows by repo.sleepWindowsFlow.collectAsState(initial = emptyList())
    val system = rememberSupersessionState()

    // Pausing outranks sleeping: a paused practice is still paused when morning comes.
    val sleep = rememberSleepIndication(windows)
    val asleep = sleep.asleep && !settings.paused

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
                text = when {
                    settings.paused -> "Paused"
                    asleep -> "Asleep"
                    else -> "Active"
                },
                style = MaterialTheme.typography.titleLarge,
                color = if (settings.paused || asleep) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    // Says what the state means for stops, like the other two. What it used to
                    // say — that paused time does not exist on the active timeline — was true,
                    // and belongs in Settings under the minimum gap, where that idea is in
                    // context and has something to explain.
                    settings.paused -> settings.pausedAtMs?.let {
                        val at = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
                            .format(Date(it))
                        "Paused since $at — no stops until you resume."
                    } ?: "No stops until you resume."
                    asleep -> sleep.untilText
                        ?.let { "A sleep window is running — no stops until $it." }
                        ?: "A sleep window is running — no stops."
                    else -> "Stops will come without warning."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            SupersessionWarnings(system, photosWanted = settings.photosEnabled)

            Spacer(Modifier.height(24.dp))
        }

        Button(
            // The same one path the home-screen widget takes, so the two can never disagree
            // about what a pause is.
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { ScheduleManager.togglePaused(context) }
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
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                Text("History")
            }
        }

        BuildFooter()
    }
}

/** Where this build's commit can be read. */
private const val COMMIT_URL = "https://github.com/mi3law/G-Stop/commit/"

/**
 * Which build this is, and the commit behind it — quiet enough to ignore, there when a version
 * has to be pinned down. Tapping it opens that commit on GitHub.
 *
 * That is the only thing in the app that reaches the network, and it does so the way any app
 * hands a URL to a browser: G-Stop itself still holds no network permission, makes no connection
 * of its own, and nothing about the practice goes with it.
 *
 * A debug build's version already carries the sha (build.gradle.kts appends it), so it is not
 * printed twice.
 */
@Composable
private fun BuildFooter() {
    val context = LocalContext.current
    val sha = BuildConfig.GIT_SHA
    val known = sha != "unknown"

    val label = buildString {
        append(BuildConfig.VERSION_NAME)
        if (known && !BuildConfig.VERSION_NAME.endsWith(sha)) append(" · ").append(sha)
        if (BuildConfig.GIT_DIRTY) append(" · modified")
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!known) Modifier else Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(COMMIT_URL + sha))
                        )
                    }
                }
            )
            .padding(top = 12.dp)
    )
}

/** Hints the app cannot verify live here, remembered once the user says they are handled. */
private const val UI_PREFS = "ui_hints"
private const val KEY_MIUI_POPUP_HANDLED = "miui_popup_handled"

@Composable
private fun SupersessionWarnings(state: SupersessionState, photosWanted: Boolean) {
    val context = LocalContext.current
    val cameraNeeded = photosWanted && !state.camera

    // Not part of SupersessionState: MIUI's switch cannot be read back, so this hint's
    // lifecycle is "shown until the user says it is handled", not "shown while false".
    var miuiHandled by remember {
        mutableStateOf(
            context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MIUI_POPUP_HANDLED, false)
        )
    }
    val miuiHintWanted = SystemState.isXiaomi && !miuiHandled

    if (state.allGood && !cameraNeeded && !miuiHintWanted) {
        Text(
            text = "Supersession checks passed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the card re-reads its state when the activity resumes */ }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.exactAlarms) {
            WarningCard(
                title = "Exact alarms are not permitted",
                body = "Stops will be delivered inexactly and may be delayed. This is the one " +
                    "permission the practice cannot do without.",
                actionLabel = "Grant"
            ) { SystemState.exactAlarmSettings(context)?.let { context.startActivity(it) } }
        }
        if (!state.canOverlay) {
            WarningCard(
                title = "The stop screen cannot come to the front",
                body = "Android shows a full-screen alarm only when the phone is locked or the " +
                    "screen is off. While you are using the phone, a stop will arrive as a " +
                    "notification banner over whatever you are doing instead of taking the " +
                    "screen. Allow \"display over other apps\" to fix that.",
                actionLabel = "Allow"
            ) { context.startActivity(SystemState.overlaySettings(context)) }
        }
        if (miuiHintWanted) {
            WarningCard(
                title = "Xiaomi hides one more switch",
                body = "Even with \"display over other apps\" allowed, this phone keeps the " +
                    "stop screen behind its own switch. In G-Stop's app settings, under Other " +
                    "permissions, enable \"Display pop-up windows while running in the " +
                    "background\" — and \"Show on lock screen\" while you are there. Android " +
                    "gives the app no way to check either, so press Done once they are on.",
                actionLabel = "Open settings",
                dismissLabel = "Done",
                onDismiss = {
                    context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_MIUI_POPUP_HANDLED, true).apply()
                    miuiHandled = true
                }
            ) {
                // MIUI's editor where it exists, the stock app page where a ROM moved it.
                runCatching { context.startActivity(SystemState.miuiPermissionEditor(context)) }
                    .onFailure { context.startActivity(SystemState.appDetailsSettings(context)) }
            }
        }
        if (!state.fullScreenIntent) {
            WarningCard(
                title = "Full-screen alarms are not permitted",
                body = "Without this the stop screen cannot take over a locked screen either. " +
                    "The sound will still play.",
                actionLabel = "Allow"
            ) { SystemState.fullScreenIntentSettings(context)?.let { context.startActivity(it) } }
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
        if (cameraNeeded) {
            WarningCard(
                title = "The camera is not permitted",
                body = "Stops will happen exactly as before, but nothing will be photographed " +
                    "and observations will have no pictures. Turn stop photos off in Settings if " +
                    "that is what you want.",
                actionLabel = "Allow"
            ) { cameraLauncher.launch(android.Manifest.permission.CAMERA) }
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    body: String,
    actionLabel: String,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
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
            Row {
                TextButton(onClick = onAction) { Text(actionLabel) }
                if (dismissLabel != null && onDismiss != null) {
                    TextButton(onClick = onDismiss) { Text(dismissLabel) }
                }
            }
        }
    }
}
