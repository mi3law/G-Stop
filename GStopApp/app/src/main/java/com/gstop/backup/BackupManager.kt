package com.gstop.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.gstop.data.AppDatabase
import com.gstop.data.HistoryType
import com.gstop.data.Repository
import com.gstop.data.StopStatus
import com.gstop.media.StopMedia
import com.gstop.schedule.ScheduleManager
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Snapshots of the practice record, written into a folder the user picked once in the system
 * picker — on this phone that folder lives in Google Drive, and the Drive app does the carrying.
 * The app itself still has no network permission: everything here is a local file write through
 * the Storage Access Framework.
 *
 * A snapshot is a zip holding `backup.json` (every table, via [BackupCodec]) and, only when the
 * user has opted in, the stop photographs and voice notes. One snapshot per day, the newest
 * [KEEP] kept. Restoring replaces everything — tables and media both — and then redraws the
 * schedule, exactly as any other regeneration trigger would.
 */
object BackupManager {

    private const val TAG = "GStop.Backup"

    private const val PREFS = "backup"
    private const val KEY_TREE = "treeUri"
    private const val KEY_INCLUDE_MEDIA = "includeMedia"
    private const val KEY_LAST_BACKUP_MS = "lastBackupMs"
    private const val KEY_LAST_RESULT = "lastResult"

    private const val KEEP = 7
    private val DUE_AFTER_MS = TimeUnit.HOURS.toMillis(20)

    private const val JSON_ENTRY = "backup.json"
    private val BACKUP_NAME = Regex("""gstop-backup-\d{4}-\d{2}-\d{2}\.zip""")
    private val MEDIA_ENTRY = Regex("""media/(\d+)/(begin\.jpg|middle\.jpg|end\.jpg|voice\.m4a)""")

    data class Status(
        val folderUri: Uri?,
        val includeMedia: Boolean,
        val lastBackupMs: Long,
        val lastResult: String?
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(context: Context): Status {
        val p = prefs(context)
        return Status(
            folderUri = p.getString(KEY_TREE, null)?.let(Uri::parse),
            includeMedia = p.getBoolean(KEY_INCLUDE_MEDIA, false),
            lastBackupMs = p.getLong(KEY_LAST_BACKUP_MS, 0L),
            lastResult = p.getString(KEY_LAST_RESULT, null)
        )
    }

    /** The folder's human name, or null when none is set or the grant has gone stale. */
    fun folderName(context: Context): String? =
        status(context).folderUri
            ?.let { DocumentFile.fromTreeUri(context, it) }
            ?.takeIf { it.canWrite() }
            ?.name

    fun setFolder(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Without the persisted grant the folder is writable only until the picker's Activity
        // dies; the nightly snapshot happens long after.
        context.contentResolver.takePersistableUriPermission(uri, flags)
        status(context).folderUri?.takeIf { it != uri }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs(context).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun setIncludeMedia(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_MEDIA, enabled).apply()
    }

    /**
     * Called from the paths that already wake the app daily — the midnight rollover, boot, and
     * opening the app — so a snapshot happens roughly once a day without any machinery of its
     * own. Quietly does nothing when no folder is set or the last snapshot is recent.
     */
    suspend fun backupIfDue(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val status = status(context)
        if (status.folderUri == null) return
        if (nowMs - status.lastBackupMs < DUE_AFTER_MS) return
        backupNow(context, nowMs)
    }

    suspend fun backupNow(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Result<String> {
        val repo = Repository.get(context)
        val result = runCatching { writeSnapshot(context, nowMs) }
        result.fold(
            onSuccess = { name ->
                prefs(context).edit()
                    .putLong(KEY_LAST_BACKUP_MS, nowMs)
                    .putString(KEY_LAST_RESULT, "Written: $name")
                    .apply()
                repo.log(HistoryType.BACKUP, nowMs, name)
                Log.i(TAG, "backup written: $name")
            },
            onFailure = { e ->
                prefs(context).edit()
                    .putString(KEY_LAST_RESULT, "Failed: ${e.message}")
                    .apply()
                repo.log(HistoryType.BACKUP, nowMs, "failed: ${e.message}")
                Log.e(TAG, "backup failed", e)
            }
        )
        return result
    }

    private suspend fun writeSnapshot(context: Context, nowMs: Long): String {
        val status = status(context)
        val treeUri = status.folderUri ?: error("No backup folder chosen.")
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: error("The backup folder is no longer reachable — choose it again.")

        // One transaction, so the snapshot is a single moment and not a smear across writes.
        val db = AppDatabase.get(context)
        val dao = db.backupDao()
        val payload = db.withTransaction {
            BackupCodec.Payload(
                exportedAtMs = nowMs,
                settings = dao.allSettings().firstOrNull(),
                sleepWindows = dao.allSleepWindows(),
                scheduledStops = dao.allScheduledStops(),
                observations = dao.allObservations(),
                history = dao.allHistory()
            )
        }
        val json = BackupCodec.encode(payload)

        val name = "gstop-backup-${localDate(nowMs)}.zip"
        // Deleting first matters on Drive: createFile over an existing name makes "name (1)".
        tree.findFile(name)?.delete()
        val file = tree.createFile("application/zip", name)
            ?: error("Could not create $name in the backup folder.")

        try {
            val out = context.contentResolver.openOutputStream(file.uri)
                ?: error("Could not open $name for writing.")
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(JSON_ENTRY))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (status.includeMedia) {
                    for ((stopDir, mediaFile) in mediaFiles(context)) {
                        zip.putNextEntry(ZipEntry("media/$stopDir/${mediaFile.name}"))
                        mediaFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            // A half-written snapshot must not sit in the folder looking like a good one.
            runCatching { file.delete() }
            throw e
        }

        rotate(tree)
        return name
    }

    private fun mediaFiles(context: Context): List<Pair<String, File>> =
        StopMedia.root(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile }.map { dir.name to it } }

    private fun rotate(tree: DocumentFile) {
        tree.listFiles()
            .filter { it.name?.matches(BACKUP_NAME) == true }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Replaces everything — tables and media — with the snapshot's contents, then redraws the
     * schedule. PENDING and SUSPENDED stops are not restored: they were the other phone's undrawn
     * future, and this phone draws its own.
     */
    suspend fun restore(
        context: Context,
        zipUri: Uri,
        nowMs: Long = System.currentTimeMillis()
    ): Result<String> = runCatching {
        val staging = File(context.cacheDir, "restore-staging")
        staging.deleteRecursively()

        var json: String? = null
        val input: InputStream = context.contentResolver.openInputStream(zipUri)
            ?: error("Could not open the backup file.")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (entryName == JSON_ENTRY) {
                    json = zip.readBytes().toString(Charsets.UTF_8)
                } else {
                    // The pattern is the path validation: anything else — including any name
                    // trying to climb out with ".." — is not something this app ever wrote.
                    MEDIA_ENTRY.matchEntire(entryName)?.let { m ->
                        val target = File(File(staging, m.groupValues[1]), m.groupValues[2])
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
                entry = zip.nextEntry
            }
        }
        val payload = BackupCodec.decode(json ?: error("Not a G-Stop backup: no $JSON_ENTRY inside."))

        val stops = payload.scheduledStops.filter {
            it.status != StopStatus.PENDING.name && it.status != StopStatus.SUSPENDED.name
        }
        val voiceStops: Set<Long> = staging.listFiles().orEmpty()
            .filter { File(it, "voice.m4a").isFile }
            .mapNotNull { it.name.toLongOrNull() }
            .toSet()
        val observations = payload.observations.map {
            it.copy(hasVoiceNote = it.stopId in voiceStops)
        }
        val settings = payload.settings?.copy(
            // The tone grants and the battery-optimisation answer belonged to the old install;
            // carrying them over would show choices this phone cannot honour.
            commandSoundUri = null,
            releaseSoundUri = null,
            batteryPromptShown = false
        )

        val db = AppDatabase.get(context)
        val dao = db.backupDao()
        db.withTransaction {
            dao.wipeSettings()
            dao.wipeSleepWindows()
            dao.wipeScheduledStops()
            dao.wipeObservations()
            dao.wipeHistory()
            settings?.let { dao.insertSettings(listOf(it)) }
            dao.insertSleepWindows(payload.sleepWindows)
            dao.insertScheduledStops(stops)
            dao.insertObservations(observations)
            dao.insertHistory(payload.history)
        }

        // Media only after the tables committed: a failed restore leaves the phone as it was.
        StopMedia.deleteAll(context)
        val mediaRoot = StopMedia.root(context).also { it.mkdirs() }
        staging.listFiles().orEmpty().forEach { dir ->
            if (!dir.renameTo(File(mediaRoot, dir.name))) {
                dir.copyRecursively(File(mediaRoot, dir.name), overwrite = true)
            }
        }
        staging.deleteRecursively()

        val summary = "Restored ${stops.size} stops, " +
            "${observations.count { it.isNoted }} noted, ${voiceStops.size} voice notes."
        Repository.get(context).log(HistoryType.BACKUP, nowMs, "restored — $summary")
        ScheduleManager.regenerate(context, "backup restored", nowMs)
        Log.i(TAG, "restore complete: $summary")
        summary
    }

    private fun localDate(nowMs: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        )
}
