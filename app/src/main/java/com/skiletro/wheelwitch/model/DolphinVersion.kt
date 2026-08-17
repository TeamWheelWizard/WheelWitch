package com.skiletro.wheelwitch.model

/**
 * Parsed Dolphin Emulator version, used to warn users running a build
 * with known security vulnerabilities.
 *
 * Dolphin version strings follow the scheme `YYYY[-N[letter]]` (e.g.
 * `2606`, `2606a`, `2606-300`). Master builds carry an extra major
 * prefix (`5.0-2606`); only the `YYYY[-N]` tail is significant. The
 * [isAtLeastMinimum] comparison is done on the year-month and, when
 * present, the dev-build number.
 */
data class DolphinVersion(val year: Int, val devBuild: Int?, val hotfix: Char? = null) {

  /** Classification of an installed Dolphin against the security floor. */
  enum class Status {
    /** Version meets or exceeds the security floor. */
    SUPPORTED,

    /** Version is below the security floor and should be updated. */
    OUTDATED,

    /** Version could not be parsed (no match, garbage, or not installed). */
    UNKNOWN,
  }

  /**
   * True when this version reaches the security floor:
   * `MINIMUM_YEAR` with at least `MINIMUM_DEV_BUILD`, or any newer year.
   */
  fun isAtLeastMinimum(): Boolean =
    when {
      year > MINIMUM_YEAR -> true
      year < MINIMUM_YEAR -> false
      hotfix != null -> hotfix >= MINIMUM_HOTFIX
      devBuild == null -> false
      else -> devBuild >= MINIMUM_DEV_BUILD
    }

  companion object {
    /** Earliest year-month with the security-relevant fixes. */
    const val MINIMUM_YEAR = 2606

    /** Earliest dev build of [MINIMUM_YEAR] carrying the fixes. */
    const val MINIMUM_DEV_BUILD = 300

    /** Earliest stable hotfix of [MINIMUM_YEAR] carrying the fixes. */
    const val MINIMUM_HOTFIX = 'a'

    private val PATTERN = Regex("""(\d{4})([a-z]?)(?:-(\d+))?""")

    /**
     * Parses a Dolphin [versionName] into [DolphinVersion], or null
     * when no `YYYY[-N]` segment is present. The first match is used;
     * a leading `5.0-` master prefix is ignored.
     */
    fun parse(versionName: String?): DolphinVersion? {
      val match = versionName?.let { PATTERN.find(it) } ?: return null
      val year = match.groupValues[1].toIntOrNull() ?: return null
      val hotfix = match.groupValues[2].firstOrNull()
      val devBuild = match.groupValues[3].toIntOrNull()
      return DolphinVersion(year, devBuild, hotfix)
    }
  }
}
