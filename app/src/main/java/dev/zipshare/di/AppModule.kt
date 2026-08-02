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
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun historyDao(db: ZipShareDb): HistoryDao = db.history()

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /**
     * Coil loader that authenticates preview requests with the active profile's token - but only
     * when the request host matches that profile. Any other host gets no credentials at all.
     */
    @Provides
    @Singleton
    fun imageLoader(
        @ApplicationContext context: Context,
        profiles: Provider<ProfileRepository>,
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
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val profile = profiles.get().activeNow()
                    val host = profile?.host
                    if (profile != null && host != null && request.url.host.equals(host, true)) {
                        chain.proceed(
                            request.newBuilder().header("authorization", profile.token).build(),
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
        }
        .build()
}
