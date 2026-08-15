package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.TimeTrialLeaderboardResponse
import com.skiletro.wheelwitch.model.TimeTrialSubmission
import com.skiletro.wheelwitch.model.TimeTrialTrack
import com.skiletro.wheelwitch.util.json.jsonArrayFromResponse
import com.skiletro.wheelwitch.util.json.optNonEmptyString
import org.json.JSONArray
import org.json.JSONObject

fun parseTracks(jsonString: String): List<TimeTrialTrack> {
    val arr = jsonArrayFromResponse(jsonString, "tracks", "data") ?: return emptyList()
    return (0 until arr.length()).map { parseTrack(arr.getJSONObject(it)) }
}

fun parseTimeTrialLeaderboard(jsonString: String): TimeTrialLeaderboardResponse {
    val root = JSONObject(jsonString)
    val track = parseTrack(root.getJSONObject("track"))
    val submissionsArr = root.optJSONArray("submissions") ?: JSONArray()
    val submissions = (0 until submissionsArr.length()).map { parseSubmission(submissionsArr.getJSONObject(it)) }

    val shroomless: Boolean? = if (root.isNull("shroomless")) null else root.optBoolean("shroomless")
    val fastestLapMs = if (root.isNull("fastestLapMs")) null else root.optLong("fastestLapMs", 0)
    val fastestLapDisplay = root.optNonEmptyString("fastestLapDisplay")

    return TimeTrialLeaderboardResponse(
        track = track,
        cc = root.optInt("cc", 150),
        glitchAllowed = root.optBoolean("glitchAllowed", true),
        shroomless = shroomless,
        vehicleFilter = root.optNonEmptyString("vehicleFilter"),
        isFlap = root.optBoolean("isFlap", false),
        submissions = submissions,
        totalSubmissions = root.optInt("totalSubmissions", 0),
        currentPage = root.optInt("currentPage", 1),
        pageSize = root.optInt("pageSize", 10),
        totalPages = root.optInt("totalPages", 1),
        fastestLapMs = fastestLapMs,
        fastestLapDisplay = fastestLapDisplay,
    )
}

private fun parseTrack(obj: JSONObject): TimeTrialTrack = TimeTrialTrack(
    id = obj.optInt("id", 0),
    name = obj.optString("name", "Unknown"),
    courseId = obj.optInt("courseId", -1),
    category = obj.optString("category", "retro"),
    laps = obj.optInt("laps", 3),
    supportsGlitch = obj.optBoolean("supportsGlitch", false),
    sortOrder = obj.optInt("sortOrder", 0),
    isHidden = obj.optBoolean("isHidden", false),
)

private fun parseSubmission(obj: JSONObject): TimeTrialSubmission {
    val fastestLapMs = if (obj.isNull("fastestLapMs")) null else obj.optLong("fastestLapMs", 0)
    val rank = if (obj.isNull("rank")) null else obj.optInt("rank", 0)
    val submittedAt = obj.optNonEmptyString("submittedAt")
    val lapSplitsMs = parseLongArray(obj.optJSONArray("lapSplitsMs"))
    val lapSplitsDisplay = parseStringArray(obj.optJSONArray("lapSplitsDisplay"))

    return TimeTrialSubmission(
        id = obj.optInt("id", 0),
        trackId = obj.optInt("trackId", 0),
        trackName = obj.optString("trackName", ""),
        ttProfileId = obj.optInt("ttProfileId", 0),
        playerName = obj.optString("playerName", "Unknown"),
        countryCode = obj.optInt("countryCode", 0),
        countryAlpha2 = obj.optNonEmptyString("countryAlpha2"),
        countryName = obj.optNonEmptyString("countryName"),
        cc = obj.optInt("cc", 150),
        finishTimeMs = obj.optLong("finishTimeMs", 0),
        finishTimeDisplay = obj.optString("finishTimeDisplay", "0:00.000"),
        vehicleId = obj.optInt("vehicleId", 0),
        characterId = obj.optInt("characterId", 0),
        controllerType = obj.optInt("controllerType", 0),
        driftType = obj.optInt("driftType", 0),
        shroomless = obj.optBoolean("shroomless", false),
        glitch = obj.optBoolean("glitch", false),
        isFlap = obj.optBoolean("isFlap", false),
        driftCategory = obj.optInt("driftCategory", 0),
        miiName = obj.optNonEmptyString("miiName"),
        lapCount = obj.optInt("lapCount", 0),
        lapSplitsMs = lapSplitsMs,
        lapSplitsDisplay = lapSplitsDisplay,
        fastestLapMs = fastestLapMs,
        fastestLapDisplay = obj.optNonEmptyString("fastestLapDisplay"),
        dateSet = obj.optString("dateSet", ""),
        submittedAt = submittedAt,
        rank = rank,
    )
}

private fun parseLongArray(arr: JSONArray?): List<Long>? {
    if (arr == null) return null
    return (0 until arr.length()).map { arr.optLong(it, 0) }
}

private fun parseStringArray(arr: JSONArray?): List<String>? {
    if (arr == null) return null
    return (0 until arr.length()).map { arr.optString(it, "") }
}
