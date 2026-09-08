package com.skiletro.wheelwitch.domain

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
 * save. Empty slots (no RKPD magic, or no friend code) are left as-is
 * without a network call; failed fetches keep the local data so the
 * Licenses grid always renders something.
 */
class LeaderboardMerger(
  private val fetchLeaderboard: suspend (String) -> Result<PlayerLeaderboardData>,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * Enriches [info]'s four slots with leaderboard VR and Mii data in
   * parallel. When [info] is null (no save file for [region] yet,
   * e.g. after a delete or a switch to an unplayed region), returns
   * [LICENSE_SLOTS] empty slots so the UI renders an empty grid
   * instead of stale data from a previous session.
   */
  suspend fun merge(region: Region, info: SaveFileInfo?): List<LicenseInfo> {
    val base =
      info?.licenses
        ?: List(LICENSE_SLOTS) { i -> LicenseInfo(slotIndex = i, exists = false) }
    return withContext(ioDispatcher) {
      coroutineScope {
        base
          .map { license ->
            async {
              if (!license.exists || license.friendCode == null) {
                license
              } else {
                val result = fetchLeaderboard(license.friendCode)
                if (result.isSuccess) {
                  val data = result.getOrThrow()
                  license.copy(
                    leaderboardVr = data.vr,
                    miiName = data.name ?: license.miiName,
                    miiDataBase64 = data.miiData ?: license.miiDataBase64,
                  )
                } else {
                  license
                }
              }
            }
          }
          .awaitAll()
      }
    }
  }

  companion object {
    /** Number of license slots in an `rksys.dat` save file. */
    const val LICENSE_SLOTS = 4
  }
}