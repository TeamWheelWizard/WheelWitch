package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.data.ExtractingPhase
import com.skiletro.wheelwitch.domain.RewindPackManager
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.model.ServerInfo
import com.skiletro.wheelwitch.util.io.DownloadProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
class PackUpdateViewModelTest {

  private val context: Application = mockk(relaxed = true)
  private val manager: RewindPackManager = mockk(relaxed = true)

  @BeforeEach
  fun setUp() {
    // viewModelScope uses Dispatchers.Main.immediate; provide a
    // UnconfinedTestDispatcher so launched coroutines run on the
    // calling thread (synchronously inside the test).
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterEach
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() =
    PackUpdateViewModel(
      application = context,
      managerFactory = { manager },
    )

  // --- init / checkStatus --------------------------------------------

  @Test
  fun `init triggers checkStatus and lands on Ready when manager returns a status`() = runTest {
    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled

    val vm = viewModel()

    // UnconfinedTestDispatcher runs the init coroutine synchronously
    // on the calling thread, so the state has already settled to Ready.
    assertThat(vm.state.value).isEqualTo(UiState.Ready(PackStatus.NotInstalled))
  }

  @Test
  fun `init falls back to NotInstalled when manager is null`() = runTest {
    val vm =
      PackUpdateViewModel(
        application = context,
        managerFactory = { null },
      )

    assertThat(vm.state.value).isEqualTo(UiState.Ready(PackStatus.NotInstalled))
  }

  @Test
  fun `checkStatus transitions through Checking then Ready`() = runTest {
    coEvery { manager.checkStatus() } returns
      PackStatus.UpToDate(SemVersion(3, 2, 6), SemVersion(3, 2, 6))
    val vm = viewModel()

    vm.checkStatus()

    assertThat(vm.state.value).isEqualTo(
      UiState.Ready(PackStatus.UpToDate(SemVersion(3, 2, 6), SemVersion(3, 2, 6)))
    )
  }

  @Test
  fun `checkStatus surfaces manager exceptions as Error`() = runTest {
    coEvery { manager.checkStatus() } throws IllegalStateException("boom")
    val vm = viewModel()

    assertThat(vm.state.value).isInstanceOf(UiState.Error::class.java)
    assertThat((vm.state.value as UiState.Error).message).contains("boom")
  }

  // --- installLatest --------------------------------------------------

  @Test
  fun `installLatest transitions through Installing then Installed then back to Ready`() = runTest {
    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled
    coEvery { manager.installLatest(any()) } returns Result.success(Unit)
    val vm = viewModel()

    vm.installLatest()

    // After success the VM auto-calls checkStatus, which lands on
    // Ready(NotInstalled) (the only stubbed response).
    assertThat(vm.state.value).isEqualTo(UiState.Ready(PackStatus.NotInstalled))
    coVerify(exactly = 1) { manager.installLatest(any()) }
  }

  @Test
  fun `installLatest surfaces manager failure as Error`() = runTest {
    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled
    coEvery { manager.installLatest(any()) } returns Result.failure(IllegalStateException("net"))
    val vm = viewModel()

    vm.installLatest()

    assertThat(vm.state.value).isInstanceOf(UiState.Error::class.java)
    assertThat((vm.state.value as UiState.Error).message).contains("net")
  }

  @Test
  fun `installLatest reports storage not configured when manager is null`() = runTest {
    every { context.getString(R.string.vm_storage_not_configured_full) } returns
      "Storage not configured"
    val vm =
      PackUpdateViewModel(
        application = context,
        managerFactory = { null },
      )

    vm.installLatest()

    val state = vm.state.value
    assertThat(state).isInstanceOf(UiState.Error::class.java)
    assertThat((state as UiState.Error).message).contains("Storage")
  }

  // --- update ---------------------------------------------------------

  @Test
  fun `update routes to incremental when manager has an update to apply`() = runTest {
    coEvery { manager.checkStatus() } returns
      PackStatus.UpdateAvailable(SemVersion(3, 2, 5), SemVersion(3, 2, 6), serverInfo())
    coEvery { manager.update(any()) } returns Result.success(Unit)
    val vm = viewModel()

    vm.update()

    coVerify(exactly = 1) { manager.update(any()) }
    coVerify(exactly = 0) { manager.installLatest(any()) }
  }

  // --- reinstall ------------------------------------------------------

  @Test
  fun `reinstall transitions through Installing then Installed then back to Ready`() = runTest {
    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled
    coEvery { manager.reinstall(any()) } returns Result.success(Unit)
    val vm = viewModel()

    vm.reinstall()

    assertThat(vm.state.value).isEqualTo(UiState.Ready(PackStatus.NotInstalled))
    coVerify(exactly = 1) { manager.reinstall(any()) }
  }

  @Test
  fun `reinstall surfaces manager failure as Error`() = runTest {
    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled
    coEvery { manager.reinstall(any()) } returns Result.failure(IllegalStateException("net"))
    val vm = viewModel()

    vm.reinstall()

    assertThat(vm.state.value).isInstanceOf(UiState.Error::class.java)
    assertThat((vm.state.value as UiState.Error).message).contains("net")
  }

  @Test
  fun `reinstall reports storage not configured when manager is null`() = runTest {
    every { context.getString(R.string.vm_storage_not_configured_full) } returns
      "Storage not configured"
    val vm =
      PackUpdateViewModel(
        application = context,
        managerFactory = { null },
      )

    vm.reinstall()

    val state = vm.state.value
    assertThat(state).isInstanceOf(UiState.Error::class.java)
    assertThat((state as UiState.Error).message).contains("Storage")
  }

  // --- clearError ----------------------------------------------------

  @Test
  fun `clearError from Error re-checks status`() = runTest {
    coEvery { manager.checkStatus() } throws IllegalStateException("boom")
    val vm = viewModel()
    assertThat(vm.state.value).isInstanceOf(UiState.Error::class.java)

    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled
    vm.clearError()

    assertThat(vm.state.value).isEqualTo(UiState.Ready(PackStatus.NotInstalled))
  }

  @Test
  fun `clearError from Ready is a no-op`() = runTest {
    coEvery { manager.checkStatus() } returns
      PackStatus.UpToDate(SemVersion(3, 2, 6), SemVersion(3, 2, 6))
    val vm = viewModel()
    val stateBefore = vm.state.value
    coVerify(exactly = 1) { manager.checkStatus() }

    vm.clearError()

    assertThat(vm.state.value).isEqualTo(stateBefore)
    // No additional checkStatus call.
    coVerify(exactly = 1) { manager.checkStatus() }
  }

  // --- installProgress ------------------------------------------------

  @Test
  fun `installProgress emits null when no install is in flight`() = runTest {
    val vm = viewModel()

    assertThat(vm.installProgress.value).isNull()
  }

  @Test
  fun `installProgress clears to null after install completes`() = runTest {
    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled
    coEvery { manager.installLatest(any()) } answers
      {
        val cb = firstArg<(RewindPackManager.InstallProgress) -> Unit>()
        cb(RewindPackManager.InstallProgress.Downloading(DownloadProgress(0.5f, 1024L, 512L, 1024L)))
        Result.success(Unit)
      }
    val vm = viewModel()

    vm.installLatest()

    // After the install completes, the VM auto-calls checkStatus
    // and lands on Ready; installProgress is the derived flow of
    // (it as? Installing.Downloading)?.progress, so it is null when
    // state is no longer Downloading.
    assertThat(vm.installProgress.value).isNull()
  }

  @Test
  fun `installProgress is null during Extracting phase`() = runTest {
    coEvery { manager.checkStatus() } returns PackStatus.NotInstalled
    coEvery { manager.installLatest(any()) } answers
      {
        val cb = firstArg<(RewindPackManager.InstallProgress) -> Unit>()
        cb(
          RewindPackManager.InstallProgress.Extracting(
            phase = ExtractingPhase.PreparingFolders,
            filesDone = 0,
            filesTotal = 0,
            currentFile = null,
            bytesDone = 0L,
            bytesTotal = 0L,
          )
        )
        Result.success(Unit)
      }
    val vm = viewModel()

    vm.installLatest()

    assertThat(vm.installProgress.value).isNull()
  }

  // --- refreshManager ------------------------------------------------

  @Test
  fun `refreshManager re-creates the manager and re-runs checkStatus`() = runTest {
    // Simulate the fresh-install / cleared-pref state: the first
    // factory call returns null (manager is null), then onboarding
    // completes and the composition root calls refreshManager(),
    // which runs the factory a second time and gets a real manager.
    val firstManager: RewindPackManager? = null
    val secondManager: RewindPackManager = mockk(relaxed = true)
    coEvery { secondManager.checkStatus() } returns
      PackStatus.UpToDate(SemVersion(3, 2, 6), SemVersion(3, 2, 6))

    var factoryCalls = 0
    val factory: (android.content.Context) -> RewindPackManager? = {
      factoryCalls++
      if (factoryCalls == 1) firstManager else secondManager
    }
    val vm =
      PackUpdateViewModel(
        application = context,
        managerFactory = factory,
      )

    // The first init { checkStatus() } ran with the null manager.
    assertThat(vm.state.value).isEqualTo(UiState.Ready(PackStatus.NotInstalled))
    coVerify(exactly = 0) { secondManager.checkStatus() }

    vm.refreshManager()

    // After refresh, the cached manager is the new one and a
    // fresh checkStatus has run against it.
    assertThat(factoryCalls).isEqualTo(2)
    coVerify(exactly = 1) { secondManager.checkStatus() }
    assertThat(vm.state.value)
      .isEqualTo(UiState.Ready(PackStatus.UpToDate(SemVersion(3, 2, 6), SemVersion(3, 2, 6))))
  }

  private fun serverInfo() =
    ServerInfo(
      latestVersion = SemVersion(3, 2, 6),
      allUpdates = emptyList(),
      deletions = emptyList(),
    )
}