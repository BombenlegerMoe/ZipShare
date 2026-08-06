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
             *
             * Lazy because compression is off by default, and encoding this for every upload that
             * will never read it is pure waste on the path the share target blocks on.
             */
            val optionsJsonCompressed by lazy {
                json.encodeToString(
                    UploadOptions.serializer(),
                    (if (secretId == null) options else options.copy(password = null))
                        .copy(compressionType = null, compressionPercent = null),
                )
            }

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
                        name = meta.name,
                    )
                } else {
                    null
                }

                // Staging happens here, while the caller's uri grant is still alive. Anything
                // large enough to take the chunked path is staged even if the grant would have
                // survived, so the per-chunk seek lands on a real file rather than a pipe.
                //
                // One branch, not four: everything that differs for a re-encoded file is decided
                // together, so a future field cannot be set for the compressed case and forgotten
                // for the plain one.
                val prepared = if (compressed != null) {
                    Prepared(
                        // Already a plain file in our own cache, and the worker must delete it after.
                        uri = Uri.fromFile(compressed),
                        staged = true,
                        name = ImageCompressor.renameForFormat(meta.name, cfg.deviceCompressionFormat),
                        mime = ImageCompressor.mimeForFormat(cfg.deviceCompressionFormat),
                        optionsJson = optionsJsonCompressed,
                    )
                } else {
                    val (uri, staged) = UploadInput.stageIfNeeded(
                        context,
                        original,
                        forSeeking = meta.size > partialThreshold,
                    )
                    Prepared(uri, staged, meta.name, meta.mime, optionsJson)
                }

                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(
                        UploadWorker.inputFor(
                            prepared.uri,
                            profileId,
                            prepared.optionsJson,
                            prepared.staged,
                            secretId,
                            prepared.name,
                            prepared.mime,
                        ),
                    )
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .addTag(workName)
                    // The queue screen has no other way to name a file that has not started yet:
                    // WorkInfo exposes tags, progress and output, but never the input data.
                    .addTag("$NAME_TAG${prepared.name.take(120)}")
                    // Same reason: which server this upload is going to is in the input data,
                    // which the queue cannot read, so it has to ride along as a tag.
                    .addTag(profileTag(profileId))
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

    /** What one file will be uploaded as, once compression and staging have both had their say. */
    private data class Prepared(
        val uri: Uri,
        val staged: Boolean,
        val name: String,
        val mime: String,
        val optionsJson: String,
    )

    companion object {
        const val TAG = "zipshare-upload"
        const val NAME_TAG = "name:"
        private const val PROFILE_TAG = "profile:"
        private const val WORK_PREFIX = "upload:"

        fun profileTag(profileId: String) = "$PROFILE_TAG$profileId"

        /** Null for work enqueued before uploads carried a profile tag. */
        fun profileOf(tags: Set<String>): String? =
            tags.firstOrNull { it.startsWith(PROFILE_TAG) }?.removePrefix(PROFILE_TAG)
    }
}
