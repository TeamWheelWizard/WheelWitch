package com.skiletro.wheelwitch.util.log

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * Parses the plain-text report produced by [LogExporter.buildReport] into
 * per-line segments so the Log Viewer screen can render it with per-level
 * colours, and colorizes it into an [AnnotatedString].
 *
 * Every captured log line (in-memory and on-disk) shares the shape
 * `<timestamp> <LEVEL>/<tag>: <message>`; header lines, section markers,
 * empty lines, and Timber stack-trace continuation lines don't match and are
 * treated as plain.
 */
object LogTextRenderer {
  private val LOG_LINE_REGEX = Regex("^\\d+ ([VDIWEA?])/([^:]*): ")

  /**
   * Returns the [Log] priority for [label] (a single log-level letter), or
   * [Log.INFO] for unknown levels so `?` lines still render as normal text.
   */
  internal fun levelForLabel(label: String): Int =
      when (label) {
        "V" -> Log.VERBOSE
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        "E" -> Log.ERROR
        "A" -> Log.ASSERT
        else -> Log.INFO
      }

  /**
   * Splits [text] into one [LogLine] per line, preserving order and content
   * exactly (the concatenation of [LogLine.text] with `\n` separators equals
   * [text]).
   */
  fun parseLogLines(text: String): List<LogLine> {
    val rawLines = text.lineSequence().toList()
    val lines =
        if (text.endsWith('\n') && rawLines.isNotEmpty()) rawLines.dropLast(1) else rawLines
    return lines.map { line ->
      val match = LOG_LINE_REGEX.find(line)
      if (match == null) {
        LogLine(line, PLAIN_LEVEL, messageStart = -1)
      } else {
        LogLine(line, levelForLabel(match.groupValues[1]), messageStart = match.range.last + 1)
      }
    }
  }

  /**
   * Renders [text] as an [AnnotatedString] with per-line colours: log lines
   * get their timestamp/level/tag prefix in [LogViewerColors.metadata] and the
   * message in its level colour; plain lines use [LogViewerColors.plain]. The
   * resulting string content is identical to [text].
   */
  fun colorizeLogReport(text: String, colors: LogViewerColors): AnnotatedString {
    val annotated = AnnotatedString.Builder()
    val lines = parseLogLines(text)
    val trailingNewline = text.endsWith('\n')
    for ((index, line) in lines.withIndex()) {
      if (line.messageStart < 0) {
        annotated.pushStyle(SpanStyle(color = colors.plain))
        annotated.append(line.text)
        annotated.pop()
      } else {
        annotated.pushStyle(SpanStyle(color = colors.metadata))
        annotated.append(line.text, 0, line.messageStart)
        annotated.pop()
        annotated.pushStyle(SpanStyle(color = colors.colorForLevel(line.level)))
        annotated.append(line.text, line.messageStart, line.text.length)
        annotated.pop()
      }
      if (trailingNewline || index < lines.lastIndex) annotated.append('\n')
    }
    return annotated.toAnnotatedString()
  }
}

/** One line of the log report. [level] is an android.util.Log priority, [PLAIN_LEVEL] for plain lines. */
data class LogLine(val text: String, val level: Int, val messageStart: Int)

/** Level for lines that are not captured log entries (headers, markers, stack-trace continuations). */
const val PLAIN_LEVEL = -1

/**
 * Theme-independent palette for [LogTextRenderer.colorizeLogReport]. The
 * Log Viewer resolves the concrete values from the active Material theme via
 * [logViewerColorsFor]; tests construct it with fixed colours.
 */
data class LogViewerColors(
  val plain: Color,
  val metadata: Color,
  val verbose: Color,
  val debug: Color,
  val info: Color,
  val warn: Color,
  val error: Color,
) {
  fun colorForLevel(level: Int): Color =
      when (level) {
        Log.VERBOSE -> verbose
        Log.DEBUG -> debug
        Log.INFO -> info
        Log.WARN -> warn
        else -> error
      }
}

/**
 * Resolves a [LogViewerColors] readable on [surface]. Verbose, debug, info,
 * warn and error use classic logcat hues (gray, blue, green, orange, red)
 * tuned to the surface's lightness so they stay distinguishable on both
 * light and dark themes; plain and metadata pass through the theme-adaptive
 * roles.
 */
fun logViewerColorsFor(
  surface: Color,
  onSurface: Color,
  onSurfaceVariant: Color,
): LogViewerColors {
  val isDark = surface.luminance() < 0.5f
  return LogViewerColors(
    plain = onSurface,
    metadata = onSurfaceVariant,
    verbose = if (isDark) Color(0xFFBDBDBD) else Color(0xFF9E9E9E),
    debug = if (isDark) Color(0xFF64B5F6) else Color(0xFF1565C0),
    info = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
    warn = if (isDark) Color(0xFFFFB300) else Color(0xFFE65100),
    error = if (isDark) Color(0xFFEF5350) else Color(0xFFC62828),
  )
}
