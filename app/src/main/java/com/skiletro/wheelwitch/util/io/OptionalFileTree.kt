package com.skiletro.wheelwitch.util.io

import android.util.Log
import com.skiletro.wheelwitch.util.log.LogEntry
import timber.log.Timber
import java.io.File

/**
 * Optional Timber tree that appends each log call to a text file when enabled.
 *
 * The tree is reactive: [isEnabled] and [logFile] are invoked on every log call, so toggling
 * the corresponding setting takes effect immediately. When [logFile] returns null the call is
 * dropped. When the on-disk file exceeds [maxBytes], it is rotated (renamed to `.1` and a
 * fresh file is started).
 *
 * Note: Timber already appends the throwable's stack trace to [message] when [t] is non-null,
 * so [formatLine] writes the message verbatim and does not re-render the throwable.
 */
class OptionalFileTree(
  private val isEnabled: () -> Boolean,
  private val logFile: () -> File?,
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  private val minPriority: Int = Log.VERBOSE,
) : Timber.Tree() {
  override fun isLoggable(tag: String?, priority: Int): Boolean =
      priority >= minPriority && isEnabled()

  override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
    val file = logFile() ?: return
    val line = formatLine(priority, tag, message) + "\n"
    appendWithRotation(file, line, maxBytes)
  }

  companion object {
    const val DEFAULT_MAX_BYTES: Long = 1_000_000L // 1 MB

    internal fun formatLine(priority: Int, tag: String?, message: String): String =
        LogEntry(System.currentTimeMillis(), priority, tag, message).serialize()
  }
}

/**
 * Appends [line] to [file], creating parent directories as needed. If the file would exceed
 * [maxBytes] after the write (measured in UTF-8 bytes), rotates the existing file to `<file>.1`
 * (overwriting any previous `.1`) and starts a new file. If the rename fails, the file is
 * truncated in place instead so it never grows unbounded. Synchronized on the file path so
 * concurrent log calls from different threads do not interleave writes.
 */
internal fun appendWithRotation(file: File, line: String, maxBytes: Long) {
  val parent = file.parentFile
  if (parent != null && !parent.exists()) parent.mkdirs()
  synchronized(file.absolutePath) {
    if (file.exists() && file.length() + line.toByteArray().size > maxBytes) {
      val rotated = File(file.parentFile, file.name + ".1")
      if (rotated.exists()) rotated.delete()
      if (!file.renameTo(rotated)) {
        // Rotation failed; truncate in place to keep the file bounded.
        file.delete()
      }
    }
    file.appendText(line)
  }
}
