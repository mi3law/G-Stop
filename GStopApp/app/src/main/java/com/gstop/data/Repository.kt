package com.gstop.data

import android.content.Context
import com.gstop.core.SleepWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single access point to persisted state. Nothing here decides *when* stops happen; that is
 * com.gstop.schedule.ScheduleManager.
 */
class Repository(context: Context) {

    private val db = AppDatabase.get(context)
    private val settingsDao = db.settingsDao()
    private val windowDao = db.sleepWindowDao()
    private val stopDao = db.scheduledStopDao()
    private val historyDao = db.historyDao()

    val settingsFlow: Flow<SettingsEntity> = settingsDao.observe().map { it ?: SettingsEntity() }

    val sleepWindowsFlow: Flow<List<SleepWindowEntity>> = windowDao.observeAll()

    val historyFlow: Flow<List<HistoryEventEntity>> = historyDao.observeAll()

    /** Reads settings, seeding the defaults (and the default nightly sleep window) on first run. */
    suspend fun settings(): SettingsEntity {
        settingsDao.get()?.let { return it }
        val fresh = SettingsEntity()
        settingsDao.upsert(fresh)
        if (windowDao.count() == 0) windowDao.insert(SleepWindowEntity.defaultNightly())
        return fresh
    }

    suspend fun saveSettings(settings: SettingsEntity) = settingsDao.upsert(settings)

    suspend fun sleepWindows(): List<SleepWindow> = windowDao.getAll().map { it.toDomain() }

    suspend fun sleepWindowEntities(): List<SleepWindowEntity> = windowDao.getAll()

    suspend fun insertWindow(window: SleepWindowEntity): Long = windowDao.insert(window)

    suspend fun updateWindow(window: SleepWindowEntity) = windowDao.update(window)

    suspend fun deleteWindow(window: SleepWindowEntity) = windowDao.delete(window)

    // --- drawn schedule (never displayed) ---

    suspend fun replacePendingStops(stops: List<ScheduledStopEntity>) {
        stopDao.deletePending()
        if (stops.isNotEmpty()) stopDao.insertAll(stops)
    }

    suspend fun clearPendingStops() = stopDao.deletePending()

    suspend fun markMissedBefore(ms: Long) = stopDao.markMissed(ms)

    suspend fun nextPendingStop(afterMs: Long): ScheduledStopEntity? = stopDao.nextPending(afterMs)

    suspend fun stopById(id: Long): ScheduledStopEntity? = stopDao.byId(id)

    suspend fun setStopStatus(id: Long, status: StopStatus) = stopDao.setStatus(id, status.name)

    suspend fun lastActualStopOn(localDate: String): Long? = stopDao.lastActualStopOn(localDate)

    // --- history ---

    suspend fun log(type: HistoryType, atMs: Long, detail: String? = null) =
        historyDao.insert(HistoryEventEntity(atMs = atMs, type = type.name, detail = detail))

    /** Manual pruning from the Logs screen; the log is never trimmed automatically. */
    suspend fun trimHistory(keep: Int) = historyDao.trimToNewest(keep)

    companion object {
        @Volatile private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }
}
