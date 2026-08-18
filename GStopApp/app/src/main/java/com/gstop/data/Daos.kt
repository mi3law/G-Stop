package com.gstop.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

@Dao
interface SleepWindowDao {
    @Query("SELECT * FROM sleep_windows ORDER BY startMinuteOfDay")
    fun observeAll(): Flow<List<SleepWindowEntity>>

    @Query("SELECT * FROM sleep_windows ORDER BY startMinuteOfDay")
    suspend fun getAll(): List<SleepWindowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(window: SleepWindowEntity): Long

    @Update
    suspend fun update(window: SleepWindowEntity)

    @Delete
    suspend fun delete(window: SleepWindowEntity)

    @Query("SELECT COUNT(*) FROM sleep_windows")
    suspend fun count(): Int
}

@Dao
interface ScheduledStopDao {
    @Query("SELECT * FROM scheduled_stops WHERE status = 'PENDING' AND triggerAtMs > :afterMs ORDER BY triggerAtMs LIMIT 1")
    suspend fun nextPending(afterMs: Long): ScheduledStopEntity?

    @Query("SELECT * FROM scheduled_stops WHERE status = 'PENDING' ORDER BY triggerAtMs")
    suspend fun allPending(): List<ScheduledStopEntity>

    @Query("SELECT * FROM scheduled_stops WHERE id = :id")
    suspend fun byId(id: Long): ScheduledStopEntity?

    @Insert
    suspend fun insertAll(stops: List<ScheduledStopEntity>)

    @Query("UPDATE scheduled_stops SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    /** Regeneration discards every pending stop; nothing is ever carried over. */
    @Query("DELETE FROM scheduled_stops WHERE status = 'PENDING'")
    suspend fun deletePending()

    // --- a pause holds the draw aside ---

    @Query("UPDATE scheduled_stops SET status = 'SUSPENDED' WHERE status = 'PENDING'")
    suspend fun suspendPending()

    @Query("UPDATE scheduled_stops SET status = 'PENDING' WHERE status = 'SUSPENDED'")
    suspend fun restoreSuspended()

    @Query("DELETE FROM scheduled_stops WHERE status = 'SUSPENDED'")
    suspend fun deleteSuspended()

    @Query("SELECT COUNT(*) FROM scheduled_stops WHERE status = 'SUSPENDED'")
    suspend fun suspendedCount(): Int

    @Query("UPDATE scheduled_stops SET status = 'MISSED' WHERE status = 'PENDING' AND triggerAtMs < :beforeMs")
    suspend fun markMissed(beforeMs: Long)

    @Query("SELECT MAX(triggerAtMs) FROM scheduled_stops WHERE status IN ('FIRED','SUPPRESSED') AND localDate = :localDate")
    suspend fun lastActualStopOn(localDate: String): Long?
}

@Dao
interface ObservationDao {
    @Query("SELECT * FROM observations WHERE stopId = :stopId")
    fun observe(stopId: Long): Flow<ObservationEntity?>

    @Query("SELECT * FROM observations WHERE stopId = :stopId")
    suspend fun byStopId(stopId: Long): ObservationEntity?

    @Upsert
    suspend fun upsert(observation: ObservationEntity)

    @Query("DELETE FROM observations WHERE stopId = :stopId")
    suspend fun delete(stopId: Long)

    /**
     * Every stop that actually happened, newest first, with whatever was noted about it.
     * PENDING and MISSED stops are absent on purpose: History is a record of stops that occurred,
     * and a pending row would leak a future stop time.
     */
    @Query(
        "SELECT s.id AS stopId, s.triggerAtMs AS atMs, s.status AS status, " +
            "o.movement AS movement, o.feeling AS feeling, o.thinking AS thinking, " +
            "o.activity AS activity, " +
            "IFNULL(o.hasVoiceNote, 0) AS hasVoiceNote, o.endedAtMs AS endedAtMs " +
            "FROM scheduled_stops s LEFT JOIN observations o ON o.stopId = s.id " +
            "WHERE s.status IN ('FIRED','SUPPRESSED') " +
            "ORDER BY s.triggerAtMs DESC, s.id DESC"
    )
    fun observeStopRecords(): Flow<List<StopRecord>>

    @Query("UPDATE observations SET hasVoiceNote = 0")
    suspend fun clearVoiceNoteFlags()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY atMs DESC, id DESC")
    fun observeAll(): Flow<List<HistoryEventEntity>>

    @Insert
    suspend fun insert(event: HistoryEventEntity)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    /** Keeps the [keep] newest events and deletes everything older. */
    @Query(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY atMs DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimToNewest(keep: Int)
}
