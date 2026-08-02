package dev.zipshare

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import dev.zipshare.debug.StrictModeSetup
import dev.zipshare.log.AppLog
import dev.zipshare.upload.UploadNotifications
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class ZipShareApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notifications: UploadNotifications

    @Inject lateinit var coilImageLoader: Provider<ImageLoader>

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
    }
}
