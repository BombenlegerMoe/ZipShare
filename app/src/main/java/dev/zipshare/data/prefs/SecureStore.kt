package dev.zipshare.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zipshare.log.AppLog
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

/**
 * Whether an upload secret started at [startedAt] can no longer belong to a live upload.
 *
 * A missing timestamp (0) means the secret was written by a build older than the sweep, so nothing
 * is known about its age and it cannot be tied to running work either - it is swept rather than
 * kept forever, which is the leak the sweep exists to close.
 *
 * A timestamp in the future is treated as fresh: the device clock moving backwards must not be
 * able to delete a secret that an upload is still using.
 *
 * Pure so the rule can be tested without Keystore or a Context.
 */
internal fun isStaleSecret(startedAt: Long, now: Long, maxAgeMillis: Long): Boolean =
    startedAt <= 0L || now - startedAt > maxAgeMillis

private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

/**
 * AES-GCM encrypts [value] as base64(iv || ciphertext). [name] is authenticated alongside it, so a
 * sealed value copied under a different pref key fails to open instead of being read as that key.
 */
internal fun seal(key: SecretKey, name: String, value: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    // No IV passed: Keystore refuses caller-chosen IVs and generates a fresh random one itself.
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
}

/** Inverse of [seal]; throws if the value was tampered with, moved to another name, or the key differs. */
internal fun unseal(key: SecretKey, name: String, sealed: String): String {
    val bytes = Base64.getDecoder().decode(sealed)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES))
    cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
    return String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8)
}

/**
 * Whether [e], thrown while opening the migrated store, proves its data is gone for good: the tag
 * no longer verifies (a different or reset key) or the stored bytes are garbage. Anything else -
 * a busy or briefly failing Keystore - may pass, and wiping the store over it would throw away
 * every profile and token for nothing.
 */
internal fun isPermanentLoss(e: Throwable): Boolean =
    e is BadPaddingException || e is IllegalArgumentException

/** Every non-null legacy entry sealed under its own name. Values are stored as strings (longs included). */
internal fun sealAll(key: SecretKey, entries: Map<String, *>): Map<String, String> =
    entries.mapNotNull { (name, value) -> value?.let { name to seal(key, name, it.toString()) } }.toMap()

/** True only if every non-null [source] entry reads back from [stored] as the same value. */
internal fun verifyMigrated(key: SecretKey, source: Map<String, *>, stored: (String) -> String?): Boolean =
    source.all { (name, value) ->
        value == null || stored(name)?.let { runCatching { unseal(key, name, it) }.getOrNull() } == value.toString()
    }

/**
 * Plain SharedPreferences whose values are AES-256-GCM sealed under a key that lives only in the
 * Android Keystore (StrongBox when the device has it). Key names are stored in the clear; none of
 * them is secret.
 *
 * Replaces androidx.security-crypto's EncryptedSharedPreferences, deprecated in 1.1.0. Opening
 * migrates an old install's store once (see [migrate]); opening is disk I/O plus Keystore work, so
 * the first access must happen off the main thread.
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext private val context: Context) {

    private class Opened(val prefs: SharedPreferences, val key: SecretKey)

    private val opened by lazy { open() }

    /**
     * Plain, unencrypted, and deliberately so: it records that the encrypted store had to be
     * discarded, which is precisely the moment nothing encrypted can be read. It holds one boolean
     * and never anything sensitive.
     */
    private val recovery: SharedPreferences by lazy {
        context.getSharedPreferences(RECOVERY_FILE, Context.MODE_PRIVATE)
    }

    fun getString(name: String): String? =
        opened.prefs.getString(name, null)?.let { unseal(opened.key, name, it) }

    /** Null removes the entry. */
    fun putString(name: String, value: String?) {
        opened.prefs.edit { if (value == null) remove(name) else putString(name, seal(opened.key, name, value)) }
    }

    private fun open(): Opened {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString(KEY_MIGRATED, null)?.let { marker ->
            // Only a definite answer may wipe the store. A Keystore exception here is rethrown with
            // the data untouched, so the next access or launch simply tries again.
            val key = keystore().getKey(KEY_ALIAS, null) as SecretKey?
            if (key != null) {
                try {
                    unseal(key, KEY_MIGRATED, marker)
                    // A crash between the marker commit and the delete leaves the old file behind.
                    if (legacyExists()) context.deleteSharedPreferences(LEGACY_FILE)
                    return Opened(prefs, key)
                } catch (e: Exception) {
                    if (!isPermanentLoss(e)) throw e
                }
            }
            // The key is gone or no longer opens this file (e.g. a Keystore reset): nothing sealed
            // under it can ever be read again. Start over, and say so rather than silently signing
            // the user out.
            prefs.edit(commit = true) { clear() }
            deleteKeystoreKey()
            flagReset()
        }
        val key = keystoreKey()
        migrate(prefs, key)
        return Opened(prefs, key)
    }

    /**
     * One-time copy out of the old EncryptedSharedPreferences file. Crash-safe by ordering: the old
     * file is only deleted after the copy is committed, read back, decrypted and compared, and the
     * marker is committed. Dying at any earlier point leaves the old file intact and this runs
     * again from scratch on the next launch (the clear() discards a half-finished earlier try).
     */
    private fun migrate(prefs: SharedPreferences, key: SecretKey) {
        val legacy = legacyExists()
        val old: Map<String, *> = if (!legacy) emptyMap<String, Any?>() else readLegacy() ?: run {
            // Same outcome the old store had for a corrupt keyset: it is unreadable for good.
            flagReset()
            emptyMap<String, Any?>()
        }
        val sealed = sealAll(key, old)
        check(prefs.edit().clear().apply { sealed.forEach { (k, v) -> putString(k, v) } }.commit()) {
            "secure store: migration write failed"
        }
        check(verifyMigrated(key, old) { prefs.getString(it, null) }) { "secure store: migration verify failed" }
        check(prefs.edit().putString(KEY_MIGRATED, seal(key, KEY_MIGRATED, "1")).commit()) {
            "secure store: migration marker write failed"
        }
        if (legacy) {
            context.deleteSharedPreferences(LEGACY_FILE)
            // Count only - never names or values.
            AppLog.log("secure", "migrated ${sealed.size} entr(y/ies) off EncryptedSharedPreferences")
        }
    }

    private fun legacyExists(): Boolean {
        val dir = File(context.dataDir, "shared_prefs")
        // The .bak is what SharedPreferences restores from when a write was interrupted.
        return File(dir, "$LEGACY_FILE.xml").exists() || File(dir, "$LEGACY_FILE.xml.bak").exists()
    }

    /**
     * Null when the old store cannot be decrypted. StrongBox is tried first like the old store did;
     * an existing master key is reused regardless of that flag.
     *
     * shortcut: keeps androidx.security-crypto solely for this read; drop the dependency and this
     * function once no install older than this release can still be upgraded.
     */
    @Suppress("DEPRECATION")
    private fun readLegacy(): Map<String, *>? {
        fun read(strongBox: Boolean): Map<String, *> {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .apply { if (strongBox) setRequestStrongBoxBacked(true) }
                .build()
            return androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                LEGACY_FILE,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).all
        }
        return runCatching { read(strongBoxAvailable()) }.recoverCatching { read(false) }.getOrNull()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keystoreKey(): SecretKey {
        (keystore().getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .apply {
                            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
                        }
                        .build(),
                )
                generateKey()
            }
        // StrongBoxUnavailableException and friends: fall back to the TEE rather than fail.
        return if (strongBoxAvailable()) runCatching { generate(true) }.getOrElse { generate(false) } else generate(false)
    }

    private fun deleteKeystoreKey() {
        runCatching { keystore().deleteEntry(KEY_ALIAS) }
    }

    private fun flagReset() {
        recovery.edit(commit = true) { putBoolean(KEY_WAS_RESET, true) }
    }

    /**
     * True exactly once after the encrypted store was discarded, so the notice shows on the next
     * screen the user sees and not on every launch afterwards. Blocks on disk I/O.
     */
    fun consumeKeysetReset(): Boolean {
        // Opening is what discovers a reset, so make sure it has happened before checking.
        runCatching { opened }
        if (!recovery.getBoolean(KEY_WAS_RESET, false)) return false
        recovery.edit { remove(KEY_WAS_RESET) }
        return true
    }

    private fun strongBoxAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")

    /**
     * Stash for an upload password on its way to a Worker.
     *
     * WorkManager persists a Worker's input Data as a plaintext blob in androidx.work.workdb, so
     * an x-zipline-password must never travel inside it. The Data carries only this opaque id and
     * the secret itself stays in the encrypted prefs until the work reaches a terminal state.
     */
    fun putUploadSecret(value: String): String {
        val id = java.util.UUID.randomUUID().toString()
        // Written together so a secret can never exist without the timestamp the sweep judges it by.
        opened.prefs.edit {
            putString(SECRET_PREFIX + id, seal(opened.key, SECRET_PREFIX + id, value))
            putString(STARTED_PREFIX + id, seal(opened.key, STARTED_PREFIX + id, System.currentTimeMillis().toString()))
        }
        return id
    }

    fun uploadSecret(id: String): String? = getString(SECRET_PREFIX + id)

    fun removeUploadSecret(id: String) {
        opened.prefs.edit { remove(SECRET_PREFIX + id); remove(STARTED_PREFIX + id) }
    }

    /**
     * Drops upload secrets that no live upload can still be using.
     *
     * The worker removes its own secret on every ending including cancellation, but a process
     * killed mid-upload never runs that code at all - and nothing else would ever remove the key,
     * so passwords would accumulate for the life of the install. Age is the only usable signal
     * here: WorkManager does not expose a pending job's input data, so a secret cannot be matched
     * back to the work that owns it.
     *
     * [maxAgeMillis] is deliberately generous. A multi-gigabyte upload over a slow link, plus
     * WorkManager's exponential backoff across five retries, can legitimately span hours; sweeping
     * a secret still in use would fail that upload with a wrong password.
     *
     * Blocks on disk I/O; call from a worker thread.
     */
    fun sweepUploadSecrets(maxAgeMillis: Long = MAX_SECRET_AGE_MS, now: Long = System.currentTimeMillis()) {
        val prefs = opened.prefs
        val ids = prefs.all.keys
            .filter { it.startsWith(SECRET_PREFIX) }
            .map { it.removePrefix(SECRET_PREFIX) }
        if (ids.isEmpty()) return

        val editor = prefs.edit()
        var swept = 0
        ids.forEach { id ->
            // An unreadable timestamp counts as missing, which isStaleSecret treats as stale.
            val started = runCatching { getString(STARTED_PREFIX + id)?.toLong() }.getOrNull() ?: 0L
            if (isStaleSecret(started, now, maxAgeMillis)) {
                editor.remove(SECRET_PREFIX + id).remove(STARTED_PREFIX + id)
                swept++
            }
        }
        if (swept > 0) {
            editor.apply()
            // Count only - never the id, which is the handle to the secret itself.
            AppLog.log("upload", "swept $swept orphaned upload secret(s)")
        }
    }

    private companion object {
        const val FILE = "zipshare_vault"
        const val LEGACY_FILE = "zipshare_secure"
        const val RECOVERY_FILE = "zipshare_recovery"
        const val KEY_WAS_RESET = "keyset_was_reset"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zipshare_vault_key"

        /** Sealed so it doubles as the check that the Keystore key still opens this file. */
        const val KEY_MIGRATED = "__migrated"
        const val SECRET_PREFIX = "upload_secret_"

        /**
         * Must not itself start with [SECRET_PREFIX], or the sweep would read its own timestamp
         * keys back as secrets and try to expire them.
         */
        const val STARTED_PREFIX = "upload_started_"
        const val MAX_SECRET_AGE_MS = 24L * 60 * 60 * 1000
    }
}
