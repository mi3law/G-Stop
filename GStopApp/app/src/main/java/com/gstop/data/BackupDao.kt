package com.gstop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Whole-table reads and writes for backup and restore, kept apart from the working DAOs: nothing
 * here is meant for ordinary use, and a restore's wipe-then-insert must only ever run inside the
 * one transaction the importer opens.
 */
@Dao
interface BackupDao {

    @Query("SELECT * FROM settings") suspend fun allSettings(): List<SettingsEntity>
    @Query("SELECT * FROM sleep_windows") suspend fun allSleepWindows(): List<SleepWindowEntity>
    @Query("SELECT * FROM scheduled_stops") suspend fun allScheduledStops(): List<ScheduledStopEntity>
    @Query("SELECT * FROM observations") suspend fun allObservations(): List<ObservationEntity>
    @Query("SELECT * FROM history") suspend fun allHistory(): List<HistoryEventEntity>

    /**
     * The History join as a one-shot list for the CSV export — same query as
     * [ObservationDao.observeStopRecords], which stays the screen's reactive source.
     */
    @Query(
        "SELECT s.id AS stopId, s.triggerAtMs AS atMs, s.status AS status, " +
            "o.movement AS movement, o.feeling AS feeling, o.thinking AS thinking, " +
            "o.activity AS activity, " +
            "IFNULL(o.hasVoiceNote, 0) AS hasVoiceNote, o.endedAtMs AS endedAtMs, " +
            "s.test AS test " +
            "FROM scheduled_stops s LEFT JOIN observations o ON o.stopId = s.id " +
            "WHERE s.status IN ('FIRED','SUPPRESSED') " +
            "ORDER BY s.triggerAtMs DESC, s.id DESC"
    )
    suspend fun stopRecords(): List<StopRecord>

    @Query("DELETE FROM settings") suspend fun wipeSettings()
    @Query("DELETE FROM sleep_windows") suspend fun wipeSleepWindows()
    @Query("DELETE FROM scheduled_stops") suspend fun wipeScheduledStops()
    @Query("DELETE FROM observations") suspend fun wipeObservations()
    @Query("DELETE FROM history") suspend fun wipeHistory()

    // Plain inserts: ids arrive from the backup and are kept, so observations still point at
    // their stops. The tables were wiped just before, so nothing can conflict.
    @Insert suspend fun insertSettings(rows: List<SettingsEntity>)
    @Insert suspend fun insertSleepWindows(rows: List<SleepWindowEntity>)
    @Insert suspend fun insertScheduledStops(rows: List<ScheduledStopEntity>)
    @Insert suspend fun insertObservations(rows: List<ObservationEntity>)
    @Insert suspend fun insertHistory(rows: List<HistoryEventEntity>)
}
