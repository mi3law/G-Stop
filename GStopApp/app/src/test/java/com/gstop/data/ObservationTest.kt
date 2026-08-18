package com.gstop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three kinds of stop, and the five-minute window, are derived rather than stored. These are
 * the derivations.
 */
class ObservationTest {

    private fun observation(
        movement: String? = null,
        feeling: String? = null,
        thinking: String? = null,
        activity: String? = null,
        hasVoiceNote: Boolean = false
    ) = ObservationEntity(
        stopId = 1,
        endedAtMs = 0,
        movement = movement,
        feeling = feeling,
        thinking = thinking,
        activity = activity,
        hasVoiceNote = hasVoiceNote
    )

    @Test
    fun `an empty observation is not noted`() {
        assertFalse(observation().isNoted)
    }

    @Test
    fun `whitespace is not an observation`() {
        assertFalse(observation(movement = "   ", feeling = "", thinking = "\n").isNoted)
    }

    @Test
    fun `any one field notes the stop`() {
        assertTrue(observation(movement = "shoulders up").isNoted)
        assertTrue(observation(feeling = "irritable").isNoted)
        assertTrue(observation(thinking = "tomorrow").isNoted)
        assertTrue(observation(activity = "reading at the desk").isNoted)
    }

    @Test
    fun `a voice note alone notes the stop`() {
        assertTrue(observation(hasVoiceNote = true).isNoted)
    }

    @Test
    fun `the window is open for five minutes and not a moment longer`() {
        val ended = 1_000_000L
        val subject = ObservationEntity(stopId = 1, endedAtMs = ended)
        assertTrue(subject.windowOpenAt(ended))
        assertTrue(subject.windowOpenAt(ended + ObservationEntity.WINDOW_MS - 1))
        assertFalse(subject.windowOpenAt(ended + ObservationEntity.WINDOW_MS))
    }

    private fun record(
        status: StopStatus = StopStatus.FIRED,
        movement: String? = null,
        activity: String? = null,
        hasVoiceNote: Boolean = false
    ) = StopRecord(
        stopId = 1,
        atMs = 0,
        status = status.name,
        movement = movement,
        feeling = null,
        thinking = null,
        activity = activity,
        hasVoiceNote = hasVoiceNote,
        endedAtMs = null
    )

    @Test
    fun `a stop that ran and was not written about is just a stop`() {
        assertEquals("Stop", record().label)
    }

    @Test
    fun `a stop with anything written about it is noted`() {
        assertEquals("Stop, noted", record(movement = "still").label)
        assertEquals("Stop, noted", record(activity = "cooking").label)
        assertEquals("Stop, noted", record(hasVoiceNote = true).label)
    }

    @Test
    fun `suppression outranks anything noted about it`() {
        assertEquals(
            "Stop suppressed",
            record(status = StopStatus.SUPPRESSED, movement = "reached for the screen").label
        )
    }
}
