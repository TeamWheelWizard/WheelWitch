package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/**
 * Parsed Dolphin Emulator version, used to warn users running a build
 * with known security vulnerabilities.
 *
 * Accepts `YYMM[letter][-N]` releases (e.g. `2606a`, `2606-300`). At the minimum year-month,
 * a dev-build number takes precedence over a hotfix letter in the security-floor comparison.
 */
@Immutable
data class DolphinVersion(val year: Int, val devBuild: Int?, val hotfix: Char? = null) {
  /**
   * True for a newer year-month, or the minimum year-month with a sufficient dev build
   * (when present) or stable hotfix.
   */
  fun isAtLeastMinimum(): Boolean = when {
    year > MINIMUM_YEAR -> true
    year < MINIMUM_YEAR -> false
    devBuild != null -> devBuild >= MINIMUM_DEV_BUILD
    else -> MINIMUM_HOTFIX.firstOrNull()?.let { minimum -> hotfix?.let { it >= minimum } ?: false }
      ?: true
  }

  companion object {
    /** Earliest year-month with the security-relevant fixes. */
    const val MINIMUM_YEAR = 2606

    /** Earliest dev build of [MINIMUM_YEAR] carrying the fixes. */
    const val MINIMUM_DEV_BUILD = 300

    /** Earliest stable hotfix of [MINIMUM_YEAR] carrying the fixes. */
    const val MINIMUM_HOTFIX = "a"

    private val PATTERN = Regex("""^(\d{4})([a-z]?)(?:-(\d+))?$""")

    private val LEGACY_PATTERN = Regex("""^\d+\.\d+-\d+$""")

    /**
     * Parses a complete `YYMM[letter][-N]` version. Legacy `N.N-N` versions map to an
     * always-outdated sentinel; other unrecognized strings return null.
     */
    fun parse(versionName: String?): DolphinVersion? {
      versionName?.let { LEGACY_PATTERN.find(it) }?.let {
        // Recognized legacy builds predate the security floor.
        return DolphinVersion(-1, -1)
      }
      val match = versionName?.let { PATTERN.find(it) } ?: return null
      val year = match.groupValues[1].toIntOrNull() ?: return null
      val hotfix = match.groupValues[2].firstOrNull()
      val devBuild = match.groupValues[3].toIntOrNull()
      return DolphinVersion(year, devBuild, hotfix)
    }
  }
}
