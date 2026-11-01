package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/** Three-part semantic version with optional pre-release label (e.g. "3.2.6-beta1"). */
@Immutable
data class SemVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVersion> {

  override fun compareTo(other: SemVersion): Int {
    major.compareTo(other.major).let { if (it != 0) return it }
    minor.compareTo(other.minor).let { if (it != 0) return it }
    patch.compareTo(other.patch).let { if (it != 0) return it }
    if (preRelease != null && other.preRelease != null) {
      comparePreRelease(preRelease, other.preRelease).let { if (it != 0) return it }
    }
    // A released version (no pre-release) outranks any pre-release of the
    // same major.minor.patch, e.g. 3.2.6 > 3.2.6-beta1.
    if (preRelease == null && other.preRelease != null) return 1
    if (preRelease != null && other.preRelease == null) return -1
    return 0
  }

  override fun toString(): String {
    return if (preRelease != null) "$major.$minor.$patch-$preRelease" else "$major.$minor.$patch"
  }

  companion object {
    private val TRAILING_NUMBER = Regex("^(.*?)(\\d+)$")

    private fun comparePreRelease(left: String, right: String): Int {
      val leftMatch = TRAILING_NUMBER.matchEntire(left)
      val rightMatch = TRAILING_NUMBER.matchEntire(right)
      val leftPrefix = leftMatch?.groupValues?.get(1) ?: left
      val rightPrefix = rightMatch?.groupValues?.get(1) ?: right
      leftPrefix.compareTo(rightPrefix).let { if (it != 0) return it }

      val leftSuffix = leftMatch?.groupValues?.get(2)
      val rightSuffix = rightMatch?.groupValues?.get(2)
      return when {
        leftSuffix == null && rightSuffix == null -> 0
        leftSuffix == null -> -1
        rightSuffix == null -> 1
        else -> compareNumericSuffix(leftSuffix, rightSuffix)
      }
    }

    private fun compareNumericSuffix(left: String, right: String): Int {
      val leftLong = left.toLongOrNull()
      val rightLong = right.toLongOrNull()
      if (leftLong != null && rightLong != null) return leftLong.compareTo(rightLong)

      val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
      val normalizedRight = right.trimStart('0').ifEmpty { "0" }
      normalizedLeft.length.compareTo(normalizedRight.length).let { if (it != 0) return it }
      return normalizedLeft.compareTo(normalizedRight)
    }

    /**
     * Parses a version string ("v3.2.6", "3.2.6-beta1") into [SemVersion], or null on failure.
     * Accepts >= 3 numeric segments; anything beyond patch is ignored.
     */
    fun parse(text: String): SemVersion? {
      val cleaned = text.trimStart('v', 'V')
      val dashIdx = cleaned.indexOf('-')
      val versionPart = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
      val preRelease = if (dashIdx >= 0) cleaned.substring(dashIdx + 1) else null
      val parts = versionPart.split(".")
      if (parts.size < 3) return null
      val major = parts[0].toIntOrNull() ?: return null
      val minor = parts[1].toIntOrNull() ?: return null
      val patch = parts[2].toIntOrNull() ?: return null
      return SemVersion(major, minor, patch, preRelease)
    }
  }
}
