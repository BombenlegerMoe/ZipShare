package dev.zipshare.data

import dev.zipshare.data.model.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted export of the server profiles, for moving to a new device.
 *
 * Separate from the settings export on purpose. That one is deliberately plaintext and deliberately
 * excludes servers, because it lands in the share sheet and can go anywhere. Profiles cannot be
 * exported the same way - they carry API tokens, which are the credential itself - so this file is
 * useless without the password that made it.
 *
 * Deliberately javax.crypto rather than the Android Keystore: a Keystore key never leaves the
 * device it was created on, which is exactly the opposite of what a migration backup needs. The
 * password is the only thing that can decrypt it, and it is never stored.
 *
 * Pure Kotlin with no Android types, so the round trip is testable on the JVM. [Base64] here is
 * java.util, not android.util, which the unit tests would stub out to null.
 */
object ProfileBackup {

    /** Bumped only if the envelope shape changes; the reader refuses anything it does not know. */
    private const val FORMAT = "zipshare-profiles"
    private const val VERSION = 1

    // OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Deliberately slow: the whole point is that a
    // stolen backup cannot be brute-forced faster than the user's password allows.
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Serializable
    private data class Envelope(
        val format: String,
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    class WrongPasswordException : Exception("Wrong password, or the file was modified.")
    class NotABackupException : Exception("That file is not a ZipShare profile backup.")

    /** Encrypts [profiles] under [password] and returns the file's text. */
    fun encrypt(profiles: List<Profile>, password: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val plain = json.encodeToString(ListSerializer(Profile.serializer()), profiles)
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        return json.encodeToString(
            Envelope.serializer(),
            Envelope(
                format = FORMAT,
                version = VERSION,
                kdf = "PBKDF2WithHmacSHA256",
                iterations = ITERATIONS,
                salt = b64(salt),
                iv = b64(iv),
                data = b64(sealed),
            ),
        )
    }

    /**
     * @throws NotABackupException when the file is not one of ours at all
     * @throws WrongPasswordException when the password is wrong *or* the ciphertext was tampered
     *   with - GCM cannot tell those apart, and pretending otherwise would be a lie
     */
    fun decrypt(text: String, password: String): List<Profile> {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }
            .getOrElse { throw NotABackupException() }
        if (envelope.format != FORMAT) throw NotABackupException()
        if (envelope.version > VERSION) {
            throw NotABackupException()
        }

        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    deriveKey(password, unb64(envelope.salt), envelope.iterations),
                    GCMParameterSpec(TAG_BITS, unb64(envelope.iv)),
                )
            }
            String(cipher.doFinal(unb64(envelope.data)), Charsets.UTF_8)
        }.getOrElse { throw WrongPasswordException() }

        return runCatching { json.decodeFromString(ListSerializer(Profile.serializer()), plain) }
            .getOrElse { throw NotABackupException() }
    }

    /**
     * Which of [imported] to actually add to [existing].
     *
     * Import adds rather than replaces: this runs on a device that may already be set up, and
     * dropping a working server to honour a file would be the worse mistake. The same server twice
     * is a duplicate rather than an update, so re-importing the same backup is a no-op instead of
     * piling up copies - matched on address and token, because ids are regenerated on import and
     * labels are the one field a user renames.
     */
    fun toAdd(existing: List<Profile>, imported: List<Profile>): List<Profile> =
        imported.filterNot { new ->
            existing.any { it.baseUrl == new.baseUrl && it.token == new.token }
        }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = ITERATIONS) =
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS))
                .encoded,
            "AES",
        )

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
