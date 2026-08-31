package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.BadgeType
import org.json.JSONObject

fun parseBadgeTypes(json: String): List<BadgeType> = runCatching {
  val badges = JSONObject(json).optJSONArray("badges") ?: return emptyList()
  buildList(badges.length()) {
    for (index in 0 until badges.length()) {
      add(BadgeType.fromApiValue(badges.optInt(index, -1)))
    }
  }
}.getOrDefault(emptyList())
