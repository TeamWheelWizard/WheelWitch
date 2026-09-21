package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.ActivePlayer
import com.skiletro.wheelwitch.model.CountStat
import com.skiletro.wheelwitch.model.HourStat
import com.skiletro.wheelwitch.model.RaceStats
import com.skiletro.wheelwitch.model.WinRateStat
import com.skiletro.wheelwitch.util.json.mapObjects
import com.skiletro.wheelwitch.util.json.optNonEmptyString
import org.json.JSONArray
import org.json.JSONObject

/** Parses the `/api/racestats/global` JSON response into [RaceStats]; missing or null fields default to empty/0. */
fun parseRaceStats(jsonString: String): RaceStats {
    val root = JSONObject(jsonString)
    return RaceStats(
        totalRaces = root.optInt("totalRacesTracked", 0),
        totalPlayers = root.optInt("uniquePlayersCount", 0),
        trackedSince = root.optNonEmptyString("trackedSince"),
        allPlayedTracks = root.optJSONArray("allPlayedTracks")?.let { parseCountStats(it, "trackName") }
            ?: emptyList(),
        topCharacters = root.optJSONArray("topCharacters")?.let { parseCountStats(it, "name") }
            ?: emptyList(),
        topVehicles = root.optJSONArray("topVehicles")?.let { parseCountStats(it, "name") } ?: emptyList(),
        topCombos = root.optJSONArray("topCombos")?.let { parseCountStats(it, "name") } ?: emptyList(),
        mostActivePlayers = root.optJSONArray("mostActivePlayers")?.let { parseActivePlayers(it) }
            ?: emptyList(),
        racesByDayOfWeek = root.optJSONArray("racesByDayOfWeek")?.let { parseCountStats(it, "dayName") }
            ?: emptyList(),
        racesByHour = root.optJSONArray("racesByHour")?.let { parseHourStats(it) } ?: emptyList(),
        topCharactersByWinRate = root.optJSONArray("topCharactersByWinRate")
            ?.let { parseWinRateStats(it) } ?: emptyList(),
        topVehiclesByWinRate = root.optJSONArray("topVehiclesByWinRate")
            ?.let { parseWinRateStats(it) } ?: emptyList(),
        topCombosByWinRate = root.optJSONArray("topCombosByWinRate")?.let { parseWinRateStats(it) }
            ?: emptyList(),
    )
}

private fun parseCountStats(arr: JSONArray, nameKey: String): List<CountStat> =
    arr.mapObjects {
        CountStat(
            name = it.optString(nameKey, ""),
            raceCount = it.optInt("raceCount", 0)
        )
    }

private fun parseWinRateStats(arr: JSONArray): List<WinRateStat> =
    arr.mapObjects {
        WinRateStat(
            name = it.optString("name", ""),
            raceCount = it.optInt("raceCount", 0),
            winCount = it.optInt("winCount", 0),
            winRate = it.optDouble("winRate", 0.0)
        )
    }

private fun parseActivePlayers(arr: JSONArray): List<ActivePlayer> =
    arr.mapObjects {
        ActivePlayer(
            name = it.optString("name", ""),
            pid = it.optString("pid", ""),
            fc = it.optString("fc", ""),
            raceCount = it.optInt("raceCount", 0)
        )
    }

private fun parseHourStats(arr: JSONArray): List<HourStat> =
    arr.mapObjects {
        HourStat(
            hour = it.optInt("hour", 0),
            raceCount = it.optInt("raceCount", 0)
        )
    }
