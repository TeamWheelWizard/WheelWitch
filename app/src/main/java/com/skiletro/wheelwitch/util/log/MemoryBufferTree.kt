package com.skiletro.wheelwitch.util.log

import timber.log.Timber

/**
 * Captures calls at or above [minPriority] in a [LogBuffer] for later export.
 */
class MemoryBufferTree(
  private val buffer: LogBuffer = LogBuffer.default,
  private val minPriority: Int = android.util.Log.VERBOSE,
) : Timber.Tree() {
  override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minPriority

  override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
    buffer.write(LogEntry(System.currentTimeMillis(), priority, tag, message, t))
  }
}
