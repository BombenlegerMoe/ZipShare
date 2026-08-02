package dev.zipshare.data.net

/**
 * Preview source for a dashboard card.
 *
 * Zipline's own web client appends `?token=` to these URLs; we never do that - the token goes in
 * the `authorization` header via the Coil image loader instead, so it cannot end up in a URL,
 * a proxy log, or a screenshot.
 */
fun ZFile.previewUrl(baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    // Videos and PDFs get a generated thumbnail; images fall back to the raw file.
    val thumb = thumbnail?.path
    return if (thumb != null) "$base/api/user/files/$thumb/raw" else "$base/api/user/files/$id/raw"
}

/**
 * Resolves the `url` a Zipline response carries into something usable outside the app.
 *
 * It is route-relative (`/u/name`) on most endpoints, but absolute when the instance is
 * configured with a return domain - so both cases have to be handled, and a link that stays
 * relative is silently broken the moment it is copied into a chat.
 */
fun resolveShareUrl(url: String?, name: String, baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    return when {
        url == null -> "$base/u/$name"
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> base + if (url.startsWith("/")) url else "/$url"
    }
}

/** The shareable link. `url` is route-relative on /api/user/files and /api/user/recent. */
fun ZFile.shareUrl(baseUrl: String): String = resolveShareUrl(url, name, baseUrl)

/** Same resolution for a freshly uploaded file, which the server returns as a different shape. */
fun UploadedFile.shareUrl(baseUrl: String): String = resolveShareUrl(url, name, baseUrl)

/**
 * The file itself, never the thumbnail. Playback needs the real media; [previewUrl] deliberately
 * prefers the generated thumbnail, which for a video is a still image.
 */
fun ZFile.rawUrl(baseUrl: String): String =
    "${baseUrl.trimEnd('/')}/api/user/files/$id/raw"

fun ZFile.isPlayable(): Boolean = type.startsWith("video/") || type.startsWith("audio/")

/**
 * Opens in the text viewer. Any `text/` subtype covers most of it; the rest are the code and
 * config types Zipline hands out for snippets, which are textual despite an `application` prefix.
 *
 * Note: no wildcard in this comment on purpose - Kotlin nests block comments, so a slash-star
 * inside one silently swallows the rest of the file.
 */
fun ZFile.isTextLike(): Boolean = type.startsWith("text/") || type in TEXTUAL_TYPES

private val TEXTUAL_TYPES = setOf(
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-sh",
    "application/sql",
    "application/yaml",
    "application/x-yaml",
    "application/toml",
)

/**
 * What the full-screen viewer should load.
 *
 * For images this is the file itself, never the thumbnail: the thumbnail is a downscaled still,
 * so using it showed GIFs as a single frozen frame and every other image at preview resolution.
 * Non-images (a PDF, say) keep the generated thumbnail, since the raw bytes would not render.
 */
fun ZFile.viewerUrl(baseUrl: String): String =
    if (type.startsWith("image/")) rawUrl(baseUrl) else previewUrl(baseUrl)

/** Opens in the full-screen viewer: images, anything with a thumbnail, and playable media. */
fun ZFile.hasVisualPreview(): Boolean =
    type.startsWith("image/") || thumbnail != null || isPlayable()

/** True when tapping the file should open something in-app rather than just copy its link. */
fun ZFile.isOpenable(): Boolean = hasVisualPreview() || isTextLike()
