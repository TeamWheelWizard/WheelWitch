package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLaunchViewModelTest {
  private val app = mockk<Application>(relaxed = true)
  private val prefs = mockk<SharedPreferences>(relaxed = true)
  private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
  private val dispatcher = UnconfinedTestDispatcher()
  private var noticeShown = false

  @BeforeEach
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    every { app.getSharedPreferences(any<String>(), any<Int>()) } returns prefs
    every { prefs.getBoolean(PrefsKeys.GAME_INI_NOTICE_SHOWN_KEY, false) } answers { noticeShown }
    every { prefs.edit() } returns editor
    every { editor.putBoolean(PrefsKeys.GAME_INI_NOTICE_SHOWN_KEY, true) } answers
        {
          noticeShown = true
          editor
        }
  }

  @AfterEach
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `notice dismissal does not acknowledge it and confirmation persists across owners`() =
      runTest {
        var launches = 0
        val vm =
            HomeLaunchViewModel(
                app,
                {
                  launches++
                  DolphinLauncher.LaunchResult.AutoStarted
                },
                dispatcher,
            )
        vm.requestLaunch(isChecking = false, beginSession = {})
        assertThat(vm.prompt.value).isEqualTo(LaunchPrompt.GameIniNotice)
        vm.dismissPrompt()
        vm.requestLaunch(isChecking = false, beginSession = {})
        assertThat(vm.prompt.value).isEqualTo(LaunchPrompt.GameIniNotice)
        assertThat(launches).isEqualTo(0)

        vm.confirmLaunch {}
        val next =
            HomeLaunchViewModel(
                app,
                {
                  launches++
                  DolphinLauncher.LaunchResult.AutoStarted
                },
                dispatcher,
            )
        next.requestLaunch(isChecking = false, beginSession = {})

        assertThat(next.prompt.value).isNull()
        assertThat(launches).isEqualTo(2)
      }

  @Test
  fun `check override launches without acknowledging the first launch notice`() = runTest {
    var launches = 0
    val vm =
        HomeLaunchViewModel(
            app,
            {
              launches++
              DolphinLauncher.LaunchResult.AutoStarted
            },
            dispatcher,
        )
    vm.requestLaunch(isChecking = true, beginSession = {})
    assertThat(vm.prompt.value).isEqualTo(LaunchPrompt.CheckInProgress)
    assertThat(launches).isEqualTo(0)

    vm.confirmLaunch {}
    vm.requestLaunch(isChecking = false, beginSession = {})

    assertThat(launches).isEqualTo(1)
    assertThat(vm.prompt.value).isEqualTo(LaunchPrompt.GameIniNotice)
  }

  @Test
  fun `repeated taps cannot launch or mark another session while launching`() = runTest {
    noticeShown = true
    val result = CompletableDeferred<DolphinLauncher.LaunchResult>()
    var launches = 0
    var sessions = 0
    val vm =
        HomeLaunchViewModel(
            app,
            {
              launches++
              result.await()
            },
            dispatcher,
        )

    vm.requestLaunch(isChecking = false, beginSession = { sessions++ })
    vm.requestLaunch(isChecking = false, beginSession = { sessions++ })
    vm.requestLaunch(isChecking = true, beginSession = { sessions++ })
    result.complete(DolphinLauncher.LaunchResult.AutoStarted)

    assertThat(launches).isEqualTo(1)
    assertThat(sessions).isEqualTo(1)
    assertThat(vm.prompt.value).isNull()
  }

  @Test
  fun `launch failure remains available until the screen consumes it`() = runTest {
    noticeShown = true
    every { app.getString(R.string.home_launch_no_rom) } returns "no ROM"
    val vm = HomeLaunchViewModel(app, { DolphinLauncher.LaunchResult.NoRom }, dispatcher)

    vm.requestLaunch(isChecking = false, beginSession = {})
    val message = async { vm.messages.first() }

    assertThat(message.await()).isEqualTo("no ROM")
  }
}
