package com.skiletro.wheelwitch.domain

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.PlayerLeaderboardCache
import com.skiletro.wheelwitch.data.SaveManager.Region
import com.skiletro.wheelwitch.model.LicenseInfo
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import com.skiletro.wheelwitch.model.SaveFileInfo
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LeaderboardMergerTest {

  private class FakeCache : PlayerLeaderboardCache {
    private val entries = mutableMapOf<String, PlayerLeaderboardData>()

    override fun load(friendCode: String): PlayerLeaderboardData? = entries[friendCode]

    override fun save(friendCode: String, data: PlayerLeaderboardData) {
      entries[friendCode] = data
    }

    fun saved(): Map<String, PlayerLeaderboardData> = entries
  }

  private class BlockingCache : PlayerLeaderboardCache {
    private val entries = ConcurrentHashMap<String, PlayerLeaderboardData>()
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val firstSave = AtomicBoolean(false)
    val bothLoadsStarted = CountDownLatch(2)
    val releaseLoads = CountDownLatch(1)
    val firstSaveStarted = CountDownLatch(1)
    val secondSaveStarted = CountDownLatch(1)
    val releaseFirstSave = CountDownLatch(1)

    override fun load(friendCode: String): PlayerLeaderboardData? {
      events += friendCode
      bothLoadsStarted.countDown()
      check(releaseLoads.await(5, TimeUnit.SECONDS)) { "loads were not released" }
      return entries[friendCode]
    }

    override fun save(friendCode: String, data: PlayerLeaderboardData) {
      events += "save:$friendCode"
      if (firstSave.compareAndSet(false, true)) {
        firstSaveStarted.countDown()
        check(releaseFirstSave.await(5, TimeUnit.SECONDS)) { "first save was not released" }
      } else {
        secondSaveStarted.countDown()
      }
      entries[friendCode] = data
    }

    fun eventSnapshot(): List<String> = synchronized(events) { events.toList() }

    fun saved(): Map<String, PlayerLeaderboardData> = entries.toMap()
  }

  private fun build(
      cache: FakeCache = FakeCache(),
      fetch: suspend (String) -> Result<PlayerLeaderboardData>,
  ): LeaderboardMerger = LeaderboardMerger(fetch, cache, Dispatchers.Unconfined)

  private fun license(slot: Int, friendCode: String? = null, name: String? = "Local"): LicenseInfo =
      LicenseInfo(
          slotIndex = slot,
          exists = friendCode != null,
          miiName = name,
          friendCode = friendCode,
      )

  private fun info(vararg licenses: LicenseInfo): SaveFileInfo {
    val all =
        licenses.toList() +
            (licenses.size until LeaderboardMerger.LICENSE_SLOTS).map { i ->
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
  fun `merge merges leaderboard data into populated slots and writes the cache`() = runTest {
    val code = "1234-5678-9012"
    val info = info(license(slot = 0, friendCode = code, name = "Local"))
    val cache = FakeCache()
    val merger =
        build(cache = cache) {
          assertThat(it).isEqualTo(code)
          Result.success(PlayerLeaderboardData(vr = 4321, name = "Net", miiData = "QUJD"))
        }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0].leaderboardVr).isEqualTo(4321)
    assertThat(merged[0].miiName).isEqualTo("Net")
    assertThat(merged[0].miiDataBase64).isEqualTo("QUJD")
    assertThat(cache.saved())
        .containsEntry(code, PlayerLeaderboardData(vr = 4321, name = "Net", miiData = "QUJD"))
  }

  @Test
  fun `merge uses the cached mii name when the leaderboard omits one`() = runTest {
    val code = "1234-5678-9012"
    val info = info(license(slot = 0, friendCode = code, name = "Local"))
    val cache = FakeCache()
    cache.save(code, PlayerLeaderboardData(vr = 111, name = "Cached", miiData = "QUJD"))
    val merger =
        build(cache = cache) {
          Result.success(PlayerLeaderboardData(vr = 999, name = null, miiData = null))
        }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0].leaderboardVr).isEqualTo(999)
    assertThat(merged[0].miiName).isEqualTo("Cached")
    assertThat(merged[0].miiDataBase64).isEqualTo("QUJD")
  }

  @Test
  fun `merge keeps the cached name when a successful leaderboard omits it`() = runTest {
    val code = "1234-5678-9012"
    val info = info(license(slot = 0, friendCode = code, name = "Local"))
    val cache = FakeCache()
    cache.save(code, PlayerLeaderboardData(vr = 111, name = "Cached", miiData = "QUJD"))
    val merger =
        build(cache = cache) {
          Result.success(PlayerLeaderboardData(vr = 999, name = null, miiData = null))
        }

    merger.merge(Region.PAL, info)

    assertThat(cache.saved())
        .containsEntry(code, PlayerLeaderboardData(vr = 999, name = "Cached", miiData = "QUJD"))
  }

  @Test
  fun `merge falls back to local when the fetch fails and the cache is empty`() = runTest {
    val local = license(slot = 0, friendCode = "1234-5678-9012", name = "Local")
    val info = info(local)
    val merger = build { Result.failure(RuntimeException("no net")) }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0]).isEqualTo(local)
  }

  @Test
  fun `merge uses cached data when the fetch fails`() = runTest {
    val code = "1234-5678-9012"
    val info = info(license(slot = 0, friendCode = code, name = "Local"))
    val cache = FakeCache()
    cache.save(code, PlayerLeaderboardData(vr = 777, name = "Cached", miiData = "QUJD"))
    val merger = build(cache = cache) { Result.failure(RuntimeException("no net")) }

    val merged = merger.merge(Region.PAL, info)

    assertThat(merged[0].leaderboardVr).isEqualTo(777)
    assertThat(merged[0].miiName).isEqualTo("Cached")
    assertThat(merged[0].miiDataBase64).isEqualTo("QUJD")
  }

  @Test
  fun `merge serializes cache writes after parallel fetches`() = runTest {
    val cache = BlockingCache()
    val firstCode = "1111-1111-1111"
    val secondCode = "2222-2222-2222"
    val merger =
        LeaderboardMerger(
            fetchLeaderboard = { code ->
              Result.success(PlayerLeaderboardData(if (code == firstCode) 100 else 200, null, null))
            },
            cache = cache,
            ioDispatcher = Dispatchers.Default,
        )
    val mergeJob =
        launch(Dispatchers.Default) {
          merger.merge(
              Region.PAL,
              info(license(0, firstCode), license(1, secondCode)),
          )
        }

    assertThat(cache.bothLoadsStarted.await(5, TimeUnit.SECONDS)).isTrue()
    cache.releaseLoads.countDown()
    assertThat(cache.firstSaveStarted.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(cache.eventSnapshot().take(2)).containsExactly(firstCode, secondCode)
    assertThat(cache.secondSaveStarted.await(200, TimeUnit.MILLISECONDS)).isFalse()
    cache.releaseFirstSave.countDown()
    mergeJob.join()

    assertThat(cache.eventSnapshot().drop(2))
        .containsExactly("save:$firstCode", "save:$secondCode")
        .inOrder()
    assertThat(cache.saved().keys).containsExactly(firstCode, secondCode)
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

  @Test
  fun `enrichOffline with no info returns four empty slots`() = runTest {
    val merger = build { error("fetch must not be called") }

    val merged = merger.enrichOffline(Region.PAL, null)

    assertThat(merged).hasSize(LeaderboardMerger.LICENSE_SLOTS)
    merged.forEachIndexed { i, license ->
      assertThat(license.slotIndex).isEqualTo(i)
      assertThat(license.exists).isFalse()
    }
  }

  @Test
  fun `enrichOffline applies cached data without fetching`() = runTest {
    val code = "1234-5678-9012"
    val info = info(license(slot = 0, friendCode = code, name = "Local"))
    val cache = FakeCache()
    cache.save(code, PlayerLeaderboardData(vr = 777, name = "Cached", miiData = "QUJD"))
    val merger = build(cache = cache) { error("fetch must not be called") }

    val merged = merger.enrichOffline(Region.PAL, info)

    assertThat(merged[0].leaderboardVr).isEqualTo(777)
    assertThat(merged[0].miiName).isEqualTo("Cached")
    assertThat(merged[0].miiDataBase64).isEqualTo("QUJD")
  }

  @Test
  fun `enrichOffline leaves local data unchanged when the cache is empty`() = runTest {
    val local = license(slot = 0, friendCode = "1234-5678-9012", name = "Local")
    val info = info(local)
    val merger = build { error("fetch must not be called") }

    val merged = merger.enrichOffline(Region.PAL, info)

    assertThat(merged[0]).isEqualTo(local)
  }
}
