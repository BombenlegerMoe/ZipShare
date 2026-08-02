package dev.zipshare.data.net

import dev.zipshare.log.AppLog
import kotlinx.serialization.json.Json
import retrofit2.Response

private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Unwraps a Retrofit [Response], parsing Zipline's error DTO on every failure.
 * Never logs or rethrows anything containing the token.
 *
 * Every API failure in the app funnels through here, so this one AppLog call covers them all.
 * Method + path only - no host, no query, no headers.
 */
fun <T> Response<T>.unwrap(): T {
    if (isSuccessful) {
        return body() ?: run {
            logFailure("empty response body")
            throw ZiplineException(
                code = 0,
                statusCode = code(),
                serverError = "Empty response body",
                display = "HTTP ${code()}: empty response body",
                action = ErrorAction.NONE,
            )
        }
    }
    val raw = runCatching { errorBody()?.string() }.getOrNull()
    val parsed = raw?.let {
        runCatching { errorJson.decodeFromString(ApiErrorBody.serializer(), it) }.getOrNull()
    }
    logFailure(parsed?.let { "E${it.code} ${it.error}" } ?: "unparsed error body")
    throw ApiErrors.from(parsed, code(), raw)
}

private fun <T> Response<T>.logFailure(detail: String) {
    val req = raw().request
    AppLog.log("api", "${req.method} ${req.url.encodedPath} -> HTTP ${code()}: $detail")
}
