package dev.zipshare.data.model

import kotlinx.serialization.Serializable

/** What the copy buttons put on the clipboard. */
@Serializable
enum class LinkFormat {
    /** The bare URL, which chat apps expand into a preview with the URL still visible. */
    PLAIN,

    /** `[name](url)` - Discord, Slack and GitHub render this as the name alone, no preview. */
    MARKDOWN,

    /**
     * Zipline's `/view/` page rather than the raw file. That route serves HTML carrying the
     * OpenGraph tags from your "Viewing files" settings, so chat apps build a rich embed - title,
     * description, colour, image - instead of showing the bare URL.
     */
    VIEW,
}

/**
 * Rewrites a raw file URL into its view-page URL by replacing the route segment.
 *
 * `https://host/u/a.png` -> `https://host/view/a.png`, and a sub-path install
 * `https://host/zipline/u/a.png` -> `https://host/zipline/view/a.png`.
 *
 * Done by position rather than by matching "/u/": Zipline's raw route is configurable
 * (`files.route`), so the segment is not always called `u` - but it is always the one directly
 * before the file name, and `/view` is fixed.
 */
fun viewPageUrl(url: String): String {
    val cut = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
    val slash = url.indexOf('/', cut)
    if (slash < 0) return url

    val origin = url.substring(0, slash)
    val segments = url.substring(slash + 1).split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return url

    val leaf = segments.last()
    // Everything before the route segment survives, so a sub-path install keeps its prefix.
    val prefix = segments.dropLast(2).joinToString("/")
    return buildString {
        append(origin).append('/')
        if (prefix.isNotEmpty()) append(prefix).append('/')
        append("view/").append(leaf)
    }
}

/**
 * Builds the clipboard text for a file.
 *
 * Markdown needs both halves escaped or the link silently breaks on paste, and a broken link is
 * worse than a plain one:
 *
 * - `[` and `]` in the name would close the label early, so they are backslash-escaped.
 * - A URL containing brackets, spaces or parentheses ends the target early, so it goes in the
 *   `<...>` form that CommonMark and Discord both accept. Zipline filenames reach the URL, so
 *   "my file (2).png" is an ordinary case, not a pathological one.
 */
fun formatLink(name: String, url: String, format: LinkFormat): String = when (format) {
    LinkFormat.PLAIN -> url
    LinkFormat.VIEW -> viewPageUrl(url)
    LinkFormat.MARKDOWN -> {
        val label = name.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        val target = if (url.any { it == '(' || it == ')' || it == ' ' || it == '<' || it == '>' }) {
            "<" + url.replace("<", "%3C").replace(">", "%3E") + ">"
        } else {
            url
        }
        "[$label]($target)"
    }
}
