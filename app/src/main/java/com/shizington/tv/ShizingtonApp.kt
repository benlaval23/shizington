package com.shizington.tv

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.shizington.tv.data.CredentialsStore
import com.shizington.tv.data.LibraryRepository
import com.shizington.tv.data.XtreamRepository
import com.shizington.tv.data.db.AppDatabase
import com.shizington.tv.data.model.PlaybackRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

const val USER_AGENT = "AliceTV/1.0 (Linux; Android) ExoPlayer"

/** Hand-rolled dependency container; the app is small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val credentialsStore = CredentialsStore(app)
    val xtream = XtreamRepository(httpClient, credentialsStore)

    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "shizington.db")
        .fallbackToDestructiveMigration()
        .build()
    val library = LibraryRepository(database)

    /** What the player screen should open next; set right before navigating to it. */
    @Volatile
    var pendingPlayback: PlaybackRequest? = null

    /** True while the player is showing; the app's backgrounds go transparent so the video layer shows. */
    val playerVisible = androidx.compose.runtime.mutableStateOf(false)

    /** Last live channel the player showed, so the channel list can refocus it. */
    @Volatile
    var lastPlayedLiveId: Long? = null
}

class ShizingtonApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.httpClient }
        // Fire TV sticks have ~1 GB of RAM shared with Fire OS; a modest cache avoids the low-memory killer.
        .memoryCache {
            val cap = 32L * 1024 * 1024
            MemoryCache.Builder(this).maxSizePercent(0.15).build().let {
                if (it.maxSize > cap) MemoryCache.Builder(this).maxSizeBytes(cap.toInt()).build() else it
            }
        }
        .diskCache {
            DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build()
        }
        .respectCacheHeaders(false)
        .allowRgb565(true)
        .crossfade(200)
        .build()
}
