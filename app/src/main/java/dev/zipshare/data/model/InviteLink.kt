package dev.zipshare.data.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What an invite gives us: the code, and - when the user pasted the whole link - the server it
 * belongs to. Zipline hands out invites as `https://host/invite/CODE`, so accepting the link
 * rather than just the code means the server address is filled in for free.
 */
data class InviteLink(val code: String, val baseUrl: String?)

/**
 * Parses an invite. Anything that is not a URL is taken as a bare code, because people paste
 * both, and the code is the one part that cannot be inferred from anything else.
 *
 * Returns null only when the input is a URL with no `/invite/<code>` in it - guessing a code out
 * of an arbitrary link would just produce a confusing server error later.
 */
fun parseInviteLink(raw: String): InviteLink? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val looksLikeUrl = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.contains("/invite/")
    if (!looksLikeUrl) return InviteLink(code = trimmed, baseUrl = null)

    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return InviteLink(code = trimmed, baseUrl = null)

    val segments = url.pathSegments.filter { it.isNotEmpty() }
    val at = segments.indexOf("invite")
    if (at < 0 || at == segments.lastIndex) return null

    // Everything before /invite/ is the server root - Zipline can be hosted under a subpath.
    // Rebuilding through HttpUrl keeps the scheme and any non-default port right for free.
    val root = url.newBuilder()
        .encodedPath("/" + segments.take(at).joinToString("/"))
        .query(null)
        .fragment(null)
        .build()
    return InviteLink(code = segments[at + 1], baseUrl = root.toString().trimEnd('/'))
}
