package dev.zipshare

import dev.zipshare.upload.perFileSecrets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A batch once shared one password id. The first worker removed it when it finished, so file 2
 * failed with "password was lost" and file 3 never ran.
 */
class BatchSecretTest {

    @Test
    fun `every file of a password batch gets its own secret`() {
        val stored = mutableListOf<String>()
        val ids = perFileSecrets(3, "geheim123") { stored += it; "id${stored.size}" }
        assertEquals(listOf("id1", "id2", "id3"), ids)
        assertEquals(listOf("geheim123", "geheim123", "geheim123"), stored)
    }

    @Test
    fun `no password stores nothing`() {
        var calls = 0
        assertEquals(listOf(null, null), perFileSecrets(2, null) { calls++; "x" })
        assertEquals(listOf(null), perFileSecrets(1, "  ") { calls++; "x" })
        assertEquals(0, calls)
    }
}
