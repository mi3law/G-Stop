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

    @Query("UPDATE scheduled_stops SET status = 'MISSED' WHERE status = 'PENDING' AND triggerAtMs < :beforeMs")
    suspend fun markMissed(beforeMs: Long)

    @Query("SELECT MAX(triggerAtMs) FROM scheduled_stops WHERE status IN ('FIRED','SUPPRESSED') AND localDate = :localDate")
    suspend fun lastActualStopOn(localDate: String): Long?
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
