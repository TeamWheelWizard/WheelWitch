package com.skiletro.wheelwitch.util.mii

import android.content.Context
import com.skiletro.wheelwitch.util.io.cacheSize
import java.io.File

/**
 * Shares Coil's `<cacheDir>/mii_faces/` disk-cache location with Settings for size reporting
 * and deletion. [init] and [initWith] create the directory and record its location.
 */
object MiiFaceCache {
  const val DIR_NAME = "mii_faces"

  private var cacheDir: File? = null

  /** The on-disk directory used by the Mii face cache. Null until [init] (or [initWith]) is called. */
  val directory: File?
    get() = cacheDir

  @Synchronized
  fun init(context: Context) {
    val dir = File(context.cacheDir, DIR_NAME).also { it.mkdirs() }
    cacheDir = dir
  }

  /** Test/init hook: point the cache at an arbitrary directory. */
  @Synchronized
  fun initWith(target: File) {
    target.mkdirs()
    cacheDir = target
  }

  @Synchronized
  fun clear() {
    val dir = cacheDir ?: return
    clear(dir)
  }

  /** Attempts to delete direct children of [dir], without recursing or requiring [init]. */
  @Synchronized
  fun clear(dir: File) {
    dir.listFiles()?.forEach { it.delete() }
  }

  @Synchronized
  fun cacheSize(): Long {
    val dir = cacheDir ?: return 0L
    return cacheSize(dir)
  }

  /** Returns the sum of file lengths under [dir], or 0 if it doesn't exist. */
  @Synchronized
  fun cacheSize(dir: File): Long = com.skiletro.wheelwitch.util.io.cacheSize(dir)
}
