package com.gstop.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The observation window, entered straight from a stop or from the notice the service leaves
 * behind. Separate from [MainActivity] on purpose: the five minutes after a stop are their own
 * thing, not a page of the app one happens to be on.
 */
class ObservationActivity : ComponentActivity() {

    private var stopId by mutableLongStateOf(NO_STOP)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        stopId = intent.getLongExtra(EXTRA_STOP_ID, NO_STOP)
        if (stopId == NO_STOP) {
            finish()
            return
        }
        dismissNotice()

        setContent {
            GStopTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                    ) {
                        ObservationScreen(
                            stopId = stopId,
                            title = "Observation",
                            backLabel = "Done",
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.getLongExtra(EXTRA_STOP_ID, NO_STOP)
        if (incoming != NO_STOP) {
            stopId = incoming
            dismissNotice()
        }
    }

    override fun onResume() {
        super.onResume()
        visibleStopId = stopId
        dismissNotice()
    }

    override fun onPause() {
        super.onPause()
        if (visibleStopId == stopId) visibleStopId = NO_STOP
    }

    /** Opening the window consumes the invitation. */
    private fun dismissNotice() {
        getSystemService(NotificationManager::class.java)?.cancel(OBSERVE_NOTIF_ID)
    }

    companion object {
        const val EXTRA_STOP_ID = "stop_id"

        /** The id of the "observe this stop" notice, cancelled once the window is opened. */
        const val OBSERVE_NOTIF_ID = 43

        private const val NO_STOP = -1L

        @Volatile private var visibleStopId: Long = NO_STOP

        /** No point inviting the user into a screen they are already looking at. */
        fun isShowing(stopId: Long): Boolean = visibleStopId == stopId

        /**
         * Claimed by the stop screen the instant before it hands over, so the service does not
         * post a notice into the half-second before this activity resumes.
         */
        fun claim(stopId: Long) {
            visibleStopId = stopId
        }

        fun intent(context: Context, stopId: Long): Intent =
            Intent(context, ObservationActivity::class.java).apply {
                putExtra(EXTRA_STOP_ID, stopId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}
