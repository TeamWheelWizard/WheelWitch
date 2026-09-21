package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.ExtractingPhase
import com.skiletro.wheelwitch.domain.RewindPackManager
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.util.io.DownloadProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Orchestrates the install/update flow for the Retro Rewind Pack.
 *
 * Loads the [RewindPackManager] from the persisted [DolphinTree] in
 * [init] and runs an initial [checkStatus]. Exposes the install /
 * update actions to the UI; both are guarded by an [installMutex]
 * so a double-tap on the install button cannot trigger parallel
 * downloads.
 *
 * The state machine (`UiState`) cycles through
 * `Idle → Checking → Ready → Installing → Installed → Checking → Ready`
 * on a successful install. Failure paths land in [UiState.Error] and
 * can be retried via [clearError].
 *
 * Tests can swap the [managerFactory] to inject a mock
 * [RewindPackManager] without going through SAF.
 */
class PackUpdateViewModel(
  application: Application,
  private val managerFactory: (Context) -> RewindPackManager? = ::defaultManagerFactory,
) : AndroidViewModel(application) {
  // Cached at construction, replaced by [refreshManager] after the
  // composition root re-runs onboarding. The var swap is intentional:
  // the manager depends on a persisted SAF tree URI which can be
  // written after the VM is created.
  private var manager: RewindPackManager? = managerFactory(application)
  private val installMutex = Mutex()

  private val _state = MutableStateFlow<UiState>(UiState.Idle)
  val state: StateFlow<UiState> = _state.asStateFlow()

  /**
   * Hot derivation of the in-flight download progress (fraction, byte
   * rate, byte counts) for the [HomeScreen] progress bar. Distinct from
   * [state] so the home screen doesn't re-compose the whole
   * `Scaffold` + `TopBar` + `Scaffold` content lambda on every 1%
   * progress callback. Emits null when not downloading.
   *
   * `distinctUntilChanged` coalesces duplicate progress events
   * (e.g. when the downloader is throttled to a sub-1% delta). The
   * downstream [HomeBottomBar] only collects this flow.
   */
  val installProgress: StateFlow<DownloadProgress?> =
    _state
      .map { (it as? UiState.Installing.Downloading)?.progress }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  init {
    checkStatus()
  }

  /** Single read-through so the `var` swap is intentional. */
  private fun currentManager(): RewindPackManager? = manager

  /**
   * Re-creates the manager from the current persisted tree URI and
   * re-runs [checkStatus]. Call from the composition root after a
   * successful onboarding flow so the cached (possibly null) manager
   * is replaced with the new tree URI. No-op if the factory still
   * returns null (the user cancelled the SAF picker or the URI was
   * cleared by a previous fromPersisted failure).
   */
  fun refreshManager() {
    manager = managerFactory(getApplication())
    checkStatus()
  }

  /**
   * Re-checks the local version against the server manifest. Emits
   * [UiState.Checking] then [UiState.Ready] with the resolved
   * [PackStatus], or [UiState.Error] on failure. When no pack
   * storage is configured (no persisted tree URI), the resolved
   * status is [PackStatus.NotInstalled]. The user is routed to
   * onboarding by the UI.
   */
  fun checkStatus() {
    viewModelScope.launch {
      val previous = (_state.value as? UiState.Ready)?.status
      _state.value = UiState.Checking(previous)
      if (currentManager() == null) {
        Timber.tag(TAG)
          .w("checkStatus: manager is null; no persisted Dolphin tree; " +
            "treating as NotInstalled (user needs to run onboarding)")
      }
      val result =
        runCatching {
          currentManager()?.checkStatus() ?: PackStatus.NotInstalled
        }
      _state.value =
        result.fold(
          onSuccess = {
            Timber.tag(TAG).d("checkStatus -> %s", it::class.simpleName)
            UiState.Ready(it)
          },
          onFailure = { e ->
            Timber.tag(TAG).e(e, "checkStatus failed")
            UiState.Error(friendlyErrorMessage(e))
          },
        )
    }
  }

  /**
   * Downloads and extracts the full pack zip. Guarded by
   * [installMutex]. On success, briefly emits [UiState.Installed]
   * and then auto-calls [checkStatus] to transition to [UiState.Ready]
   * with the new status.
   */
  fun installLatest() {
    runInstall("installLatest") {
      this.installLatest { phase -> _state.value = phase.toUiState() }
    }
  }

  /**
   * Performs the smallest set of incremental updates needed. Falls
   * back to a full reinstall if the local version is missing.
   * Same [installMutex] guard as [installLatest]; calling both in
   * parallel is a no-op for the second caller.
   */
  fun update() {
    runInstall("update") {
      this.update { phase -> _state.value = phase.toUiState() }
    }
  }

  /**
   * Performs a fresh full install. Same [installMutex] guard as [installLatest]
   * and [update].
   */
  fun reinstall() {
    runInstall("reinstall") {
      this.reinstall { phase -> _state.value = phase.toUiState() }
    }
  }

  /**
   * Runs an install/update/reinstall against the current manager:
   * [installMutex]-guarded, fails to a "storage not configured"
   * error when no SAF grant exists, emits the initial extracting
   * state, then funnels the result through [handleInstallResult].
   */
  private fun runInstall(
    opName: String,
    install: suspend RewindPackManager.() -> Result<Unit>,
  ) {
    viewModelScope.launch {
      installMutex.withLock {
        val mgr = currentManager()
        if (mgr == null) {
          Timber.tag(TAG)
            .e(
              "$opName: manager is null; DolphinTree.fromPersisted returned null. " +
                "User has no valid SAF grant; route to onboarding."
            )
          _state.value =
            UiState.Error(
              getApplication<Application>().getString(R.string.vm_storage_not_configured_full)
            )
          return@withLock
        }
        _state.value = extractingInitial()
        val result = mgr.install()
        handleInstallResult(result)
      }
    }
  }

  private fun extractingInitial(): UiState.Installing.Extracting =
    UiState.Installing.Extracting(
      phase = ExtractingPhase.PreparingFolders,
      filesDone = 0,
      filesTotal = 1,
      currentFile = null,
      bytesDone = 0L,
      bytesTotal = 0L,
    )

  /** Maps a [RewindPackManager.InstallProgress] phase to the corresponding [UiState.Installing] phase. */
  private fun RewindPackManager.InstallProgress.toUiState(): UiState.Installing =
    when (this) {
      is RewindPackManager.InstallProgress.Downloading ->
        UiState.Installing.Downloading(progress)
      is RewindPackManager.InstallProgress.Extracting ->
        UiState.Installing.Extracting(
          phase = phase,
          filesDone = filesDone,
          filesTotal = filesTotal,
          currentFile = currentFile,
          bytesDone = bytesDone,
          bytesTotal = bytesTotal,
        )
    }

  /** Clears an error state and re-checks status. No-op for non-error states. */
  fun clearError() {
    if (_state.value is UiState.Error) checkStatus()
  }

  private fun handleInstallResult(result: Result<Unit>) {
    result.fold(
      onSuccess = {
        _state.value = UiState.Installed
        // Briefly show Installed, then immediately re-check status so
        // the UI lands on the new Ready(UpToDate) state.
        viewModelScope.launch { checkStatus() }
      },
      onFailure = { e ->
        Timber.tag(TAG).e(e, "install/update failed")
        val friendly = friendlyErrorMessage(e)
        _state.value = UiState.Error(friendly)
      },
    )
  }

  /**
   * Maps a thrown exception to a user-facing message. Most app
   * exceptions have decent `message` text, but a few (notably
   * [android.os.NetworkOnMainThreadException] and the various
   * [java.io.IOException] subclasses thrown by OkHttp/SAF) just dump
   * the class name, which is meaningless in a toast. We surface the
   * most common cases explicitly; everything else falls through to
   * the exception's own message.
   */
  private fun friendlyErrorMessage(e: Throwable): String {
    val raw = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
    return when {
      e is android.os.NetworkOnMainThreadException ->
        getApplication<Application>().getString(R.string.vm_install_main_thread_error)
      e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ->
        getApplication<Application>().getString(R.string.vm_network_error)
      e is java.io.IOException && raw.contains("saf", ignoreCase = true) ->
        getApplication<Application>().getString(R.string.vm_storage_error_format, raw)
      else -> raw
    }
  }

  /**
   * Internal constants + the default [RewindPackManager] factory plus
   * the public [Factory] used by [androidx.lifecycle.viewmodel.compose.viewModel]
   * in the composition root. The default [ViewModelProvider] for
   * [AndroidViewModel] looks up a single-arg `(Application)`
   * constructor, which doesn't exist anymore. The second
   * `managerFactory` parameter requires a custom factory.
   */
  companion object {
    const val TAG = "PackUpdate"

    /** Default production factory: read the persisted SAF tree and wire up the manager. */
    fun defaultManagerFactory(context: Context): RewindPackManager? {
      val tree = DolphinTree.fromPersisted(context) ?: return null
      return RewindPackManager(tree = tree, cacheDir = context.cacheDir)
    }

    /**
     * [ViewModelProvider.Factory] for the composition root. Resolves
     * the [Application] from [ViewModelProvider]'s CreationExtras
     * (set by [androidx.lifecycle.viewmodel.compose.viewModel] via
     * the LocalViewModelStoreOwner) and constructs the VM with its
     * default manager factory.
     */
    val Factory: ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val app =
          this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        PackUpdateViewModel(app)
      }
    }
  }
}

