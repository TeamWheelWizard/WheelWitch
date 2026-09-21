package com.skiletro.wheelwitch.data

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DolphinConfigTest {

  @BeforeEach
  fun setUp() {
    mockkStatic(Uri::class)
    every { Uri.encode(any()) } answers { firstArg<String>() }
  }

  @AfterEach
  fun tearDown() {
    unmockkStatic(Uri::class)
  }

  // --- IsoPaths.toIniLines ---------------------------------------------

  @Test
  fun `toIniLines on an empty list renders just the count line`() {
    val lines = DolphinConfig.IsoPaths(emptyList()).toIniLines()
    assertThat(lines).containsExactly("ISOPaths = 0")
  }

  @Test
  fun `toIniLines on a single path renders count and one entry`() {
    val lines = DolphinConfig.IsoPaths(listOf("/a/b")).toIniLines()
    assertThat(lines).containsExactly("ISOPaths = 1", "ISOPath0 = /a/b").inOrder()
  }

  @Test
  fun `toIniLines renumbers indices in order`() {
    val lines = DolphinConfig.IsoPaths(listOf("x", "y", "z")).toIniLines()
    assertThat(lines)
        .containsExactly(
            "ISOPaths = 3",
            "ISOPath0 = x",
            "ISOPath1 = y",
            "ISOPath2 = z",
        )
        .inOrder()
  }

  // --- read ------------------------------------------------------------

  @Test
  fun `read of blank content returns empty paths`() {
    val paths = DolphinConfig.read("").paths
    assertThat(paths).isEmpty()
  }

  @Test
  fun `read of content with no ISOPath lines returns empty paths`() {
    val paths =
        DolphinConfig.read(
                "[General]\n" + "SomeKey = some value\n" + "[Controls]\n" + "AButton = 1\n"
            )
            .paths
    assertThat(paths).isEmpty()
  }

  @Test
  fun `read extracts a single ISOPath0 entry`() {
    val paths =
        DolphinConfig.read(
                "[General]\n" + "ISOPaths = 1\n" + "ISOPath0 = /storage/emulated/0/Games\n"
            )
            .paths
    assertThat(paths).containsExactly("/storage/emulated/0/Games")
  }

  @Test
  fun `read preserves file order of ISOPathN entries`() {
    val content =
        "[General]\n" + "ISOPaths = 3\n" + "ISOPath0 = a\n" + "ISOPath1 = b\n" + "ISOPath2 = c\n"
    val paths = DolphinConfig.read(content).paths
    assertThat(paths).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun `read handles out-of-order ISOPathN indices by preserving file order`() {
    val content =
        "[General]\n" + "ISOPaths = 3\n" + "ISOPath2 = c\n" + "ISOPath0 = a\n" + "ISOPath1 = b\n"
    val paths = DolphinConfig.read(content).paths
    assertThat(paths).containsExactly("c", "a", "b").inOrder()
  }

  @Test
  fun `read ignores the ISOPaths count line itself`() {
    val content =
        "[General]\n" +
            "ISOPaths = 5\n" + // count lies; should be ignored
            "ISOPath0 = a\n" +
            "ISOPath1 = b\n"
    val paths = DolphinConfig.read(content).paths
    assertThat(paths).containsExactly("a", "b")
  }

  @Test
  fun `read ignores malformed ISOPath lines and the strays they break the block on`() {
    // A malformed `ISOPathBad` (no digit) breaks the contiguous block;
    // it's not in the ISOPathN regex family. The trailing ISOPath2
    // then sits outside the block and is also ignored, matching the
    // behavior we use in upsert/remove to keep the file canonical.
    val content =
        "[General]\n" +
            "ISOPath0 = \n" + // empty value; still parsed as ""
            "ISOPath1 = good\n" +
            "ISOPathBad\n" + // breaks the contiguous run
            "ISOPath2 = trailing\n"
    val paths = DolphinConfig.read(content).paths
    assertThat(paths).containsExactly("", "good").inOrder()
  }

  // --- upsert ----------------------------------------------------------

  @Test
  fun `upsert into empty content writes a new General section with the block`() {
    // The output is a well-formed INI: a [General] header followed by
    // a blank line and the ISOPaths block. This matches the non-blank
    // path so a caller can re-upsert into the result without producing
    // a stray block.
    val out = DolphinConfig.upsert("", "/Games")
    assertThat(out).isEqualTo("[General]\n\nISOPaths = 1\nISOPath0 = /Games")
  }

  @Test
  fun `upsert into content with no General section appends a new section`() {
    val out = DolphinConfig.upsert("SomeKey = value\n", "/Games")
    assertThat(out).isEqualTo("SomeKey = value\n\n[General]\nISOPaths = 1\nISOPath0 = /Games")
  }

  @Test
  fun `upsert with no existing ISOPaths block appends at the end of General`() {
    val out =
        DolphinConfig.upsert(
            "[General]\n" + "SomeKey = value\n" + "[Controls]\n" + "AButton = 1\n",
            "/Games",
        )
    assertThat(out)
        .isEqualTo(
            "[General]\n" +
                "SomeKey = value\n" +
                "\n" +
                "ISOPaths = 1\n" +
                "ISOPath0 = /Games\n" +
                "[Controls]\n" +
                "AButton = 1\n"
        )
  }

  @Test
  fun `upsert with one existing entry adds as ISOPath1 and increments count to 2`() {
    val out =
        DolphinConfig.upsert(
            "[General]\n" + "ISOPaths = 1\n" + "ISOPath0 = /A\n",
            "/B",
        )
    assertThat(out).isEqualTo("[General]\nISOPaths = 2\nISOPath0 = /A\nISOPath1 = /B\n")
  }

  @Test
  fun `upsert with the same path already present is a no-op`() {
    val content = "[General]\n" + "ISOPaths = 1\n" + "ISOPath0 = /A\n"
    val out = DolphinConfig.upsert(content, "/A")
    assertThat(out).isEqualTo(content)
  }

  @Test
  fun `upsert replaces the existing block in place`() {
    val out =
        DolphinConfig.upsert(
            "[General]\n" +
                "ISOPaths = 1\n" +
                "ISOPath0 = /A\n" +
                "SomeKey = value\n" +
                "ISOPath1 = /B\n", // stray line; should not survive block replacement
            "/C",
        )
    assertThat(out)
        .isEqualTo(
            "[General]\n" +
                "ISOPaths = 2\n" +
                "ISOPath0 = /A\n" +
                "ISOPath1 = /C\n" +
                "SomeKey = value\n"
        )
  }

  @Test
  fun `upsert preserves comments and unknown keys`() {
    val out =
        DolphinConfig.upsert(
            "; top comment\n" +
                "# hash comment\n" +
                "[General]\n" +
                "; inside section\n" +
                "SomeKey = value\n" +
                "ISOPaths = 1\n" +
                "ISOPath0 = /A\n" +
                "AnotherKey = ok\n",
            "/B",
        )
    assertThat(out)
        .isEqualTo(
            "; top comment\n" +
                "# hash comment\n" +
                "[General]\n" +
                "; inside section\n" +
                "SomeKey = value\n" +
                "ISOPaths = 2\n" +
                "ISOPath0 = /A\n" +
                "ISOPath1 = /B\n" +
                "AnotherKey = ok\n"
        )
  }

  @Test
  fun `upsert preserves sections after General`() {
    val out =
        DolphinConfig.upsert(
            "[General]\n" + "ISOPaths = 0\n" + "[Controls]\n" + "AButton = 1\n",
            "/Games",
        )
    assertThat(out)
        .isEqualTo(
            "[General]\n" +
                "ISOPaths = 1\n" +
                "ISOPath0 = /Games\n" +
                "[Controls]\n" +
                "AButton = 1\n"
        )
  }

  // --- remove ----------------------------------------------------------

  @Test
  fun `remove of a present path decrements count and renumbers`() {
    val out =
        DolphinConfig.remove(
            "[General]\n" +
                "ISOPaths = 3\n" +
                "ISOPath0 = /A\n" +
                "ISOPath1 = /B\n" +
                "ISOPath2 = /C\n",
            "/B",
        )
    assertThat(out).isEqualTo("[General]\nISOPaths = 2\nISOPath0 = /A\nISOPath1 = /C\n")
  }

  @Test
  fun `remove of an absent path is a no-op`() {
    val content = "[General]\n" + "ISOPaths = 1\n" + "ISOPath0 = /A\n"
    val out = DolphinConfig.remove(content, "/Z")
    assertThat(out).isEqualTo(content)
  }

  @Test
  fun `remove of the last path leaves an empty ISOPaths block`() {
    val out =
        DolphinConfig.remove(
            "[General]\n" + "ISOPaths = 1\n" + "ISOPath0 = /A\n",
            "/A",
        )
    assertThat(out).isEqualTo("[General]\nISOPaths = 0\n")
  }

  @Test
  fun `remove preserves comments and unknown keys`() {
    val out =
        DolphinConfig.remove(
            "[General]\n" +
                "; comment\n" +
                "SomeKey = value\n" +
                "ISOPaths = 2\n" +
                "ISOPath0 = /A\n" +
                "ISOPath1 = /B\n" +
                "AnotherKey = ok\n",
            "/B",
        )
    assertThat(out)
        .isEqualTo(
            "[General]\n" +
                "; comment\n" +
                "SomeKey = value\n" +
                "ISOPaths = 1\n" +
                "ISOPath0 = /A\n" +
                "AnotherKey = ok\n"
        )
  }

  // --- dolphinUserTreeUri ---------------------------------------------

  @Test
  fun `dolphinUserTreeUri for a single segment uses root prefix with the segment`() {
    val uri = DolphinConfig.dolphinUserTreeUri("Games")
    assertThat(uri).isEqualTo("content://org.dolphinemu.dolphinemu.user/tree/root%2FGames")
  }

  @Test
  fun `dolphinUserTreeUri for nested segments joins with literal percent-2F`() {
    val uri = DolphinConfig.dolphinUserTreeUri("Games/sub")
    assertThat(uri).isEqualTo("content://org.dolphinemu.dolphinemu.user/tree/root%2FGames%2Fsub")
  }

  @Test
  fun `dolphinUserTreeUri for empty path still produces the root prefix`() {
    val uri = DolphinConfig.dolphinUserTreeUri("")
    assertThat(uri).isEqualTo("content://org.dolphinemu.dolphinemu.user/tree/root%2F")
  }
}
