package com.gstop.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.gstop.schedule.StopService
import com.gstop.schedule.StopSession

/**
 * The stop screen (PRD §6.1): pure black, an enneagram in bright orange stroke, the phrase in
 * green. No timer, no progress indicator, no interactive elements beyond the suppress gesture.
 *
 * The screen shows only *that* a stop is happening. The release comes from the service.
 */
class StopActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The stop ends when the app says so; the back gesture is not a release.
        onBackPressedDispatcher.addCallback(this) { /* deliberately inert */ }

        setContent {
            StopScreen(
                onSuppress = {
                    startService(StopService.suppressIntent(this))
                    finish()
                },
                onReleased = { finish() }
            )
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}

/** Adds an inert back-press consumer without pulling in extra dependencies. */
private fun androidx.activity.OnBackPressedDispatcher.addCallback(
    owner: androidx.lifecycle.LifecycleOwner,
    onBack: () -> Unit
) {
    addCallback(owner, object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = onBack()
    })
}

@Composable
fun StopScreen(onSuppress: () -> Unit, onReleased: () -> Unit) {
    val activeStopId by StopSession.activeStopId.collectAsState()

    LaunchedEffect(activeStopId) {
        if (activeStopId == null) onReleased()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // The single deliberate gesture: a long press suppresses this stop only.
                detectTapGestures(onLongPress = { onSuppress() })
            },
        contentAlignment = Alignment.Center
    ) {
        Enneagram(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp)
        )
    }
}
