package com.skiletro.wheelwitch.domain

import com.skiletro.wheelwitch.data.PlayerLeaderboardCache
import com.skiletro.wheelwitch.data.SaveManager.Region
import com.skiletro.wheelwitch.model.LicenseInfo
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import com.skiletro.wheelwitch.model.SaveFileInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Merges per-slot leaderboard VR and Mii data into a region's parsed
 * save, preferring the latest data pulled from the API and falling
 * back to the last-known-good [cache], then the local save data.
 * Empty slots (no RKPD magic, or no friend code) are left as-is
 * without a network call.
 */
class LeaderboardMerger(
  private val fetchLeaderboard: suspend (String) -> Result<PlayerLeaderboardData>,
  private val cache: PlayerLeaderboardCache,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  private fun baseLicenses(info: SaveFileInfo?): List<LicenseInfo> =
    info?.licenses ?: List(LICENSE_SLOTS) { i -> LicenseInfo(slotIndex = i, exists = false) }

  /**
   * Enriches [info]'s four slots with leaderboard VR and Mii data in
   * parallel. Each successful fetch updates [cache] with the
   * last-known-good data (conserving previously cached name/Mii when
   * the API omits them); a failed fetch falls back to the cached
   * values, and only to local save data when the cache has no entry
   * yet. When [info] is null (no save file for [region] yet, e.g.
   * after a delete or a switch to an unplayed region), returns
   * [LICENSE_SLOTS] empty slots so the UI renders an empty grid
   * instead of stale data from a previous session.
   */
  suspend fun merge(region: Region, info: SaveFileInfo?): List<LicenseInfo> =
    withContext(ioDispatcher) {
      coroutineScope {
        baseLicenses(info)
          .map { license ->
            async {
              val friendCode = license.friendCode
              if (!license.exists || friendCode == null) {
                license
              } else {
                val cached = cache.load(friendCode)
                val result = fetchLeaderboard(friendCode)
                if (result.isSuccess) {
                  val data = result.getOrThrow()
                  val enriched =
                    data.copy(
                      name = data.name ?: cached?.name,
                      miiData = data.miiData ?: cached?.miiData,
                    )
                  cache.save(friendCode, enriched)
                  license.copy(
                    leaderboardVr = data.vr,
                    miiName = enriched.name ?: license.miiName,
                    miiDataBase64 = enriched.miiData ?: license.miiDataBase64,
                  )
                } else {
                  license.copy(
                    leaderboardVr = cached?.vr ?: license.leaderboardVr,
                    miiName = cached?.name ?: license.miiName,
                    miiDataBase64 = cached?.miiData ?: license.miiDataBase64,
                  )
                }
              }
            }
          }
          .awaitAll()
      }
    }

  /**
   * Enriches [info]'s slots from [cache] only — no network — so a
   * cold start can render the last-known-good API data immediately
   * instead of stale local save data. Slots without a cache entry
   * keep their local data.
   */
  suspend fun enrichOffline(region: Region, info: SaveFileInfo?): List<LicenseInfo> =
    withContext(ioDispatcher) {
      coroutineScope {
        baseLicenses(info)
          .map { license ->
            async {
              val friendCode = license.friendCode
              if (!license.exists || friendCode == null) {
                license
              } else {
                val cached = cache.load(friendCode)
                if (cached == null) {
                  license
                } else {
                  license.copy(
                    leaderboardVr = cached.vr,
                    miiName = cached.name ?: license.miiName,
                    miiDataBase64 = cached.miiData ?: license.miiDataBase64,
                  )
                }
              }
            }
          }
          .awaitAll()
      }
    }

  companion object {
    /** Number of license slots in an `rksys.dat` save file. */
    const val LICENSE_SLOTS = 4
  }
}