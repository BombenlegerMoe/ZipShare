package dev.zipshare.upload

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.db.HistoryDao
import dev.zipshare.data.db.HistoryEntry
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.data.net.ApiErrors
import dev.zipshare.data.net.ErrorAction
import dev.zipshare.data.net.ProgressRequestBody
import dev.zipshare.data.net.UploadHeaderBuilder
import dev.zipshare.data.net.UploadResponse
import dev.zipshare.data.net.UploadedFile
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.data.net.shareUrl
import dev.zipshare.data.net.unwrap
import dev.zipshare.data.prefs.SecureStore
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import java.io.File
import java.io.IOException
import kotlin.math.min

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
    private val history: HistoryDao,
    private val settings: SettingsStore,
    private val notifications: UploadNotifications,
    private val secure: SecureStore,
) : CoroutineWorker(appContext, params) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var lastNotified = 0L

    /** Captured so the (non-suspending) streaming callback can publish progress. */
    private var scope: CoroutineScope? = null

    /** Mirrors the notifyProgress setting; the ongoing notification stays either way. */
    private var detailedProgress = true

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        scope = this
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
            ?: return@withContext fail("No file to upload.", "unknown")
        val profileId = inputData.getString(KEY_PROFILE_ID).orEmpty()
        val staged = inputData.getBoolean(KEY_STAGED, false)
        val secretId = inputData.getString(KEY_SECRET_ID)
        val options = (
            inputData.getString(KEY_OPTIONS)
                ?.let { runCatching { json.decodeFromString(UploadOptions.serializer(), it) }.getOrNull() }
                ?: UploadOptions.DEFAULT
            ).let { o ->
            // The password never rides in the Data blob; rehydrate it from the encrypted store.
            if (secretId == null) o else o.copy(password = secure.uploadSecret(secretId))
        }

        profiles.awaitReady()
        val profile = profiles.byId(profileId)
            ?: return@withContext fail("Server profile was deleted.", "unknown")

        val probed = runCatching { UploadInput.meta(applicationContext, uri) }.getOrElse {
            return@withContext fail("Cannot read the selected file: ${it.message}", "unknown")
        }
        // Prefer what the enqueuer resolved from the original content uri; the staged copy can
        // only guess the type from its filename.
        val meta = probed.copy(
            name = inputData.getString(KEY_NAME)?.takeIf { it.isNotBlank() } ?: probed.name,
            mime = inputData.getString(KEY_MIME)?.takeIf { it.isNotBlank() } ?: probed.mime,
        )
        val notificationId = UploadNotifications.foregroundId(id.hashCode())
        val cfg = settings.current()
        detailedProgress = cfg.notifyProgress
        AppLog.log("upload", "start ${meta.name} (${meta.size} B, attempt ${runAttemptCount + 1})")

        runCatching {
            setForeground(
                notifications.foregroundInfo(notificationId, meta.name, 0, cfg.notifyProgress),
            )
        }

        val result = try {
            val uploaded = if (meta.size > cfg.partialThresholdBytes) {
                uploadPartial(profileId, uri, meta, options, cfg.chunkSizeBytes, notificationId)
            } else {
                uploadWhole(profileId, uri, meta, options, notificationId)
            }

            history.insert(
                HistoryEntry(
                    remoteId = uploaded.file.id,
                    profileId = profile.id,
                    localUri = if (staged) null else uri.toString(),
                    remoteUrl = uploaded.file.shareUrl(profile.baseUrl),
                    name = uploaded.file.name,
                    mime = uploaded.file.type,
                    size = meta.size,
                    deletesAt = uploaded.response.deletesAt,
                    ts = System.currentTimeMillis(),
                    pending = uploaded.file.pending == true,
                ),
            )

            // File id only - on a private instance the share URL is the secret.
            AppLog.log("upload", "ok ${uploaded.file.name} (id ${uploaded.file.id})")

            notifications.copyToClipboard(uploaded.file.url)
            if (cfg.notifyComplete) {
                notifications.success(uploaded.file.name, uploaded.file.url)
            }

            Result.success(
                workDataOf(KEY_OUT_URL to uploaded.file.url, KEY_OUT_NAME to uploaded.file.name),
            )
        } catch (e: ZiplineException) {
            when (e.action) {
                ErrorAction.REAUTH -> profiles.markUnauthenticated(profile.id)
                ErrorAction.CLEAR_FOLDER -> settings.clearDefaultUploadFolder()
                else -> Unit
            }
            if (ApiErrors.retryable(e.statusCode) && runAttemptCount < MAX_ATTEMPTS) {
                AppLog.log("upload", "retrying ${meta.name}: ${e.display}")
                Result.retry()
            } else {
                AppLog.log("upload", "failed ${meta.name}: ${e.display}")
                if (cfg.notifyFailed) notifications.failure(meta.name, e.display)
                fail(e.display, meta.name, e.code)
            }
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                AppLog.log("upload", "retrying ${meta.name}: ${e.message ?: "I/O error"}")
                Result.retry()
            } else {
                val msg = e.message ?: "Network error"
                AppLog.log("upload", "failed ${meta.name}: $msg")
                if (cfg.notifyFailed) notifications.failure(meta.name, msg)
                fail(msg, meta.name)
            }
        } catch (e: IllegalArgumentException) {
            // Bad upload option (e.g. a non-ASCII header value) - retrying cannot help.
            val msg = e.message ?: "Invalid upload option"
            AppLog.log("upload", "failed ${meta.name}: $msg")
            if (cfg.notifyFailed) notifications.failure(meta.name, msg)
            fail(msg, meta.name)
        }

        // Only tear down on a terminal outcome. A retry still needs the staged copy and the
        // password; deleting them in a finally block would make every retry fail.
        if (result !is Result.Retry) {
            secretId?.let { secure.removeUploadSecret(it) }
            if (staged && uri.scheme == "file") {
                uri.path?.let { runCatching { File(it).delete() } }
            }
        }
        result
    }

    private data class Uploaded(val response: UploadResponse, val file: UploadedFile)

    private suspend fun uploadWhole(
        profileId: String,
        uri: Uri,
        meta: FileMeta,
        options: UploadOptions,
        notificationId: Int,
    ): Uploaded {
        val profile = requireNotNull(profiles.byId(profileId))
        val body = ProgressRequestBody(
            mediaType = meta.mime.toMediaTypeOrNull(),
            length = meta.size,
            openStream = { openStream(uri) },
            onProgress = { written, total -> report(notificationId, meta.name, written, total) },
        )
        val part = MultipartBody.Part.createFormData("file", meta.name, body)
        val response = clients.api(profile)
            .upload(UploadHeaderBuilder.build(options, meta.mime), listOf(part))
            .unwrap()
        val file = response.files.firstOrNull() ?: throw ZiplineException(
            0, 200, "empty files[]",
            "Server accepted the upload but returned no file.", ErrorAction.NONE,
        )
        return Uploaded(response, file)
    }

    /**
     * Chunked upload. Chunks must go sequentially: the server issues `partialIdentifier` on the
     * first chunk (start == 0) and requires it on every following one.
     */
    private suspend fun uploadPartial(
        profileId: String,
        uri: Uri,
        meta: FileMeta,
        options: UploadOptions,
        chunkSize: Long,
        notificationId: Int,
    ): Uploaded {
        val profile = requireNotNull(profiles.byId(profileId))
        val api = clients.api(profile)
        val base = UploadHeaderBuilder.build(options, meta.mime)
        val mediaType = meta.mime.toMediaTypeOrNull()

        var identifier: String? = null
        var start = 0L
        var lastResponse: UploadResponse? = null

        while (start < meta.size) {
            val end = min(start + chunkSize, meta.size)
            val lastChunk = end >= meta.size
            val chunkStart = start

            val body = ProgressRequestBody(
                mediaType = mediaType,
                length = end - chunkStart,
                offset = chunkStart,
                openStream = { openStream(uri) },
                onProgress = { written, _ ->
                    report(notificationId, meta.name, chunkStart + written, meta.size)
                },
            )
            val part = MultipartBody.Part.createFormData("file", meta.name, body)
            val headers = base + UploadHeaderBuilder.partial(
                filename = meta.name,
                contentType = meta.mime,
                totalLength = meta.size,
                start = chunkStart,
                end = end,
                lastChunk = lastChunk,
                identifier = identifier,
            )

            val response = api.uploadPartial(headers, part).unwrap()
            lastResponse = response
            if (chunkStart == 0L) {
                identifier = response.partialIdentifier ?: throw ZiplineException(
                    1003, 400, "no partialIdentifier",
                    "Server did not return a partial upload identifier.", ErrorAction.NONE,
                )
            }
            start = end
        }

        val response = lastResponse ?: throw ZiplineException(
            0, 0, "empty file", "Nothing to upload - the file is empty.", ErrorAction.NONE,
        )
        val file = response.files.firstOrNull() ?: throw ZiplineException(
            0, 200, "empty files[]",
            "Chunks uploaded but the server returned no file.", ErrorAction.NONE,
        )
        return Uploaded(response, file)
    }

    private fun openStream(uri: Uri) = applicationContext.contentResolver.openInputStream(uri)
        ?: throw IOException("Cannot open the selected file - the permission may have been revoked.")

    /**
     * Called from ProgressRequestBody on every 8 KiB write, so it must not suspend and must not
     * launch thousands of coroutines: publication is throttled to ~2.5/s plus the final 100%.
     */
    private fun report(notificationId: Int, name: String, written: Long, total: Long) {
        val percent = if (total <= 0) 0 else ((written * 100) / total).toInt().coerceIn(0, 100)
        val now = System.currentTimeMillis()
        if (now - lastNotified < PROGRESS_INTERVAL_MS && written < total) return
        lastNotified = now

        scope?.launch {
            // Work progress still flows either way; only the notification's detail changes.
            setProgress(workDataOf(KEY_PROGRESS to percent, KEY_OUT_NAME to name))
            if (detailedProgress) {
                runCatching {
                    setForeground(notifications.foregroundInfo(notificationId, name, percent, true))
                }
            }
        }
    }

    private fun fail(message: String, name: String, code: Int = 0): Result = Result.failure(
        workDataOf(KEY_ERROR to message, KEY_ERROR_CODE to code, KEY_OUT_NAME to name),
    )

    companion object {
        const val KEY_URI = "uri"
        const val KEY_PROFILE_ID = "profile"
        const val KEY_OPTIONS = "options"
        const val KEY_STAGED = "staged"
        const val KEY_SECRET_ID = "secret_id"
        const val KEY_NAME = "name_override"
        const val KEY_MIME = "mime_override"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_OUT_URL = "url"
        const val KEY_OUT_NAME = "name"
        const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MS = 400L

        fun inputFor(
            uri: Uri,
            profileId: String,
            optionsJson: String,
            staged: Boolean,
            secretId: String?,
            name: String?,
            mime: String?,
        ): Data = workDataOf(
            KEY_URI to uri.toString(),
            KEY_PROFILE_ID to profileId,
            KEY_OPTIONS to optionsJson,
            KEY_STAGED to staged,
            KEY_SECRET_ID to secretId,
            KEY_NAME to name,
            KEY_MIME to mime,
        )
    }
}
