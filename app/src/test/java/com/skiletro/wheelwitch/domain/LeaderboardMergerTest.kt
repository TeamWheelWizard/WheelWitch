package com.skiletro.wheelwitch.domain

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.SaveManager.Region
import com.skiletro.wheelwitch.model.LicenseInfo
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import com.skiletro.wheelwitch.model.SaveFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LeaderboardMergerTest {

  private fun build(
    fetch: suspend (String) -> Result<PlayerLeaderboardData>,
  ): LeaderboardMerger = LeaderboardMerger(fetch, Dispatchers.Unconfined)

  private fun license(slot: Int, friendCode: String? = null, name: String? = "Local"): LicenseInfo =
    LicenseInfo(
      slotIndex = slot,
      exists = friendCode != null,
      miiName = name,
      friendCode = friendCode,
    )

  private fun info(vararg licenses: LicenseInfo): SaveFileInfo {
    val all = licenses.toList() + (licenses.size until LeaderboardMerger.LICENSE_SLOTS).map { i ->
      LicenseInfo(slotIndex = i, exists = false)
    }
    return SaveFileInfo(all)
  }

  @Test
  fun `merge with no info returns four empty slots`() = runTest {
    val merger = build { error("fetch must not be called") }

    val merged = merger.merge(Region.PAL, null)

    assertThat(merged).hasSize(LeaderboardMerger.LICENSE_SLOTS)
    merged.forEachIndexed { i, license ->
      assertThat(license.slotIndex).isEqualTo(i)
      assertThat(license.exists).isFalse()
    }
  }

  @Test
  fun `merge skips slots without a friend code`() = runTest {
    val local = license(slot = 0, name = "Local")
    val info = info(local)
    val merger = build { error("fetch must not be called") }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0]).isEqualTo(local)
    assertThat(merged[0].miiName).isEqualTo("Local")
  }

  @Test
  fun `merge merges leaderboard data into populated slots`() = runTest {
    val code = "1234-5678-9012"
    val info = info(license(slot = 0, friendCode = code, name = "Local"))
    val merger = build {
      assertThat(it).isEqualTo(code)
      Result.success(PlayerLeaderboardData(vr = 4321, name = "Net", miiData = "QUJD"))
    }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0].leaderboardVr).isEqualTo(4321)
    assertThat(merged[0].miiName).isEqualTo("Net")
    assertThat(merged[0].miiDataBase64).isEqualTo("QUJD")
  }

  @Test
  fun `merge keeps the local mii name when the leaderboard has none`() = runTest {
    val info = info(license(slot = 0, friendCode = "1234-5678-9012", name = "Local"))
    val merger = build { Result.success(PlayerLeaderboardData(vr = 999, name = null, miiData = null)) }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0].leaderboardVr).isEqualTo(999)
    assertThat(merged[0].miiName).isEqualTo("Local")
  }

  @Test
  fun `merge keeps local data when the fetch fails`() = runTest {
    val local = license(slot = 0, friendCode = "1234-5678-9012", name = "Local")
    val info = info(local)
    val merger = build { Result.failure(RuntimeException("no net")) }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0]).isEqualTo(local)
  }

  @Test
  fun `merge fetches every populated slot`() = runTest {
    val fetched = mutableListOf<String>()
    val info =
      info(
        license(slot = 0, friendCode = "AAAA-AAAA-AAAA"),
        license(slot = 1, friendCode = "BBBB-BBBB-BBBB"),
        license(slot = 2, friendCode = "CCCC-CCCC-CCCC"),
      )
    val merger = build {
      fetched += it
      Result.success(PlayerLeaderboardData(vr = 1, name = null, miiData = null))
    }

    merger.merge(Region.PAL, info)

    assertThat(fetched).containsExactly("AAAA-AAAA-AAAA", "BBBB-BBBB-BBBB", "CCCC-CCCC-CCCC")
    assertThat(fetched).hasSize(3)
  }
}