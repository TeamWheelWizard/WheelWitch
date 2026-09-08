package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.RRRatingParser
import com.skiletro.wheelwitch.data.RksysParser
import com.skiletro.wheelwitch.data.SaveManager
import com.skiletro.wheelwitch.data.SaveManager.Region
import com.skiletro.wheelwitch.data.readDolphinBytes
import com.skiletro.wheelwitch.domain.LeaderboardMerger
import com.skiletro.wheelwitch.domain.SaveBackupCoordinator
import com.skiletro.wheelwitch.domain.SaveOpOutcome
import com.skiletro.wheelwitch.model.LicenseInfo
import com.skiletro.wheelwitch.domain.computeScore
import com.skiletro.wheelwitch.model.LicenseStats
import com.skiletro.wheelwitch.model.SaveFileInfo
import com.skiletro.wheelwitch.model.ScoreResult
import com.skiletro.wheelwitch.model.BadgeType
import com.skiletro.wheelwitch.network.VersionFileParser
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the save file state: per-region parse, leaderboard merge,
 * unified backup/restore/delete, and slot selection.
 *
 * The pack install flow lives in [PackUpdateViewModel]. This VM
 * listens to its [UiState] via [packStatusFlow] and re-parses the
 * save whenever the pack state transitions to [UiState.Ready]. This
 * keeps the two VMs decoupled (no static companion pointers like
 * the deleted `SaveDataDelegate`) and survives process death
 * naturally; both VMs are reconstructed on the next composition
 * and re-collect the [packStatusFlow] from scratch.
 *
 * Multi-region: a user with multiple ROMs (one per region) has one
 * save file per region. [SaveManager.listRegions] walks the user's
 * [DolphinTree.romDir] and [refresh] reads + parses a save for each
 * present region in parallel. [selectedRegion] defaults to the first
 * region with a ROM, and is persisted across launches. The Licenses
 * screen is a pure viewer of the selected region and never picks one.
 *
 * Leaderboard merge: the home screen renders all 4 slots of the
 * selected region, so [mergedLicenses] holds a 4-entry list per
 * region with leaderboard VR and Mii name merged in. The VR fetch
 * is fanned out in parallel for all 4 slots of the selected region
 * (4 in-flight requests max) via [LeaderboardMerger].
 *
 * Unified save data: [hasAnySave] is the single source of truth for
 * whether the user has anything worth backing up (any region's
 * `rksys.dat`, the Mii DB, any Pulsar pul file, or any ghost). The
 * Save Data section in Settings uses it to drive the enabled/disabled
 * state of the three buttons, and to switch between the
 * "no save data" status line and the "Last backed up" line.
 * [lastBackupTimestamp] is read from
 * [PrefsKeys.LAST_BACKUP_TIMESTAMP_KEY] on construction and updated
 * after every successful [backupAll] call.
 *
 * Tests can swap the [treeFactory], the [parser], the [LeaderboardMerger],
 * the [SaveManager], the [now] lambda (for timestamps), and the
 * [ioDispatcher] to inject mocks without going through SAF, the network,
 * or real time.
 */
class SaveDataViewModel(
  application: Application,
  private val packStatusFlow: StateFlow<UiState>,
  private val treeFactory: (Context) -> DolphinTree? = ::defaultTreeFactory,
  private val parser: (ByteArray) -> SaveFileInfo = RksysParser::parse,
  private val leaderboardMerger: LeaderboardMerger =
    LeaderboardMerger(VersionFileParser::fetchPlayerLeaderboard),
  private val saveManager: SaveManager = SaveManager,
  private val now: () -> Long = System::currentTimeMillis,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
  private val app = application
  private val prefs = Prefs.main(application)
  private val saveOps = SaveBackupCoordinator(treeFactory)

  private val _saveInfos = MutableStateFlow<Map<Region, SaveFileInfo>>(emptyMap())
  internal val saveInfos: StateFlow<Map<Region, SaveFileInfo>> = _saveInfos.asStateFlow()

  private val _hasSave = MutableStateFlow<Map<Region, Boolean>>(emptyMap())
  internal val hasSave: StateFlow<Map<Region, Boolean>> = _hasSave.asStateFlow()

  private val _hasAnySave = MutableStateFlow(false)
  val hasAnySave: StateFlow<Boolean> = _hasAnySave.asStateFlow()

  private val _hasRRSave = MutableStateFlow(false)
  val hasRRSave: StateFlow<Boolean> = _hasRRSave.asStateFlow()

  private val _lastBackupTimestamp = MutableStateFlow(0L)
  val lastBackupTimestamp: StateFlow<Long> = _lastBackupTimestamp.asStateFlow()

  private val _lastBackupRRTimestamp = MutableStateFlow(0L)
  val lastBackupRRTimestamp: StateFlow<Long> = _lastBackupRRTimestamp.asStateFlow()

  private val _selectedRegion = MutableStateFlow<Region?>(null)
  val selectedRegion: StateFlow<Region?> = _selectedRegion.asStateFlow()

  private val _mergedLicenses = MutableStateFlow<Map<Region, List<LicenseInfo>>>(emptyMap())
  val mergedLicenses: StateFlow<Map<Region, List<LicenseInfo>>> = _mergedLicenses.asStateFlow()

  val scoreResults: StateFlow<Map<Int, ScoreResult?>> =
    combine(_mergedLicenses, _selectedRegion) { merged, region ->
      val licenses = region?.let { merged[it] } ?: return@combine emptyMap()
      licenses.associate { license ->
        license.slotIndex to buildScoreResult(license)
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  private fun buildScoreResult(license: LicenseInfo): ScoreResult? {
    if (!license.exists) return null
    val vrPoints = license.profileId?.let { pid ->
      ratingVrMap[pid]?.let { java.lang.Math.round(it * 100.0).toDouble() }
    } ?: (license.vr ?: 0).toDouble()
    return computeScore(
      LicenseStats(
        vrPoints = vrPoints,
        vsWins = license.raceWins ?: 0,
        vsLosses = license.raceLosses ?: 0,
        firsts = license.firsts ?: 0,
        dist = license.totalDist ?: 0.0,
        dist1st = license.dist1st ?: 0.0,
      )
    )
  }

  private val _badges = MutableStateFlow<Map<Long, List<BadgeType>>>(emptyMap())
  val badges: StateFlow<Map<Long, List<BadgeType>>> = _badges.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  /** Maps profileId → internal VR (0–10000) from RRating.pul, or empty if unavailable. */
  private var ratingVrMap: Map<Long, Float> = emptyMap()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /**
   * Wall-clock time of the most recent [refresh] call, used by
   * [refreshIfStale] to dedup the `init` collect and the
   * `SaveInfoScreen` lifecycle ON_RESUME observer. The latter
   * previously triggered a second full save re-parse + 4 leaderboard
   * round trips every time the user opened the screen. Updated with
   * an atomic CAS so two concurrent stale-checks cannot both start a
   * refresh.
   */
  private val lastRefreshAt = AtomicLong(0L)

  /**
   * Handle to the in-flight [refresh] job. A re-entrant [refresh]
   * cancels the previous run so a stale badge fetch or leaderboard
   * merge from the old run cannot overwrite fresh state.
   */
  private var refreshJob: Job? = null

  init {
    // Read persisted state synchronously — SharedPreferences get*()
    // calls are fast and running them inline guarantees the persisted
    // region/slot are set before the packStatusFlow collect (which
    // triggers refresh()) observes them. The StateFlows start with
    // their default values and immediately receive the real values
    // without flicker.
    loadPersistedState()
    viewModelScope.launch {
      packStatusFlow
        .map { (it as? UiState.Ready)?.status }
        .distinctUntilChanged()
        .collect { status ->
          if (status != null) refresh()
        }
    }
  }

  /**
   * Reads every persisted state (last-backup timestamps, selected
   * region) from SharedPreferences. Idempotent.
   */
  private fun loadPersistedState() {
    _lastBackupTimestamp.value = prefs.getLong(PrefsKeys.LAST_BACKUP_TIMESTAMP_KEY, 0L)
    _lastBackupRRTimestamp.value = prefs.getLong(PrefsKeys.LAST_BACKUP_RR_TIMESTAMP_KEY, 0L)
    _selectedRegion.value =
      loadPersistedRegion(prefs.getString(PrefsKeys.SELECTED_REGION_KEY, null))
  }

  /**
   * Re-reads every region's save from the SAF tree, parses them in
   * parallel, and refreshes the leaderboard for the selected
   * region's 4 slots. Also recomputes [hasAnySave] for the unified
   * backup UI. No-op if [treeFactory] returns null (no persisted
   * SAF grant).
   *
   * [hasSave] is derived from the [SaveManager.readSave] result
   * (a `null` payload means the region has no save) rather than
   * calling [SaveManager.hasSave] per region, which would pay a
   * second SAF `findFile` round trip for every region.
   */
  fun refresh() {
    refreshJob?.cancel()
    refreshJob =
      viewModelScope.launch {
      _isLoading.value = true
      lastRefreshAt.set(now())
      try {
        val tree =
          treeFactory(app)
            ?: run {
              _saveInfos.value = emptyMap()
              _hasSave.value = emptyMap()
              _hasAnySave.value = false
              _hasRRSave.value = false
              _mergedLicenses.value = emptyMap()
              return@launch
            }
        val regions = saveManager.listRegions(tree)
        if (regions.isEmpty()) {
          _saveInfos.value = emptyMap()
          _hasSave.value = emptyMap()
          _selectedRegion.value = null
          _mergedLicenses.value = emptyMap()
          _hasAnySave.value = computeHasAnySave(tree)
          _hasRRSave.value = computeHasRRSave(tree)
          _isLoading.value = false
          return@launch
        }
        val parsed =
          coroutineScope {
            regions
              .map { region ->
                async(ioDispatcher) {
                  val bytes = saveManager.readSave(tree, region)
                  if (bytes != null) {
                    val info = runCatching { parser(bytes) }.getOrNull()
                    if (info != null) {
                      RegionRead(region, info, hasSave = true)
                    } else null
                  } else {
                    RegionRead(region, info = null, hasSave = false)
                  }
                }
              }
              .awaitAll()
          }
        val validReads = parsed.filterNotNull()
        val rawInfos = validReads.mapNotNull { it.info?.let { info -> it.region to info } }.toMap()
        val hasSaves = validReads.associate { it.region to it.hasSave }
        ratingVrMap = loadRatingVrMap(tree)
        val infos = populateRatingVr(ratingVrMap, rawInfos)
        _saveInfos.value = infos
        _hasSave.value = hasSaves
        _hasAnySave.value = computeHasAnySave(tree)
        _hasRRSave.value = computeHasRRSave(tree)
        val target = pickSelectedRegion(regions)
        if (target != _selectedRegion.value) {
          _selectedRegion.value = target
        }
        // Publish local un-merged licenses immediately so the UI shows
        // local data (Mii name, local VR) without waiting for network.
        if (target != null && infos[target] != null && mergedLicenses.value[target] == null) {
          _mergedLicenses.value = mergedLicenses.value + (target to infos[target]!!.licenses)
        }
        _isLoading.value = false

        // Network enhancements run in background — they never block
        // the license grid from rendering local save data.
        coroutineScope {
          launch {
            val profileIds = infos.values.flatMap { it.licenses }.mapNotNull { it.profileId }
            val badges = withContext(ioDispatcher) { VersionFileParser.fetchBadges(profileIds) }
            Timber.tag(TAG).d("Fetched %d badge entries", badges.size)
            _badges.value = badges
          }
          if (target != null) {
            launch { publishMerged(target, infos[target]) }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "refresh failed")
        _error.value =
          e.message ?: app.getString(R.string.vm_failed_format, "read save data")
        _isLoading.value = false
      }
    }
  }

  /**
   * [refresh] wrapper used by the `SaveInfoScreen` lifecycle
   * ON_RESUME observer. Skips the work if a refresh already ran
   * within [maxAgeMs], so navigating into and out of the screen in
   * quick succession (or re-entering right after the
   * `packStatusFlow` collect already triggered a refresh) does
   * not pay a second full save re-parse + 4 leaderboard round
   * trips. Forced refreshes (the manual pull-to-refresh button)
   * should call [refresh] directly.
   */
  fun refreshIfStale(maxAgeMs: Long = DEFAULT_REFRESH_STALE_MS) {
    val nowValue = now()
    val last = lastRefreshAt.get()
    if (nowValue - last < maxAgeMs) return
    if (lastRefreshAt.compareAndSet(last, nowValue)) refresh()
  }

  /**
   * Persists the selected region and fetches the new region's 4
   * leaderboards in parallel. The Licenses screen never picks a
   * region; this is invoked from the Settings Save Data section.
   */
  fun selectRegion(region: Region) {
    if (region == _selectedRegion.value) return
    prefs.edit().putString(PrefsKeys.SELECTED_REGION_KEY, region.code).apply()
    _selectedRegion.value = region
    viewModelScope.launch { publishMerged(region, _saveInfos.value[region]) }
  }

  /**
   * Bundles every save file the user owns (all regions' `rksys.dat`,
   * the Mii DB, all Pulsar pul files, and the Ghosts directory) into
   * a single zip at [dest] (typically from `ACTION_CREATE_DOCUMENT`).
   * On success, the current wall-clock time is written to
   * [PrefsKeys.LAST_BACKUP_TIMESTAMP_KEY] so the Settings screen can
   * show "Last backed up: …" on next launch.
   */
  fun backupAll(dest: Uri) {
    runSaveCallback(
      logTag = "backup",
      fallback = { app.getString(R.string.vm_save_write_failed) },
      onSuccess = {
        val timestamp = now()
        prefs.edit().putLong(PrefsKeys.LAST_BACKUP_TIMESTAMP_KEY, timestamp).apply()
        _lastBackupTimestamp.value = timestamp
        refresh()
      },
      op = { tree -> saveManager.backupAll(tree, dest) },
    )
  }

  /**
   * Restores the user's save data from the zip at [source]
   * (typically from `ACTION_OPEN_DOCUMENT`). Refreshes the parsed
   * state on success.
   */
  fun restoreAll(source: Uri) {
    runSaveCallback(
      logTag = "restore",
      fallback = { app.getString(R.string.vm_save_read_failed) },
      onSuccess = { refresh() },
      op = { tree -> saveManager.restoreAll(tree, source) },
    )
  }

  /**
   * Wipes every save file the user owns (all regions' `rksys.dat`,
   * the Mii DB, all Pulsar pul files, and the contents of Ghosts/).
   * Refreshes the parsed state and [hasAnySave] on success.
   */
  fun deleteAll() {
    runSaveCallback(
      logTag = "delete",
      fallback = { app.getString(R.string.vm_failed_format, "delete save") },
      onSuccess = { refresh() },
      op = { tree -> saveManager.deleteAll(tree) },
    )
  }

  /**
   * Bundles only the RR per-region `rksys.dat` files, rating data, and
   * ghosts into a zip at [dest]. On success, writes the timestamp to
   * [PrefsKeys.LAST_BACKUP_RR_TIMESTAMP_KEY].
   */
  fun backupRR(dest: Uri) {
    runSaveCallback(
      logTag = "backupRR",
      fallback = { app.getString(R.string.vm_save_write_failed) },
      onSuccess = {
        val timestamp = now()
        prefs.edit().putLong(PrefsKeys.LAST_BACKUP_RR_TIMESTAMP_KEY, timestamp).apply()
        _lastBackupRRTimestamp.value = timestamp
        refresh()
      },
      op = { tree -> saveManager.backupRR(tree, dest) },
    )
  }

  /**
   * Restores only the `RetroWFC/` entries from the zip at [source].
   * Refreshes the parsed state on success.
   */
  fun restoreRR(source: Uri) {
    runSaveCallback(
      logTag = "restoreRR",
      fallback = { app.getString(R.string.vm_save_read_failed) },
      onSuccess = { refresh() },
      op = { tree -> saveManager.restoreRR(tree, source) },
    )
  }

  /**
   * Wipes only the RR per-region `rksys.dat` files. Refreshes the
   * parsed state on success.
   */
  fun deleteRR() {
    runSaveCallback(
      logTag = "deleteRR",
      fallback = { app.getString(R.string.vm_failed_format, "delete RR save") },
      onSuccess = { refresh() },
      op = { tree -> saveManager.deleteRR(tree) },
    )
  }

  /**
   * Formats [lastBackupTimestamp] as a localized date + time for the
   * Save Data section. Returns null when the user has never backed
   * up so the UI can show the "no save data" / "never backed up"
   * status line instead.
   */
  fun formatLastBackup(): String? = formatTimestamp(_lastBackupTimestamp.value)

  /**
   * Formats [lastBackupRRTimestamp] as a localized date + time.
   * Returns null when the user has never performed an RR-only backup.
   */
  fun formatLastBackupRR(): String? = formatTimestamp(_lastBackupRRTimestamp.value)

  private fun formatTimestamp(ts: Long): String? {
    if (ts <= 0L) return null
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))
  }

  /** Clears [error]. */
  fun clearError() {
    _error.value = null
  }

  // --- internals --------------------------------------------------------

  /**
   * Resolves the persisted tree via [SaveBackupCoordinator] and runs
   * one unified save operation, publishing the resolved error message
   * (storage-not-configured, the exception message, or [fallback]) and
   * calling [onSuccess] on success.
   */
  private fun runSaveCallback(
    logTag: String,
    fallback: () -> String,
    onSuccess: () -> Unit = {},
    op: suspend (DolphinTree) -> Result<*>,
  ) {
    viewModelScope.launch {
      when (
        val outcome =
          saveOps.run(
            app,
            logTag,
            notConfiguredError = { app.getString(R.string.vm_save_not_configured) },
            fallbackError = fallback,
            onSuccess = onSuccess,
            op = op,
          )
      ) {
        is SaveOpOutcome.Failure -> _error.value = outcome.message
        SaveOpOutcome.Success -> Unit
      }
    }
  }

  /**
   * Merges leaderboard data for [region]'s 4 slots and publishes the
   * result into [mergedLicenses]. The initial (un-merged) local list
   * is published by [refresh] before this runs, so the UI shows the
   * local VR while the network round trips are in flight.
   */
  private suspend fun publishMerged(region: Region, info: SaveFileInfo?) {
    val enriched = leaderboardMerger.merge(region, info)
    _mergedLicenses.update { current -> current + (region to enriched) }
  }

  private fun computeHasAnySave(tree: DolphinTree): Boolean = saveManager.hasAnySave(tree)

  private fun computeHasRRSave(tree: DolphinTree): Boolean = saveManager.hasRRSave(tree)

  /** Per-region read result so [refresh] can reuse the `readSave` output. */
  private data class RegionRead(
    val region: Region,
    val info: SaveFileInfo?,
    val hasSave: Boolean,
  )

  private fun pickSelectedRegion(regions: List<Region>): Region? {
    if (regions.isEmpty()) return null
    val current = _selectedRegion.value
    return if (current != null && current in regions) current else regions.first()
  }

  /** Maps a persisted region code (e.g. `RMCP`) back to its [Region] enum, or null. */
  private fun loadPersistedRegion(code: String?): Region? =
    code?.let { c -> Region.entries.firstOrNull { it.code == c } }

  private fun loadRatingVrMap(tree: DolphinTree): Map<Long, Float> {
    val file = tree.pulsarRrDir?.findFile("RRRating.pul") ?: return emptyMap()
    val bytes = readDolphinBytes(tree.resolver, file) ?: return emptyMap()
    return RRRatingParser.parse(bytes).associate { it.profileId.toLong() to it.vr }
  }

  private fun populateRatingVr(
    ratingVrMap: Map<Long, Float>,
    infos: Map<Region, SaveFileInfo>
  ): Map<Region, SaveFileInfo> {
    return infos.mapValues { (_, saveFile) ->
      SaveFileInfo(saveFile.licenses.map { license ->
        license.copy(
          ratingVr = ratingVrMap[license.profileId]?.let { (it * 100).toInt() }
        )
      })
    }
  }

  /**
   * Internal tag + default [DolphinTree] factory plus the public
   * [factory] used by [androidx.lifecycle.viewmodel.compose.viewModel]
   * in the composition root. The default [ViewModelProvider] for
   * [AndroidViewModel] looks up a single-arg `(Application)`
   * constructor, which doesn't exist anymore. The second
   * `packStatusFlow` parameter requires a custom factory.
   *
   * The other constructor parameters (`treeFactory`, `parser`,
   * `leaderboardMerger`, `saveManager`, `now`, `ioDispatcher`) use
   * their production defaults; tests that need to swap them continue
   * to construct the VM directly with explicit arguments.
   */
  companion object {
    const val TAG = "SaveData"

    /**
     * Default staleness window for [refreshIfStale]. The
     * `SaveInfoScreen` lifecycle observer uses this to dedup the
     * `init`-triggered `packStatusFlow` collect from the
     * `ON_RESUME` re-entry.
     */
    private const val DEFAULT_REFRESH_STALE_MS: Long = 5_000L

    fun defaultTreeFactory(context: Context): DolphinTree? = DolphinTree.fromPersisted(context)

    /**
     * [ViewModelProvider.Factory] that wires the production
     * dependencies; the [Application] from [ViewModelProvider]'s
     * CreationExtras, and the [packStatusFlow] from the
     * already-constructed [PackUpdateViewModel]. The pack VM lives
     * in the parent scope (`MainScreen`) so it can be passed in
     * here without a circular construction.
     */
    fun factory(packUpdate: PackUpdateViewModel): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          val app =
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
          SaveDataViewModel(application = app, packStatusFlow = packUpdate.state)
        }
      }
  }
}
