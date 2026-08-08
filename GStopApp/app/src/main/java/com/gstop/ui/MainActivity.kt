package com.gstop.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

enum class Screen { MAIN, SETTINGS, HISTORY }

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
    var screen by remember { mutableStateOf(Screen.MAIN) }
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

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.MAIN -> MainScreen(
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenHistory = { screen = Screen.HISTORY }
            )
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.MAIN })
            Screen.HISTORY -> HistoryScreen(onBack = { screen = Screen.MAIN })
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
