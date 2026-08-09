package com.gstop.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.gstop.media.SelfieCapture
import com.gstop.schedule.ScheduleManager

/**
 * Everything the app needs the OS to allow for a stop to actually supersede. Anything false here
 * is surfaced on the main screen — the app degrades loudly rather than failing silently (PRD §3).
 */
data class SupersessionState(
    val exactAlarms: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val dndAllowsAlarms: Boolean,
    val dndPolicyAccess: Boolean,
    /**
     * "Display over other apps". The full-screen intent is honoured only when the screen is off
     * or the device is locked; while the phone is in the user's hands Android downgrades it to a
     * banner. This is what lets the app raise the stop screen itself instead.
     */
    val canOverlay: Boolean,
    /** Android 14 gates full-screen intents behind their own per-app toggle. */
    val fullScreenIntent: Boolean,
    /** Not a supersession matter: without it a stop still happens, it just goes unphotographed. */
    val camera: Boolean
) {
    val allGood: Boolean
        get() = exactAlarms && notifications && batteryUnrestricted && dndAllowsAlarms &&
            canOverlay && fullScreenIntent
}

object SystemState {

    fun read(context: Context): SupersessionState = SupersessionState(
        exactAlarms = ScheduleManager.canScheduleExactAlarms(context),
        notifications = hasNotificationPermission(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(context),
        dndAllowsAlarms = dndAllowsAlarms(context),
        dndPolicyAccess = hasDndPolicyAccess(context),
        canOverlay = canDrawOverlays(context),
        fullScreenIntent = canUseFullScreenIntent(context),
        camera = SelfieCapture.hasPermission(context)
    )

    /**
     * Grants the app a background activity start, which is the only way to put the stop screen in
     * front of an app the user is actively using.
     */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun hasDndPolicyAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /**
     * True when the current DND state would let an alarm through. Without policy access the
     * filter level is not readable, so the optimistic answer is given — alarm-stream audio passes
     * DND under the default alarms exception anyway.
     */
    fun dndAllowsAlarms(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        val filter = nm.currentInterruptionFilter
        return when (filter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> false // total silence blocks alarms
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> true
            else -> true
        }
    }

    // --- intents that take the user to the relevant system page ---

    fun exactAlarmSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else null

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryOptimizationSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    fun dndAccessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    fun overlaySettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun fullScreenIntentSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${context.packageName}")
            )
        } else null

    fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
