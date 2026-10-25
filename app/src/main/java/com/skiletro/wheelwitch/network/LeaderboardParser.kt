package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.LeaderboardEntry
import com.skiletro.wheelwitch.model.LeaderboardResponse
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import com.skiletro.wheelwitch.util.json.jsonArrayFromResponse
import com.skiletro.wheelwitch.util.json.optAnyString
import com.skiletro.wheelwitch.util.json.optNonEmptyString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a `/api/leaderboard` JSON response into [LeaderboardResponse].
 *
 * Accepts a bare array or an entries array under `players`, `data`, `results`, `leaderboard`,
 * or `entries`. Pagination supports camelCase/snake_case fields and treats the presence of
 * `next` as more pages when explicit flags are absent, even if its value is null.
 */
fun parseLeaderboardResponse(jsonString: String): LeaderboardResponse {
    val data = jsonArrayFromResponse(
        jsonString,
        "players", "data", "results", "leaderboard", "entries",
    ) ?: JSONArray()

    val page: Int
    val hasMore: Boolean
    if (jsonString.trim().startsWith("[")) {
        page = 1
        hasMore = false
    } else {
        val root = JSONObject(jsonString)
        page = root.optInt("currentPage", root.optInt("page", 1))
        val totalPages = root.optInt("totalPages", root.optInt("total_pages", 1))
        hasMore = page < totalPages ||
                root.optBoolean("hasMore", root.optBoolean("has_more", root.has("next")))
    }

    val entries = (0 until data.length()).map { i ->
        val obj = data.getJSONObject(i)
        LeaderboardEntry(
            rank = obj.optInt("rank", 0),
            friendCode = obj.optAnyString("friendCode", "friend_code", "fc") ?: "",
            player = PlayerLeaderboardData(
                vr = obj.optInt("vr", 0),
                name = obj.optString("name", "Unknown"),
                miiData = obj.optNonEmptyString("miiData"),
            ),
            miiImageBase64 = obj.optNonEmptyString("miiImageBase64"),
        )
    }
    return LeaderboardResponse(entries = entries, page = page, hasMore = hasMore)
}
