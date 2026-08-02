package dev.zipshare.log

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-wide diagnostic log, encrypted at rest.
 *
 * Every line is AES-256-GCM encrypted with a non-exportable Android Keystore key before it
 * touches disk, so the file is ciphertext even to root or a physical extraction - it can only be
 * read back through [export], i.e. through the app itself. Lines are encrypted individually so
 * the file stays append-only and one corrupt line never takes out the rest.
 *
 * Two channels:
 * - the main log ([log]/[export]), readable only behind the app lock (Settings), and
 * - the login log ([logAuth]/[exportAuth]), which records ONLY lock/unlock events and is
 *   exportable from the lock screen itself - i.e. without authenticating. Nothing that goes in
 *   it may reference files, servers or any other app content.
 *
 * Call sites must never pass secrets (tokens, passwords, full share URLs); the encryption is a
 * containment layer, not permission to log them - the export IS clear text by design.
 *
 * A plain object rather than an injected type so data-layer code without DI access
 * (e.g. Response.unwrap) can log too. All file and Keystore I/O runs on one background thread;
 * [log] and [logAuth] never block the caller.
 */
object AppLog {

    private const val KEY_ALIAS = "zipshare_log"
    private const val MAIN = "app.log"
    private const val AUTH = "auth.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_AUTH_BYTES = 64 * 1024L

    @Volatile
    private var appContext: Context? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "applog") }

    /** Confined to the executor thread. */
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Lazily created on the executor thread - Keystore I/O must stay off the main thread. */
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun log(tag: String, message: String) = write(MAIN, MAX_BYTES, "[$tag] $message")

    /** Lock/unlock events only - this channel is exportable without unlocking the app. */
    fun logAuth(message: String) = write(AUTH, MAX_AUTH_BYTES, "[auth] $message")

    /** Decrypts the main log to clear text, oldest line first. Blocks; call from a worker thread. */
    fun export(): String = read(MAIN)

    /** Decrypts the login log to clear text. Blocks; call from a worker thread. */
    fun exportAuth(): String = read(AUTH)

    /** Blocks; call from a worker thread. */
    fun clear() {
        delete(MAIN)
        log("log", "log cleared")
    }

    /** Blocks; call from a worker thread. */
    fun clearAuth() {
        delete(AUTH)
        logAuth("login log cleared")
    }

    private fun write(base: String, maxBytes: Long, line: String) {
        val ctx = appContext ?: return
        val at = System.currentTimeMillis()
        executor.execute {
            runCatching { append(ctx, base, maxBytes, "${format.format(Date(at))} $line") }
        }
    }

    private fun read(base: String): String {
        val ctx = appContext ?: return ""
        return executor.submit(
            Callable {
                files(ctx, base)
                    .filter(File::exists)
                    .flatMap { it.readLines() }
                    .joinToString("\n") { line -> runCatching { decrypt(line) }.getOrDefault("[unreadable line]") }
            },
        ).get()
    }

    private fun delete(base: String) {
        val ctx = appContext ?: return
        executor.submit { files(ctx, base).forEach(File::delete) }.get()
    }

    /** Oldest first, so export reads in chronological order. */
    private fun files(ctx: Context, base: String): List<File> {
        val dir = File(ctx.filesDir, "logs")
        return listOf(File(dir, "$base.1"), File(dir, base))
    }

    private fun append(ctx: Context, base: String, maxBytes: Long, line: String) {
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, base)
        if (file.length() > maxBytes) {
            File(dir, "$base.1").delete()
            file.renameTo(File(dir, "$base.1"))
        }
        // The Keystore mandates its own random IV; ship it alongside the ciphertext.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(line.toByteArray(Charsets.UTF_8))
        val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP) + "\n"
        FileOutputStream(file, true).use { it.write(record.toByteArray(Charsets.US_ASCII)) }
    }

    private fun decrypt(record: String): String {
        val (iv, ct) = record.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
