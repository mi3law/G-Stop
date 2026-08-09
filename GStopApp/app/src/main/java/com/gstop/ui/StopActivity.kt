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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gstop.media.SelfieCapture
import com.gstop.schedule.StopService
import com.gstop.schedule.StopSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The stop screen (PRD §6.1): pure black, an enneagram in bright orange stroke, the phrase in
 * green. No timer, no progress indicator, no interactive elements beyond the suppress gesture.
 *
 * The screen shows only *that* a stop is happening. The release comes from the service.
 *
 * It is also where the three photographs are taken, because the camera is a while-in-use
 * permission and a visible activity is the one place the app reliably holds it. Nothing about the
 * capture is visible: no preview, no shutter, no change to the black.
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
                onReleased = { stopId ->
                    if (stopId != null) openObservation(stopId)
                    finish()
                }
            )
        }
    }

    /**
     * Straight into the observation, but only if the phone is actually in the user's hands. Behind
     * a locked screen the notification the service posts is the way back in — putting a screen
     * full of one's own notes on the far side of an unlock is not a courtesy.
     */
    private fun openObservation(stopId: Long) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return
        ObservationActivity.claim(stopId)
        runCatching { startActivity(ObservationActivity.intent(this, stopId)) }
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
fun StopScreen(onSuppress: () -> Unit, onReleased: (Long?) -> Unit) {
    // The full-screen intent is posted a moment before the service announces the stop, so an
    // empty session at first frame means "not yet", not "already over".
    LaunchedEffect(Unit) {
        val stopId = withTimeoutOrNull(SESSION_GRACE_MS) {
            StopSession.activeStopId.first { it != null }
        }
        if (stopId == null) {
            onReleased(null)
            return@LaunchedEffect
        }
        StopSession.activeStopId.first { it == null }
        onReleased(stopId)
    }

    SelfieCaptureEffect()

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

/** A capture request the screen took too long to reach is dropped rather than taken late. */
private const val CAPTURE_STALE_MS = 6_000L

/** How long the stop screen waits for the service to announce the stop before giving up. */
private const val SESSION_GRACE_MS = 5_000L

/**
 * Opens the front camera for the length of the stop and honours the beginning, middle and end
 * requests the service makes. Silent about failure: a refused camera costs the record, never
 * the stop.
 */
@Composable
private fun SelfieCaptureEffect() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val photosEnabled by StopSession.photosEnabled.collectAsState()

    val wanted = photosEnabled &&
        SelfieCapture.hasPermission(context) &&
        SelfieCapture.deviceHasFrontCamera(context)

    if (wanted) {
        val capture = remember { SelfieCapture(context) }

        DisposableEffect(capture) {
            onDispose { capture.release() }
        }

        LaunchedEffect(capture) {
            if (!capture.bind(owner)) return@LaunchedEffect
            StopSession.captures.collect { request ->
                if (System.currentTimeMillis() - request.atMs <= CAPTURE_STALE_MS) {
                    capture.capture(request.stopId, request.slot)
                }
            }
        }
    }
}
