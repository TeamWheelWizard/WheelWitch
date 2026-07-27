package com.skiletro.wheelwitch.util.io

/**
 * Helpers for safe zip entry name handling. Every zip extraction
 * site in the codebase should validate entry names through these
 * helpers to prevent path-traversal (Zip Slip) attacks.
 */
object ZipSafety {

  /**
   * Strips a leading `./` prefix.
   */
  fun normalizeEntryName(name: String): String {
    return name.removePrefix("./")
  }

  /**
   * Returns `true` when [name] is a safe, relative entry path
   * suitable for extraction into a target directory.
   *
   * Rejected patterns:
   * - Absolute paths (`/etc/passwd`)
   * - Parent-directory components (`../escape`, `foo/../../bar`)
   * - Current-directory components (`./sneaky`, `foo/./bar`)
   * - Empty components from double slashes (`foo//bar`)
   * - Paths starting with any prefix in [prefixBlockList]
   *
   * A leading `./` prefix is stripped before checking, for
   * compatibility with zip tools that produce `./foo/bar` entries.
   */
  fun isSafeEntryName(
    name: String,
    prefixBlockList: List<String> = emptyList(),
  ): Boolean {
    val normalized = normalizeEntryName(name)
    return !normalized.startsWith("/") &&
      normalized.split('/').none { it.isEmpty() || it == ".." || it == "." } &&
      prefixBlockList.none { normalized.startsWith(it) }
  }
}
