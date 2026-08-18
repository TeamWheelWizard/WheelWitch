package com.skiletro.wheelwitch.model

/**
 * Parsed Dolphin Emulator version, used to warn users running a build
 * with known security vulnerabilities.
 *
 * Dolphin version strings follow the scheme `YYYY[letter][-N]` (e.g.
 * `2606`, `2606a`, `2606-300`, `2503a-254` (the latter format does not seem
 * to be used anymore, however)). The [isAtLeastMinimum] comparison is
 * done on the year-month and, when present, the dev-build number.
 */
data class DolphinVersion(val year: Int, val devBuild: Int?, val hotfix: Char? = null) {
  /**
   * True when this version reaches the security floor:
   * `MINIMUM_YEAR` with at least `MINIMUM_DEV_BUILD` and/or `MINIMUM_HOTFIX`, or any newer year.
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
