package dev.zipshare

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import dev.zipshare.data.prefs.SecureStore
import dev.zipshare.debug.StrictModeSetup
import dev.zipshare.log.AppLog
import dev.zipshare.upload.UploadNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class ZipShareApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notifications: UploadNotifications

    @Inject lateinit var coilImageLoader: Provider<ImageLoader>

    @Inject lateinit var secure: SecureStore

    /**
     * Coil's AsyncImage resolves its loader from `context.imageLoader`, which is this factory -
     * without it the DI-provided loader is never consulted and preview requests go out
     * unauthenticated.
     */
    override fun newImageLoader(): ImageLoader = coilImageLoader.get()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR,
            )
            .build()

    override fun onCreate() {
        // Armed before anything touches disk, so the very first Room/DataStore open is covered.
        StrictModeSetup.install()
        super.onCreate()
        AppLog.init(this)
        AppLog.log(
            "app",
            "started v${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.SDK_INT})",
        )
        notifications.ensureChannels()

        // A process killed mid-upload never runs the worker's teardown, so its upload password
        // would stay in the encrypted store for the life of the install. Startup is the only
        // moment we can be sure no worker from the dead process is still running.
        //
        // Off the main thread: opening the encrypted store and reading every key is disk I/O plus
        // Keystore init, which is exactly what StrictMode (armed above) is watching for.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { secure.sweepUploadSecrets() }
        }
    }
}
