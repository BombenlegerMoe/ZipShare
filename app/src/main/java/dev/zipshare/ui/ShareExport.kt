package dev.zipshare.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares an export through the FileProvider, granting read for this one intent only. */
internal fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export"))
}

/**
 * Writes [text] as a fresh export, first deleting exports older than [EXPORT_KEEP_MS]. Some are
 * plaintext copies of encrypted data (the log), so they must not pile up in the cache. The age
 * gap leaves the receiving app time to read the file it was just handed. Blocking: call on IO.
 */
internal fun writeExportFile(context: Context, name: String, text: String): File {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    pruneExports(dir)
    return File(dir, name).apply { writeText(text) }
}

internal fun pruneExports(dir: File, now: Long = System.currentTimeMillis()) {
    dir.listFiles()?.filter { now - it.lastModified() > EXPORT_KEEP_MS }?.forEach { it.delete() }
}

private const val EXPORT_KEEP_MS = 10 * 60 * 1000L
