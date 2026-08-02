package dev.zipshare.data.model

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val baseUrl: String,
    val token: String,
    val pinnedSpkiSha256: String? = null,
    val allowCleartext: Boolean = false,
    /** Flipped to false when the server answers E2001; the UI then prompts for a new token. */
    val authenticated: Boolean = true,
) {
    /** Retrofit requires a trailing slash on the base url. */
    val retrofitBase: String get() = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    val host: String? get() = baseUrl.toHttpUrlOrNull()?.host
}

/** Every caller reads only [message], so the reason travels as the text itself. */
class BaseUrlException(message: String) : Exception(message)

object BaseUrl {

    /** Parses and normalises a user-typed base url. */
    fun parse(raw: String, allowCleartext: Boolean): Result<HttpUrl> {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return fail("That is not a valid URL.")

        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"

        val url = withScheme.toHttpUrlOrNull() ?: return fail("That is not a valid URL.")

        if (url.querySize > 0 || url.fragment != null) {
            return fail("Base URL must not contain a query string or fragment.")
        }
        if (!url.isHttps && !allowCleartext) {
            return fail(
                "http:// requires \"Allow cleartext\", and the host must be listed in network_security_config.xml.",
            )
        }
        return Result.success(url)
    }

    private fun fail(message: String) = Result.failure<HttpUrl>(BaseUrlException(message))
}
