package com.skiletro.wheelwitch.util.log

import android.content.Context
import com.skiletro.wheelwitch.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Exports the in-memory buffer followed by any rotated and active on-disk logs.
 * Reports include app/build metadata and may duplicate entries captured by both trees.
 * Timber has already inlined throwable stacks into each message.
 */
object LogExporter {
  private const val DIR_NAME = "logs"
  private const val FILE_PREFIX = "wheelwitch-"
  private const val FILE_EXTENSION = ".log"
  private const val ON_DISK_LOG_NAME = "wheelwitch.log"
  private const val ON_DISK_LOG_ROTATED_NAME = "wheelwitch.log.1"
  private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  /**
   * Uses a thread-safe formatter for timestamped export filenames.
   */
  internal fun formatTimestamp(dateTime: LocalDateTime): String = dateTime.format(TIMESTAMP_FORMAT)

  /**
   * Writes the report to `cacheDir/logs/` with second-resolution filenames; exports within the
   * same second overwrite the same file. Does not drain the in-memory buffer.
   */
  fun flushToCacheFile(context: Context): File {
    val dir = File(context.cacheDir, DIR_NAME)
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "$FILE_PREFIX${formatTimestamp(LocalDateTime.now())}$FILE_EXTENSION")
    file.writeText(buildReport(context))
    return file
  }

  /**
   * Returns the report text without writing a file, so the Log Viewer
   * screen can show exactly what [flushToCacheFile] would export.
   */
  fun exportToString(context: Context): String = buildReport(context)

  internal fun buildReport(context: Context): String {
    val sb = StringBuilder()
    sb.append("Wheel Witch v${BuildConfig.VERSION_NAME} ")
      .append("(build ${BuildConfig.VERSION_CODE}, git=${BuildConfig.GIT_HASH})\n")
      .append("Exported at ${Date()}\n")
      .append("Cache dir: ${context.cacheDir}\n")
      .append("=".repeat(60))
      .append('\n')
    val entries = LogBuffer.default.snapshot()
    if (entries.isEmpty()) {
      sb.append("(no log entries captured)\n")
    } else {
      for (entry in entries) {
        sb.append(entry.serialize()).append('\n')
      }
    }
    appendOnDiskLogs(sb, File(context.cacheDir, DIR_NAME))
    return sb.toString()
  }

  /**
   * Appends the on-disk `wheelwitch.log.1` (older, rotated) and `wheelwitch.log`
   * (newer, active) files to [sb] under labelled section headers, when present.
   * The two disk files are ordered oldest first; the report's preceding buffer can overlap them.
   */
  private fun appendOnDiskLogs(sb: StringBuilder, logsDir: File) {
    val rotated = File(logsDir, ON_DISK_LOG_ROTATED_NAME)
    val active = File(logsDir, ON_DISK_LOG_NAME)
    if (!rotated.exists() && !active.exists()) return
    sb.append('\n')
    if (rotated.exists()) {
      sb.append("--- on-disk log: ").append(rotated.name).append(" ---\n")
      appendFileContents(sb, rotated)
    }
    if (active.exists()) {
      sb.append("--- on-disk log: ").append(active.name).append(" ---\n")
      appendFileContents(sb, active)
    }
  }

  private fun appendFileContents(sb: StringBuilder, file: File) {
    val text = file.readText()
    sb.append(text)
    if (!text.endsWith('\n')) sb.append('\n')
  }

  /**
   * Returns a one-line human summary of the most recent error in the
   * buffer, for embedding in the bug-report email body.
   */
  fun lastErrorSummary(): String? {
    val lastError = LogBuffer.default.snapshot().lastOrNull { it.level >= android.util.Log.ERROR }
    return lastError?.let { entry ->
      val throwableInfo = entry.throwable?.let { t ->
        val msg = t.message
        if (msg.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $msg"
      }
      val base = entry.message.lineSequence().firstOrNull().orEmpty()
      if (throwableInfo != null) "$base | $throwableInfo" else base
    }
  }
}
