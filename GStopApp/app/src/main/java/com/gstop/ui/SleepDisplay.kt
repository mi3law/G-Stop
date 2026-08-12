package com.gstop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.gstop.core.SleepClock
import com.gstop.data.SleepWindowEntity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * How sleep is *said*. Shared by the main screen and the home-screen widget so the two can never
 * word the same fact differently.
 *
 * Sleep is the one thing about the future the app may show: a sleep window is the user's own
 * standing instruction, not a drawn schedule, so naming the hour it releases gives nothing away.
 */

/** What the main screen has to show about sleep at this moment. */
data class SleepIndication(val asleep: Boolean, val untilText: String?) {
    companion object {
        val AWAKE = SleepIndication(asleep = false, untilText = null)
    }
}

/** At most this long between recomputations; a boundary nearer than this is landed on exactly. */
private const val TICK_MS = 60_000L

/**
 * The current sleep state, recomputed on the minute and exactly on the boundary — so the line
 * appears and disappears while the screen is open, without the user reopening anything.
 *
 * The wording is produced here rather than at the call site on purpose: "07:00 tomorrow" becomes
 * "07:00" when midnight passes, and only a value that changes with the clock makes that happen.
 */
@Composable
fun rememberSleepIndication(windows: List<SleepWindowEntity>): SleepIndication {
    val domain = remember(windows) { windows.map { it.toDomain() } }
    return produceState(SleepIndication.AWAKE, domain) {
        while (true) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val status = SleepClock.status(domain, now, zone)
            value = SleepIndication(
                asleep = status.asleep,
                untilText = status.changesAtMs
                    ?.takeIf { status.asleep }
                    ?.let { sleepUntilText(it, now, zone) }
            )
            val changesAt = status.changesAtMs
            delay(if (changesAt != null) (changesAt - now).coerceIn(1_000L, TICK_MS) else TICK_MS)
        }
    }.value
}

/**
 * "07:00", "07:00 tomorrow", "07:00 on Saturday" — the end of a sleep window, said the way a
 * person would say it.
 */
fun sleepUntilText(untilMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val until = Instant.ofEpochMilli(untilMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val clock = String.format(Locale.getDefault(), "%02d:%02d", until.hour, until.minute)
    return when (until.toLocalDate()) {
        today -> clock
        today.plusDays(1) -> "$clock tomorrow"
        else -> "$clock on " + until.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }
}
