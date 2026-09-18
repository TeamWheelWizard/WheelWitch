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
 * The API has historically returned the entries array under several
 * field names (`players`, `data`, `results`, `leaderboard`, `entries`)
 * and pagination under camelCase, snake_case, or a `next` link. All
 * known variants are tolerated; a bare top-level array is also accepted.
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
