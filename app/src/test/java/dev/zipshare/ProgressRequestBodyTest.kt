package dev.zipshare

import dev.zipshare.data.net.ProgressRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ProgressRequestBodyTest {

    private val mediaType = "application/octet-stream".toMediaType()

    private fun source(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `streams the whole source and reports monotonic progress ending at contentLength`() {
        val data = source(20_000)
        val progress = mutableListOf<Long>()
        val body = ProgressRequestBody(
            mediaType = mediaType,
            length = data.size.toLong(),
            openStream = { ByteArrayInputStream(data) },
            onProgress = { written, total ->
                assertEquals(data.size.toLong(), total)
                progress += written
            },
        )
        val sink = Buffer()
        body.writeTo(sink)

        assertEquals(data.size.toLong(), body.contentLength())
        assertArrayEquals(data, sink.readByteArray())
        assertEquals(0L, progress.first())
        assertEquals(data.size.toLong(), progress.last())
        assertTrue(
            "progress must never go backwards: $progress",
            progress.zipWithNext().all { (a, b) -> b >= a },
        )
        // 20000 bytes / 8 KiB => 3 writes, plus the initial 0 emission.
        assertEquals(4, progress.size)
    }

    @Test
    fun `emits one progress callback per 8 KiB chunk`() {
        val data = source(8 * 1024 * 3)
        val progress = mutableListOf<Long>()
        ProgressRequestBody(
            mediaType = mediaType,
            length = data.size.toLong(),
            openStream = { ByteArrayInputStream(data) },
            onProgress = { written, _ -> progress += written },
        ).writeTo(Buffer())

        assertEquals(listOf(0L, 8192L, 16384L, 24576L), progress)
    }

    @Test
    fun `offset and length select exactly one chunk of the source`() {
        val data = source(1000)
        val body = ProgressRequestBody(
            mediaType = mediaType,
            length = 300,
            offset = 400,
            openStream = { ByteArrayInputStream(data) },
        )
        val sink = Buffer()
        body.writeTo(sink)

        assertEquals(300L, body.contentLength())
        assertArrayEquals(data.copyOfRange(400, 700), sink.readByteArray())
    }

    @Test
    fun `body is re-readable so OkHttp retries do not truncate the upload`() {
        val data = source(5000)
        val body = ProgressRequestBody(
            mediaType = mediaType,
            length = data.size.toLong(),
            openStream = { ByteArrayInputStream(data) },
        )
        val first = Buffer().also(body::writeTo).readByteArray()
        val second = Buffer().also(body::writeTo).readByteArray()
        assertArrayEquals(first, second)
    }

    @Test
    fun `zero-length body writes nothing and still reports completion`() {
        val progress = mutableListOf<Long>()
        val sink = Buffer()
        ProgressRequestBody(
            mediaType = mediaType,
            length = 0,
            openStream = { ByteArrayInputStream(ByteArray(0)) },
            onProgress = { written, _ -> progress += written },
        ).writeTo(sink)

        assertEquals(0L, sink.size)
        assertEquals(listOf(0L), progress)
    }

    @Test
    fun `a source that shrinks mid-upload fails loudly instead of sending a short body`() {
        val body = ProgressRequestBody(
            mediaType = mediaType,
            length = 10_000,
            openStream = { ByteArrayInputStream(source(500)) },
        )
        val error = runCatching { body.writeTo(Buffer()) }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertTrue(error!!.message!!.contains("500 of 10000"))
    }

    @Test
    fun `smaller buffer still produces identical bytes`() {
        val data = source(3000)
        val sink = Buffer()
        ProgressRequestBody(
            mediaType = mediaType,
            length = data.size.toLong(),
            bufferSize = 64,
            openStream = { ByteArrayInputStream(data) },
        ).writeTo(sink)
        assertArrayEquals(data, sink.readByteArray())
    }
}
