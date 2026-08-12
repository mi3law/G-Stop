package com.gstop.widget

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewConfiguration
import com.gstop.schedule.ScheduleManager
import com.gstop.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a tap on the widget lands. Once: pause or resume. Twice, quickly: open the app.
 *
 * An invisible activity rather than a broadcast, because the second tap has to *start* the app.
 * A broadcast receiver doing that is a background activity start, which Android blocks unless the
 * app happens to hold "display over other apps"; an activity started by the launcher is in the
 * foreground, and an activity starting another activity is never in question. This one draws
 * nothing and finishes inside onCreate, so what the user sees is the widget the whole time.
 *
 * The single tap has to wait out the double-tap window before acting. It cannot act at once and
 * undo itself on the second tap: pausing regenerates the remainder of the day, so a double tap
 * would silently redraw the schedule on its way to opening the app.
 */
class WidgetTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // elapsedRealtime, not the wall clock: a timezone change mid-gesture is not a double tap.
        val tappedAt = SystemClock.elapsedRealtime()
        val previous = lastTapAt.getAndSet(tappedAt)

        if (tappedAt - previous < doubleTapMs()) {
            // The first tap's pending toggle finds the mark moved and stands down. A third tap in
            // the same flurry lands here too, which is why this does not clear the timestamp.
            startActivity(MainActivity.launchIntent(this))
        } else {
            scope.launch {
                delay(doubleTapMs())
                // Still the most recent tap? Then no second one came, and it was a pause.
                if (lastTapAt.compareAndSet(tappedAt, 0L)) {
                    ScheduleManager.togglePaused(applicationContext)
                }
            }
        }

        finish()
    }

    companion object {
        /** The system's own idea of how long a double tap may take, so this feels like the phone. */
        private fun doubleTapMs(): Long = ViewConfiguration.getDoubleTapTimeout().toLong()

        /** When the widget was last tapped, or 0 once that tap has been spent. */
        private val lastTapAt = AtomicLong(0L)

        /** Outlives the activity, which is gone before the window closes. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
