package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.VanityBadge

/**
 * Parses vanity badge text files from
 * `https://update.rwfc.net/RetroRewind/badges/{ant,dev,dono}.txt`.
 * Each file contains one friend code per line in the format
 * `XXXX-XXXX-XXXX,  // name`.
 */
object VanityBadgeParser {
  private val FRIEND_CODE_REGEX = Regex("\\d{4}-\\d{4}-\\d{4}")

  fun parseBadgeText(text: String, badge: VanityBadge): Map<String, VanityBadge> {
    return text.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .mapNotNull { line ->
        val fc = line.split(",").firstOrNull()?.trim()
        if (fc != null && fc.matches(FRIEND_CODE_REGEX)) fc else null
      }
      .associateWith { badge }
  }
}
