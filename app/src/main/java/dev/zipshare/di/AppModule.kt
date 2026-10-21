package dev.zipshare.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.work.WorkManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.db.HistoryDao
import dev.zipshare.data.db.ZipShareDb
import dev.zipshare.data.net.ZiplineClients
import okhttp3.Call
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): ZipShareDb =
        Room.databaseBuilder(context, ZipShareDb::class.java, "zipshare.db")
            // This table is a local mirror of uploads the server already holds, so on a schema
            // change losing it is recoverable - crashing on launch is not. Schemas are exported,
            // so add a real Migration when the shape changes and drop this.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun historyDao(db: ZipShareDb): HistoryDao = db.history()

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /**
     * Coil loader for previews. A request to the active profile's own host goes through that
     * profile's API client - the token AND its certificate pin and cleartext policy, exactly as the
     * API calls get them. Any other host gets a plain client with no credentials at all.
     */
    @Provides
    @Singleton
    fun imageLoader(
        @ApplicationContext context: Context,
        profiles: Provider<ProfileRepository>,
        clients: ZiplineClients,
    ): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        // Without an animated decoder Coil shows a GIF's first frame and nothing moves.
        // ImageDecoder is the platform decoder (API 28+); older devices need Coil's own.
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .callFactory {
            val plain = OkHttpClient()
            Call.Factory { request ->
                val profile = profiles.get().activeNow()
                if (profile != null && sendsCredentials(request.url.host, profile.host)) {
                    clients.client(profile).newCall(request)
                } else {
                    plain.newCall(request)
                }
            }
        }
        .build()
}

/** Only the profile's own host may ever see its token. */
internal fun sendsCredentials(requestHost: String, profileHost: String?): Boolean =
    profileHost != null && requestHost.equals(profileHost, ignoreCase = true)
