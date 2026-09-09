package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.PlayerLeaderboardCache
import com.skiletro.wheelwitch.data.RksysParser
import com.skiletro.wheelwitch.data.SaveManager
import com.skiletro.wheelwitch.data.SaveManager.Region
import com.skiletro.wheelwitch.domain.LeaderboardMerger
import com.skiletro.wheelwitch.model.BadgeType
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.network.VersionFileParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaveDataViewModelTest {

  private val app: Application = mockk(relaxed = true)
  private lateinit var packStatus: MutableStateFlow<UiState>
  private lateinit var mockTree: DolphinTree
  private lateinit var vm: SaveDataViewModel

private val leaderboardResult = mutableMapOf<String, Result<PlayerLeaderboardData>>()
  private var leaderboardCalls = 0
  private var fixedNow: Long = 1_700_000_000_000L

  private class InMemoryCache : PlayerLeaderboardCache {
    private val entries = mutableMapOf<String, PlayerLeaderboardData>()

    override fun load(friendCode: String): PlayerLeaderboardData? = entries[friendCode]

    override fun save(friendCode: String, data: PlayerLeaderboardData) {
      entries[friendCode] = data
    }
  }

  @BeforeEach
  fun setUp() {
    val testDispatcher = UnconfinedTestDispatcher()
    Dispatchers.setMain(testDispatcher)
    ioDispatcher = testDispatcher
    packStatus = MutableStateFlow(UiState.Idle)
    mockTree = mockk(relaxed = true)
    mockkObject(SaveManager)
    mockkObject(VersionFileParser)
    leaderboardResult.clear()
    leaderboardCalls = 0
    leaderboardResult["1234-5678-9012"] = Result.success(PlayerLeaderboardData(9999, null, null))
  }

  @AfterEach
  fun tearDown() {
    Dispatchers.resetMain()
    unmockkObject(SaveManager)
    unmockkObject(VersionFileParser)
  }

  private lateinit var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher

  private fun buildVm(
    tree: DolphinTree? = mockTree,
    leaderboard: suspend (String) -> Result<PlayerLeaderboardData> = { code ->
      leaderboardCalls++
      leaderboardResult[code] ?: Result.failure(RuntimeException("no stub for $code"))
    },
    now: () -> Long = { fixedNow },
  ): SaveDataViewModel =
    SaveDataViewModel(
      application = app,
      packStatusFlow = packStatus as StateFlow<UiState>,
      treeFactory = { tree },
      leaderboardMerger = LeaderboardMerger(leaderboard, InMemoryCache(), ioDispatcher),
      now = now,
      ioDispatcher = ioDispatcher,
    )

  // --- per-region Licenses viewer tests (unchanged behaviour) ---------

  @Test
  fun `init with no tree keeps saveInfos empty and no error`() = runTest {
    vm = buildVm(tree = null)

    assertThat(vm.saveInfos.value).isEmpty()
    assertThat(vm.hasSave.value).isEmpty()
    assertThat(vm.mergedLicenses.value).isEmpty()
    assertThat(vm.error.value).isNull()
    assertThat(vm.hasAnySave.value).isFalse()
  }

  // --- refreshIfStale -------------------------------------------------

  @Test
  fun `refreshIfStale skips when a refresh ran within the staleness window`() = runTest {
    val bytes = rksysWithLicense(pid = 0x00000010L, name = "Alice", slot = 0)
    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.refresh()
    val callsAfterFirst = leaderboardCalls

    // Move the clock forward by less than the staleness window.
    fixedNow += 1_000L
    vm.refreshIfStale(maxAgeMs = 5_000L)

    assertThat(leaderboardCalls).isEqualTo(callsAfterFirst)
  }

  @Test
  fun `refreshIfStale runs when the last refresh is older than the window`() = runTest {
    val bytes = rksysWithLicense(pid = 0x00000010L, name = "Alice", slot = 0)
    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.refresh()
    val callsAfterFirst = leaderboardCalls

    // Move the clock forward past the staleness window.
    fixedNow += 6_000L
    vm.refreshIfStale(maxAgeMs = 5_000L)

    assertThat(leaderboardCalls).isGreaterThan(callsAfterFirst)
  }

  @Test
  fun `init with tree refreshes when packStatusFlow emits Ready`() = runTest {
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns false
    vm = buildVm()

    packStatus.value = UiState.Ready(PackStatus.UpToDate(SemVersion(3, 2, 6), SemVersion(3, 2, 6)))

    assertThat(vm.saveInfos.value).isEmpty()
    coVerify { SaveManager.listRegions(mockTree) }
  }

  @Test
  fun `refresh with no tree clears state without error`() = runTest {
    vm = buildVm(tree = null)
    vm.refresh()

    assertThat(vm.saveInfos.value).isEmpty()
    assertThat(vm.hasSave.value).isEmpty()
    assertThat(vm.mergedLicenses.value).isEmpty()
    assertThat(vm.hasAnySave.value).isFalse()
    assertThat(vm.error.value).isNull()
  }

  @Test
  fun `refresh with tree but no ROMs clears save state`() = runTest {
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns false
    vm = buildVm()

    vm.refresh()

    assertThat(vm.saveInfos.value).isEmpty()
    assertThat(vm.hasSave.value).isEmpty()
    assertThat(vm.selectedRegion.value).isNull()
    assertThat(vm.mergedLicenses.value).isEmpty()
    assertThat(vm.hasAnySave.value).isFalse()
  }

  @Test
  fun `refresh parses each region's save in parallel`() = runTest {
    val palBytes = rksysWithLicense(pid = 0x11111111L, name = "PAL", slot = 0)
    val usaBytes = rksysWithLicense(pid = 0x22222222L, name = "USA", slot = 0)
    val palInfo = RksysParser.parse(palBytes)
    val usaInfo = RksysParser.parse(usaBytes)

    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL, Region.USA)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns palBytes
    coEvery { SaveManager.readSave(mockTree, Region.USA) } returns usaBytes
    coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns true
    coEvery { SaveManager.hasSave(mockTree, Region.USA) } returns true
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.refresh()

    assertThat(vm.saveInfos.value).containsExactly(Region.PAL, palInfo, Region.USA, usaInfo)
    assertThat(vm.hasSave.value).containsExactly(Region.PAL, true, Region.USA, true)
    assertThat(vm.selectedRegion.value).isEqualTo(Region.PAL)
    assertThat(vm.hasAnySave.value).isTrue()
  }

  @Test
  fun `refresh populates mergedLicenses with leaderboard data for all 4 slots of selected region`() =
    runTest {
      val bytes = ByteArray(0x20000)
      for ((slot, base) in RksysParser.LICENSE_BASES.withIndex()) {
        if (slot > 1) break
        writeAscii(bytes, base, "RKPD")
        writeUtf16Be(bytes, base + 0x14, if (slot == 0) "Zero" else "One")
        writeUInt32Be(bytes, base + 0x5C, if (slot == 0) 0x00000010L else 0x00000011L)
      }
      val info = RksysParser.parse(bytes)
      val fc0 = info.licenses[0].friendCode!!
      val fc1 = info.licenses[1].friendCode!!
      leaderboardResult[fc0] = Result.success(PlayerLeaderboardData(1000, null, null))
      leaderboardResult[fc1] = Result.success(PlayerLeaderboardData(2000, null, null))

      every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
      coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
      coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns true
      every { SaveManager.hasAnySave(mockTree) } returns true
      vm = buildVm()

      vm.refresh()

      val merged = vm.mergedLicenses.value[Region.PAL]
      assertThat(merged).hasSize(4)
      assertThat(merged!![0].leaderboardVr).isEqualTo(1000)
      assertThat(merged[1].leaderboardVr).isEqualTo(2000)
      assertThat(merged[2].exists).isFalse()
      assertThat(merged[3].exists).isFalse()
      assertThat(leaderboardCalls).isEqualTo(2)
    }

  @Test
  fun `mergedLicenses merges leaderboard VR and Mii for the selected region`() = runTest {
    val bytes = rksysWithLicense(pid = 0x00000001L, name = "X", slot = 0)
    val info = RksysParser.parse(bytes)
    val friendCode = info.licenses[0].friendCode!!
    leaderboardResult[friendCode] = Result.success(PlayerLeaderboardData(9999, "NewName", null))

    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
    coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns true
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.refresh()
    val merged = vm.mergedLicenses.value[Region.PAL]!!
    assertThat(merged[0].friendCode).isEqualTo(friendCode)
    assertThat(merged[0].leaderboardVr).isEqualTo(9999)
    assertThat(merged[0].miiName).isEqualTo("NewName")
  }

  @Test
  fun `scoreResults computes VR norm from leaderboard VR not local rating`() = runTest {
    val bytes = rksysWithLicense(pid = 0x00000001L, name = "X", slot = 0)
    val info = RksysParser.parse(bytes)
    val friendCode = info.licenses[0].friendCode!!
    leaderboardResult[friendCode] = Result.success(PlayerLeaderboardData(9999, null, null))

    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
    coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns true
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.refresh()

    val result = vm.scoreResults.value[0]
    assertThat(result).isNotNull()
    assertThat(result!!.vrNorm).isWithin(0.001).of(9.999)
  }

  @Test
  fun `selectRegion triggers leaderboard fetch for the new region`() = runTest {
    val palBytes = rksysWithLicense(pid = 0x11111111L, name = "PAL", slot = 0)
    val usaBytes = rksysWithLicense(pid = 0x22222222L, name = "USA", slot = 0)
    val palInfo = RksysParser.parse(palBytes)
    val usaInfo = RksysParser.parse(usaBytes)
    val palFc = palInfo.licenses[0].friendCode!!
    val usaFc = usaInfo.licenses[0].friendCode!!
    leaderboardResult[palFc] = Result.success(PlayerLeaderboardData(1111, null, null))
    leaderboardResult[usaFc] = Result.success(PlayerLeaderboardData(2222, null, null))

    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL, Region.USA)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns palBytes
    coEvery { SaveManager.readSave(mockTree, Region.USA) } returns usaBytes
    coEvery { SaveManager.hasSave(mockTree, any()) } returns true
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()
    vm.refresh()
    assertThat(vm.mergedLicenses.value).doesNotContainKey(Region.USA)
    val callsAfterRefresh = leaderboardCalls

    vm.selectRegion(Region.USA)

    assertThat(vm.selectedRegion.value).isEqualTo(Region.USA)
    val merged = vm.mergedLicenses.value[Region.USA]
    assertThat(merged).hasSize(4)
    assertThat(merged!![0].leaderboardVr).isEqualTo(2222)
    assertThat(merged[0].friendCode).isEqualTo(usaFc)
    assertThat(leaderboardCalls - callsAfterRefresh).isEqualTo(1)
  }

  @Test
  fun `re-refresh cancels an in-flight badge fetch so stale badges cannot overwrite fresh ones`() =
    runTest {
      val bytes = rksysWithLicense(pid = 0x00000010L, name = "Alice", slot = 0)
      every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
      coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
      every { SaveManager.hasAnySave(mockTree) } returns true
      val gate = CompletableDeferred<Unit>()
      var calls = 0
      val fresh = mapOf(0x00000010L to listOf(BadgeType.CONTRIBUTOR))
      val stale = mapOf(0x00000010L to listOf(BadgeType.UNKNOWN))
      coEvery { VersionFileParser.fetchBadges(any()) } coAnswers {
        val seq = calls
        calls++
        if (seq == 0) gate.await()
        if (seq == 0) stale else fresh
      }
      vm = buildVm()

      vm.refresh()
      // Second refresh answers with the fresh badge set immediately…
      vm.refresh()
      assertThat(vm.badges.value).isEqualTo(fresh)

      gate.complete(Unit)
      testScheduler.advanceUntilIdle()
      assertThat(vm.badges.value).isEqualTo(fresh)
      assertThat(calls).isEqualTo(2)
    }

  @Test
  fun `selectRegion with the same region is a no-op`() = runTest {
    val bytes = rksysWithLicense(pid = 0x00000001L, name = "X", slot = 0)
    val info = RksysParser.parse(bytes)
    leaderboardResult[info.licenses[0].friendCode!!] = Result.success(PlayerLeaderboardData(100, null, null))

    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
    coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns true
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()
    vm.refresh()
    val callsAfterRefresh = leaderboardCalls

    vm.selectRegion(Region.PAL)

    assertThat(leaderboardCalls).isEqualTo(callsAfterRefresh)
  }

  @Test
  fun `deleteSave updates mergedLicenses to four empty slots for the deleted region`() = runTest {
    val bytes = rksysWithLicense(pid = 0x00000010L, name = "Alice", slot = 0)
    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns bytes
    coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns true
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()
    vm.refresh()
    assertThat(vm.mergedLicenses.value[Region.PAL]?.get(0)?.exists).isTrue()

    // After delete: save is gone, refresh must publish 4 empty slots.
    coEvery { SaveManager.deleteAll(mockTree) } returns Result.success(Unit)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns null
    coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns false
    every { SaveManager.hasAnySave(mockTree) } returns false

    vm.deleteAll()

    val merged = vm.mergedLicenses.value[Region.PAL]
    assertThat(merged).hasSize(4)
    merged!!.forEachIndexed { i, license ->
      assertThat(license.slotIndex).isEqualTo(i)
      assertThat(license.exists).isFalse()
    }
  }

  @Test
  fun `selectRegion after a delete publishes four empty slots for the new region`() = runTest {
    val palBytes = rksysWithLicense(pid = 0x00000010L, name = "Alice", slot = 0)
    val palInfo = RksysParser.parse(palBytes)
    every { SaveManager.listRegions(mockTree) } returns listOf(Region.PAL, Region.USA)
    coEvery { SaveManager.readSave(mockTree, Region.PAL) } returns palBytes
    coEvery { SaveManager.readSave(mockTree, Region.USA) } returns null
    coEvery { SaveManager.hasSave(mockTree, Region.PAL) } returns true
    coEvery { SaveManager.hasSave(mockTree, Region.USA) } returns false
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()
    vm.refresh()

    assertThat(vm.mergedLicenses.value[Region.PAL]).isEqualTo(palInfo.licenses)
    assertThat(vm.mergedLicenses.value).doesNotContainKey(Region.USA)

    vm.selectRegion(Region.USA)

    val usaMerged = vm.mergedLicenses.value[Region.USA]
    assertThat(usaMerged).hasSize(4)
    usaMerged!!.forEachIndexed { i, license ->
      assertThat(license.slotIndex).isEqualTo(i)
      assertThat(license.exists).isFalse()
    }
  }

  // --- unified save data tests ----------------------------------------

  @Test
  fun `backupAll delegates to SaveManager and persists the timestamp on success`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.backupAll(mockTree, uri) } returns
      Result.success(
        SaveManager.BackupSummary(rksys = 2, vanillaSaves = 0, patchedIso = false, faceLib = true, pulsar = 3, ghosts = 4)
      )
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.backupAll(uri)

    coVerify { SaveManager.backupAll(mockTree, uri) }
    assertThat(vm.lastBackupTimestamp.value).isEqualTo(fixedNow)
  }

  @Test
  fun `backupAll sets an error when SaveManager fails and does not persist a timestamp`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.backupAll(mockTree, uri) } returns
      Result.failure(RuntimeException("disk full"))
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.backupAll(uri)

    assertThat(vm.error.value).isEqualTo("disk full")
    assertThat(vm.lastBackupTimestamp.value).isEqualTo(0L)
  }

  @Test
  fun `backupAll surfaces a not-configured error when the tree factory returns null`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    vm = buildVm(tree = null)
    every {
      app.getString(com.skiletro.wheelwitch.R.string.vm_save_not_configured)
    } returns "no storage"

    vm.backupAll(uri)

    assertThat(vm.error.value).isEqualTo("no storage")
  }

  @Test
  fun `restoreAll delegates to SaveManager and refreshes on success`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.restoreAll(mockTree, uri) } returns
      Result.success(SaveManager.RestoreSummary(rksys = 2, vanillaSaves = 0, patchedIso = false, faceLib = true, pulsar = 3, ghosts = 4))
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns false
    vm = buildVm()

    vm.restoreAll(uri)

    coVerify { SaveManager.restoreAll(mockTree, uri) }
    coVerify { SaveManager.listRegions(mockTree) }
  }

  @Test
  fun `restoreAll surfaces a not-configured error when the tree factory returns null`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    vm = buildVm(tree = null)
    every {
      app.getString(com.skiletro.wheelwitch.R.string.vm_save_not_configured)
    } returns "no storage"

    vm.restoreAll(uri)

    assertThat(vm.error.value).isEqualTo("no storage")
  }

  @Test
  fun `deleteAll delegates to SaveManager and refreshes on success`() = runTest {
    coEvery { SaveManager.deleteAll(mockTree) } returns Result.success(Unit)
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns false
    vm = buildVm()

    vm.deleteAll()

    coVerify(exactly = 1) { SaveManager.deleteAll(mockTree) }
  }

  @Test
  fun `formatLastBackup returns null when the timestamp is zero`() {
    vm = buildVm()
    assertThat(vm.formatLastBackup()).isNull()
  }

  // --- RR-only save data tests ----------------------------------------

  @Test
  fun `backupRR delegates to SaveManager and persists the RR timestamp on success`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.backupRR(mockTree, uri) } returns
      Result.success(
        SaveManager.BackupSummary(rksys = 2, vanillaSaves = 0, patchedIso = false, faceLib = false, pulsar = 0, ghosts = 0)
      )
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.backupRR(uri)

    coVerify { SaveManager.backupRR(mockTree, uri) }
    assertThat(vm.lastBackupRRTimestamp.value).isEqualTo(fixedNow)
  }

  @Test
  fun `backupRR sets an error when SaveManager fails and does not persist a timestamp`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.backupRR(mockTree, uri) } returns
      Result.failure(RuntimeException("disk full"))
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.backupRR(uri)

    assertThat(vm.error.value).isEqualTo("disk full")
    assertThat(vm.lastBackupRRTimestamp.value).isEqualTo(0L)
  }

  @Test
  fun `backupRR surfaces a not-configured error when the tree factory returns null`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    vm = buildVm(tree = null)
    every {
      app.getString(com.skiletro.wheelwitch.R.string.vm_save_not_configured)
    } returns "no storage"

    vm.backupRR(uri)

    assertThat(vm.error.value).isEqualTo("no storage")
  }

  @Test
  fun `restoreRR delegates to SaveManager and refreshes on success`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.restoreRR(mockTree, uri) } returns
      Result.success(SaveManager.RestoreSummary(rksys = 2, vanillaSaves = 0, patchedIso = false, faceLib = false, pulsar = 0, ghosts = 0))
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns false
    vm = buildVm()

    vm.restoreRR(uri)

    coVerify { SaveManager.restoreRR(mockTree, uri) }
  }

  @Test
  fun `restoreRR surfaces a not-configured error when the tree factory returns null`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    vm = buildVm(tree = null)
    every {
      app.getString(com.skiletro.wheelwitch.R.string.vm_save_not_configured)
    } returns "no storage"

    vm.restoreRR(uri)

    assertThat(vm.error.value).isEqualTo("no storage")
  }

  @Test
  fun `deleteRR delegates to SaveManager and refreshes on success`() = runTest {
    coEvery { SaveManager.deleteRR(mockTree) } returns Result.success(Unit)
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns false
    vm = buildVm()

    vm.deleteRR()

    coVerify(exactly = 1) { SaveManager.deleteRR(mockTree) }
  }

  @Test
  fun `formatLastBackupRR returns null when the RR timestamp is zero`() {
    vm = buildVm()
    assertThat(vm.formatLastBackupRR()).isNull()
  }

  @Test
  fun `formatLastBackupRR returns a localized timestamp after a successful RR backup`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.backupRR(mockTree, uri) } returns
      Result.success(
        SaveManager.BackupSummary(rksys = 1, vanillaSaves = 0, patchedIso = false, faceLib = false, pulsar = 0, ghosts = 0)
      )
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.backupRR(uri)

    val label = vm.formatLastBackupRR()
    assertThat(label).isNotNull()
    assertThat(label).isNotEmpty()
  }

  @Test
  fun `formatLastBackup returns a localized timestamp after a successful backup`() = runTest {
    val uri = mockk<Uri>(relaxed = true)
    coEvery { SaveManager.backupAll(mockTree, uri) } returns
      Result.success(
        SaveManager.BackupSummary(rksys = 1, vanillaSaves = 0, patchedIso = false, faceLib = false, pulsar = 0, ghosts = 0)
      )
    every { SaveManager.listRegions(mockTree) } returns emptyList()
    every { SaveManager.hasAnySave(mockTree) } returns true
    vm = buildVm()

    vm.backupAll(uri)

    val label = vm.formatLastBackup()
    assertThat(label).isNotNull()
    assertThat(label).isNotEmpty()
  }

  // --- helpers ----------------------------------------------------------

  /**
   * Builds a minimal `rksys.dat` byte array with one valid license at
   * the given [slot] and a 20-byte UTF-16BE [name]. All other fields
   * (VR, race counts, Mii RFL) are zero. The array is large enough
   * for all 4 license slots.
   */
  private fun rksysWithLicense(pid: Long, name: String, slot: Int): ByteArray {
    val bytes = ByteArray(0x20000)
    val base = RksysParser.LICENSE_BASES[slot]
    writeAscii(bytes, base, "RKPD")
    writeUtf16Be(bytes, base + 0x14, name)
    writeUInt32Be(bytes, base + 0x5C, pid)
    return bytes
  }

  private fun writeAscii(bytes: ByteArray, offset: Int, text: String) {
    val data = text.encodeToByteArray()
    data.copyInto(bytes, offset)
  }

  private fun writeUtf16Be(bytes: ByteArray, offset: Int, text: String) {
    for ((i, c) in text.withIndex()) {
      val pos = offset + i * 2
      bytes[pos] = (c.code shr 8).toByte()
      bytes[pos + 1] = c.code.toByte()
    }
  }

  private fun writeUInt32Be(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = (value shr 24).toByte()
    bytes[offset + 1] = (value shr 16).toByte()
    bytes[offset + 2] = (value shr 8).toByte()
    bytes[offset + 3] = value.toByte()
  }
}
