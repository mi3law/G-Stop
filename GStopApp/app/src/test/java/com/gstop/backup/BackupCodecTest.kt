package com.gstop.backup

import com.gstop.data.HistoryEventEntity
import com.gstop.data.ObservationEntity
import com.gstop.data.ScheduledStopEntity
import com.gstop.data.SettingsEntity
import com.gstop.data.SleepWindowEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val fullPayload = BackupCodec.Payload(
        exportedAtMs = 1_756_300_000_000,
        settings = SettingsEntity(
            durationMinSec = 30,
            durationMaxSec = 90,
            countMin = 1,
            countMax = 4,
            minGapMinutes = 60,
            volumeFloorPercent = 75,
            commandSoundUri = "content://media/command",
            releaseSoundUri = null,
            paused = true,
            pausedAtMs = 1_756_200_000_000,
            batteryPromptShown = true,
            photosEnabled = false
        ),
        sleepWindows = listOf(
            SleepWindowEntity(
                id = 3,
                daysMask = 0b1010101,
                startMinuteOfDay = 23 * 60,
                endMinuteOfDay = 7 * 60,
                enabled = false
            )
        ),
        scheduledStops = listOf(
            ScheduledStopEntity(
                id = 42,
                triggerAtMs = 1_756_250_000_000,
                durationMs = 45_000,
                localDate = "2026-08-27",
                status = "FIRED"
            )
        ),
        observations = listOf(
            ObservationEntity(
                stopId = 42,
                endedAtMs = 1_756_250_045_000,
                movement = "typing, then still",
                feeling = "tight chest",
                thinking = null,
                activity = "writing an email",
                hasVoiceNote = true,
                notedAtMs = 1_756_250_100_000
            )
        ),
        history = listOf(
            HistoryEventEntity(id = 7, atMs = 1_756_250_045_000, type = "STOP_FIRED", detail = null)
        )
    )

    @Test
    fun `round trip preserves every field`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(fullPayload))
        assertEquals(fullPayload, decoded)
    }

    @Test
    fun `round trip of an empty install`() {
        val empty = BackupCodec.Payload(
            exportedAtMs = 5,
            settings = null,
            sleepWindows = emptyList(),
            scheduledStops = emptyList(),
            observations = emptyList(),
            history = emptyList()
        )
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
    }

    @Test
    fun `nulls survive as nulls, not the string null`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(fullPayload))
        assertNull(decoded.settings!!.releaseSoundUri)
        assertNull(decoded.observations.single().thinking)
        assertNull(decoded.history.single().detail)
    }

    @Test
    fun `a settings field an older build never wrote falls back to its default`() {
        val json = JSONObject(BackupCodec.encode(fullPayload))
        json.getJSONObject("settings").remove("photosEnabled")
        json.getJSONObject("settings").remove("volumeFloorPercent")
        val decoded = BackupCodec.decode(json.toString())
        assertEquals(SettingsEntity().photosEnabled, decoded.settings!!.photosEnabled)
        assertEquals(SettingsEntity().volumeFloorPercent, decoded.settings!!.volumeFloorPercent)
    }

    @Test
    fun `a table an older build never wrote decodes as empty`() {
        val json = JSONObject(BackupCodec.encode(fullPayload))
        json.remove("observations")
        assertTrue(BackupCodec.decode(json.toString()).observations.isEmpty())
    }

    @Test
    fun `a newer format is refused rather than half-read`() {
        val json = JSONObject(BackupCodec.encode(fullPayload))
        json.put("format", BackupCodec.FORMAT + 1)
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(json.toString())
        }
    }

    @Test
    fun `text with quotes, commas and newlines survives`() {
        val awkward = fullPayload.copy(
            observations = listOf(
                fullPayload.observations.single().copy(
                    movement = "she said \"stop\",\nthen a comma, and a tab\there"
                )
            )
        )
        assertEquals(awkward, BackupCodec.decode(BackupCodec.encode(awkward)))
    }
}
