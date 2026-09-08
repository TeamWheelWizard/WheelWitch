package com.skiletro.wheelwitch.util.log

/**
 * A single captured log entry, held in [LogBuffer] and serialised by [LogExporter].
 *
 * Note: when [throwable] is non-null, [message] already includes the rendered stack trace
 * appended by Timber's [Timber.Tree] dispatch. Consumers must not render [throwable]
 * separately, or the stack trace will be written twice.
 */
data class LogEntry(
  val timestampMillis: Long,
  val level: Int,
  val tag: String?,
  val message: String,
  val throwable: Throwable? = null,
) {
  /** Short uppercase label for [level] (e.g. "ERROR"). Returns "?" for unknown levels. */
  fun levelLabel(): String =
      when (level) {
        android.util.Log.VERBOSE -> "V"
        android.util.Log.DEBUG -> "D"
        android.util.Log.INFO -> "I"
        android.util.Log.WARN -> "W"
        android.util.Log.ERROR -> "E"
        android.util.Log.ASSERT -> "A"
        else -> "?"
      }

  /**
   * Serialises this entry to the canonical on-disk/report line shape
   * `<timestamp> <LEVEL>/<tag>: <message>`. Single source of truth for the
   * format consumed by [LogTextRenderer]'s regex.
   */
  fun serialize(): String = "$timestampMillis ${levelLabel()}/${tag ?: "?"}: $message"
}
