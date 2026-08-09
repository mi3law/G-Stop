package com.gstop.media

import android.content.Context
import java.io.File

/** The three moments of a stop that are photographed. */
enum class PhotoSlot(val fileName: String, val label: String) {
    BEGIN("begin.jpg", "Beginning"),
    MIDDLE("middle.jpg", "Middle"),
    END("end.jpg", "End")
}

/**
 * Where a stop's photos and voice note live.
 *
 * Everything sits in the app's private `filesDir`, never in MediaStore: these are pictures of the
 * user taken without them posing, in their own home, and they have no business appearing in the
 * phone's gallery, in a backup, or in any other app.
 */
object StopMedia {

    private const val ROOT = "stops"
    private const val VOICE_NOTE = "voice.m4a"

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun dir(context: Context, stopId: Long): File = File(root(context), stopId.toString())

    fun photo(context: Context, stopId: Long, slot: PhotoSlot): File =
        File(dir(context, stopId), slot.fileName)

    /** Only the photos that were actually taken — a suppressed stop has at most the first. */
    fun photos(context: Context, stopId: Long): List<Pair<PhotoSlot, File>> =
        PhotoSlot.entries
            .map { it to photo(context, stopId, it) }
            .filter { (_, file) -> file.isFile && file.length() > 0 }

    fun voiceNote(context: Context, stopId: Long): File = File(dir(context, stopId), VOICE_NOTE)

    fun hasVoiceNote(context: Context, stopId: Long): Boolean =
        voiceNote(context, stopId).let { it.isFile && it.length() > 0 }

    /**
     * The stop ids that have any media on disk. One directory listing, so the History screen can
     * decide which rows are worth opening without stat-ing a file per row.
     */
    fun stopsWithMedia(context: Context): Set<Long> =
        root(context).listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }
            ?.mapNotNull { it.name.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun totalBytes(context: Context): Long =
        root(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun delete(context: Context, stopId: Long) {
        dir(context, stopId).deleteRecursively()
    }

    fun deleteAll(context: Context) {
        root(context).deleteRecursively()
    }
}
