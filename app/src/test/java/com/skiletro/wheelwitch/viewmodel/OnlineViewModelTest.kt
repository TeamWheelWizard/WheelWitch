package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.network.VersionFileParser
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
    fun `fetchRaceStats failure reads the cache inside the io dispatcher`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wrapper =
            JSONObject()
                .put("raceStats", "{}")
                .put("cachedAt", 123L)
                .toString()
        var readInsideIoDispatcher: Boolean? = null
        every { prefs.getString(PrefsKeys.RACE_STATS_KEY, null) } answers {
            readInsideIoDispatcher = ioDispatcher.active
            wrapper
        }
        every { VersionFileParser.fetchGlobalRaceStatsRaw() } returns
            Result.failure(Exception("server boom"))

        val vm = OnlineViewModel(application, ioDispatcher = ioDispatcher)
        vm.fetchRaceStats()
        testScheduler.advanceUntilIdle()

        // The cached fallback read must be dispatched to the injected io
        // dispatcher, not run inline on the main dispatcher.
        assertThat(readInsideIoDispatcher).isTrue()
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