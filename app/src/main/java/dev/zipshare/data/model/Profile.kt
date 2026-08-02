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

sealed interface UrlProblem {
    data object Unparseable : UrlProblem
    data object CleartextNotAllowed : UrlProblem
    data object HasQueryOrFragment : UrlProblem
}

class BaseUrlException(val problem: UrlProblem) : Exception(BaseUrl.message(problem))

object BaseUrl {

    /** Parses and normalises a user-typed base url. */
    fun parse(raw: String, allowCleartext: Boolean): Result<HttpUrl> {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return Result.failure(BaseUrlException(UrlProblem.Unparseable))

        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"

        val url = withScheme.toHttpUrlOrNull()
            ?: return Result.failure(BaseUrlException(UrlProblem.Unparseable))

        if (url.querySize > 0 || url.fragment != null) {
            return Result.failure(BaseUrlException(UrlProblem.HasQueryOrFragment))
        }
        if (!url.isHttps && !allowCleartext) {
            return Result.failure(BaseUrlException(UrlProblem.CleartextNotAllowed))
        }
        return Result.success(url)
    }

    fun message(problem: UrlProblem): String = when (problem) {
        UrlProblem.Unparseable -> "That is not a valid URL."
        UrlProblem.CleartextNotAllowed ->
            "http:// requires \"Allow cleartext\", and the host must be listed in network_security_config.xml."
        UrlProblem.HasQueryOrFragment -> "Base URL must not contain a query string or fragment."
    }
}
