package com.skiletro.wheelwitch.data

import android.content.SharedPreferences
import com.skiletro.wheelwitch.model.BadgeType
import org.json.JSONArray
import org.json.JSONObject

private const val KEY_BADGES = "badges"

/**
 * Serialises [entries] (profile id -> badges) into a single JSON blob,
 * storing each badge's [BadgeType.apiValue]. [BadgeType.UNKNOWN] values
 * are dropped so the decode side cannot resurrect them from storage.
 */
internal fun encodeBadgeCache(entries: Map<Long, List<BadgeType>>): String {
  val root = JSONObject()
  for ((pid, badges) in entries) {
    val clean = badges.filter { it != BadgeType.UNKNOWN }
    if (clean.isEmpty()) continue
    val array = JSONArray()
    clean.forEach { array.put(it.apiValue) }
    root.put(pid.toString(), JSONObject().put(KEY_BADGES, array))
  }
  return root.toString()
}

/** Parses the JSON blob produced by [encodeBadgeCache]; blank or malformed input yields an empty map. */
internal fun decodeBadgeCache(json: String): Map<Long, List<BadgeType>> {
  if (json.isBlank()) return emptyMap()
  return runCatching {
    val root = JSONObject(json)
    val result = mutableMapOf<Long, List<BadgeType>>()
    val keys = root.keys()
    while (keys.hasNext()) {
      val pid = keys.next().toLongOrNull() ?: continue
      val entry = root.optJSONObject(pid.toString()) ?: continue
      val array = entry.optJSONArray(KEY_BADGES) ?: continue
      val badges =
        buildList(array.length()) {
          for (index in 0 until array.length()) {
            add(BadgeType.fromApiValue(array.optInt(index, -1)))
          }
        }
      result[pid] = badges.filter { it != BadgeType.UNKNOWN }
    }
    result
  }.getOrElse { emptyMap() }
}

/** Cache of the last-known-good per-profile badges, keyed by profile id. */
interface BadgeCache {
  fun load(profileId: Long): List<BadgeType>?
  fun save(profileId: Long, badges: List<BadgeType>)
}

/** [BadgeCache] backed by a single JSON blob in SharedPreferences. */
class PrefsBadgeCache(
  private val prefs: SharedPreferences,
  private val key: String,
) : BadgeCache {
  override fun load(profileId: Long): List<BadgeType>? =
    decodeBadgeCache(prefs.getString(key, null).orEmpty())[profileId]

  override fun save(profileId: Long, badges: List<BadgeType>) {
    val entries = decodeBadgeCache(prefs.getString(key, null).orEmpty()).toMutableMap()
    entries[profileId] = badges
    prefs.edit().putString(key, encodeBadgeCache(entries)).apply()
  }
}