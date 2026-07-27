package com.skiletro.wheelwitch.util.io

/**
 * Helpers for safe zip entry name handling. Every zip extraction
 * site in the codebase should validate entry names through these
 * helpers to prevent path-traversal (Zip Slip) attacks.
 */
object ZipSafety {

  /**
   * Returns `true` when [name] is a safe, relative entry path
   * suitable for extraction into a target directory.
   *
   * Rejected patterns:
   * - Absolute paths (`/etc/passwd`)
   * - Parent-directory components (`../escape`, `foo/../../bar`)
   * - Current-directory components (`./sneaky`, `foo/./bar`)
   * - Empty components from double slashes (`foo//bar`)
   *
   * A leading `./` prefix is stripped before checking, for
   * compatibility with zip tools that produce `./foo/bar` entries.
   */
  fun isSafeEntryName(name: String): Boolean {
    val normalized = name.removePrefix("./")
    return !normalized.startsWith("/") &&
      normalized.split('/').none { it.isEmpty() || it == ".." || it == "." }
  }
}
