package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.util.mii.MiiWadInstaller
import com.skiletro.wheelwitch.util.net.isNetworkAvailable
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiiMakerViewModelTest {

  private val application: Application = mockk(relaxed = true)

  @BeforeEach
  fun setUp() {
    mockkObject(MiiWadInstaller)
    every { MiiWadInstaller.getCachedWadFile(application) } returns null
  }

  @AfterEach
  fun tearDown() {
    unmockkObject(MiiWadInstaller)
    Dispatchers.resetMain()
  }

  @Test
  fun `launch cancellation does not become a user-facing error`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val wad = File("cached.wad")
    every { MiiWadInstaller.getCachedWadFile(application) } returns wad
    every { MiiWadInstaller.launchWadFile(application, wad) } throws
        CancellationException("launch cancelled")
    val vm = MiiMakerViewModel(application, dispatcher)

    vm.launchMiiMaker()
    advanceUntilIdle()

    assertThat(vm.miiMakerError.value).isNull()
  }

  @Test
  fun `install cancellation resets progress without a user-facing error`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    mockkStatic("com.skiletro.wheelwitch.util.net.NetworkExtensionsKt")
    every { application.isNetworkAvailable() } returns true
    coEvery { MiiWadInstaller.downloadAndExtractWad(application) } throws
        CancellationException("download cancelled")
    val vm = MiiMakerViewModel(application, dispatcher)

    try {
      vm.installMiiMakerWad()
      advanceUntilIdle()

      assertThat(vm.isInstallingWad.value).isFalse()
      assertThat(vm.miiMakerError.value).isNull()
    } finally {
      unmockkStatic("com.skiletro.wheelwitch.util.net.NetworkExtensionsKt")
    }
  }

  @Test
  fun `companion factory constructs the view model from application extras`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val extras = MutableCreationExtras()
    extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application

    val store = ViewModelStore()
    val vm = MiiMakerViewModel.Factory.create(MiiMakerViewModel::class.java, extras)
    store.put("vm", vm)

    assertThat(vm).isInstanceOf(MiiMakerViewModel::class.java)

    // Cancel the viewModelScope before the main dispatcher is reset so no
    // coroutine outlives the test into teardown.
    store.clear()
  }
}
