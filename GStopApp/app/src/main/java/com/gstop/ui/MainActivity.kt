package com.gstop.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
            repo.settings()
            if (repo.nextPendingStop(System.currentTimeMillis()) == null) {
                ScheduleManager.regenerate(context, "app opened — no schedule armed")
            }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
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
