package com.skiletro.wheelwitch.data

import android.net.Uri

/**
 * Pure reader/writer for the `ISOPaths` / `ISOPathN` block in Dolphin's `Config/Dolphin.ini`
 * `[General]` section.
 *
 * The Dolphin emulator reads the `[General] ISOPathN` keys when building its game library.
 * WheelWitch edits this file so the `WheelWitch/rom/` folder shows up as a library entry, this is
 * the bridge that makes "RR shows up in Dolphin's library" work even though `rr_autostartfile.json`
 * itself uses physical paths (Riivolution is native code and cannot resolve `content://` URIs).
 *
 * Operations are idempotent: writing the same path twice is a no-op. [upsert] preserves comments,
 * unknown keys, and section ordering.
 */
object DolphinConfig {

  /** The set of `ISOPathN` entries as parsed or to-be-written. */
  data class IsoPaths(val paths: List<String>) {
    /**
     * Renders the canonical INI block: a single `ISOPaths = <count>` line followed by one
     * `ISOPath<i> = <path>` line per entry, indices in order.
     */
    fun toIniLines(): List<String> = buildList {
      add("ISOPaths = ${paths.size}")
      paths.forEachIndexed { i, p -> add("ISOPath$i = $p") }
    }
  }

  /**
   * Reads the values from the canonical `ISOPathN` block in `[General]`.
   *
   * The canonical block is the contiguous run of `ISOPaths = N` and `ISOPathN = ...` lines starting
   * at the `ISOPaths` count line (or at the first `ISOPathN` line if no count line is present) and
   * ending at the first non-`ISOPathN` line. Lines that match the `ISOPathN` pattern but sit
   * outside this run (a stray) are ignored; they would otherwise produce a broken file when
   * [upsert] renumbers the block.
   *
   * The returned list preserves file order. Writing it back via [IsoPaths.toIniLines] renumbers
   * indices to a clean `0..N-1` sequence.
   */
  fun read(content: String): IsoPaths {
    if (content.isBlank()) return IsoPaths(emptyList())
    val lines = content.lines()
    val generalIdx = lines.indexOfFirst { it.trim() == "[General]" }
    if (generalIdx < 0) return IsoPaths(emptyList())
    val sectionEnd = findSectionEnd(lines, generalIdx)
    val blockRange =
        findIsopathsBlockRange(lines, generalIdx, sectionEnd) ?: return IsoPaths(emptyList())
    val values = blockRange.map { lines[it] }.mapNotNull(::parseIsopathLine)
    return IsoPaths(values)
  }

  /**
   * Adds [newPath] to the `ISOPathN` block. Idempotent: if [newPath] is already present, [content]
   * is returned unchanged.
   *
   * Section ordering and comments are preserved. The existing block is replaced in place; if no
   * block exists, a new one is appended at the end of the `[General]` section; if `[General]` does
   * not exist, a new section is created at the end of the file.
   */
  fun upsert(content: String, newPath: String): String {
    val current = read(content)
    if (newPath in current.paths) return content
    return applyBlock(content, IsoPaths(current.paths + newPath))
  }

  /**
   * Removes [path] from the `ISOPathN` block. If [path] is not present, [content] is returned
   * unchanged. After removal the block is renumbered to a clean `0..N-1` sequence (or collapses to
   * `ISOPaths = 0` when the last path is removed).
   */
  fun remove(content: String, path: String): String {
    val current = read(content)
    if (path !in current.paths) return content
    return applyBlock(content, IsoPaths(current.paths - path))
  }

  /**
   * Computes the `content://` URI that Dolphin's own `.user` provider expects for a given relative
   * path under its `files/` tree.
   *
   * WheelWitch never reads from this URI (it has no grant for it); it only writes the string into
   * `Dolphin.ini`'s `ISOPathN` and lets Dolphin resolve it through its own provider.
   *
   * Each `/`-separated segment is percent-encoded individually; segments are then joined with
   * literal `%2F` so the resulting path is the one the provider expects. For example:
   *
   * - `dolphinUserTreeUri("Games")` → `content://org.dolphinemu.dolphinemu.user/tree/root%2FGames`
   * - `dolphinUserTreeUri("Games/sub")` →
   *   `content://org.dolphinemu.dolphinemu.user/tree/root%2FGames%2Fsub`
   */
  fun dolphinUserTreeUri(relativePath: String): String {
    val encoded = relativePath.split('/').joinToString("%2F") { Uri.encode(it) }
    return "content://org.dolphinemu.dolphinemu.user/tree/root%2F$encoded"
  }

  // --- internal helpers ---------------------------------------------------

  /**
   * Replaces (or creates) the `ISOPaths` block in [content] with [newBlock]. Always returns a
   * well-formed INI: a fresh upsert into blank input produces a `[General]` section header so the
   * result is structurally identical to a non-blank upsert. The earlier behavior of returning just
   * the bare block on blank input was inconsistent with the non-blank path and produced a broken
   * file when the result was fed back into [upsert].
   */
  private fun applyBlock(content: String, newPaths: IsoPaths): String {
    val newBlock = newPaths.toIniLines()
    if (content.isBlank()) {
      return (listOf("[General]", "") + newBlock).joinToString("\n")
    }
    val lines = content.lines()
    val generalIdx = lines.indexOfFirst { it.trim() == "[General]" }
    if (generalIdx < 0) return appendNewSection(lines, newBlock)
    val sectionEnd = findSectionEnd(lines, generalIdx)
    val blockRange = findIsopathsBlockRange(lines, generalIdx, sectionEnd)
    return if (blockRange != null) {
      replaceBlockAndStripStrays(lines, blockRange, sectionEnd, newBlock)
    } else {
      appendBlockInSection(lines, generalIdx, sectionEnd, newBlock)
    }
  }

  private fun parseIsopathLine(raw: String): String? {
    val line = raw.trim()
    if (!ISOPATH_LINE.matches(line)) return null
    val eq = line.indexOf('=')
    if (eq < 0) return null
    return line.substring(eq + 1).trim()
  }

  private fun findSectionEnd(lines: List<String>, sectionStart: Int): Int =
      (sectionStart + 1 until lines.size).firstOrNull { i ->
        val t = lines[i].trim()
        t.startsWith("[") && t.endsWith("]") && t != "[General]"
      } ?: lines.size

  /**
   * Returns the inclusive-exclusive line range of the existing `ISOPaths` / `ISOPathN` block within
   * `[General]`, or `null` if no block is present. The range starts at the `ISOPaths = N` line (or
   * the first `ISOPathN` line, if the count line is missing) and ends just after the last
   * consecutive `ISOPathN` line.
   */
  private fun findIsopathsBlockRange(
      lines: List<String>,
      sectionStart: Int,
      sectionEnd: Int,
  ): IntRange? {
    val countIdx =
        (sectionStart + 1 until sectionEnd).firstOrNull { i ->
          ISOPATHS_COUNT.matches(lines[i].trim())
        }
    val blockStart =
        countIdx
            ?: (sectionStart + 1 until sectionEnd).firstOrNull { i ->
              ISOPATH_LINE.matches(lines[i].trim())
            }
            ?: return null
    val blockEnd =
        (blockStart + 1 until sectionEnd).firstOrNull { i ->
          !ISOPATH_LINE.matches(lines[i].trim())
        } ?: sectionEnd
    return blockStart until blockEnd
  }

  /**
   * Replaces the canonical `ISOPaths` block in place AND removes any stray `ISOPathN` / `ISOPaths`
   * lines elsewhere in `[General]`. Strays can appear when a hand-edited file or a prior bug left
   * orphaned entries between unrelated keys; reading them and writing them back unchanged would
   * leave the file in a broken state where the new block says `ISOPaths = 2` but the file contains
   * three `ISOPathN` lines.
   */
  private fun replaceBlockAndStripStrays(
      lines: List<String>,
      blockRange: IntRange,
      sectionEnd: Int,
      newBlock: List<String>,
  ): String {
    val out = mutableListOf<String>()
    out.addAll(lines.subList(0, blockRange.first))
    out.addAll(newBlock)
    var i = blockRange.last + 1
    while (i < sectionEnd) {
      val t = lines[i].trim()
      val isStray = ISOPATHS_COUNT.matches(t) || ISOPATH_LINE.matches(t)
      if (!isStray) out.add(lines[i])
      i++
    }
    out.addAll(lines.subList(sectionEnd, lines.size))
    return out.joinToString("\n")
  }

  private fun appendBlockInSection(
      lines: List<String>,
      sectionStart: Int,
      sectionEnd: Int,
      newBlock: List<String>,
  ): String {
    val out = mutableListOf<String>()
    out.addAll(lines.subList(0, sectionEnd))
    // Trim trailing blanks from the [General] section so the new block
    // is separated from prior content by exactly one blank line.
    while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
    if (out.size > sectionStart + 1) out.add("")
    out.addAll(newBlock)
    out.addAll(lines.subList(sectionEnd, lines.size))
    return out.joinToString("\n")
  }

  private fun appendNewSection(lines: List<String>, newBlock: List<String>): String {
    val out = lines.dropLastWhile { it.isBlank() }.toMutableList()
    if (out.isNotEmpty()) out.add("")
    out.add("[General]")
    out.addAll(newBlock)
    return out.joinToString("\n")
  }

  // INI keys are case-insensitive by convention; Dolphin's parser follows.
  private val ISOPATHS_COUNT: Regex =
      Regex("""^ISOPaths\s*=\s*\d+\s*$""", setOf(RegexOption.IGNORE_CASE))

  private val ISOPATH_LINE: Regex = Regex("""^ISOPath\d+\s*=.*$""", setOf(RegexOption.IGNORE_CASE))
}
