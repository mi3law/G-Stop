package com.gstop.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gstop.data.AppDatabase
import com.gstop.data.StopRecord
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The practice record as a spreadsheet: one row per stop that actually happened, oldest first.
 * Saved into Drive (or shared anywhere), the file opens straight into Google Sheets — which is
 * the whole reason this is CSV and not the backup zip.
 */
object CsvExport {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** Pure text building, so the quoting rules are testable on the JVM. */
    fun build(records: List<StopRecord>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        // The byte-order mark is for Excel, which otherwise misreads UTF-8; Sheets ignores it.
        sb.append('\uFEFF')
        sb.append("Date,Time,Status,Movement,Feeling,Thinking,Activity,Voice note\r\n")
        for (r in records.asReversed()) {
            val at = Instant.ofEpochMilli(r.atMs).atZone(zone)
            sb.append(
                listOf(
                    DATE.format(at),
                    TIME.format(at),
                    r.label,
                    r.movement.orEmpty(),
                    r.feeling.orEmpty(),
                    r.thinking.orEmpty(),
                    r.activity.orEmpty(),
                    if (r.hasVoiceNote) "yes" else ""
                ).joinToString(",") { field(it) }
            )
            sb.append("\r\n")
        }
        return sb.toString()
    }

    // RFC 4180: a field holding a comma, quote or line break is quoted, quotes doubled.
    private fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** Writes today's CSV into the app cache and wraps it in a share-sheet chooser intent. */
    suspend fun shareIntent(context: Context): Intent {
        val records = AppDatabase.get(context).backupDao().stopRecords()
        val date = DATE.format(Instant.now().atZone(ZoneId.systemDefault()))
        val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = File(dir, "gstop-stops-$date.csv")
        file.writeText(build(records), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export stops as CSV")
    }
}
