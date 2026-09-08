package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.util.mii.MiiWadInstaller
import com.skiletro.wheelwitch.util.net.isNetworkAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the Mii Channel WAD install / launch / delete state.
 *
 * Exposes three pieces of state: [hasWad] (a cached WAD is present),
 * [isInstallingWad] (an install is in flight), and [miiMakerError]
 * (the last user-facing error message, if any).
 *
 * [installMiiMakerWad] is guarded by a [Mutex] so concurrent user taps
 * cannot trigger parallel installs.
 */
class MiiMakerViewModel(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    private val app = application
    private val _hasWad = MutableStateFlow(false)
    val hasWad: StateFlow<Boolean> = _hasWad.asStateFlow()

    private val _isInstallingWad = MutableStateFlow(false)
    val isInstallingWad: StateFlow<Boolean> = _isInstallingWad.asStateFlow()

    private val _miiMakerError = MutableStateFlow<String?>(null)
    val miiMakerError: StateFlow<String?> = _miiMakerError.asStateFlow()

    private val installMutex = Mutex()

    init {
        refreshHasWad()
    }

  /** Re-checks whether a cached WAD exists and updates [hasWad]. */
  fun refreshHasWad() {
    viewModelScope.launch(ioDispatcher) {
      _hasWad.value = MiiWadInstaller.getCachedWadFile(app) != null
    }
  }

    /** Launches Dolphin with the cached Mii Channel WAD, if one is present. */
    fun launchMiiMaker() {
        viewModelScope.launch {
            try {
                val cached = withContext(ioDispatcher) {
                    MiiWadInstaller.getCachedWadFile(app)
                }
                if (cached != null) {
                    MiiWadInstaller.launchWadFile(app, cached).getOrThrow()
                    Timber.tag("MiiMaker").i("Launched WAD: %s", cached.absolutePath)
                }
            } catch (e: Exception) {
                Timber.tag("MiiMaker").e(e, "Mii Maker launch failed")
                _miiMakerError.value =
                    e.message ?: app.getString(R.string.vm_failed_format, "launch Mii Maker")
            }
        }
    }

    /**
     * Downloads and extracts the Mii Channel WAD. No-op when there is no
     * network connection; in that case [miiMakerError] is populated.
     *
     * Guarded by [installMutex] so concurrent calls serialize.
     */
    fun installMiiMakerWad() {
        viewModelScope.launch {
            installMutex.withLock {
                _isInstallingWad.value = true
                _miiMakerError.value = null
                try {
                    if (!app.isNetworkAvailable()) {
                        Timber.tag("MiiMaker").w("Install aborted: no network")
                        _miiMakerError.value = app.getString(R.string.error_no_internet)
                        return@withLock
                    }
                    Timber.tag("MiiMaker").i("Starting WAD download/extract")
                    withContext(ioDispatcher) {
                        MiiWadInstaller.downloadAndExtractWad(app)
                    }
                    refreshHasWad()
                    Timber.tag("MiiMaker").i("WAD installed successfully")
                } catch (e: Exception) {
                    Timber.tag("MiiMaker").e(e, "WAD install failed")
                    _miiMakerError.value = e.message ?: app.getString(
                        R.string.vm_failed_format,
                        "install Mii Maker WAD"
                    )
                } finally {
                    _isInstallingWad.value = false
                }
            }
        }
    }

    /** Deletes the cached WAD and refreshes [hasWad]. */
    fun deleteWad() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                MiiWadInstaller.clearCache(app)
            }
            refreshHasWad()
            Timber.tag("MiiMaker").i("WAD cache cleared")
        }
    }

    companion object {
        /**
         * [ViewModelProvider.Factory] for the composition root. The default
         * [ViewModelProvider] for [AndroidViewModel] only handles a single
         * `(Application)` constructor, so the injected [ioDispatcher]
         * parameter requires a custom factory.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MiiMakerViewModel(app)
            }
        }
    }
}
