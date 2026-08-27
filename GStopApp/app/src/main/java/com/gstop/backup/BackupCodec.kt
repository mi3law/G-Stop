package com.gstop.backup

import com.gstop.data.HistoryEventEntity
import com.gstop.data.ObservationEntity
import com.gstop.data.ScheduledStopEntity
import com.gstop.data.SettingsEntity
import com.gstop.data.SleepWindowEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * The backup payload as JSON — every table, every column, spelled out by hand rather than
 * reflected, so that what a backup contains is exactly what this file says it contains.
 *
 * Deliberately free of Android imports: the whole round trip runs on the JVM under plain JUnit.
 * Decoding fills missing fields with the entity defaults, so a backup written by an older build
 * restores into a newer one; a `format` above [FORMAT] is refused rather than half-read.
 */
object BackupCodec {

    const val FORMAT = 1

    data class Payload(
        val exportedAtMs: Long,
        val settings: SettingsEntity?,
        val sleepWindows: List<SleepWindowEntity>,
        val scheduledStops: List<ScheduledStopEntity>,
        val observations: List<ObservationEntity>,
        val history: List<HistoryEventEntity>
    )

    fun encode(payload: Payload): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("exportedAtMs", payload.exportedAtMs)

        payload.settings?.let { s ->
            root.put("settings", JSONObject().apply {
                put("durationMinSec", s.durationMinSec)
                put("durationMaxSec", s.durationMaxSec)
                put("countMin", s.countMin)
                put("countMax", s.countMax)
                put("minGapMinutes", s.minGapMinutes)
                put("volumeFloorPercent", s.volumeFloorPercent)
                s.commandSoundUri?.let { put("commandSoundUri", it) }
                s.releaseSoundUri?.let { put("releaseSoundUri", it) }
                put("paused", s.paused)
                s.pausedAtMs?.let { put("pausedAtMs", it) }
                put("batteryPromptShown", s.batteryPromptShown)
                put("photosEnabled", s.photosEnabled)
            })
        }

        root.put("sleepWindows", JSONArray().apply {
            payload.sleepWindows.forEach { w ->
                put(JSONObject().apply {
                    put("id", w.id)
                    put("daysMask", w.daysMask)
                    put("startMinuteOfDay", w.startMinuteOfDay)
                    put("endMinuteOfDay", w.endMinuteOfDay)
                    put("enabled", w.enabled)
                })
            }
        })

        root.put("scheduledStops", JSONArray().apply {
            payload.scheduledStops.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("triggerAtMs", s.triggerAtMs)
                    put("durationMs", s.durationMs)
                    put("localDate", s.localDate)
                    put("status", s.status)
                })
            }
        })

        root.put("observations", JSONArray().apply {
            payload.observations.forEach { o ->
                put(JSONObject().apply {
                    put("stopId", o.stopId)
                    put("endedAtMs", o.endedAtMs)
                    o.movement?.let { put("movement", it) }
                    o.feeling?.let { put("feeling", it) }
                    o.thinking?.let { put("thinking", it) }
                    o.activity?.let { put("activity", it) }
                    put("hasVoiceNote", o.hasVoiceNote)
                    o.notedAtMs?.let { put("notedAtMs", it) }
                })
            }
        })

        root.put("history", JSONArray().apply {
            payload.history.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("atMs", e.atMs)
                    put("type", e.type)
                    e.detail?.let { put("detail", it) }
                })
            }
        })

        return root.toString()
    }

    fun decode(json: String): Payload {
        val root = JSONObject(json)
        val format = root.optInt("format", Int.MAX_VALUE)
        require(format <= FORMAT) {
            "This backup was written by a newer version of G-Stop (format $format)."
        }

        val defaults = SettingsEntity()
        val settings = root.optJSONObject("settings")?.let { s ->
            SettingsEntity(
                id = 1,
                durationMinSec = s.optInt("durationMinSec", defaults.durationMinSec),
                durationMaxSec = s.optInt("durationMaxSec", defaults.durationMaxSec),
                countMin = s.optInt("countMin", defaults.countMin),
                countMax = s.optInt("countMax", defaults.countMax),
                minGapMinutes = s.optInt("minGapMinutes", defaults.minGapMinutes),
                volumeFloorPercent = s.optInt("volumeFloorPercent", defaults.volumeFloorPercent),
                commandSoundUri = s.stringOrNull("commandSoundUri"),
                releaseSoundUri = s.stringOrNull("releaseSoundUri"),
                paused = s.optBoolean("paused", defaults.paused),
                pausedAtMs = s.longOrNull("pausedAtMs"),
                batteryPromptShown = s.optBoolean("batteryPromptShown", defaults.batteryPromptShown),
                photosEnabled = s.optBoolean("photosEnabled", defaults.photosEnabled)
            )
        }

        val sleepWindows = root.optJSONArray("sleepWindows").objects().map { w ->
            SleepWindowEntity(
                id = w.getLong("id"),
                daysMask = w.getInt("daysMask"),
                startMinuteOfDay = w.getInt("startMinuteOfDay"),
                endMinuteOfDay = w.getInt("endMinuteOfDay"),
                enabled = w.optBoolean("enabled", true)
            )
        }

        val scheduledStops = root.optJSONArray("scheduledStops").objects().map { s ->
            ScheduledStopEntity(
                id = s.getLong("id"),
                triggerAtMs = s.getLong("triggerAtMs"),
                durationMs = s.getLong("durationMs"),
                localDate = s.getString("localDate"),
                status = s.getString("status")
            )
        }

        val observations = root.optJSONArray("observations").objects().map { o ->
            ObservationEntity(
                stopId = o.getLong("stopId"),
                endedAtMs = o.getLong("endedAtMs"),
                movement = o.stringOrNull("movement"),
                feeling = o.stringOrNull("feeling"),
                thinking = o.stringOrNull("thinking"),
                activity = o.stringOrNull("activity"),
                hasVoiceNote = o.optBoolean("hasVoiceNote", false),
                notedAtMs = o.longOrNull("notedAtMs")
            )
        }

        val history = root.optJSONArray("history").objects().map { e ->
            HistoryEventEntity(
                id = e.getLong("id"),
                atMs = e.getLong("atMs"),
                type = e.getString("type"),
                detail = e.stringOrNull("detail")
            )
        }

        return Payload(
            exportedAtMs = root.optLong("exportedAtMs", 0L),
            settings = settings,
            sleepWindows = sleepWindows,
            scheduledStops = scheduledStops,
            observations = observations,
            history = history
        )
    }

    // opt* with a null fallback turns JSON null into the string "null" on some org.json
    // versions; isNull sidesteps the whole question.
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONArray?.objects(): List<JSONObject> =
        this?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList()
}
