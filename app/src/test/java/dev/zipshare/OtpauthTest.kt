package dev.zipshare

import dev.zipshare.data.model.otpauthUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A malformed otpauth URI fails in the worst possible way: the authenticator accepts it and then
 * produces codes the server rejects, or files the entry under a nonsense name. Both halves of the
 * label and the secret have to survive encoding exactly.
 */
class OtpauthTest {

    @Test
    fun `builds the label and issuer the way authenticators expect`() {
        assertEquals(
            "otpauth://totp/Zipline:ada?secret=JBSWY3DPEHPK3PXP&issuer=Zipline",
            otpauthUri(secret = "JBSWY3DPEHPK3PXP", username = "ada"),
        )
    }

    @Test
    fun `spaces become percent-20, never plus`() {
        // URLEncoder is form encoding and would emit '+', which authenticators show literally.
        val uri = otpauthUri(secret = "ABC", username = "ada lovelace", issuer = "My Files")
        assertEquals("otpauth://totp/My%20Files:ada%20lovelace?secret=ABC&issuer=My%20Files", uri)
        assertTrue("no raw plus may appear: $uri", !uri.contains("+"))
    }

    @Test
    fun `a colon in the username cannot break the label into three parts`() {
        val uri = otpauthUri(secret = "ABC", username = "a:b")
        assertTrue("colon must be escaped: $uri", uri.contains("Zipline:a%3Ab"))
    }
}
