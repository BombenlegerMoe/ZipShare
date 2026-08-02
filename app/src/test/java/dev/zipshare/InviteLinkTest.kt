package dev.zipshare

import dev.zipshare.data.model.parseInviteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The invite link is the only thing a new user is given, so it has to survive being pasted in
 * whatever form it arrived: a full URL, a bare code, a host without a scheme, or a server hosted
 * under a subpath.
 */
class InviteLinkTest {

    @Test
    fun `a full link yields both the code and the server`() {
        val invite = parseInviteLink("https://zipline.example.com/invite/abc123")!!
        assertEquals("abc123", invite.code)
        assertEquals("https://zipline.example.com", invite.baseUrl)
    }

    @Test
    fun `a bare code is accepted and leaves the server for the user to type`() {
        val invite = parseInviteLink("  abc123 ")!!
        assertEquals("abc123", invite.code)
        assertNull(invite.baseUrl)
    }

    @Test
    fun `a missing scheme still parses, because nobody copies the https`() {
        val invite = parseInviteLink("zipline.example.com/invite/abc123")!!
        assertEquals("abc123", invite.code)
        assertEquals("https://zipline.example.com", invite.baseUrl)
    }

    @Test
    fun `a subpath install keeps its prefix out of the code and in the base url`() {
        val invite = parseInviteLink("https://example.com/zipline/invite/xyz")!!
        assertEquals("xyz", invite.code)
        assertEquals("https://example.com/zipline", invite.baseUrl)
    }

    @Test
    fun `a non-default port survives`() {
        val invite = parseInviteLink("http://10.0.2.2:8099/invite/code9")!!
        assertEquals("code9", invite.code)
        assertEquals("http://10.0.2.2:8099", invite.baseUrl)
    }

    @Test
    fun `a link that is not an invite is rejected rather than guessed at`() {
        assertNull(parseInviteLink("https://zipline.example.com/dashboard/files"))
        assertNull(parseInviteLink("https://zipline.example.com/invite/"))
        assertNull(parseInviteLink("   "))
    }
}
