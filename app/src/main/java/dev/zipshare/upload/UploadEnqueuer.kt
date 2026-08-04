package dev.zipshare.upload

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.data.prefs.SecureStore
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val secure: SecureStore,
    private val settings: SettingsStore,
) {
    private val json = Json { encodeDefaults = true }

    /** Returns the unique work name so the caller can observe or cancel the batch. */
    suspend fun enqueue(uris: List<Uri>, profileId: String, options: UploadOptions): String =
        withContext(Dispatchers.IO) {
            require(uris.isNotEmpty()) { "no files selected" }

            // One override name cannot apply to a batch - the server would name every file the
            // same. The sheet hides the field for batches, but the option can still arrive here
            // through other paths (e.g. share-target multi-select), so drop it at the choke point.
            @Suppress("NAME_SHADOWING")
            val options = if (uris.size > 1) options.copy(filename = null) else options
            AppLog.log("upload", "enqueued ${uris.size} file(s)")

            // The password must not reach WorkManager's plaintext Data blob; hand over an id instead.
            val secretId = options.password
                ?.takeIf { it.isNotBlank() }
                ?.let { secure.putUploadSecret(it) }
            val optionsJson = json.encodeToString(
                UploadOptions.serializer(),
                if (secretId == null) options else options.copy(password = null),
            )
            val workName = "$WORK_PREFIX${UUID.randomUUID()}"
            val cfg = settings.current()
            val partialThreshold = cfg.partialThresholdBytes

            /**
             * Server-side compression stripped for anything already re-encoded here: letting
             * Zipline compress it again would be a second lossy pass that saves nothing, since the
             * bytes have already been shrunk before they left the device.
             */
            val optionsJsonCompressed = json.encodeToString(
                UploadOptions.serializer(),
                (if (secretId == null) options else options.copy(password = null))
                    .copy(compressionType = null, compressionPercent = null),
            )

            val requests = uris.map { original ->
                // Resolve name/type from the ORIGINAL content uri, where the ContentResolver still
                // knows the real mime. A staged copy is a plain file:// and getType() would fall
                // back to guessing from the extension.
                val meta = UploadInput.meta(context, original)

                // Re-encode before staging, while the caller's uri grant is still alive. A null
                // result means it was not worth it or not possible, and the original goes up
                // untouched - never a failed upload just because compression did not help.
                val compressed = if (cfg.deviceCompression && ImageCompressor.canCompress(meta.mime)) {
                    ImageCompressor.compress(
                        context = context,
                        uri = original,
                        format = cfg.deviceCompressionFormat,
                        quality = cfg.deviceCompressionQuality,
                        cacheDir = File(context.cacheDir, "staged"),
                        originalSize = meta.size,
                    )
                } else {
                    null
                }

                // Staging happens here, while the caller's uri grant is still alive. Anything
                // large enough to take the chunked path is staged even if the grant would have
                // survived, so the per-chunk seek lands on a real file rather than a pipe.
                val (uri, staged) = if (compressed != null) {
                    // Already a plain file in our own cache, and the worker must delete it after.
                    Uri.fromFile(compressed) to true
                } else {
                    UploadInput.stageIfNeeded(
                        context,
                        original,
                        forSeeking = meta.size > partialThreshold,
                    )
                }
                val name = if (compressed != null) {
                    ImageCompressor.renameForFormat(meta.name, cfg.deviceCompressionFormat)
                } else {
                    meta.name
                }
                val mime = if (compressed != null) {
                    ImageCompressor.mimeForFormat(cfg.deviceCompressionFormat)
                } else {
                    meta.mime
                }

                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(
                        UploadWorker.inputFor(
                            uri,
                            profileId,
                            if (compressed != null) optionsJsonCompressed else optionsJson,
                            staged,
                            secretId,
                            name,
                            mime,
                        ),
                    )
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .addTag(workName)
                    .build()
            }

            // A chain uploads the batch one file at a time instead of saturating the uplink.
            var chain = workManager.beginUniqueWork(
                workName,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                requests.first(),
            )
            requests.drop(1).forEach { chain = chain.then(it) }
            chain.enqueue()
            workName
        }

    fun cancel(workName: String) = workManager.cancelUniqueWork(workName)

    companion object {
        const val TAG = "zipshare-upload"
        private const val WORK_PREFIX = "upload:"
    }
}
