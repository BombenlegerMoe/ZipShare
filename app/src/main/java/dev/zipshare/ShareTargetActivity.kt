package dev.zipshare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.model.Profile
import dev.zipshare.data.net.CreateUrlBody
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.shortLink
import dev.zipshare.data.net.unwrap
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.log.AppLog
import dev.zipshare.upload.UploadEnqueuer
import dev.zipshare.upload.UploadNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The one http(s) link in [text], or null when there is not exactly one.
 *
 * Apps share links inconsistently: Chrome sends the bare url, others prepend a title or a comment.
 * A single link in the text is unambiguous whatever surrounds it. Two or more is a guess, and
 * guessing would silently shorten the wrong one, so that case falls through to the normal path.
 *
 * Deliberately string-only rather than [android.net.Uri]: the scheme is already the thing being
 * checked, and staying off the framework keeps it testable on the jvm.
 */
internal fun sharedLink(text: String?): String? {
    val links = text.orEmpty().trim().split(WHITESPACE)
        .map { it.trimUrlPunctuation() }
        .filter { candidate -> SCHEMES.any { candidate.startsWith(it, ignoreCase = true) } }
    val only = links.singleOrNull() ?: return null
    // Reject "https://" and "https:///path": there has to be a host to shorten.
    val host = only.substringAfter("//").substringBefore('/').substringBefore('?').substringBefore('#')
    return only.takeIf { host.isNotEmpty() }
}

/**
 * Strips the punctuation a link picks up from the sentence around it — `look at https://x.com.`
 * or `"https://x.com"` — without eating punctuation the url actually owns.
 *
 * A trailing `)` is the interesting case: it closes something the url opened in
 * `.../Mercury_(planet)`, and is sentence punctuation in `(https://x.com)`. Counting brackets tells
 * the two apart, which is what linkifiers generally do.
 */
private fun String.trimUrlPunctuation(): String {
    var trimmed = trimStart(*LEADING_PUNCTUATION).trimEnd(*TRAILING_PUNCTUATION)
    while (trimmed.endsWith(')') && trimmed.count { it == ')' } > trimmed.count { it == '(' }) {
        trimmed = trimmed.dropLast(1).trimEnd(*TRAILING_PUNCTUATION)
    }
    return trimmed
}

private val WHITESPACE = Regex("\\s+")
private val SCHEMES = listOf("http://", "https://")
private val LEADING_PUNCTUATION = charArrayOf('"', '\'', '(', '<', '[')
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', '"', '\'', '>', ']')

/**
 * Transparent share target: enqueues with the active profile's default upload options and finishes.
 * The uris are staged/persisted inside [UploadEnqueuer] before this task dies and the grant is lost.
 *
 * A shared plain-text link is shortened instead of uploaded - see [sharedLink].
 */
@AndroidEntryPoint
class ShareTargetActivity : ComponentActivity() {

    @Inject lateinit var profiles: ProfileRepository

    @Inject lateinit var enqueuer: UploadEnqueuer

    @Inject lateinit var settings: SettingsStore

    @Inject lateinit var clients: ZiplineClients

    @Inject lateinit var notifications: UploadNotifications

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)

        // Sharing a plain link here used to dead-end on "Nothing to upload" - the share sheet
        // offers ZipShare for text/plain because the filter is */*. There is nothing to upload
        // about a bare url, so the only thing it can mean is "shorten this".
        val link = if (uris.isEmpty()) sharedLink(intent?.getStringExtra(Intent.EXTRA_TEXT)) else null

        AppLog.log("share", if (link != null) "share received: 1 link" else "share received: ${uris.size} item(s)")
        if (uris.isEmpty() && link == null) {
            toastAndFinish("Nothing to upload.")
            return
        }
        lifecycleScope.launch {
            // Profiles load off the main thread, so wait for that before deciding there is none.
            profiles.awaitReady()
            val profile = profiles.activeNow()
            if (profile == null) {
                startActivity(
                    Intent(this@ShareTargetActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                toastAndFinish("Add a server profile first.")
                return@launch
            }

            if (link != null) {
                shorten(link, profile)
                return@launch
            }
            val options = settings.current().defaultOptions
            runCatching { enqueuer.enqueue(uris, profile.id, options) }
                .onSuccess { toastAndFinish("Uploading ${uris.size} file(s) to ${profile.label}") }
                .onFailure { toastAndFinish(it.message ?: "Could not start the upload.") }
        }
    }

    /** The destination is the user's own business, so it never reaches the log. */
    private suspend fun shorten(link: String, profile: Profile) {
        val created = try {
            withContext(Dispatchers.IO) {
                clients.api(profile).createUrl(CreateUrlBody(link)).unwrap()
            }
        } catch (e: CancellationException) {
            // runCatching would have swallowed this and toasted at an activity already tearing
            // down. Cancellation is not a failure to report.
            throw e
        } catch (e: Exception) {
            toastAndFinish(e.message ?: "Could not shorten that link.")
            return
        }

        val short = created.shortLink(profile.baseUrl)
        notifications.copyToClipboard(short)
        AppLog.log("share", "shortened a shared link")
        toastAndFinish("Copied $short")
    }

    private fun extractUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key) as? T
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableList(key: String): List<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            getParcelableArrayListExtra<T>(key)
        }
}
