package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.LeaderboardEntry
import com.skiletro.wheelwitch.model.LeaderboardResponse
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import com.skiletro.wheelwitch.model.TimeTrialLeaderboardResponse
import com.skiletro.wheelwitch.model.TimeTrialSubmission
import com.skiletro.wheelwitch.model.TimeTrialTrack
import com.skiletro.wheelwitch.network.VersionFileParser
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineViewModelTest {

  private val application: Application = mockk(relaxed = true)
  private lateinit var prefs: SharedPreferences

  @BeforeEach
  fun setUp() {
    prefs = mockk(relaxed = true)
    mockkObject(Prefs)
    mockkObject(VersionFileParser)
    every { Prefs.raceStatsCache(application) } returns prefs
    every { VersionFileParser.probeServer() } returns true
    every { VersionFileParser.fetchRooms() } returns Result.success(emptyList())
  }

  @AfterEach
  fun tearDown() {
    unmockkObject(Prefs)
    unmockkObject(VersionFileParser)
    Dispatchers.resetMain()
  }

  @Test
  fun `refresh leaderboard reloads page one after pagination is exhausted`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val requestedPages = mutableListOf<Int>()
    every { VersionFileParser.fetchLeaderboard(page = any(), limit = any()) } answers
        {
          val page = firstArg<Int>()
          requestedPages += page
          Result.success(
              LeaderboardResponse(
                  entries = listOf(entry(rank = page, friendCode = "fc-$page")),
                  page = page,
                  hasMore = page == 1,
              )
          )
        }
    val store = ViewModelStore()
    val vm = OnlineViewModel(application, ioDispatcher = ioDispatcher)
    store.put("vm", vm)

    vm.refreshLeaderboard()
    advanceUntilIdle()
    vm.fetchLeaderboard()
    advanceUntilIdle()
    assertThat((vm.leaderboardState.value as LeaderboardState.Success).page).isEqualTo(2)
    assertThat((vm.leaderboardState.value as LeaderboardState.Success).hasMore).isFalse()

    vm.refreshLeaderboard()
    advanceUntilIdle()

    val refreshed = vm.leaderboardState.value as LeaderboardState.Success
    assertThat(requestedPages).containsExactly(1, 2, 1).inOrder()
    assertThat(refreshed.page).isEqualTo(1)
    assertThat(refreshed.entries.map { it.friendCode }).containsExactly("fc-1")
    store.clear()
  }

  @Test
  fun `completed old track request never publishes after selection changes`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    val observed = recordTrackStates(vm)
    val stale = trackResponse(trackId = 1, submissionId = 11)
    val active = trackResponse(trackId = 2, submissionId = 21)
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) } returns
        Result.success(stale)
    every { VersionFileParser.fetchTrackLeaderboard(2, 150, true, 1, 50) } returns
        Result.success(active)

    vm.selectTrack(1)
    runCurrent()
    io.runNext() // The old response is ready, but its main-thread publication is still queued.
    vm.selectTrack(2)
    runCurrent()
    assertThat(vm.trackLeaderboardState.value).isEqualTo(TrackLeaderboardState.Loading)
    io.runNext()
    runCurrent()

    assertThat(vm.selectedTrackId.value).isEqualTo(2)
    assertThat(observed.filterIsInstance<TrackLeaderboardState.Success>().map { it.submissions })
        .containsExactly(active.submissions)
    store.clear()
  }

  @Test
  fun `completed old engine and glitch filter requests cannot publish`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    val observed = recordTrackStates(vm)
    val active = trackResponse(submissionId = 3)
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) } returns
        Result.success(trackResponse(submissionId = 1))
    every { VersionFileParser.fetchTrackLeaderboard(1, 200, true, 1, 50) } returns
        Result.failure(IllegalStateException("stale filter error"))
    every { VersionFileParser.fetchTrackLeaderboard(1, 200, false, 1, 50) } returns
        Result.success(active)

    vm.selectTrack(1)
    runCurrent()
    io.runNext()
    vm.setCc(200)
    runCurrent()
    io.runNext()
    vm.setGlitchAllowed(false)
    runCurrent()
    io.runNext()
    runCurrent()

    assertThat(vm.cc.value).isEqualTo(200)
    assertThat(vm.glitchAllowed.value).isFalse()
    assertThat(observed.filterIsInstance<TrackLeaderboardState.Error>()).isEmpty()
    assertThat(observed.filterIsInstance<TrackLeaderboardState.Success>().map { it.submissions })
        .containsExactly(active.submissions)
    verify(exactly = 1) { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) }
    verify(exactly = 1) { VersionFileParser.fetchTrackLeaderboard(1, 200, true, 1, 50) }
    verify(exactly = 1) { VersionFileParser.fetchTrackLeaderboard(1, 200, false, 1, 50) }
    store.clear()
  }

  @Test
  fun `load more is single flight and stops at the final page`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) } returns
        Result.success(trackResponse(page = 1, totalPages = 2, submissionId = 1))
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 2, 50) } returns
        Result.success(trackResponse(page = 2, totalPages = 2, submissionId = 2))
    vm.selectTrack(1)
    runCurrent()
    io.runNext()
    runCurrent()
    val firstPage = vm.trackLeaderboardState.value

    vm.fetchMoreSubmissions()
    vm.fetchMoreSubmissions()
    runCurrent()
    vm.fetchMoreSubmissions()
    assertThat(io.pendingCount).isEqualTo(1)
    assertThat(vm.trackLeaderboardState.value).isEqualTo(firstPage)
    io.runNext()
    runCurrent()

    val result = vm.trackLeaderboardState.value as TrackLeaderboardState.Success
    assertThat(result.submissions.map { it.id }).containsExactly(1, 2).inOrder()
    assertThat(result.currentPage).isEqualTo(2)
    assertThat(result.fastestLapMs).isEqualTo(2_000L)
    assertThat(result.fastestLapDisplay).isEqualTo("lap-2")
    vm.fetchMoreSubmissions()
    runCurrent()
    assertThat(io.pendingCount).isEqualTo(0)
    verify(exactly = 1) { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 2, 50) }
    store.clear()
  }

  @Test
  fun `pagination failure preserves submissions and allows retry`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) } returns
        Result.success(trackResponse(submissionId = 1))
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 2, 50) } returnsMany
        listOf(
            Result.failure(IllegalStateException("page failed")),
            Result.success(trackResponse(page = 2, submissionId = 2)),
        )
    vm.selectTrack(1)
    runCurrent()
    io.runNext()
    runCurrent()
    val firstPage = vm.trackLeaderboardState.value

    vm.fetchMoreSubmissions()
    runCurrent()
    io.runNext()
    runCurrent()
    assertThat(vm.trackLeaderboardState.value).isEqualTo(firstPage)
    vm.fetchMoreSubmissions()
    runCurrent()
    io.runNext()
    runCurrent()

    val result = vm.trackLeaderboardState.value as TrackLeaderboardState.Success
    assertThat(result.submissions.map { it.id }).containsExactly(1, 2).inOrder()
    verify(exactly = 2) { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 2, 50) }
    store.clear()
  }

  @Test
  fun `first page filter change invalidates completed pagination`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    val observed = recordTrackStates(vm)
    val active = trackResponse(submissionId = 3)
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) } returns
        Result.success(trackResponse(submissionId = 1))
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 2, 50) } returns
        Result.success(trackResponse(page = 2, submissionId = 2))
    every { VersionFileParser.fetchTrackLeaderboard(1, 200, true, 1, 50) } returns
        Result.success(active)
    vm.selectTrack(1)
    runCurrent()
    io.runNext()
    runCurrent()
    vm.fetchMoreSubmissions()
    runCurrent()
    io.runNext()
    observed.clear()

    vm.setCc(200)
    runCurrent()
    io.runNext()
    runCurrent()

    assertThat(observed.filterIsInstance<TrackLeaderboardState.Success>().map { it.submissions })
        .containsExactly(active.submissions)
    assertThat((vm.trackLeaderboardState.value as TrackLeaderboardState.Success).currentPage)
        .isEqualTo(1)
    store.clear()
  }

  @Test
  fun `track list reset invalidates completed first page and filters hidden tracks`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    val observed = recordTrackStates(vm)
    val visible = track(1)
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) } returns
        Result.success(trackResponse())
    every { VersionFileParser.fetchTracks() } returns
        Result.success(listOf(visible, track(2, isHidden = true)))
    vm.selectTrack(1)
    runCurrent()
    io.runNext()

    vm.fetchTracks()
    assertThat(vm.selectedTrackId.value).isNull()
    assertThat(vm.trackLeaderboardState.value).isEqualTo(TrackLeaderboardState.Idle)
    assertThat(vm.ttState.value).isEqualTo(TimeTrialState.Loading)
    runCurrent()
    io.runNext()
    runCurrent()

    assertThat(observed.filterIsInstance<TrackLeaderboardState.Success>()).isEmpty()
    assertThat(vm.trackLeaderboardState.value).isEqualTo(TrackLeaderboardState.Idle)
    assertThat((vm.ttState.value as TimeTrialState.Success).tracks).containsExactly(visible)
    store.clear()
  }

  @Test
  fun `track list reset invalidates completed pagination`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    val observed = recordTrackStates(vm)
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 1, 50) } returns
        Result.success(trackResponse(submissionId = 1))
    every { VersionFileParser.fetchTrackLeaderboard(1, 150, true, 2, 50) } returns
        Result.success(trackResponse(page = 2, submissionId = 2))
    every { VersionFileParser.fetchTracks() } returns Result.success(emptyList())
    vm.selectTrack(1)
    runCurrent()
    io.runNext()
    runCurrent()
    vm.fetchMoreSubmissions()
    runCurrent()
    io.runNext()
    observed.clear()

    vm.fetchTracks()
    runCurrent()
    io.runNext()
    runCurrent()

    assertThat(vm.selectedTrackId.value).isNull()
    assertThat(vm.trackLeaderboardState.value).isEqualTo(TrackLeaderboardState.Idle)
    assertThat(observed.filterIsInstance<TrackLeaderboardState.Success>()).isEmpty()
    store.clear()
  }

  @Test
  fun `only the newest track list request publishes`() = runTest {
    val io = ControlledDispatcher()
    val store = ViewModelStore()
    val vm = timeTrialViewModel(io, store)
    val observed = mutableListOf<TimeTrialState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ttState.toList(observed) }
    val active = track(2)
    every { VersionFileParser.fetchTracks() } returnsMany
        listOf(
            Result.failure(IllegalStateException("stale track list error")),
            Result.success(listOf(active)),
        )

    vm.fetchTracks()
    runCurrent()
    io.runNext()
    vm.fetchTracks()
    runCurrent()
    io.runNext()
    runCurrent()

    assertThat(observed.filterIsInstance<TimeTrialState.Error>()).isEmpty()
    assertThat(observed.filterIsInstance<TimeTrialState.Success>().map { it.tracks })
        .containsExactly(listOf(active))
    store.clear()
  }

  private fun TestScope.timeTrialViewModel(
      io: ControlledDispatcher,
      store: ViewModelStore,
  ): OnlineViewModel {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val vm = OnlineViewModel(application, ioDispatcher = io)
    store.put("vm", vm)
    runCurrent()
    io.runNext() // Complete the initial room probe before controlling time-trial requests.
    runCurrent()
    return vm
  }

  private fun TestScope.recordTrackStates(vm: OnlineViewModel): MutableList<TrackLeaderboardState> {
    val observed = mutableListOf<TrackLeaderboardState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      vm.trackLeaderboardState.toList(observed)
    }
    return observed
  }

  private fun track(id: Int, isHidden: Boolean = false) =
      TimeTrialTrack(
          id = id,
          name = "Track $id",
          courseId = id,
          category = "retro",
          laps = 3,
          supportsGlitch = true,
          sortOrder = id,
          isHidden = isHidden,
      )

  private fun trackResponse(
      trackId: Int = 1,
      page: Int = 1,
      totalPages: Int = 3,
      submissionId: Int = page,
  ) =
      TimeTrialLeaderboardResponse(
          track = track(trackId),
          cc = 150,
          glitchAllowed = true,
          shroomless = null,
          vehicleFilter = null,
          isFlap = false,
          submissions = listOf(mockk<TimeTrialSubmission> { every { id } returns submissionId }),
          totalSubmissions = totalPages,
          currentPage = page,
          pageSize = 50,
          totalPages = totalPages,
          fastestLapMs = page * 1_000L,
          fastestLapDisplay = "lap-$page",
      )

  /** Keeps blocking fetches separate from their queued main-thread result delivery. */
  private class ControlledDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()
    val pendingCount: Int
      get() = pending.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      pending.addLast(block)
    }

    fun runNext() {
      pending.removeFirst().run()
    }
  }

  private fun entry(rank: Int, friendCode: String) =
      LeaderboardEntry(
          rank = rank,
          friendCode = friendCode,
          player = PlayerLeaderboardData(vr = rank * 100, name = "Player $rank", miiData = null),
          miiImageBase64 = null,
      )

  @Test
  fun `fetchRaceStats failure reads the cache inside the io dispatcher`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val wrapper = JSONObject().put("raceStats", "{}").put("cachedAt", 123L).toString()
    var readInsideIoDispatcher: Boolean? = null
    every { prefs.getString(PrefsKeys.RACE_STATS_KEY, null) } answers
        {
          readInsideIoDispatcher = ioDispatcher.active
          wrapper
        }
    every { VersionFileParser.fetchGlobalRaceStatsRaw() } returns
        Result.failure(Exception("server boom"))

    val store = ViewModelStore()
    val vm = OnlineViewModel(application, ioDispatcher = ioDispatcher)
    store.put("vm", vm)
    vm.fetchRaceStats()
    testScheduler.advanceUntilIdle()

    // The cached fallback read must be dispatched to the injected io
    // dispatcher, not run inline on the main dispatcher.
    assertThat(readInsideIoDispatcher).isTrue()

    // Cancel the viewModelScope before the main dispatcher is reset so no
    // coroutine resumes on the missing-Main dispatcher after teardown.
    store.clear()
  }

  private val ioDispatcher = InlineFlaggedDispatcher()

  private class InlineFlaggedDispatcher : CoroutineDispatcher() {
    @Volatile
    var active = false
      private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      active = true
      try {
        block.run()
      } finally {
        active = false
      }
    }
  }
}
