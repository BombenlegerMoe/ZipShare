package dev.zipshare.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zipshare.log.AppLog
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

/**
 * Keystore-backed AES256_SIV (keys) / AES256_GCM (values) prefs. StrongBox is requested when the
 * device exposes it, falling back transparently when key generation is refused.
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext private val context: Context) {

    val prefs: SharedPreferences by lazy { create() }

    /**
     * Plain, unencrypted, and deliberately so: it records that the encrypted store had to be
     * discarded, which is precisely the moment nothing encrypted can be read. It holds one boolean
     * and never anything sensitive.
     */
    private val recovery: SharedPreferences by lazy {
        context.getSharedPreferences(RECOVERY_FILE, Context.MODE_PRIVATE)
    }

    private fun create(): SharedPreferences = runCatching { open(strongBox = strongBoxAvailable()) }
        .recoverCatching { open(strongBox = false) }
        .recoverCatching {
            // A corrupt keyset (e.g. after a Keystore reset) must not brick the app. Deleting the
            // file is the only way back, but it also takes every server profile and token with it,
            // so leave a marker: silently returning the user to an empty sign-in screen is
            // indistinguishable from a bug or an unexplained logout.
            context.deleteSharedPreferences(FILE)
            recovery.edit { putBoolean(KEY_WAS_RESET, true) }
            open(strongBox = false)
        }
        .getOrThrow()

    /**
     * True exactly once after the encrypted store was discarded, so the notice shows on the next
     * screen the user sees and not on every launch afterwards.
     */
    fun consumeKeysetReset(): Boolean {
        if (!recovery.getBoolean(KEY_WAS_RESET, false)) return false
        recovery.edit { remove(KEY_WAS_RESET) }
        return true
    }

    private fun strongBoxAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")

    private fun open(strongBox: Boolean): SharedPreferences {
        val key = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .apply { if (strongBox) setRequestStrongBoxBacked(true) }
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

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
        prefs.edit {
            putString(SECRET_PREFIX + id, value)
            putLong(STARTED_PREFIX + id, System.currentTimeMillis())
        }
        return id
    }

    fun uploadSecret(id: String): String? = prefs.getString(SECRET_PREFIX + id, null)

    fun removeUploadSecret(id: String) {
        prefs.edit { remove(SECRET_PREFIX + id); remove(STARTED_PREFIX + id) }
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
        val ids = prefs.all.keys
            .filter { it.startsWith(SECRET_PREFIX) }
            .map { it.removePrefix(SECRET_PREFIX) }
        if (ids.isEmpty()) return

        val editor = prefs.edit()
        var swept = 0
        ids.forEach { id ->
            if (isStaleSecret(prefs.getLong(STARTED_PREFIX + id, 0L), now, maxAgeMillis)) {
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
        const val FILE = "zipshare_secure"
        const val RECOVERY_FILE = "zipshare_recovery"
        const val KEY_WAS_RESET = "keyset_was_reset"
        const val SECRET_PREFIX = "upload_secret_"

        /**
         * Must not itself start with [SECRET_PREFIX], or the sweep would read its own timestamp
         * keys back as secrets and try to expire them.
         */
        const val STARTED_PREFIX = "upload_started_"
        const val MAX_SECRET_AGE_MS = 24L * 60 * 60 * 1000
    }
}
