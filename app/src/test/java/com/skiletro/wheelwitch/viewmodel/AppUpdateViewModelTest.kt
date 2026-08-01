package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.SemVersion
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
class AppUpdateViewModelTest {

    private val context: Application = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        // viewModelScope uses Dispatchers.Main.immediate; provide an
        // UnconfinedTestDispatcher so the init coroutine runs synchronously.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        enabled: Boolean = true,
        currentVersion: SemVersion? = SemVersion(0, 301, 0),
        fetcher: suspend () -> Result<SemVersion> = { Result.success(SemVersion(0, 302, 0)) },
    ) =
        AppUpdateViewModel(
            application = context,
            enabled = enabled,
            currentVersion = currentVersion,
            fetcher = fetcher,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    @Test
    fun `init lands on UpdateAvailable and shows the dialog when latest is newer`() = runTest {
        val vm =
            viewModel(
                currentVersion = SemVersion(0, 301, 0),
                fetcher = { Result.success(SemVersion(0, 302, 0)) },
            )

        assertThat(vm.state.value)
            .isEqualTo(
                AppUpdateState.UpdateAvailable(SemVersion(0, 301, 0), SemVersion(0, 302, 0))
            )
        assertThat(vm.dialogVisible.value).isTrue()
    }

    @Test
    fun `init lands on UpToDate when latest matches current`() = runTest {
        val vm =
            viewModel(
                currentVersion = SemVersion(0, 301, 0),
                fetcher = { Result.success(SemVersion(0, 301, 0)) },
            )

        assertThat(vm.state.value).isEqualTo(AppUpdateState.UpToDate(SemVersion(0, 301, 0)))
        assertThat(vm.dialogVisible.value).isFalse()
    }

    @Test
    fun `init lands on UpToDate when current is newer than latest`() = runTest {
        val vm =
            viewModel(
                currentVersion = SemVersion(0, 302, 0),
                fetcher = { Result.success(SemVersion(0, 301, 0)) },
            )

        assertThat(vm.state.value).isEqualTo(AppUpdateState.UpToDate(SemVersion(0, 302, 0)))
        assertThat(vm.dialogVisible.value).isFalse()
    }

    @Test
    fun `init lands on CheckFailed when the fetch fails`() = runTest {
        val vm =
            viewModel(
                fetcher = { Result.failure(IllegalStateException("offline")) },
            )

        assertThat(vm.state.value).isEqualTo(AppUpdateState.CheckFailed)
        assertThat(vm.dialogVisible.value).isFalse()
    }

    @Test
    fun `init skips the check when disabled`() = runTest {
        var calls = 0
        val vm =
            viewModel(
                enabled = false,
                fetcher = {
                    calls++
                    Result.success(SemVersion(0, 302, 0))
                },
            )

        assertThat(vm.state.value).isEqualTo(AppUpdateState.CheckFailed)
        assertThat(calls).isEqualTo(0)
        assertThat(vm.dialogVisible.value).isFalse()
    }

    @Test
    fun `init lands on CheckFailed when the current version is unparseable`() = runTest {
        val vm = viewModel(currentVersion = null)

        assertThat(vm.state.value).isEqualTo(AppUpdateState.CheckFailed)
    }

    @Test
    fun `dismissDialog hides the dialog but keeps the UpdateAvailable state`() = runTest {
        val vm =
            viewModel(
                currentVersion = SemVersion(0, 301, 0),
                fetcher = { Result.success(SemVersion(0, 302, 0)) },
            )
        assertThat(vm.dialogVisible.value).isTrue()

        vm.dismissDialog()

        assertThat(vm.dialogVisible.value).isFalse()
        assertThat(vm.state.value)
            .isEqualTo(
                AppUpdateState.UpdateAvailable(SemVersion(0, 301, 0), SemVersion(0, 302, 0))
            )
    }
}
