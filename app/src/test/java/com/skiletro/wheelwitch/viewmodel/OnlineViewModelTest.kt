package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.MutableCreationExtras
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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
  fun `newer track selection keeps stale response from publishing`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val firstStarted = CompletableDeferred<Unit>()
    val firstResponse = CompletableDeferred<Result<TimeTrialLeaderboardResponse>>()
    val store = ViewModelStore()
    val vm =
        OnlineViewModel(
            application,
            ioDispatcher = dispatcher,
            trackLeaderboardFetcher = { trackId, _, _, _, _ ->
              if (trackId == 1) {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { firstResponse.await() }
              } else {
                Result.success(trackResponse(trackId = 2, page = 1, totalPages = 1))
              }
            },
        )
    store.put("vm", vm)

    vm.selectTrack(1)
    runCurrent()
    assertThat(firstStarted.isCompleted).isTrue()

    vm.selectTrack(2)
    advanceUntilIdle()
    assertTrackState(trackId = 2, page = 1, totalPages = 1, vm = vm)

    firstResponse.complete(Result.success(trackResponse(trackId = 1, page = 1, totalPages = 1)))
    advanceUntilIdle()

    assertTrackState(trackId = 2, page = 1, totalPages = 1, vm = vm)
    store.clear()
  }

  @Test
  fun `new filter keeps stale pagination from appending`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val pageTwoStarted = CompletableDeferred<Unit>()
    val pageTwoResponse = CompletableDeferred<Result<TimeTrialLeaderboardResponse>>()
    val store = ViewModelStore()
    val vm =
        OnlineViewModel(
            application,
            ioDispatcher = dispatcher,
            trackLeaderboardFetcher = { trackId, cc, _, page, _ ->
              if (page == 2) {
                pageTwoStarted.complete(Unit)
                withContext(NonCancellable) { pageTwoResponse.await() }
              } else {
                Result.success(
                    trackResponse(
                        trackId = trackId,
                        page = 1,
                        totalPages = if (cc == 150) 2 else 1,
                    )
                )
              }
            },
        )
    store.put("vm", vm)

    vm.selectTrack(1)
    advanceUntilIdle()
    vm.fetchMoreSubmissions()
    runCurrent()
    assertThat(pageTwoStarted.isCompleted).isTrue()

    vm.setCc(200)
    advanceUntilIdle()
    assertTrackState(trackId = 1, page = 1, totalPages = 1, vm = vm)

    pageTwoResponse.complete(Result.success(trackResponse(trackId = 1, page = 2, totalPages = 2)))
    advanceUntilIdle()

    assertTrackState(trackId = 1, page = 1, totalPages = 1, vm = vm)
    store.clear()
  }

  private fun assertTrackState(trackId: Int, page: Int, totalPages: Int, vm: OnlineViewModel) {
    val state = vm.trackLeaderboardState.value as TrackLeaderboardState.Success
    assertThat(state.submissions).hasSize(1)
    assertThat(state.submissions.single().trackId).isEqualTo(trackId)
    assertThat(state.currentPage).isEqualTo(page)
    assertThat(state.totalPages).isEqualTo(totalPages)
  }

  private fun trackResponse(
      trackId: Int,
      page: Int,
      totalPages: Int,
  ): TimeTrialLeaderboardResponse {
    val submission = mockk<TimeTrialSubmission>(relaxed = true)
    every { submission.trackId } returns trackId
    return TimeTrialLeaderboardResponse(
        track =
            TimeTrialTrack(
                id = trackId,
                name = "Track $trackId",
                courseId = trackId,
                category = "test",
                laps = 3,
                supportsGlitch = true,
                sortOrder = trackId,
                isHidden = false,
            ),
        cc = 150,
        glitchAllowed = true,
        shroomless = null,
        vehicleFilter = null,
        isFlap = false,
        submissions = listOf(submission),
        totalSubmissions = 1,
        currentPage = page,
        pageSize = 50,
        totalPages = totalPages,
        fastestLapMs = null,
        fastestLapDisplay = null,
    )
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

  @Test
  fun `companion factory constructs the view model from application extras`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val extras = MutableCreationExtras()
    extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application

    val store = ViewModelStore()
    val vm = OnlineViewModel.Factory.create(OnlineViewModel::class.java, extras)
    store.put("vm", vm)

    assertThat(vm).isInstanceOf(OnlineViewModel::class.java)

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
