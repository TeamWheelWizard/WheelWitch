package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelTest {

  private val app: Application = mockk(relaxed = true)

  @BeforeEach
  fun setUp() {
    // viewModelScope uses Dispatchers.Main.immediate; provide an
    // UnconfinedTestDispatcher so launched coroutines run on the
    // calling thread (synchronously inside the test).
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterEach
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel(
    logLoader: suspend (Context) -> String,
    clipboard: (String) -> Unit = {},
    ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
  ) =
    LogViewerViewModel(
      application = app,
      logLoader = logLoader,
      clipboard = clipboard,
      ioDispatcher = ioDispatcher,
    )

  // --- init / reload ---------------------------------------------------

  @Test
  fun `init loads the log text into state`() = runTest {
    val vm = viewModel(logLoader = { "Wheel Witch v1.0.0\nI/Tag: hello" })

    assertThat(vm.state.value.isLoading).isFalse()
    assertThat(vm.state.value.logText).contains("Wheel Witch v1.0.0")
    assertThat(vm.state.value.logText).contains("I/Tag: hello")
  }

  @Test
  fun `state stays loading until the loader completes`() = runTest {
    val gate = CompletableDeferred<String>()
    val vm =
      LogViewerViewModel(
        application = app,
        logLoader = { gate.await() },
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
      )

    // The loader is suspended waiting on the gate, so the initial
    // snapshot still reports the in-flight loading state.
    assertThat(vm.state.value.isLoading).isTrue()

    gate.complete("loaded")

    assertThat(vm.state.value.isLoading).isFalse()
    assertThat(vm.state.value.logText).isEqualTo("loaded")
  }

  @Test
  fun `reload refreshes the log text from the loader`() = runTest {
    var text = "first snapshot"
    val vm = viewModel(logLoader = { text })

    assertThat(vm.state.value.logText).isEqualTo("first snapshot")

    text = "second snapshot"
    vm.reload()

    assertThat(vm.state.value.logText).isEqualTo("second snapshot")
  }

  // --- copyToClipboard -------------------------------------------------

  @Test
  fun `copyToClipboard writes the text and bumps the copy trigger`() = runTest {
    val copied = mutableListOf<String>()
    val vm = viewModel(logLoader = { "log text" }, clipboard = { copied += it })
    val before = vm.state.value.copyRequest

    vm.copyToClipboard()

    assertThat(copied).containsExactly("log text")
    assertThat(vm.state.value.copyRequest).isEqualTo(before + 1)
  }
}
