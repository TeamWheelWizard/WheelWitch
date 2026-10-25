package com.skiletro.wheelwitch.util.io

import android.util.Log
import com.skiletro.wheelwitch.util.log.LogEntry
import timber.log.Timber
import java.io.File

/**
 * Optional Timber tree that appends each log call to a text file when enabled.
 *
 * [isEnabled] is checked for each log call; [logFile] is resolved for accepted calls.
 * Rotation is attempted before an append would exceed [maxBytes].
 *
 * Timber already includes throwable stacks in the message; do not render [t] again.
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
 * Appends UTF-8 [line], creating parent directories as needed. Attempts rotation to `<file>.1`
 * before an append would exceed [maxBytes]; if rename fails, attempts deletion of the active
 * file. A single line can exceed the limit. Synchronization uses [File.absolutePath]'s String
 * monitor, not a shared lock guaranteed to cover separate File instances for the same path.
 */
internal fun appendWithRotation(file: File, line: String, maxBytes: Long) {
  val parent = file.parentFile
  if (parent != null && !parent.exists()) parent.mkdirs()
  synchronized(file.absolutePath) {
    if (file.exists() && file.length() + line.toByteArray().size > maxBytes) {
      val rotated = File(file.parentFile, file.name + ".1")
      if (rotated.exists()) rotated.delete()
      if (!file.renameTo(rotated)) {
        // Attempt to discard the active file if rotation is unavailable.
        file.delete()
      }
    }
    file.appendText(line)
  }
}
