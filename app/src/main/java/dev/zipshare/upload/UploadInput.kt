package dev.zipshare.upload

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class FileMeta(val name: String, val size: Long, val mime: String)

object UploadInput {

    fun meta(context: Context, uri: Uri): FileMeta {
        var name: String? = null
        var size = -1L

        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }

        if (size < 0 && uri.scheme == "file") {
            size = uri.path?.let { File(it).length() } ?: -1L
        }
        if (size < 0) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        if (size < 0) {
            // Last resort: stream once to measure. Still never buffers the file in memory.
            size = context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    total += r
                }
                total
            } ?: 0L
        }

        val resolved = name
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                resolved?.substringAfterLast('.', "")?.lowercase().orEmpty(),
            )
            ?: "application/octet-stream"

        val mimeMap = MimeTypeMap.getSingleton()
        val extension = mimeMap.getExtensionFromMimeType(mime)
        val fileName = ensureExtension(resolved ?: uri.lastPathSegment ?: "upload", extension) {
            mimeMap.getMimeTypeFromExtension(it).equals(mime, ignoreCase = true)
        }
        return FileMeta(fileName, size, mime)
    }

    /**
     * True for photo-picker uris. Their DISPLAY_NAME is the MediaStore id, not the real file
     * name - Android withholds original names from apps without READ_MEDIA permissions, and this
     * app deliberately holds none. Detected so the UI can say so instead of surprising the user.
     */
    fun isPickerRedacted(uri: Uri): Boolean =
        uri.pathSegments.firstOrNull() == "picker" || uri.authority?.contains("photopicker") == true

    /**
     * Zipline derives the stored file's extension from the multipart filename, and serves the file
     * using the recorded type. A gallery share often hands over a display name with no extension
     * (Google Photos in particular), which previously produced an extensionless
     * `application/octet-stream` upload that browsers download instead of rendering.
     *
     * Pure string logic so it can be unit tested.
     */
    fun ensureExtension(
        name: String,
        extension: String?,
        /** True when the name's existing suffix already denotes the same type (.jpeg vs .jpg). */
        isEquivalentExtension: (String) -> Boolean = { false },
    ): String {
        if (extension.isNullOrBlank()) return name
        val current = name.substringAfterLast('.', "").lowercase()
        // Guessing by length is wrong - "my.holiday.photo" would read "photo" as an extension.
        if (current == extension.lowercase() || (current.isNotEmpty() && isEquivalentExtension(current))) {
            return name
        }
        return "$name.$extension"
    }

    /**
     * Share-sheet grants die with the sending task and photo-picker grants die with the process,
     * so anything not persistable is streamed into cache first — 8 KiB at a time, never a ByteArray.
     *
     * [forSeeking] forces a staged copy even when the grant would survive. A chunked upload opens
     * the source once per chunk and skips to that chunk's offset; on a `content://` provider backed
     * by a pipe, `skip()` cannot seek and falls back to *reading* the skipped bytes, so chunk N
     * re-reads everything before it and the upload costs O(n²). A staged copy is a plain `file://`,
     * whose skip is a real seek, which makes each chunk cost its own size and nothing more.
     *
     * Returns the uri to upload from and whether it is a staged copy the worker must delete.
     */
    fun stageIfNeeded(context: Context, uri: Uri, forSeeking: Boolean = false): Pair<Uri, Boolean> {
        // Already seekable, and not ours to delete.
        if (uri.scheme == "file") return uri to false

        if (!forSeeking) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            if (persisted) return uri to false
        }

        val dir = File(context.cacheDir, "staged").apply { mkdirs() }
        val safeName = meta(context, uri).name.take(80).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "${System.nanoTime()}_$safeName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    out.write(buf, 0, r)
                }
            }
        } ?: throw IOException("Cannot open $uri for reading.")

        return Uri.fromFile(target) to true
    }
}
