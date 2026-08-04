package dev.zipshare.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
            recovery.edit().putBoolean(KEY_WAS_RESET, true).apply()
            open(strongBox = false)
        }
        .getOrThrow()

    /**
     * True exactly once after the encrypted store was discarded, so the notice shows on the next
     * screen the user sees and not on every launch afterwards.
     */
    fun consumeKeysetReset(): Boolean {
        if (!recovery.getBoolean(KEY_WAS_RESET, false)) return false
        recovery.edit().remove(KEY_WAS_RESET).apply()
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
        prefs.edit().putString(SECRET_PREFIX + id, value).apply()
        return id
    }

    fun uploadSecret(id: String): String? = prefs.getString(SECRET_PREFIX + id, null)

    fun removeUploadSecret(id: String) {
        prefs.edit().remove(SECRET_PREFIX + id).apply()
    }

    private companion object {
        const val FILE = "zipshare_secure"
        const val RECOVERY_FILE = "zipshare_recovery"
        const val KEY_WAS_RESET = "keyset_was_reset"
        const val SECRET_PREFIX = "upload_secret_"
    }
}
