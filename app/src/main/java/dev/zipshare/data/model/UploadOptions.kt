package dev.zipshare.data.model

import kotlinx.serialization.Serializable

enum class NameFormat(val wire: String) {
    RANDOM("random"),
    DATE("date"),
    UUID("uuid"),
    NAME("name"),
    GFYCAT("gfycat"),
}

enum class CompressionType(val wire: String) {
    JPG("jpg"),
    PNG("png"),
    WEBP("webp"),
    JXL("jxl"),

    /**
     * Not a wire value. Means "re-encode to whatever the file already is", resolved per upload
     * from its mime type - see [forMime]. The server never sees the string "auto"; the header
     * builder either substitutes a real format or omits the header entirely.
     */
    AUTO("auto"),
    ;

    companion object {
        /**
         * The format a file should stay in. Null for anything Zipline cannot re-encode - every
         * non-image, and image types with no matching target (gif, svg, bmp, heic) - so those
         * upload untouched rather than being silently converted to something else.
         */
        fun forMime(mime: String?): CompressionType? = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
            "image/jpeg", "image/jpg" -> JPG
            "image/png", "image/apng" -> PNG
            "image/webp" -> WEBP
            "image/jxl", "image/jpegxl" -> JXL
            else -> null
        }
    }
}

/**
 * Every field is nullable / false-by-default: null means "do not send the header at all".
 * Zipline rejects empty header values (E1001), so blanks must never reach the wire.
 */
@Serializable
data class UploadOptions(
    val deletesAt: String? = null,
    val format: NameFormat? = null,
    val compressionPercent: Int? = null,
    val compressionType: CompressionType? = null,
    /**
     * Per-format quality for [CompressionType.AUTO]. Sharp treats the percent completely
     * differently per format - it is a real lossy quality for JPEG and WebP, but PNG is lossless
     * so the same number barely changes the file. One shared percent therefore cannot suit both.
     *
     * WebP and JXL follow [autoPercentJpg]: like JPEG they are lossy, so the same number means
     * roughly the same thing.
     */
    val autoPercentJpg: Int? = null,
    val autoPercentPng: Int? = null,
    val password: String? = null,
    val maxViews: Int? = null,
    val originalName: Boolean = false,
    val folderId: String? = null,
    val filename: String? = null,
    val domain: String? = null,
    val fileExtension: String? = null,
) {
    companion object {
        val DEFAULT = UploadOptions()
        val EXPIRY_PRESETS = listOf("30m", "1h", "12h", "1d", "7d", "30d", "never")
    }
}
