package com.gstop.schedule

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process handle on the stop currently in progress, so the stop screen can appear and
 * disappear in step with the service that owns the sound and the timing.
 *
 * The screen deliberately learns only *that* a stop is running — never how long it will last.
 */
object StopSession {

    private val _activeStopId = MutableStateFlow<Long?>(null)

    /** Non-null while a stop is in progress. */
    val activeStopId: StateFlow<Long?> = _activeStopId

    fun begin(stopId: Long) {
        _activeStopId.value = stopId
    }

    fun end(stopId: Long) {
        if (_activeStopId.value == stopId) _activeStopId.value = null
    }

    fun endAll() {
        _activeStopId.value = null
    }
}
