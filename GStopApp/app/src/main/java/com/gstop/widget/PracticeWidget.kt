package com.gstop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.gstop.R
import com.gstop.data.Repository
import com.gstop.schedule.async

/**
 * The practice on the home screen, as one icon: the enneagram in orange while stops are coming,
 * gone out to grey while they are not. Tapping it is the pause; tapping it twice opens the app.
 *
 * PRD §2 asks for pause/resume to be reachable in one tap from outside the app if it is cheap to
 * build; this is that, at the size of an app icon. It says nothing else, because there is nothing
 * else it could say without leaking the schedule onto a screen that is read a hundred times a day.
 *
 * Neither gesture is one the platform offers. A widget has exactly one hook — a click — since the
 * launcher keeps long-press for picking widgets up and RemoteViews has no long-click regardless.
 * Both taps therefore go to [WidgetTapActivity], which tells them apart itself.
 */
class PracticeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val appContext = context.applicationContext
        async { refresh(appContext) }
    }

    companion object {
        private const val REQ_TAP = 2001

        /** Redraws every placed widget from current state. A no-op when none are placed. */
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PracticeWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, render(context))
        }

        /**
         * Sleep is deliberately not drawn here. Orange means the practice is running, and during a
         * sleep window it is: nothing was paused, and stops resume of their own accord. The main
         * screen is where the hour it releases is named.
         */
        private suspend fun render(context: Context): RemoteViews {
            val paused = Repository.get(context).settings().paused
            val views = RemoteViews(context.packageName, R.layout.widget_icon)

            views.setImageViewResource(
                R.id.widget_icon,
                if (paused) R.drawable.widget_enneagram_paused else R.drawable.widget_enneagram
            )
            views.setContentDescription(
                R.id.widget_icon,
                context.getString(
                    if (paused) R.string.widget_a11y_paused else R.string.widget_a11y_active
                )
            )
            views.setOnClickPendingIntent(R.id.widget_icon, tapPendingIntent(context))
            return views
        }

        private fun tapPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQ_TAP,
                Intent(context, WidgetTapActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
