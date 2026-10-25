package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.skiletro.wheelwitch.BuildConfig
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.network.GitHubReleaseParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** App-update check state; [AppUpdateState.CheckFailed] is silent in the UI. */
sealed interface AppUpdateState {
    /** The version check is in flight. */
    data object Checking : AppUpdateState

    /** The installed version matches (or exceeds) the latest GitHub release. */
    data class UpToDate(val currentVersion: SemVersion) : AppUpdateState

    /** A newer build exists on GitHub; the UI shows the update dialog. */
    data class UpdateAvailable(
        val currentVersion: SemVersion,
        val latestVersion: SemVersion,
    ) : AppUpdateState

    /** The check failed (offline, GitHub unreachable, or unparseable); stay quiet. */
    data object CheckFailed : AppUpdateState
}

/**
 * Checks for a newer GitHub release on construction and exposes dialog visibility.
 * Disabled by default in debug builds, whose local versions need not match CI releases.
 */
class AppUpdateViewModel(
    application: Application,
    private val enabled: Boolean = !BuildConfig.DEBUG,
    private val currentVersion: SemVersion? = defaultCurrentVersion(),
    private val fetcher: suspend () -> Result<SemVersion> = {
        GitHubReleaseParser.fetchLatestReleaseVersion()
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Checking)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** Retains dismissal when HomeScreen leaves composition to show Settings. */
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    init {
        check()
    }

    fun check() {
        viewModelScope.launch {
            if (!enabled || currentVersion == null) {
                _state.value = AppUpdateState.CheckFailed
                return@launch
            }
            _state.value = AppUpdateState.Checking
            val latest = withContext(ioDispatcher) { fetcher() }
            _state.value =
                latest.fold(
                    onSuccess = { latestVersion ->
                        if (latestVersion > currentVersion) {
                            Timber.tag(TAG)
                                .d(
                                    "app update available: v%s -> v%s",
                                    currentVersion,
                                    latestVersion,
                                )
                            _dialogVisible.value = true
                            AppUpdateState.UpdateAvailable(currentVersion, latestVersion)
                        } else {
                            AppUpdateState.UpToDate(currentVersion)
                        }
                    },
                    onFailure = { e ->
                        Timber.tag(TAG).w(e, "app version check failed")
                        AppUpdateState.CheckFailed
                    },
                )
        }
    }

    /** Hides the dialog until another successful check finds an update. */
    fun dismissDialog() {
        _dialogVisible.value = false
    }

    companion object {
        const val TAG = "AppUpdate"

        /** Current app version (`0.<commitCount>.0+<hash>`) without the build-metadata suffix. */
        private fun defaultCurrentVersion(): SemVersion? =
            SemVersion.parse(BuildConfig.VERSION_NAME.substringBefore("+"))

        /**
         * [ViewModelProvider.Factory] for the composition root; resolves the
         * [Application] from CreationExtras like [PackUpdateViewModel.Factory].
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                AppUpdateViewModel(app)
            }
        }
    }
}
