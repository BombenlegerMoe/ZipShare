package dev.zipshare.data.net

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

/**
 * Streams [length] bytes starting at [offset] from a freshly-opened [InputStream] into the sink in
 * 8 KiB chunks. The content is never materialised as a ByteArray, so a 4 GiB upload costs 8 KiB of heap.
 *
 * [openStream] is called per [writeTo], so OkHttp-level retries and redirects re-read from the source.
 */
class ProgressRequestBody(
    private val mediaType: MediaType?,
    private val length: Long,
    private val offset: Long = 0L,
    private val bufferSize: Int = DEFAULT_BUFFER,
    private val openStream: () -> InputStream,
    private val onProgress: (bytesWritten: Long, total: Long) -> Unit = { _, _ -> },
) : RequestBody() {

    init {
        require(length >= 0) { "length must be >= 0" }
        require(offset >= 0) { "offset must be >= 0" }
        require(bufferSize > 0) { "bufferSize must be > 0" }
    }

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        openStream().use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(bufferSize)
            var written = 0L
            onProgress(0L, length)
            while (written < length) {
                val want = minOf(buffer.size.toLong(), length - written).toInt()
                val read = input.read(buffer, 0, want)
                if (read == -1) {
                    throw IOException(
                        "Source ended after $written of $length bytes — the file changed while uploading.",
                    )
                }
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, length)
            }
            sink.flush()
        }
    }

    /** InputStream.skip may skip fewer bytes than asked; loop until the offset is really consumed. */
    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val scratch = ByteArray(bufferSize)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read == -1) throw IOException("Cannot seek to offset $bytes: stream ended early.")
            remaining -= read
        }
    }

    companion object {
        const val DEFAULT_BUFFER = 8 * 1024
    }
}
