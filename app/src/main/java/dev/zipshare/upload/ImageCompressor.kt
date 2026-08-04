package dev.zipshare.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import dev.zipshare.log.AppLog
import java.io.File
import java.io.FileOutputStream

/**
 * Re-encodes an image before it is uploaded, so the phone sends fewer bytes.
 *
 * Distinct from Zipline's own compression, which happens *after* a full-size upload and therefore
 * saves the server disk but costs the phone the same upload. This one trades CPU on the device for
 * bytes on the wire, which is the trade worth making on mobile data.
 *
 * The two must not both run on one file: the server would re-encode an already lossy image and
 * throw away quality for nothing, so [UploadEnqueuer] drops the server-side compression headers
 * for anything compressed here.
 */
object ImageCompressor {

    /** What the user can pick. Values are persisted, so they are not the enum names. */
    const val WEBP = "webp"
    const val JPEG = "jpeg"

    /**
     * Whether re-encoding [mime] is safe and worthwhile.
     *
     * GIF and WebP are excluded because either may be animated, and decoding one through
     * [BitmapFactory] silently keeps only the first frame - a corrupted upload rather than a
     * smaller one. Everything else here is a still photo format where re-encoding is the point.
     */
    fun canCompress(mime: String): Boolean = mime.lowercase() in COMPRESSIBLE

    private val COMPRESSIBLE = setOf("image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif")

    /**
     * Swaps the extension, because the bytes are no longer what the old one claims.
     *
     * Zipline takes the stored file's extension from the multipart filename, so leaving `.png` on
     * WebP bytes produces a file the server serves with the wrong type and browsers refuse to show.
     */
    fun renameForFormat(name: String, format: String): String {
        val extension = if (format == JPEG) "jpg" else "webp"
        val stem = name.substringBeforeLast('.', name).ifBlank { name }
        return "$stem.$extension"
    }

    fun mimeForFormat(format: String): String = if (format == JPEG) "image/jpeg" else "image/webp"

    /**
     * Human-readable saving, or null when the re-encode did not actually help.
     *
     * Re-encoding can make a file *bigger* - a small, already-optimised JPEG pushed through a
     * higher quality setting is the usual case - and shipping a larger file to save bandwidth
     * would be absurd, so the caller uses null to mean "keep the original".
     */
    fun savingPercent(before: Long, after: Long): Int? {
        if (before <= 0 || after <= 0 || after >= before) return null
        return (100 - (after * 100 / before)).toInt().coerceIn(1, 99)
    }

    /**
     * Writes a re-encoded copy into [cacheDir] and returns it, or null when it could not be done or
     * would not help. Blocking and memory-hungry; call from a worker thread.
     */
    fun compress(
        context: Context,
        uri: Uri,
        format: String,
        quality: Int,
        cacheDir: File,
        originalSize: Long,
    ): File? {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null

        // JPEG has no alpha channel, so a transparent screenshot would come out flattened onto
        // black. Losing the image is worse than losing the saving, so leave it alone.
        if (format == JPEG && bitmap.hasAlpha()) {
            bitmap.recycle()
            AppLog.log("upload", "on-device compression skipped: jpeg cannot keep transparency")
            return null
        }

        val target = File(cacheDir.apply { mkdirs() }, "cmp_${System.nanoTime()}")
        return try {
            val encoded = FileOutputStream(target).use { out ->
                bitmap.compress(formatOf(format), quality, out)
            }
            val saving = if (encoded) savingPercent(originalSize, target.length()) else null
            if (saving == null) {
                // Bigger than what we started with, or the encoder refused: keep the original.
                target.delete()
                null
            } else {
                AppLog.log("upload", "compressed on device: -$saving% ($originalSize -> ${target.length()} B)")
                target
            }
        } catch (e: OutOfMemoryError) {
            // A very large image can exhaust the heap mid-encode. Uploading the original is a far
            // better outcome than failing the upload to save bandwidth.
            target.delete()
            AppLog.log("upload", "on-device compression skipped: out of memory")
            null
        } catch (e: Exception) {
            target.delete()
            AppLog.log("upload", "on-device compression skipped: ${e.javaClass.simpleName}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun formatOf(format: String): Bitmap.CompressFormat = when {
        format == JPEG -> Bitmap.CompressFormat.JPEG
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
        // WEBP is deprecated from API 30 but is the only lossy WebP below it, and minSdk is 26.
        else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }
}
