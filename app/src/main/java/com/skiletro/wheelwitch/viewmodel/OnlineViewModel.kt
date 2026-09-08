package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.RaceStats
import com.skiletro.wheelwitch.model.ServerConnectivity
import com.skiletro.wheelwitch.model.ServerHealth
import com.skiletro.wheelwitch.model.TimeTrialTrack
import com.skiletro.wheelwitch.network.VersionFileParser
import com.skiletro.wheelwitch.network.parseRaceStats
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import com.skiletro.wheelwitch.viewmodel.OnlineViewModel.Companion.MAX_CACHE_AGE_MS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Wrapper for a cached [RaceStats] payload and the wall-clock time it was cached at. */
private data class RaceStatsCache(val stats: RaceStats, val cachedAt: Long)

/**
 * Owns rooms, leaderboard, server health, race stats, and time-trial
 * tracks state. Each sub-screen has its own state machine; pagination
 * for the leaderboard is race-free via a conflated channel.
 */
class OnlineViewModel(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    private val app = application
    private val prefs = Prefs.raceStatsCache(application)

    private val _currentPage = MutableStateFlow(OnlineMenuPage.Hub)
    val currentPage: StateFlow<OnlineMenuPage> = _currentPage.asStateFlow()

    private val _roomsState = MutableStateFlow<RoomsState>(RoomsState.Idle)
    val roomsState: StateFlow<RoomsState> = _roomsState.asStateFlow()

    private val _leaderboardState = MutableStateFlow<LeaderboardState>(LeaderboardState.Idle)
    val leaderboardState: StateFlow<LeaderboardState> = _leaderboardState.asStateFlow()

    private val _healthState = MutableStateFlow<HealthState>(HealthState.Idle)
    val healthState: StateFlow<HealthState> = _healthState.asStateFlow()

    private val _raceStatsState = MutableStateFlow<RaceStatsState>(RaceStatsState.Idle)
    val raceStatsState: StateFlow<RaceStatsState> = _raceStatsState.asStateFlow()

    private val _ttState = MutableStateFlow<TimeTrialState>(TimeTrialState.Idle)
    val ttState: StateFlow<TimeTrialState> = _ttState.asStateFlow()

    private val _selectedTrackId = MutableStateFlow<Int?>(null)
    val selectedTrackId: StateFlow<Int?> = _selectedTrackId.asStateFlow()

    private val _trackLeaderboardState = MutableStateFlow<TrackLeaderboardState>(TrackLeaderboardState.Idle)
    val trackLeaderboardState: StateFlow<TrackLeaderboardState> = _trackLeaderboardState.asStateFlow()

    private val _cc = MutableStateFlow(150)
    val cc: StateFlow<Int> = _cc.asStateFlow()

    private val _glitchAllowed = MutableStateFlow(true)
    val glitchAllowed: StateFlow<Boolean> = _glitchAllowed.asStateFlow()

    /**
     * Conflated channel so rapid "load more" taps coalesce into one
     * in-flight request. The consumer in [launchLeaderboardConsumer]
     * reads the current state to decide the next page.
     */
    private val leaderboardRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        initialFetch()
        launchLeaderboardConsumer()
    }

    /**
     * Consume pagination requests. The channel is conflated, so at most
     * one request is in flight at a time and rapid taps coalesce.
     *
     * On page 1 we transition to [LeaderboardState.Loading] so the UI
     * shows a spinner; on later pages we keep the existing [Success]
     * visible while the next page loads (infinite-scroll UX).
     */
    private fun launchLeaderboardConsumer() {
        viewModelScope.launch {
            leaderboardRequests.consumeAsFlow().collect { _ ->
                val stateBeforeFetch = _leaderboardState.value
                if (stateBeforeFetch is LeaderboardState.Success && !stateBeforeFetch.hasMore) return@collect
                val nextPage = when (stateBeforeFetch) {
                    is LeaderboardState.Success -> stateBeforeFetch.page + 1
                    else -> 1
                }
                if (nextPage == 1) {
                    _leaderboardState.value = LeaderboardState.Loading
                }
                val result = withContext(ioDispatcher) {
                    VersionFileParser.fetchLeaderboard(page = nextPage)
                }
                result.onSuccess { response ->
                    val existing =
                        (stateBeforeFetch as? LeaderboardState.Success)?.entries ?: emptyList()
                    _leaderboardState.value = LeaderboardState.Success(
                        entries = existing + response.entries,
                        hasMore = response.hasMore,
                        page = nextPage
                    )
                }.onFailure { e ->
                    Timber.tag(TAG).w(e, "Leaderboard page %d fetch failed", nextPage)
                    _leaderboardState.value = if (stateBeforeFetch is LeaderboardState.Success) {
                        stateBeforeFetch
                    } else {
                        LeaderboardState.Error(
                            e.message ?: app.getString(
                                R.string.vm_failed_format,
                                "load leaderboard"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun initialFetch() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                // Quick probe first — avoids 15s timeout when the server
                // is unreachable and immediately shows the Offline state.
                if (!VersionFileParser.probeServer()) {
                    _roomsState.value = RoomsState.Success(
                        rooms = emptyList(),
                        playerCount = null,
                        serverConnectivity = ServerConnectivity.Offline
                    )
                    return@withContext
                }
                val roomsResult = VersionFileParser.fetchRooms()
                if (roomsResult.isSuccess) {
                    val rooms = roomsResult.getOrThrow()
                    _roomsState.value = RoomsState.Success(
                        rooms = rooms,
                        playerCount = rooms.sumOf { it.players.size },
                        serverConnectivity = ServerConnectivity.Online
                    )
                } else {
                    Timber.tag("Online").w(roomsResult.exceptionOrNull(), "Initial rooms fetch failed")
                    _roomsState.value = RoomsState.Success(
                        rooms = emptyList(),
                        playerCount = null,
                        serverConnectivity = ServerConnectivity.Offline
                    )
                }
            }
        }
    }

    fun navigateTo(page: OnlineMenuPage) {
        _currentPage.value = page
        when (page) {
            OnlineMenuPage.Rooms -> fetchRooms()
            OnlineMenuPage.Leaderboard -> fetchLeaderboard()
            OnlineMenuPage.Health -> fetchHealth()
            OnlineMenuPage.RaceStats -> loadRaceStats()
            OnlineMenuPage.TimeTrial -> fetchTracks()
            OnlineMenuPage.Hub -> {}
        }
    }

    fun goBack() {
        _currentPage.value = OnlineMenuPage.Hub
        refreshConnectivity()
    }

    /**
     * Lightweight server-reachability probe that runs when the user
     * returns to the hub. If the probe succeeds and the current rooms
     * state shows offline, the connectivity flag is flipped to Online
     * so hub options re-enable without a full page reload.
     */
    private fun refreshConnectivity() {
        viewModelScope.launch {
            val online = withContext(ioDispatcher) { VersionFileParser.probeServer() }
            if (online) {
                val current = _roomsState.value
                if (current is RoomsState.Success && current.serverConnectivity != ServerConnectivity.Online) {
                    _roomsState.value = current.copy(serverConnectivity = ServerConnectivity.Online)
                }
            }
        }
    }

    /**
     * Runs a fetch on [ioDispatcher], setting the loading state first
     * and routing success/failure to the caller. Failure is logged with
     * [logMessage]; the caller decides the resulting state.
     */
    private fun <T> fetchAsync(
        logMessage: String,
        setLoading: () -> Unit,
        fetch: suspend () -> Result<T>,
        onSuccess: (T) -> Unit,
        onFailure: suspend (Throwable) -> Unit,
    ) {
        viewModelScope.launch {
            setLoading()
            val result = withContext(ioDispatcher) { fetch() }
            result.onSuccess(onSuccess).onFailure { e ->
                Timber.tag(TAG).w(e, "%s", logMessage)
                onFailure(e)
            }
        }
    }

    private fun errorMessage(e: Throwable, what: String): String =
        e.message ?: app.getString(R.string.vm_failed_format, what)

    /** Fetches the current room list. Does not navigate; use [navigateTo] for that. */
    fun fetchRooms() {
        fetchAsync(
            logMessage = "Rooms fetch failed",
            setLoading = { _roomsState.value = RoomsState.Loading },
            fetch = { VersionFileParser.fetchRooms() },
            onSuccess = { rooms ->
                val old = _roomsState.value
                val connectivity =
                    if (old is RoomsState.Success) old.serverConnectivity else ServerConnectivity.Online
                _roomsState.value =
                    RoomsState.Success(rooms, rooms.sumOf { it.players.size }, connectivity)
            },
            onFailure = { e -> _roomsState.value = RoomsState.Error(errorMessage(e, "load rooms")) },
        )
    }

    /** Enqueues a leaderboard fetch (page 1) or a "load more" (next page). */
    fun fetchLeaderboard() {
        leaderboardRequests.trySend(Unit)
    }

    /** Fetches the full server health report, falling back to a liveness check. */
    fun fetchHealth() {
        fetchAsync(
            logMessage = "Detailed health fetch failed; trying live endpoint",
            setLoading = { _healthState.value = HealthState.Loading },
            fetch = { VersionFileParser.fetchHealth() },
            onSuccess = { health -> _healthState.value = HealthState.Success(health) },
            onFailure = { e ->
                val liveOk = withContext(ioDispatcher) { VersionFileParser.probeServer() }
                if (liveOk) {
                    // Live endpoint reachable but detailed health failed; synthesize a
                    // minimal "ok" health so the UI can show the server is up.
                    val liveOnlyHealth = ServerHealth(
                        status = STATUS_OK,
                        database = null,
                        postgresql = null,
                        retroWfcApi = null,
                        memory = null
                    )
                    _healthState.value = HealthState.Success(liveOnlyHealth)
                } else {
                    _healthState.value = HealthState.Error(errorMessage(e, "fetch server health"))
                }
            },
        )
    }

    /**
     * Emits cached race stats immediately (if present), then fetches
     * fresh data when the cache is older than [MAX_CACHE_AGE_MS].
     * Cache-then-network pattern.
     */
    private fun loadRaceStats() {
        viewModelScope.launch {
            // Move the SharedPreferences read + JSON parse off the
            // main thread; the cache can be a few hundred KB.
            val cached = withContext(ioDispatcher) { loadRaceStatsCache() }
            if (cached != null) {
                _raceStatsState.value = RaceStatsState.Success(cached.stats, cached.cachedAt)
                if (System.currentTimeMillis() - cached.cachedAt < MAX_CACHE_AGE_MS) return@launch
            }
            fetchRaceStats()
        }
    }

    /** Fetches global race stats and caches the raw JSON. Falls back to cache on failure. */
    fun fetchRaceStats() {
        fetchAsync(
            logMessage = "Race stats fetch failed",
            setLoading = { _raceStatsState.value = RaceStatsState.Loading },
            fetch = { VersionFileParser.fetchGlobalRaceStatsRaw() },
            onSuccess = { (stats, rawJson) ->
                val now = System.currentTimeMillis()
                saveRaceStatsCache(rawJson, now)
                _raceStatsState.value = RaceStatsState.Success(stats, now)
            },
            onFailure = { e ->
                val fallback = withContext(ioDispatcher) { loadRaceStatsCache() }
                if (fallback != null) {
                    _raceStatsState.value =
                        RaceStatsState.Success(fallback.stats, fallback.cachedAt)
                } else {
                    _raceStatsState.value = RaceStatsState.Error(errorMessage(e, "fetch race stats"))
                }
            },
        )
    }

    private fun saveRaceStatsCache(rawJson: String, timestamp: Long) {
        val wrapper = JSONObject().apply {
            put(CACHE_KEY_JSON, rawJson)
            put(CACHE_KEY_CACHED_AT, timestamp)
        }
        prefs.edit().putString(PrefsKeys.RACE_STATS_KEY, wrapper.toString()).apply()
    }

    private fun loadRaceStatsCache(): RaceStatsCache? {
        val raw = prefs.getString(PrefsKeys.RACE_STATS_KEY, null) ?: return null
        return try {
            val wrapper = JSONObject(raw)
            val json = wrapper.getString(CACHE_KEY_JSON)
            val stats = parseRaceStats(json)
            val timestamp = wrapper.optLong(CACHE_KEY_CACHED_AT, 0L)
            RaceStatsCache(stats, timestamp)
        } catch (e: Exception) {
            Timber.tag("Online").w(e, "Failed to parse race stats cache")
            null
        }
    }

    /** Fetches the time-trial track list and resets selection. */
    fun fetchTracks() {
        fetchAsync(
            logMessage = "Time trial tracks fetch failed",
            setLoading = {
                _ttState.value = TimeTrialState.Loading
                _selectedTrackId.value = null
                _trackLeaderboardState.value = TrackLeaderboardState.Idle
            },
            fetch = { VersionFileParser.fetchTracks() },
            onSuccess = { tracks ->
                val visible = tracks.filter { !it.isHidden }
                _ttState.value = TimeTrialState.Success(visible)
            },
            onFailure = { e ->
                _ttState.value = TimeTrialState.Error(errorMessage(e, "load time trial tracks"))
            },
        )
    }

    /** Selects a track and fetches its leaderboard (page 1). */
    fun selectTrack(trackId: Int) {
        _selectedTrackId.value = trackId
        fetchTrackLeaderboard()
    }

    /** Fetches page 1 of the leaderboard for the currently selected track + filters. */
    fun fetchTrackLeaderboard() {
        val trackId = _selectedTrackId.value ?: return
        fetchAsync(
            logMessage = "Track leaderboard fetch failed",
            setLoading = { _trackLeaderboardState.value = TrackLeaderboardState.Loading },
            fetch = {
                VersionFileParser.fetchTrackLeaderboard(
                    trackId = trackId,
                    cc = _cc.value,
                    glitchAllowed = _glitchAllowed.value,
                    page = 1,
                    pageSize = 50,
                )
            },
            onSuccess = { response ->
                _trackLeaderboardState.value = TrackLeaderboardState.Success(
                    submissions = response.submissions,
                    currentPage = response.currentPage,
                    totalPages = response.totalPages,
                    fastestLapMs = response.fastestLapMs,
                    fastestLapDisplay = response.fastestLapDisplay,
                )
            },
            onFailure = { e ->
                _trackLeaderboardState.value =
                    TrackLeaderboardState.Error(errorMessage(e, "load track leaderboard"))
            },
        )
    }

    /** Fetches the next page of submissions for the currently selected track. */
    fun fetchMoreSubmissions() {
        val trackId = _selectedTrackId.value ?: return
        val current = _trackLeaderboardState.value
        if (current !is TrackLeaderboardState.Success) return
        if (current.currentPage >= current.totalPages) return
        val nextPage = current.currentPage + 1
        fetchAsync(
            logMessage = "More submissions fetch failed",
            setLoading = {},
            fetch = {
                VersionFileParser.fetchTrackLeaderboard(
                    trackId = trackId,
                    cc = _cc.value,
                    glitchAllowed = _glitchAllowed.value,
                    page = nextPage,
                    pageSize = 50,
                )
            },
            onSuccess = { response ->
                _trackLeaderboardState.value = TrackLeaderboardState.Success(
                    submissions = current.submissions + response.submissions,
                    currentPage = response.currentPage,
                    totalPages = response.totalPages,
                    fastestLapMs = response.fastestLapMs,
                    fastestLapDisplay = response.fastestLapDisplay,
                )
            },
            onFailure = {},
        )
    }

    /** Changes the engine class and re-fetches the leaderboard if a track is selected. */
    fun setCc(newCc: Int) {
        if (_cc.value == newCc) return
        _cc.value = newCc
        if (_selectedTrackId.value != null) fetchTrackLeaderboard()
    }

    /** Toggles glitch filter and re-fetches the leaderboard if a track is selected. */
    fun setGlitchAllowed(allowed: Boolean) {
        if (_glitchAllowed.value == allowed) return
        _glitchAllowed.value = allowed
        if (_selectedTrackId.value != null) fetchTrackLeaderboard()
    }

    companion object {
        private const val TAG = "Online"
        private const val STATUS_OK = "ok"
        private const val CACHE_KEY_JSON = "raceStats"
        private const val CACHE_KEY_CACHED_AT = "cachedAt"
        private val MAX_CACHE_AGE_MS = TimeUnit.DAYS.toMillis(1)
    }
}
