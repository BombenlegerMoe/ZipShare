package dev.zipshare

import dev.zipshare.data.net.ApiErrorBody
import dev.zipshare.data.net.ApiErrors
import dev.zipshare.data.net.ErrorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorsTest {

    @Test
    fun `unknown code does not print the E-prefix twice`() {
        // Regression: only known codes stripped the server's own prefix, so an unknown code
        // rendered as "E1099: E1099: Something". Seen live on the login path.
        assertEquals("E1099: Something broke", ApiErrors.message(1099, "E1099: Something broke"))
    }

    @Test
    fun `login failures map to readable messages`() {
        // The server's own wording is kept alongside ours, per the "surface error verbatim" rule,
        // but the duplicated "E1044:" prefix is stripped.
        assertEquals(
            "E1044: Incorrect username or password - Invalid username or password",
            ApiErrors.message(1044, "E1044: Invalid username or password"),
        )
        assertEquals(
            "E1045: Incorrect two-factor code - Invalid code",
            ApiErrors.message(1045, "E1045: Invalid code"),
        )
    }


    @Test
    fun `every explicitly mapped code renders its own explanation`() {
        val expected = mapOf(
            1000 to "Invalid request schema",
            1001 to "Invalid upload options",
            1006 to "File extension is not allowed on this server",
            1007 to "Invalid characters in filename",
            1008 to "Invalid characters in original filename",
            1009 to "Invalid filename",
            1010 to "Unrecognized file mimetype",
            1014 to "A file with this name already exists - change or clear the filename override",
            2001 to "Invalid token",
            3004 to "File is password protected",
            3005 to "Incorrect password",
            4001 to "Folder not found",
            5000 to "File size exceeds the configured limit",
            5001 to "File is too large",
            5002 to "Storage quota exceeded",
            9001 to "Forbidden",
            9002 to "Not found",
            9004 to "Internal server error",
        )
        expected.forEach { (code, explanation) ->
            assertEquals("E$code: $explanation", ApiErrors.message(code, "E$code: $explanation"))
        }
    }

    @Test
    fun `unknown code falls back to E-code plus the server string verbatim`() {
        assertEquals("E7777: something exploded", ApiErrors.message(7777, "something exploded"))
        assertEquals("E1234: Unknown error", ApiErrors.message(1234, ""))
    }

    @Test
    fun `server detail is appended verbatim, never swallowed`() {
        val message = ApiErrors.message(
            1001,
            "E1001: bad options[x-zipline-max-views]: Invalid max views (NaN)",
        )
        assertEquals(
            "E1001: Invalid upload options - bad options[x-zipline-max-views]: Invalid max views (NaN)",
            message,
        )
    }

    @Test
    fun `the E-prefix is not printed twice`() {
        val message = ApiErrors.message(5002, "E5002: Storage quota exceeded")
        assertEquals("E5002: Storage quota exceeded", message)
        assertFalse(message.contains("E5002: E5002"))
    }

    @Test
    fun `side effects are wired to the codes that require them`() {
        assertEquals(ErrorAction.REAUTH, ApiErrors.actionFor(2001))
        assertEquals(ErrorAction.CLEAR_FOLDER, ApiErrors.actionFor(4001))
        assertEquals(ErrorAction.NEEDS_PASSWORD, ApiErrors.actionFor(3004))
        assertEquals(ErrorAction.NEEDS_PASSWORD, ApiErrors.actionFor(3005))
        assertEquals(ErrorAction.NONE, ApiErrors.actionFor(1006))
        assertEquals(ErrorAction.NONE, ApiErrors.actionFor(9999))
    }

    @Test
    fun `4xx is never retried, 5xx and transport failures are`() {
        listOf(400, 401, 403, 404, 413, 429).forEach { assertFalse("$it", ApiErrors.retryable(it)) }
        listOf(500, 502, 503, 504).forEach { assertTrue("$it", ApiErrors.retryable(it)) }
        assertTrue("transport failure", ApiErrors.retryable(0))
    }

    @Test
    fun `from() prefers the parsed DTO status over the HTTP status`() {
        val e = ApiErrors.from(ApiErrorBody("E5001: File is too large", 5001, 413), 413, null)
        assertEquals(5001, e.code)
        assertEquals(413, e.statusCode)
        assertEquals("E5001: File is too large", e.serverError)
        assertEquals("E5001: File is too large", e.display)
        assertEquals(ErrorAction.NONE, e.action)
        assertFalse(ApiErrors.retryable(e.statusCode))
    }

    @Test
    fun `from() degrades gracefully when the body is not the error DTO`() {
        val e = ApiErrors.from(null, 502, "<html>bad gateway</html>")
        assertEquals(0, e.code)
        assertEquals(502, e.statusCode)
        assertEquals("HTTP 502: <html>bad gateway</html>", e.display)
        assertTrue(ApiErrors.retryable(e.statusCode))
    }

    @Test
    fun `from() with no body at all still yields something showable`() {
        val e = ApiErrors.from(null, 504, null)
        assertEquals("HTTP 504: HTTP 504", e.display)
        assertTrue(ApiErrors.retryable(e.statusCode))
    }

    @Test
    fun `E2001 marks the profile for re-entry`() {
        val e = ApiErrors.from(ApiErrorBody("E2001: Invalid token", 2001, 401), 401, null)
        assertEquals(ErrorAction.REAUTH, e.action)
        assertFalse(ApiErrors.retryable(e.statusCode))
    }
}
