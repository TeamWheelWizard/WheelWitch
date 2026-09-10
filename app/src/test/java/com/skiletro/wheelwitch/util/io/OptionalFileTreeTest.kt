package com.skiletro.wheelwitch.util.io

import android.util.Log
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import timber.log.Timber

class OptionalFileTreeTest {

  @TempDir lateinit var tempDir: Path

  private val file: File by lazy { File(tempDir.toFile(), "wheelwitch.log") }

  @BeforeEach
  fun setUp() {
    Timber.uprootAll()
  }

  @AfterEach
  fun tearDown() {
    Timber.uprootAll()
  }

  @Test
  fun `drops when disabled`() {
    plant(enabled = true, minPriority = Log.VERBOSE)
    // Swap to disabled by planting a new tree
    Timber.uprootAll()
    plant(enabled = false, minPriority = Log.VERBOSE)
    Timber.tag("Tag").i("hello")
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun `drops when logFile is null`() {
    val tree = OptionalFileTree(
      isEnabled = { true },
      logFile = { null },
    )
    Timber.plant(tree)
    Timber.tag("Tag").i("hello")
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun `appends a formatted line when enabled`() {
    plant(enabled = true, minPriority = Log.VERBOSE)
    Timber.tag("Tag").i("hello")
    val contents = file.readText()
    assertThat(contents).contains("I/Tag: hello")
    assertThat(contents).endsWith("\n")
  }

  @Test
  fun `appends throwable stack trace when present`() {
    plant(enabled = true, minPriority = Log.VERBOSE)
    Timber.tag("Tag").e(RuntimeException("nope"), "boom")
    val contents = file.readText()
    assertThat(contents).contains("E/Tag: boom")
    assertThat(contents).contains("RuntimeException")
    assertThat(contents).contains("nope")
    // Timber already appends the stack trace to the message; the tree must not
    // re-render the throwable, otherwise the stack trace would appear twice.
    val first = contents.indexOf("java.lang.RuntimeException")
    val last = contents.lastIndexOf("java.lang.RuntimeException")
    assertThat(first).isGreaterThan(-1)
    assertThat(first).isEqualTo(last)
  }

  @Test
  fun `rotates when file would exceed maxBytes`() {
    plant(enabled = true, minPriority = Log.VERBOSE, maxBytes = 200L)
    repeat(20) { i -> Timber.tag("Tag").i("line $i with some content") }
    val rotated = File(file.parentFile, file.name + ".1")
    assertThat(rotated.exists()).isTrue()
    assertThat(file.exists()).isTrue()
    assertThat(file.length()).isLessThan(500L)
  }

  @Test
  fun `truncates in place when rename rotation fails`() {
    file.writeText("x".repeat(1000))
    val rotated = File(file.parentFile, file.name + ".1")
    rotated.mkdirs()
    File(rotated, "blocker").writeText("block")

    appendWithRotation(file, "y".repeat(100), maxBytes = 100)

    assertThat(rotated.exists()).isTrue()
    assertThat(file.length()).isEqualTo(100L)
    assertThat(file.readText()).isEqualTo("y".repeat(100))
  }

  @Test
  fun `rotates based on utf8 byte length not char count`() {
    val emoji = "\uD83D\uDE00"
    file.writeText(emoji)
    appendWithRotation(file, emoji + emoji, maxBytes = 10)

    val rotated = File(file.parentFile, file.name + ".1")
    assertThat(rotated.exists()).isTrue()
    assertThat(file.length()).isEqualTo(emoji.toByteArray(Charsets.UTF_8).size * 2L)
  }

  @Test
  fun `drops entries below minPriority`() {
    plant(enabled = true, minPriority = Log.WARN)
    Timber.tag("Tag").d("drop")
    Timber.tag("Tag").w("keep")
    val contents = file.readText()
    assertThat(contents).doesNotContain("drop")
    assertThat(contents).contains("keep")
  }

  @Test
  fun `creates parent directories`() {
    Timber.uprootAll()
    val nested = File(tempDir.toFile(), "a/b/c/wheelwitch.log")
    Timber.plant(
      OptionalFileTree(
        isEnabled = { true },
        logFile = { nested },
        minPriority = Log.VERBOSE,
      )
    )
    Timber.tag("Tag").i("hello")
    assertThat(nested.exists()).isTrue()
    assertThat(nested.readText()).contains("hello")
  }

  @Test
  fun `isLoggable returns true when enabled and above minPriority`() {
    plant(enabled = true, minPriority = Log.WARN)
    Timber.tag("Tag").i("info") // dropped
    Timber.tag("Tag").w("warn") // kept
    val contents = file.readText()
    assertThat(contents).doesNotContain("info")
    assertThat(contents).contains("warn")
  }

  @Test
  fun `isLoggable returns false when disabled`() {
    plant(enabled = false, minPriority = Log.VERBOSE)
    Timber.tag("Tag").w("hello")
    assertThat(file.exists()).isFalse()
  }

  private fun plant(enabled: Boolean, minPriority: Int, maxBytes: Long = OptionalFileTree.DEFAULT_MAX_BYTES) {
    Timber.plant(
      OptionalFileTree(
        isEnabled = { enabled },
        logFile = { file },
        maxBytes = maxBytes,
        minPriority = minPriority,
      )
    )
  }
}
