package com.gstop.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gstop.BuildConfig
import com.gstop.backup.BackupManager
import com.gstop.data.Repository
import com.gstop.schedule.ScheduleManager
import com.gstop.schedule.StopService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A shallow stack rather than a flat set of screens: Logs now hangs off Settings, and a stop's
 * observation hangs off History, so Back has to mean "one step up", not "home".
 */
sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data object Logs : Screen
    data object History : Screen
    data class Observation(val stopId: Long) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StopService.ensureChannels(this)

        setContent {
            GStopTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GStopApp()
                }
            }
        }
    }

    companion object {
        /**
         * The way in from outside the app — the widget's double tap, the system's alarm entry.
         *
         * This is deliberately the launcher's *own* intent rather than one naming MainActivity
         * directly. `FLAG_ACTIVITY_NEW_TASK` reuses an existing task only when the new intent
         * matches the one that started it, and the task was started by the home screen with
         * ACTION_MAIN / CATEGORY_LAUNCHER. A bare component intent does not match, so the task
         * comes forward with a *second* MainActivity stacked on the first — and Back then lands
         * on the home screen again instead of leaving the app.
         *
         * Asking the package manager for the launcher's intent means these entrances behave
         * exactly like tapping the icon, including leaving an open observation where it was.
         */
        fun launchIntent(context: Context): Intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
fun GStopApp() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Main) }
    val screen = stack.last()
    val context = androidx.compose.ui.platform.LocalContext.current

    // First launch: seed defaults and draw today's schedule.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val repo = Repository.get(context)
            // Not while paused: there is nothing to arm, the regeneration would write a log line
            // on every single launch, and it would throw away the schedule the pause set aside.
            if (!repo.settings().paused &&
                repo.nextPendingStop(System.currentTimeMillis()) == null
            ) {
                ScheduleManager.regenerate(context, "app opened — no schedule armed")
            }
            // The third of the daily wake-ups a backup can ride on, after rollover and boot.
            BackupManager.backupIfDue(context)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the main screen re-reads state on resume */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !SystemState.hasNotificationPermission(context)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // The back gesture is navigation, not exit: it walks one step back up the stack.
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    fun open(next: Screen) = stack.add(next)
    fun back() = stack.removeAt(stack.lastIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        if (BuildConfig.DEBUG) DevBuildBanner()

        Box(modifier = Modifier.weight(1f)) {
            when (val current = screen) {
                Screen.Main -> MainScreen(
                    onOpenSettings = { open(Screen.Settings) },
                    onOpenHistory = { open(Screen.History) }
                )
                Screen.Settings -> SettingsScreen(
                    onBack = { back() },
                    onOpenLogs = { open(Screen.Logs) }
                )
                Screen.Logs -> LogsScreen(onBack = { back() })
                Screen.History -> HistoryScreen(
                    onBack = { back() },
                    onOpenStop = { open(Screen.Observation(it)) }
                )
                is Screen.Observation -> ObservationScreen(
                    stopId = current.stopId,
                    title = "Observation",
                    backLabel = "Back",
                    onBack = { back() }
                )
            }
        }
    }
}

/**
 * Only in a debug build, and only in the app's own screens — never on the stop screen, which is
 * black and bears the enneagram and nothing else (PRD §6.1).
 *
 * The dev copy installs beside the real one wearing the same icon, and mistaking the two means
 * reading the wrong practice log, or judging a change against a database that is not the one that
 * matters. A release build has no banner and no way to grow one: this whole function is compiled
 * out of it.
 */
@Composable
private fun DevBuildBanner() {
    Text(
        text = "Dev build",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(vertical = 3.dp)
    )
}

/** Re-reads [SupersessionState] whenever the activity resumes, so returning from a system settings page updates the warnings. */
@Composable
fun rememberSupersessionState(): SupersessionState {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember { mutableStateOf(SystemState.read(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = SystemState.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}
