package com.gstop.schedule

import com.gstop.media.PhotoSlot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process handle on the stop currently in progress, so the stop screen can appear and
 * disappear in step with the service that owns the sound and the timing.
 *
 * The screen deliberately learns only *that* a stop is running — never how long it will last.
 * That is also why the three photographs are *requested* from here rather than timed by the
 * screen: the service knows the beginning, the middle and the end; the screen only knows that
 * one of them has arrived.
 */
object StopSession {

    private val _activeStopId = MutableStateFlow<Long?>(null)

    /** Non-null while a stop is in progress. */
    val activeStopId: StateFlow<Long?> = _activeStopId

    /**
     * Replayed, because the stop screen starts collecting a moment after the stop begins and a
     * missed beginning is worse than a slightly late one. Requests carry the time they were made
     * so the screen can discard one it is too late to honour.
     */
    private val _captures = MutableSharedFlow<CaptureRequest>(
        replay = PhotoSlot.entries.size,
        extraBufferCapacity = 4
    )
    val captures: SharedFlow<CaptureRequest> = _captures

    data class CaptureRequest(val stopId: Long, val slot: PhotoSlot, val atMs: Long)

    private val _photosEnabled = MutableStateFlow(false)

    /**
     * Whether this stop photographs itself. Carried here rather than read from the database by the
     * stop screen, so the screen never opens the camera for a stop that will not use it.
     */
    val photosEnabled: StateFlow<Boolean> = _photosEnabled

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun begin(stopId: Long, photosEnabled: Boolean) {
        // A new stop must not inherit the previous one's unclaimed requests.
        _captures.resetReplayCache()
        _photosEnabled.value = photosEnabled
        _activeStopId.value = stopId
    }

    fun requestCapture(stopId: Long, slot: PhotoSlot) {
        _captures.tryEmit(CaptureRequest(stopId, slot, System.currentTimeMillis()))
    }

    fun end(stopId: Long) {
        if (_activeStopId.value == stopId) _activeStopId.value = null
    }

    fun endAll() {
        _activeStopId.value = null
    }
}
