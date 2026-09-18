package com.skiletro.wheelwitch.data

import android.content.SharedPreferences
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import org.json.JSONObject

private const val KEY_VR = "vr"
private const val KEY_NAME = "name"
private const val KEY_MII_DATA = "miiData"

/**
 * Serialises [entries] (friend code -> leaderboard data) into a single
 * JSON blob. Null [PlayerLeaderboardData.name]/[PlayerLeaderboardData.miiData]
 * are omitted and reconstructed as null by [decodeLeaderboardCache], so the
 * "API supplied nothing" case stays distinct from an absent entry.
 */
internal fun encodeLeaderboardCache(entries: Map<String, PlayerLeaderboardData>): String {
  val root = JSONObject()
  for ((friendCode, data) in entries) {
    val entry = JSONObject()
    entry.put(KEY_VR, data.vr)
    data.name?.let { entry.put(KEY_NAME, it) }
    data.miiData?.let { entry.put(KEY_MII_DATA, it) }
    root.put(friendCode, entry)
  }
  return root.toString()
}

/** Parses the JSON blob produced by [encodeLeaderboardCache]; blank or malformed input yields an empty map. */
internal fun decodeLeaderboardCache(json: String): Map<String, PlayerLeaderboardData> {
  if (json.isBlank()) return emptyMap()
  return runCatching {
    val root = JSONObject(json)
    val result = mutableMapOf<String, PlayerLeaderboardData>()
    val keys = root.keys()
    while (keys.hasNext()) {
      val friendCode = keys.next()
      val entry = root.optJSONObject(friendCode) ?: continue
      val rawName = entry.opt(KEY_NAME)
      val rawMiiData = entry.opt(KEY_MII_DATA)
      result[friendCode] =
        PlayerLeaderboardData(
          vr = entry.optInt(KEY_VR, 0),
          name = if (rawName == null || rawName === JSONObject.NULL) null else rawName.toString(),
          miiData = if (rawMiiData == null || rawMiiData === JSONObject.NULL) null else rawMiiData.toString(),
        )
    }
    result
  }.getOrElse { emptyMap() }
}

/** Cache of the last-known-good per-player leaderboard data, keyed by friend code. */
interface PlayerLeaderboardCache {
  fun load(friendCode: String): PlayerLeaderboardData?
  fun save(friendCode: String, data: PlayerLeaderboardData)
}

/** [PlayerLeaderboardCache] backed by a single JSON blob in SharedPreferences. */
class PrefsPlayerLeaderboardCache(
  private val prefs: SharedPreferences,
  private val key: String,
) : PlayerLeaderboardCache {
  override fun load(friendCode: String): PlayerLeaderboardData? =
    decodeLeaderboardCache(prefs.getString(key, null).orEmpty())[friendCode]

  override fun save(friendCode: String, data: PlayerLeaderboardData) {
    val entries = decodeLeaderboardCache(prefs.getString(key, null).orEmpty()).toMutableMap()
    entries[friendCode] = data
    prefs.edit().putString(key, encodeLeaderboardCache(entries)).apply()
  }
}