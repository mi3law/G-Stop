package com.gstop.data

import android.content.Context
import com.gstop.core.SleepWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val observationDao = db.observationDao()

    /**
     * For writes that must outlive the screen that started them — the last keystroke before the
     * user leaves the observation is still part of the observation.
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsFlow: Flow<SettingsEntity> = settingsDao.observe().map { it ?: SettingsEntity() }

    val sleepWindowsFlow: Flow<List<SleepWindowEntity>> = windowDao.observeAll()

    val historyFlow: Flow<List<HistoryEventEntity>> = historyDao.observeAll()

    val stopRecordsFlow: Flow<List<StopRecord>> = observationDao.observeStopRecords()

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

    // --- observations ---

    fun observationFlow(stopId: Long): Flow<ObservationEntity?> = observationDao.observe(stopId)

    suspend fun observation(stopId: Long): ObservationEntity? = observationDao.byStopId(stopId)

    /** Called when a stop releases: the empty row *is* the open window. */
    suspend fun openObservationWindow(stopId: Long, endedAtMs: Long) {
        if (observationDao.byStopId(stopId) == null) {
            observationDao.upsert(ObservationEntity(stopId = stopId, endedAtMs = endedAtMs))
        }
    }

    /**
     * Saves an edit and, the first time the observation stops being empty, logs the stop as noted
     * so the change is visible in the log as its own event, at the time it was made.
     */
    suspend fun saveObservation(observation: ObservationEntity, nowMs: Long) {
        val previous = observationDao.byStopId(observation.stopId)
        val becameNoted = observation.isNoted && previous?.isNoted != true
        observationDao.upsert(
            observation.copy(
                notedAtMs = when {
                    !observation.isNoted -> null
                    else -> previous?.notedAtMs ?: nowMs
                }
            )
        )
        if (becameNoted) log(HistoryType.STOP_NOTED, nowMs)
    }

    fun saveObservationAsync(observation: ObservationEntity, nowMs: Long) {
        ioScope.launch { saveObservation(observation, nowMs) }
    }

    /** Media is gone from disk; no observation may still claim a voice note. */
    suspend fun clearVoiceNoteFlags() = observationDao.clearVoiceNoteFlags()

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
