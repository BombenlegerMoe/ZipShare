package dev.zipshare.upload

import dev.zipshare.data.net.ErrorAction
import dev.zipshare.data.net.PartialProtocol
import dev.zipshare.data.net.UploadResponse
import dev.zipshare.data.net.ZiplineException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** One chunk to send: [start] inclusive, [end] exclusive, in the given [protocol]. */
data class Chunk(
    val start: Long,
    val end: Long,
    val last: Boolean,
    val protocol: PartialProtocol,
    /** Token or identifier the server issued for this upload; null on the first chunk. */
    val continuation: String?,
)

/**
 * Drives a chunked upload against either contract Zipline has shipped (see [PartialProtocol]).
 * Chunks go strictly in order; [send] performs one request and throws [ZiplineException] on an
 * error response.
 *
 * Which contract a server speaks is not asked up front: `/api/version` refuses non-admins on many
 * instances and fails outright when the server cannot reach GitHub. It is learned from the first
 * chunk instead - each server version rejects the other contract's first chunk with E1002 before
 * keeping anything, because their byte-count checks disagree by exactly one. What worked is
 * remembered per server, so an older instance pays one extra chunk per process, not per upload.
 */
object PartialUpload {

    private val learned = ConcurrentHashMap<String, PartialProtocol>()

    suspend fun run(
        server: String,
        size: Long,
        chunkSize: Long,
        send: suspend (Chunk) -> UploadResponse,
    ): UploadResponse {
        require(size > 0 && chunkSize > 0) { "bad partial upload $size/$chunkSize" }
        val preferred = learned[server] ?: PartialProtocol.TOKEN
        val other = if (preferred == PartialProtocol.TOKEN) PartialProtocol.LEGACY else PartialProtocol.TOKEN
        val response = try {
            upload(preferred, size, chunkSize, send).also { learned[server] = preferred }
        } catch (mismatch: FirstChunkRejected) {
            try {
                upload(other, size, chunkSize, send).also { learned[server] = other }
            } catch (_: FirstChunkRejected) {
                // Both rejected the range: not a protocol question after all, report the first.
                throw mismatch.error
            }
        }
        return response
    }

    private suspend fun upload(
        protocol: PartialProtocol,
        size: Long,
        chunkSize: Long,
        send: suspend (Chunk) -> UploadResponse,
        mayRestart: Boolean = true,
    ): UploadResponse {
        var start = 0L
        var continuation: String? = null
        while (true) {
            val end = min(start + chunkSize, size)
            val last = end >= size
            val response = try {
                send(Chunk(start, end, last, protocol, continuation))
            } catch (e: ZiplineException) {
                if (start == 0L && e.code == E_RANGE) throw FirstChunkRejected(e)
                // The session is gone: a server restart, an expired token, or a chunk the server
                // dropped. Zipline's docs say to start over from byte 0 - once, not in a loop.
                if (start > 0L && e.code == E_SESSION && mayRestart) {
                    return upload(protocol, size, chunkSize, send, mayRestart = false)
                }
                throw e
            }
            if (last) return response
            continuation = when (protocol) {
                PartialProtocol.TOKEN -> response.partialToken
                PartialProtocol.LEGACY -> continuation ?: response.partialIdentifier
            } ?: throw ZiplineException(
                E_SESSION, 400, "no continuation",
                "Server did not return a partial upload token.", ErrorAction.NONE,
            )
            start = end
        }
    }

    private class FirstChunkRejected(val error: ZiplineException) : Exception(error)

    private const val E_RANGE = 1002
    private const val E_SESSION = 1003
}
