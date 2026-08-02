package dev.zipshare.data.net

import dev.zipshare.data.model.CompressionType
import dev.zipshare.data.model.UploadOptions
import java.net.URLEncoder

/**
 * Option -> header mapping. A header is emitted only when the user actually set the option;
 * empty strings are never sent.
 */
object UploadHeaderBuilder {

    /** OkHttp only accepts header values in [0x20, 0x7e]; fail here with something a user can act on. */
    private fun headerSafe(name: String, value: String): String {
        value.forEachIndexed { i, c ->
            if (c.code < 0x20 || c.code > 0x7e) {
                throw IllegalArgumentException(
                    "Header $name contains an unsupported character '$c' at index $i. Use ASCII only.",
                )
            }
        }
        return value
    }

    /**
     * [mime] is the type of the file being sent, needed only to resolve
     * [CompressionType.AUTO] - "keep the format it already is". Omitting it leaves AUTO
     * unresolved, which drops both compression headers rather than guessing a format.
     */
    fun build(o: UploadOptions, mime: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()

        fun put(name: String, value: String?) {
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty()) h[name] = headerSafe(name, v)
        }

        // AUTO never reaches the wire: it becomes the file's own format, or nothing at all for a
        // type Zipline cannot re-encode. Sending the percent without a resolvable type would let
        // the server pick the format, which is the opposite of what "auto" promises.
        val auto = o.compressionType == CompressionType.AUTO
        val resolvedType = if (auto) CompressionType.forMime(mime) else o.compressionType

        // In auto mode the quality follows the resolved format, because the same number means
        // different things to sharp per format. PNG has its own; the lossy formats share one.
        val percent = when {
            !auto -> o.compressionPercent
            resolvedType == CompressionType.PNG -> o.autoPercentPng ?: o.compressionPercent
            resolvedType != null -> o.autoPercentJpg ?: o.compressionPercent
            else -> null
        }

        put("x-zipline-deletes-at", o.deletesAt)
        put("x-zipline-format", o.format?.wire)
        // Auto with no quality configured for this format sends nothing rather than letting the
        // server apply its own default - "auto" should never compress harder than you asked.
        if (!auto || (resolvedType != null && percent != null)) {
            percent?.let {
                put("x-zipline-image-compression-percent", it.coerceIn(0, 100).toString())
            }
            put("x-zipline-image-compression-type", resolvedType?.wire)
        }
        put("x-zipline-password", o.password)
        o.maxViews?.let { if (it >= 0) put("x-zipline-max-views", it.toString()) }
        if (o.originalName) put("x-zipline-original-name", "true")
        put("x-zipline-folder", o.folderId)
        // x-zipline-filename overrides x-zipline-format; both may legitimately be present.
        put("x-zipline-filename", o.filename)
        put(
            "x-zipline-domain",
            o.domain?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(","),
        )
        put("x-zipline-file-extension", o.fileExtension?.removePrefix("."))
        // x-zipline-no-json is intentionally never sent: the JSON response is required.
        return h
    }

    /**
     * Partial-upload headers, matching Zipline's own client.
     * [end] is exclusive; the server asserts `end - start == bytes received` and, on the last
     * chunk, that the accumulated length equals [totalLength].
     */
    fun partial(
        filename: String,
        contentType: String,
        totalLength: Long,
        start: Long,
        end: Long,
        lastChunk: Boolean,
        identifier: String?,
    ): Map<String, String> {
        require(start >= 0 && end > start && end <= totalLength) {
            "bad chunk range $start-$end/$totalLength"
        }
        val h = LinkedHashMap<String, String>()
        h["content-range"] = "bytes $start-$end/$totalLength"
        h["x-zipline-p-filename"] = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        h["x-zipline-p-content-type"] = headerSafe("x-zipline-p-content-type", contentType)
        h["x-zipline-p-content-length"] = totalLength.toString()
        h["x-zipline-p-lastchunk"] = if (lastChunk) "true" else "false"
        if (!identifier.isNullOrBlank()) {
            h["x-zipline-p-identifier"] = headerSafe("x-zipline-p-identifier", identifier)
        }
        return h
    }
}
