package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.BadgeType
import com.skiletro.wheelwitch.model.DeletionEntry
import com.skiletro.wheelwitch.model.LeaderboardResponse
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import com.skiletro.wheelwitch.model.RaceStats
import com.skiletro.wheelwitch.model.Room
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.model.ServerHealth
import com.skiletro.wheelwitch.model.ServerInfo
import com.skiletro.wheelwitch.model.TimeTrialLeaderboardResponse
import com.skiletro.wheelwitch.model.TimeTrialTrack
import com.skiletro.wheelwitch.model.UpdateEntry
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/** Parses RetroRewindVersion.txt format: lines of "version url path description". Skips malformed lines. */
internal fun parseUpdatesText(text: String): List<UpdateEntry> {
    return text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(" ", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val version = parts[0].trim()
            val url = parts[1].trim()
            val path = parts[2].trim()
            val description = parts[3].trim()
            val parsed = SemVersion.parse(version) ?: return@mapNotNull null
            UpdateEntry(parsed, normalizeUpdateUrl(url), path, description)
        }
}

/**
 * Normalises an update manifest URL:
 *  - upgrades `http://` to `https://`
 *  - drops the legacy `:8000` port
 *
 * Leaves anything HttpUrl can't parse unchanged so a malformed
 * entry fails through to the normal skip path in [parseUpdatesText].
 */
internal fun normalizeUpdateUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    if (parsed.scheme != "http") return url
    return parsed.newBuilder().scheme("https").port(443).build().toString()
}

/** Parses RetroRewindDelete.txt format: lines of "version path". Skips malformed lines. */
internal fun parseDeletionsText(text: String): List<DeletionEntry> {
    return text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(" ", limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val version = SemVersion.parse(parts[0].trim()) ?: return@mapNotNull null
            DeletionEntry(version, parts[1].trim())
        }
}

/** Fetches update manifests, room status, leaderboard data from the RWFC network. */
object VersionFileParser {
    private const val RR_BASE = "https://update.rwfc.net/"
    private const val VERSION_URL = "${RR_BASE}RetroRewind/RetroRewindVersion.txt"
    private const val DELETE_URL = "${RR_BASE}RetroRewind/RetroRewindDelete.txt"
    private const val INSTALL_URL = "${RR_BASE}RetroRewind/RetroRewindInstall.txt"
    private const val RWFC_API = "https://rwfc.net"
    private const val ROOM_STATUS_URL = "$RWFC_API/api/roomstatus"
    private const val LEADERBOARD_URL = "$RWFC_API/api/leaderboard"
    private const val HEALTH_URL = "$RWFC_API/api/health"
    private const val HEALTH_LIVE_URL = "$RWFC_API/api/health/live"
    private const val RACE_STATS_URL = "$RWFC_API/api/racestats/global"
    private const val TIME_TRIAL_TRACKS_URL = "$RWFC_API/api/timetrial/tracks"
    private const val TIME_TRIAL_LEADERBOARD_URL = "$RWFC_API/api/timetrial/leaderboard"
    private const val BADGES_BASE = "$RWFC_API/api/badges/by-pid/"

    private val httpClient get() = HttpClientProvider.client
    private val probeClient get() = HttpClientProvider.probeClient

    /** Fetches the full update manifest: latest version, all update steps, and file deletions. */
    fun fetchServerInfo(): Result<ServerInfo> = runCatching {
        val updates = fetchUpdates()
        if (updates.isEmpty()) error("No versions found on server")
        val deletions = fetchDeletions()
        val latestEntry = updates.maxBy { it.version }
        ServerInfo(latestVersion = latestEntry.version, allUpdates = updates, deletions = deletions)
    }

    private fun fetchUpdates() = parseUpdatesText(fetchUrl(VERSION_URL))

    private fun fetchDeletions() = parseDeletionsText(fetchUrl(DELETE_URL))

    /** Fetches the URL for the full Retro Rewind zip download from the server pointer file. */
    fun getFullZipUrl(): String = fetchUrl(INSTALL_URL).trim()

    /** Fetches the list of active multiplayer rooms from the RWFC API. */
    fun fetchRooms(): Result<List<Room>> = runCatching {
        val json = fetchUrl(ROOM_STATUS_URL)
        parseRooms(json)
    }

    /** Fetches a single page from the paginated VR leaderboard. */
    fun fetchLeaderboard(page: Int = 1, limit: Int = 50): Result<LeaderboardResponse> =
        runCatching {
            val url = "$LEADERBOARD_URL?page=$page&limit=$limit"
            val json = fetchUrl(url)
            parseLeaderboardResponse(json)
        }

    /** Fetches the full server health report. Returns a basic ServerHealth if the detailed endpoint fails. */
    fun fetchHealth(): Result<ServerHealth> = runCatching {
        val json = fetchUrl(HEALTH_URL)
        parseHealthResponse(json)
    }

    /**
     * Quick reachability check using the short-timeout [probeClient].
     * Returns true if the server is reachable and responds successfully.
     * Used by VMs to short-circuit before expensive timeouts.
     */
    fun probeServer(): Boolean = runCatching {
        val request = Request.Builder().url(HEALTH_LIVE_URL).build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** Fetches global race statistics and returns the raw JSON for caching. */
    fun fetchGlobalRaceStatsRaw(): Result<Pair<RaceStats, String>> = runCatching {
        val json = fetchUrl(RACE_STATS_URL)
        Pair(parseRaceStats(json), json)
    }

    /** Fetches the list of time trial tracks. */
    fun fetchTracks(): Result<List<TimeTrialTrack>> = runCatching {
        val json = fetchUrl(TIME_TRIAL_TRACKS_URL)
        parseTracks(json)
    }

    /**
     * Fetches the time trial leaderboard for a given track, filtering by
     * engine class and glitch preference. Paginated via [page] and [pageSize].
     */
    fun fetchTrackLeaderboard(
        trackId: Int,
        cc: Int = 150,
        glitchAllowed: Boolean = true,
        page: Int = 1,
        pageSize: Int = 10,
    ): Result<TimeTrialLeaderboardResponse> = runCatching {
        val url = "$TIME_TRIAL_LEADERBOARD_URL?glitchAllowed=$glitchAllowed&trackId=$trackId&cc=$cc&page=$page&pageSize=$pageSize"
        val json = fetchUrl(url)
        parseTimeTrialLeaderboard(json)
    }

    /** Fetches the leaderboard VR and Mii name for a given friend code. */
    fun fetchPlayerLeaderboard(friendCode: String): Result<PlayerLeaderboardData> = runCatching {
        val url = "$RWFC_API/api/leaderboard/player/$friendCode/"
        val json = fetchUrl(url)
        val obj = JSONObject(json)
        val rawName = obj.opt("name")
        val rawMiiData = obj.opt("miiData")
        PlayerLeaderboardData(
            vr = obj.optInt("vr", 0),
            name = if (rawName == null || rawName === JSONObject.NULL) null else rawName.toString(),
            miiData = if (rawMiiData == null || rawMiiData === JSONObject.NULL) null else rawMiiData.toString(),
        )
    }

    /** Fetches the badges for each profile ID. Pids with no badges are omitted. */
    suspend fun fetchBadges(profileIds: Collection<Long>): Map<Long, List<BadgeType>> =
        coroutineScope {
            profileIds
                .distinct()
                .map { pid -> async { pid to badgesForPid(pid) } }
                .awaitAll()
                .filter { (_, badges) -> badges.isNotEmpty() }
                .toMap()
        }

    private fun badgesForPid(pid: Long): List<BadgeType> = runCatching {
        parseBadgeTypes(fetchUrl("$BADGES_BASE$pid")).filter { it != BadgeType.UNKNOWN }
    }
        .getOrElse {
            Timber.tag("Network").w(it, "Failed to fetch badges for %d", pid)
            emptyList()
        }

    /** Blocking HTTP GET. Throws on non-2xx or empty body. */
    private fun fetchUrl(urlString: String): String {
        val request = Request.Builder().url(urlString).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: error("Empty response from $urlString")
            if (!response.isSuccessful) {
                Timber.tag("Network").w("%s returned %d", urlString, response.code)
                error("$urlString returned ${response.code}: $body")
            }
            return body
        }
    }
}
