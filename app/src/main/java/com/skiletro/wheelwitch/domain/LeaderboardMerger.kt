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
 * Merges per-slot leaderboard VR and Mii data into a region's parsed save, preferring the latest
 * data pulled from the API and falling back to the last-known-good [cache], then the local save
 * data. Empty slots (no RKPD magic, or no friend code) are left as-is without a network call.
 */
class LeaderboardMerger(
    private val fetchLeaderboard: suspend (String) -> Result<PlayerLeaderboardData>,
    private val cache: PlayerLeaderboardCache,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  private data class CacheWrite(val friendCode: String, val data: PlayerLeaderboardData)

  private data class MergeResult(val license: LicenseInfo, val cacheWrite: CacheWrite?)

  private fun baseLicenses(info: SaveFileInfo?): List<LicenseInfo> =
      info?.licenses ?: List(LICENSE_SLOTS) { i -> LicenseInfo(slotIndex = i, exists = false) }

  /**
   * Fetches eligible slots in parallel, then applies successful cache writes in slot order.
   * Missing API name/Mii fields fall back to cache, then local data; failed fetches also use cached
   * VR when available. Null [info] produces [LICENSE_SLOTS] empty slots.
   */
  suspend fun merge(region: Region, info: SaveFileInfo?): List<LicenseInfo> =
      withContext(ioDispatcher) {
        val results = coroutineScope {
          baseLicenses(info)
              .map { license ->
                async {
                  val friendCode = license.friendCode
                  if (!license.exists || friendCode == null) {
                    MergeResult(license, cacheWrite = null)
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
                      MergeResult(
                          license =
                              license.copy(
                                  leaderboardVr = data.vr,
                                  miiName = enriched.name ?: license.miiName,
                                  miiDataBase64 = enriched.miiData ?: license.miiDataBase64,
                              ),
                          cacheWrite = CacheWrite(friendCode, enriched),
                      )
                    } else {
                      MergeResult(
                          license =
                              license.copy(
                                  leaderboardVr = cached?.vr ?: license.leaderboardVr,
                                  miiName = cached?.name ?: license.miiName,
                                  miiDataBase64 = cached?.miiData ?: license.miiDataBase64,
                              ),
                          cacheWrite = null,
                      )
                    }
                  }
                }
              }
              .awaitAll()
        }
        results.forEach { result ->
          result.cacheWrite?.let { write -> cache.save(write.friendCode, write.data) }
        }
        results.map { it.license }
      }

  /**
   * Enriches eligible slots from [cache] without network calls. Missing cached name/Mii fields
   * retain local values; null [info] produces empty slots.
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
