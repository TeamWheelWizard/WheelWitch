package com.skiletro.wheelwitch.util.log

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LogTextRendererTest {

  private val colors =
      LogViewerColors(
        plain = Color.Red,
        metadata = Color.Blue,
        verbose = Color.Gray,
        debug = Color.Cyan,
        info = Color.Green,
        warn = Color.Yellow,
        error = Color.Magenta,
      )

  @Test
  fun `parse classifies each log level letter`() {
    val lines =
        LogTextRenderer.parseLogLines(
            "0 V/V: v\n" + "1 D/D: d\n" + "2 I/I: i\n" + "3 W/W: w\n" + "4 E/E: e\n" + "5 A/A: a\n"
        )
    assertThat(lines.map { it.level })
        .containsExactly(Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT)
        .inOrder()
  }

  @Test
  fun `parse treats unknown level letter as info`() {
    val lines = LogTextRenderer.parseLogLines("0 ?/T: odd\n")
    assertThat(lines.single().level).isEqualTo(Log.INFO)
  }

  @Test
  fun `parse marks header marker and stack-trace lines as plain`() {
    val lines =
        LogTextRenderer.parseLogLines(
            "Wheel Witch v1.0 (build 1, git=abc)\n" +
                "1700000000000 I/T: started\n" +
                "  at com.foo.Bar.baz(Bar.kt:1)\n" +
                "--- on-disk log: wheelwitch.log ---\n" +
                "1700000000001 E/T: boom\n"
        )
    assertThat(lines.map { it.level })
        .containsExactly(PLAIN_LEVEL, Log.INFO, PLAIN_LEVEL, PLAIN_LEVEL, Log.ERROR)
        .inOrder()
    assertThat(lines.filter { it.level == PLAIN_LEVEL }.all { it.messageStart == -1 }).isTrue()
  }

  @Test
  fun `parse messageStart points past the metadata prefix`() {
    val line = "1700000000000 I/Tag: hello world"
    val parsed = LogTextRenderer.parseLogLines(line).single()
    assertThat(parsed.text).isEqualTo(line)
    assertThat(parsed.text.substring(parsed.messageStart)).isEqualTo("hello world")
  }

  @Test
  fun `colorize preserves the exact input text`() {
    val text =
        "Wheel Witch v1.0 (build 1, git=abc)\n" +
            "1700000000000 V/V: verbose\n" +
            "  at com.foo.Bar.baz(Bar.kt:1)\n" +
            "--- on-disk log: wheelwitch.log ---\n" +
            "1700000000001 E/T: boom\n"
    assertThat(LogTextRenderer.colorizeLogReport(text, colors).text).isEqualTo(text)
  }

  @Test
  fun `colorize preserves input that does not end in a newline`() {
    val text = "1700000000000 I/T: no trailing newline"
    assertThat(LogTextRenderer.colorizeLogReport(text, colors).text).isEqualTo(text)
  }

  @Test
  fun `colorize applies plain color to header and metadata dimming to log lines`() {
    val text = "header\n1700000000000 W/Tag: warn me"
    val annotated = LogTextRenderer.colorizeLogReport(text, colors)

    assertThat(spanColorAt(annotated, text.indexOf("header"))).isEqualTo(Color.Red)
    assertThat(spanColorAt(annotated, text.indexOf("1700000000000 W/Tag:"))).isEqualTo(Color.Blue)
    assertThat(spanColorAt(annotated, text.indexOf("warn me"))).isEqualTo(Color.Yellow)
  }

  @Test
  fun `colorize maps every level to its colour`() {
    val text =
        "0 V/V: verbose\n" +
            "1 D/D: debug\n" +
            "2 I/I: info\n" +
            "3 W/W: warn\n" +
            "4 E/E: error\n" +
            "5 A/A: assert\n"
    val annotated = LogTextRenderer.colorizeLogReport(text, colors)
    assertThat(spanColorAt(annotated, text.indexOf("verbose"))).isEqualTo(Color.Gray)
    assertThat(spanColorAt(annotated, text.indexOf("debug"))).isEqualTo(Color.Cyan)
    assertThat(spanColorAt(annotated, text.indexOf("info"))).isEqualTo(Color.Green)
    assertThat(spanColorAt(annotated, text.indexOf("warn"))).isEqualTo(Color.Yellow)
    assertThat(spanColorAt(annotated, text.indexOf("error"))).isEqualTo(Color.Magenta)
    assertThat(spanColorAt(annotated, text.indexOf("assert"))).isEqualTo(Color.Magenta)
  }

  @Test
  fun `logViewerColorsFor picks light-tuned logcat hues on light surfaces`() {
    val colors = logViewerColorsFor(Color.White, Color.DarkGray, Color.Gray)
    assertThat(colors.verbose).isEqualTo(Color(0xFF9E9E9E))
    assertThat(colors.debug).isEqualTo(Color(0xFF1565C0))
    assertThat(colors.info).isEqualTo(Color(0xFF2E7D32))
    assertThat(colors.warn).isEqualTo(Color(0xFFE65100))
    assertThat(colors.error).isEqualTo(Color(0xFFC62828))
  }

  @Test
  fun `logViewerColorsFor picks dark-tuned logcat hues on dark surfaces`() {
    val colors = logViewerColorsFor(Color.Black, Color.White, Color.LightGray)
    assertThat(colors.verbose).isEqualTo(Color(0xFFBDBDBD))
    assertThat(colors.debug).isEqualTo(Color(0xFF64B5F6))
    assertThat(colors.info).isEqualTo(Color(0xFF81C784))
    assertThat(colors.warn).isEqualTo(Color(0xFFFFB300))
    assertThat(colors.error).isEqualTo(Color(0xFFEF5350))
  }

  @Test
  fun `logViewerColorsFor passes through theme-adaptive roles`() {
    val colors = logViewerColorsFor(Color.Black, Color.White, Color.LightGray)
    assertThat(colors.plain).isEqualTo(Color.White)
    assertThat(colors.metadata).isEqualTo(Color.LightGray)
  }

  private fun spanColorAt(annotated: AnnotatedString, index: Int): Color {
    val range = annotated.spanStyles.first { it.start <= index && index < it.end }
    return range.item.color
  }
}
