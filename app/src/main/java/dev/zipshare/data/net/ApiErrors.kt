package dev.zipshare.data.net

/** Side effect the app must perform on top of showing the message. */
enum class ErrorAction { NONE, REAUTH, CLEAR_FOLDER, NEEDS_PASSWORD }

/**
 * Thrown for any non-2xx response. [serverError] is the server's `error` string, surfaced verbatim.
 * Deliberately NOT an IOException, so retry logic can tell transport failures from protocol errors.
 */
class ZiplineException(
    val code: Int,
    val statusCode: Int,
    val serverError: String,
    val display: String,
    val action: ErrorAction,
) : Exception(display)

object ApiErrors {

    /** Retry only on transport failures and 5xx. Never on 4xx. */
    fun retryable(statusCode: Int): Boolean = statusCode >= 500 || statusCode == 0

    fun actionFor(code: Int): ErrorAction = when (code) {
        2001 -> ErrorAction.REAUTH
        4001 -> ErrorAction.CLEAR_FOLDER
        3004, 3005 -> ErrorAction.NEEDS_PASSWORD
        else -> ErrorAction.NONE
    }

    /**
     * Maps a Zipline error code to a message. The server's own `error` string is always appended
     * verbatim so nothing is hidden; unknown codes fall back to "E{code}: {error}".
     */
    fun message(code: Int, serverError: String): String {
        val explanation = when (code) {
            1000 -> "Invalid request schema"
            1001 -> "Invalid upload options"
            1002 -> "Invalid partial upload (chunk range rejected)"
            1003 -> "Partial upload session expired - restart the upload"
            1004 -> "Partial upload was not detected"
            1005 -> "Partial uploads accept exactly one file"
            1006 -> "File extension is not allowed on this server"
            1007 -> "Invalid characters in filename"
            1008 -> "Invalid characters in original filename"
            1009 -> "Invalid filename"
            1010 -> "Unrecognized file mimetype"
            1014 -> "A file with this name already exists - change or clear the filename override"
            1035 -> "That invite is invalid, expired, or already used up"
            1036 -> "This server has invites disabled"
            1037 -> "This server does not allow open registration - you need an invite link"
            1039 -> "That username is already taken"
            1044 -> "Incorrect username or password"
            1045 -> "Incorrect two-factor code"
            1053 -> "Two-factor authentication is not enabled on this account"
            2001 -> "Invalid token"
            3004 -> "File is password protected"
            3005 -> "Incorrect password"
            4001 -> "Folder not found"
            5000 -> "File size exceeds the configured limit"
            5001 -> "File is too large"
            5002 -> "Storage quota exceeded"
            9001 -> "Forbidden"
            9002 -> "Not found"
            9004 -> "Internal server error"
            else -> null
        }

        // Some deployments prefix the error string with "E1234: " themselves. Strip it before
        // building the message - previously this only happened for codes in the table above, so
        // an unknown code rendered as "E1045: E1045: Invalid code".
        val extra = serverError.trim().removePrefix("E$code:").trim()

        if (explanation == null) return "E$code: ${extra.ifBlank { "Unknown error" }}"

        return if (extra.isEmpty() || extra.equals(explanation, ignoreCase = true)) {
            "E$code: $explanation"
        } else {
            "E$code: $explanation - $extra"
        }
    }

    fun from(body: ApiErrorBody?, httpStatus: Int, rawBody: String?): ZiplineException {
        val code = body?.code ?: 0
        val serverError = body?.error?.takeIf { it.isNotBlank() }
            ?: rawBody?.take(300)?.takeIf { it.isNotBlank() }
            ?: "HTTP $httpStatus"
        val status = body?.statusCode?.takeIf { it > 0 } ?: httpStatus
        val display = if (code == 0) "HTTP $httpStatus: $serverError" else message(code, serverError)
        return ZiplineException(code, status, serverError, display, actionFor(code))
    }
}
