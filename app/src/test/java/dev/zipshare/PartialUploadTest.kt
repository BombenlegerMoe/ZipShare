package dev.zipshare

import dev.zipshare.data.net.ErrorAction
import dev.zipshare.data.net.PartialProtocol
import dev.zipshare.data.net.UploadHeaderBuilder
import dev.zipshare.data.net.UploadResponse
import dev.zipshare.data.net.UploadedFile
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.upload.Chunk
import dev.zipshare.upload.PartialUpload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [PartialUpload] against two fakes that reproduce the checks of Zipline's own partial route
 * (src/server/routes/api/upload/partial.ts at v4.7.0 and v4.8.0): the byte count against the range,
 * the session lookup and, on 4.8, the single-use token. Headers go through the real
 * [UploadHeaderBuilder], so an off-by-one in the wire range fails here, not on someone's server.
 */
class PartialUploadTest {

    private fun err(code: Int) = ZiplineException(code, 400, "E$code", "E$code", ErrorAction.NONE)

    private fun done() = UploadResponse(
        files = listOf(UploadedFile("f1", "big.bin", "application/octet-stream", "https://x/u/big.bin", pending = true)),
        partialSuccess = true,
    )

    /** What the server parses back out of our headers. Bytes received is the real body length. */
    private class Wire(chunk: Chunk, total: Long) {
        val headers = UploadHeaderBuilder.partial("big.bin", "application/octet-stream", total, chunk.start, chunk.end, chunk.last, chunk.protocol, chunk.continuation)
        private val m = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(headers.getValue("content-range"))!!
        val start = m.groupValues[1].toLong()
        val end = m.groupValues[2].toLong()
        val total = m.groupValues[3].toLong()
        val bytes = chunk.end - chunk.start
        val last = headers["x-zipline-p-lastchunk"] == "true"
    }

    /** v4.8.0: inclusive end, a fresh single-use token after every non-final chunk. */
    private inner class Server48 {
        var requests = 0
        var dropSessionsAt: Long? = null // simulate a restart: forget sessions when this offset arrives
        private var token: String? = null
        private var received = 0L
        private var n = 0

        fun handle(c: Chunk, total: Long): UploadResponse {
            requests++
            val w = Wire(c, total)
            if (w.end < w.start || w.end >= w.total || w.last != (w.end == w.total - 1)) throw err(1002)
            if (w.start == 0L) {
                received = 0
            } else {
                if (dropSessionsAt == w.start) { token = null; dropSessionsAt = null }
                if (w.headers["x-zipline-p-token"] == null || w.headers["x-zipline-p-token"] != token || w.start != received) throw err(1003)
                token = null // single use
            }
            if (w.end - w.start + 1 != w.bytes) throw err(1002)
            received += w.bytes
            if (w.last) return done()
            token = "t${++n}"
            return UploadResponse(partialSuccess = true, partialToken = token)
        }
    }

    /** v4.7.0 and older: exclusive end, one identifier issued on the first chunk. */
    private inner class Server47 {
        var requests = 0
        private var id: String? = null
        private var received = 0L

        fun handle(c: Chunk, total: Long): UploadResponse {
            requests++
            val w = Wire(c, total)
            if (w.end < w.start || w.end > w.total) throw err(1002)
            if (w.start == 0L) {
                id = "abc"; received = 0
            } else if (w.headers["x-zipline-p-identifier"] != id) {
                throw err(1003)
            }
            if (w.end - w.start != w.bytes) { id = null; throw err(1002) }
            received += w.bytes
            if (w.last) {
                if (received != total) throw err(1002)
                return done()
            }
            return UploadResponse(partialSuccess = true, partialIdentifier = if (w.start == 0L) id else null)
        }
    }

    @Test
    fun `a 4_8 server gets the token protocol with a new token per chunk`() = runBlocking {
        val s = Server48()
        val r = PartialUpload.run("srv-48", 25, 10) { s.handle(it, 25) }
        assertEquals("https://x/u/big.bin", r.files.single().url)
        assertEquals("three chunks, no probing", 3, s.requests)
    }

    @Test
    fun `an older server costs one rejected chunk, then legacy is remembered`() = runBlocking {
        val s = Server47()
        PartialUpload.run("srv-47", 25, 10) { s.handle(it, 25) }
        assertEquals("one rejected token chunk + three legacy chunks", 4, s.requests)

        s.requests = 0
        PartialUpload.run("srv-47", 25, 10) { s.handle(it, 25) }
        assertEquals("the second upload goes straight to legacy", 3, s.requests)
    }

    @Test
    fun `a server upgraded to 4_8 after legacy was learned switches back`() = runBlocking {
        val old = Server47()
        PartialUpload.run("srv-upgraded", 25, 10) { old.handle(it, 25) }

        val new = Server48()
        val r = PartialUpload.run("srv-upgraded", 25, 10) { new.handle(it, 25) }
        assertEquals("https://x/u/big.bin", r.files.single().url)
        assertEquals("one rejected legacy chunk + three token chunks", 4, new.requests)
    }

    @Test
    fun `a lost session restarts from byte 0 once`() = runBlocking {
        val s = Server48().apply { dropSessionsAt = 20 }
        val r = PartialUpload.run("srv-restart", 25, 10) { s.handle(it, 25) }
        assertEquals("https://x/u/big.bin", r.files.single().url)
        assertEquals("0,10, 20 rejected, then 0,10,20 again", 6, s.requests)
    }

    @Test
    fun `a session that keeps getting lost fails instead of looping`() = runBlocking {
        var requests = 0
        try {
            PartialUpload.run("srv-flaky", 25, 10) { c ->
                requests++
                if (c.start > 0) throw err(1003)
                UploadResponse(partialSuccess = true, partialToken = "t")
            }
            fail("expected E1003")
        } catch (e: ZiplineException) {
            assertEquals(1003, e.code)
        }
        assertEquals("two attempts of two requests each", 4, requests)
    }

    @Test
    fun `a range both protocols reject is reported, not retried forever`() = runBlocking {
        var requests = 0
        try {
            PartialUpload.run("srv-broken", 25, 10) { requests++; throw err(1002) }
            fail("expected E1002")
        } catch (e: ZiplineException) {
            assertEquals(1002, e.code)
        }
        assertEquals(2, requests)
    }

    @Test
    fun `a file within one chunk is a single final request`() = runBlocking {
        val s = Server48()
        PartialUpload.run("srv-one", 7, 10) { s.handle(it, 7) }
        assertEquals(1, s.requests)
    }

    @Test
    fun `a 4_8 response without a token is an error, not a silent restart`() = runBlocking {
        try {
            PartialUpload.run("srv-notoken", 25, 10) { UploadResponse(partialSuccess = true) }
            fail("expected an error")
        } catch (e: ZiplineException) {
            assertTrue(e.display.contains("token"))
        }
    }
}
