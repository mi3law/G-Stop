package com.gstop.backup

import com.gstop.data.StopRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CsvExportTest {

    private val zone = ZoneId.of("Asia/Kuwait")

    /** 2026-08-27 13:00 in Kuwait, as epoch millis. */
    private val onePm: Long =
        ZonedDateTime.of(2026, 8, 27, 13, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun record(
        stopId: Long,
        atMs: Long,
        status: String = "FIRED",
        movement: String? = null,
        feeling: String? = null,
        thinking: String? = null,
        activity: String? = null,
        hasVoiceNote: Boolean = false
    ) = StopRecord(
        stopId = stopId,
        atMs = atMs,
        status = status,
        movement = movement,
        feeling = feeling,
        thinking = thinking,
        activity = activity,
        hasVoiceNote = hasVoiceNote,
        endedAtMs = null
    )

    @Test
    fun `empty history is just the header`() {
        assertEquals(
            "\uFEFFDate,Time,Status,Movement,Feeling,Thinking,Activity,Voice note\r\n",
            CsvExport.build(emptyList(), zone)
        )
    }

    @Test
    fun `rows come out oldest first even though the query is newest first`() {
        val later = onePm + 3_600_000L
        val csv = CsvExport.build(
            listOf(record(2, later), record(1, onePm)),
            zone
        )
        val lines = csv.trimEnd().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("2026-08-27,13:00"))
        assertTrue(lines[2].startsWith("2026-08-27,14:00"))
    }

    @Test
    fun `an unnoted fired stop is a plain row`() {
        val csv = CsvExport.build(listOf(record(1, onePm)), zone)
        assertEquals("2026-08-27,13:00,Stop,,,,,", csv.trimEnd().lines()[1])
    }

    @Test
    fun `the noted label's own comma is quoted`() {
        val csv = CsvExport.build(
            listOf(record(1, onePm, feeling = "calm", hasVoiceNote = true)),
            zone
        )
        assertEquals("2026-08-27,13:00,\"Stop, noted\",,calm,,,yes", csv.trimEnd().lines()[1])
    }

    @Test
    fun `quotes, commas and line breaks in an observation are escaped`() {
        val csv = CsvExport.build(
            listOf(
                record(
                    1,
                    onePm,
                    movement = "froze, mid-reach",
                    thinking = "\"not now\"\nthen nothing"
                )
            ),
            zone
        )
        val body = csv.substringAfter("\r\n")
        assertEquals(
            "2026-08-27,13:00,\"Stop, noted\",\"froze, mid-reach\",," +
                "\"\"\"not now\"\"\nthen nothing\",,\r\n",
            body
        )
    }

    @Test
    fun `a suppressed stop keeps its label`() {
        val csv = CsvExport.build(
            listOf(record(1, onePm, status = "SUPPRESSED")),
            zone
        )
        assertTrue(csv.contains("Stop suppressed"))
    }
}
