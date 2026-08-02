package dev.zipshare

import dev.zipshare.data.net.BulkDeleteBody
import dev.zipshare.data.net.CreateFolderBody
import dev.zipshare.data.net.DeleteFolderBody
import dev.zipshare.data.net.DeleteUserBody
import dev.zipshare.data.net.PatchFileBody
import dev.zipshare.data.net.RegisterBody
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the exact serializer settings the API depends on.
 *
 * Three separate bugs came from the same cause: kotlinx.serialization omits a property whose
 * value equals its declared default, so required fields silently vanished from request bodies -
 * the folder delete failed with E1000, bulk delete left orphaned files on disk, and a password
 * could never be cleared. These tests pin both halves of the contract: defaults ARE sent, nulls
 * are NOT.
 */
class RequestBodyEncodingTest {

    /** Mirrors the converter in ZiplineClients. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `a required field equal to its default is still sent`() {
        val body = json.encodeToString(
            DeleteFolderBody.serializer(),
            DeleteFolderBody(delete = "folder", childrenAction = "root"),
        )
        assertTrue("delete missing from $body", body.contains("\"delete\":\"folder\""))
        assertTrue(body.contains("\"childrenAction\":\"root\""))
    }

    @Test
    fun `boolean flags survive even when they match the default`() {
        val del = json.encodeToString(
            BulkDeleteBody.serializer(),
            BulkDeleteBody(files = listOf("a"), deleteDatasourceFiles = true),
        )
        assertTrue("flag missing from $del", del.contains("\"delete_datasourceFiles\":true"))

        val user = json.encodeToString(DeleteUserBody.serializer(), DeleteUserBody(delete = false))
        assertTrue("false flag missing from $user", user.contains("\"delete\":false"))
    }

    @Test
    fun `nulls stay absent so a PATCH only changes what was set`() {
        val body = json.encodeToString(
            PatchFileBody.serializer(),
            PatchFileBody(favorite = true),
        )
        assertEquals("{\"favorite\":true}", body)
        assertFalse("null fields must not be sent: $body", body.contains("null"))
    }

    /**
     * The absent/present distinction is load-bearing here: an omitted `code` asks the server for
     * open registration, a present one asks it to spend an invite. Sending `"code": null` would
     * be a third thing the server does not accept.
     */
    @Test
    fun `registration omits the invite code entirely when there is none`() {
        val open = json.encodeToString(
            RegisterBody.serializer(),
            RegisterBody(username = "ada", password = "s3cret"),
        )
        assertEquals("{\"username\":\"ada\",\"password\":\"s3cret\"}", open)

        val invited = json.encodeToString(
            RegisterBody.serializer(),
            RegisterBody(username = "ada", password = "s3cret", code = "welcome1"),
        )
        assertTrue("code missing from $invited", invited.contains("\"code\":\"welcome1\""))
    }

    @Test
    fun `create bodies carry their defaults`() {
        val body = json.encodeToString(
            CreateFolderBody.serializer(),
            CreateFolderBody(name = "Holiday"),
        )
        assertTrue(body.contains("\"name\":\"Holiday\""))
        assertTrue("isPublic missing from $body", body.contains("\"isPublic\":false"))
    }
}
