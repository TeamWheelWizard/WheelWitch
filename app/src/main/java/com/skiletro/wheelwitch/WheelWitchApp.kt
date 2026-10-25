package com.skiletro.wheelwitch

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.skiletro.wheelwitch.util.log.AppReleaseLogTree
import com.skiletro.wheelwitch.util.log.MemoryBufferTree
import com.skiletro.wheelwitch.util.mii.MiiFaceCache
import com.skiletro.wheelwitch.util.io.OptionalFileTree
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import java.io.File
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Configures Timber logging and Coil's shared HTTP client and Mii face caches.
 */
class WheelWitchApp :
  Application(), SingletonImageLoader.Factory {
  override fun onCreate() {
    super.onCreate()
    MiiFaceCache.init(this)
    val prefs = Prefs.main(this)
    if (BuildConfig.DEBUG) {
      Timber.plant(Timber.DebugTree())
    } else {
      Timber.plant(AppReleaseLogTree())
    }
    Timber.plant(MemoryBufferTree())
    Timber.plant(
      OptionalFileTree(
        isEnabled = { prefs.getBoolean(PrefsKeys.LOGGING_TO_FILE_KEY, false) },
        logFile = { logFilePath() },
        minPriority = if (BuildConfig.DEBUG) Log.VERBOSE else Log.WARN,
      )
    )
  }

  private fun logFilePath(): File? {
    val dir = File(cacheDir, "logs")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "wheelwitch.log")
  }

  /**
   * Shares the disk cache location with Settings so it can report and clear the cache.
   */
  override fun newImageLoader(context: PlatformContext): ImageLoader {
    val diskCacheDir = MiiFaceCache.directory ?: File(context.cacheDir, MiiFaceCache.DIR_NAME)
    val callFactory: () -> OkHttpClient = { HttpClientProvider.client }
    return ImageLoader.Builder(context)
      .components { add(OkHttpNetworkFetcherFactory(callFactory = callFactory)) }
      .memoryCache {
        MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(diskCacheDir)
          .maxSizeBytes(MAX_DISK_CACHE_BYTES)
          .build()
      }
      .crossfade(false)
      .build()
  }

  private companion object {
    /** Memory cache: up to 10% of the process's available app memory. */
    const val MEMORY_CACHE_PERCENT: Double = 0.10

    /** Disk cache limit: 50 MiB. */
    const val MAX_DISK_CACHE_BYTES: Long = 50L * 1024 * 1024
  }
}
