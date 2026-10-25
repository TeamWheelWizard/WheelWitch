package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.data.DolphinPaths
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.GameTypeParser
import com.skiletro.wheelwitch.data.InvalidTreeReason
import com.skiletro.wheelwitch.data.InvalidTreeUriException
import com.skiletro.wheelwitch.util.io.readUpTo
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The five wizard steps, in navigation order. */
enum class OnboardingStep {
  Welcome,
  Dolphin,
  Storage,
  Rom,
  Complete;

  fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)
}

enum class OnboardingRomActivity {
  Idle,
  Scanning,
  Verifying,
  Copying,
}

/** A single ROM operation is active at a time; an existing ROM can also be selected for reuse. */
data class OnboardingRomState(
    val existingRomName: String? = null,
    val activity: OnboardingRomActivity = OnboardingRomActivity.Idle,
)

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val dolphinInstalled: Boolean = false,
    val hasCheckedDolphin: Boolean = false,
    val storageError: String? = null,
    val romError: String? = null,
    val rom: OnboardingRomState = OnboardingRomState(),
)

/**
 * Owns the in-memory Welcome → Dolphin → Storage → Rom → Complete wizard. State survives activity
 * recreation, but is not persisted across process death. Only the UI's Complete action marks
 * onboarding complete. The application is the only retained context.
 *
 * SAF validation/bootstrap, ROM scanning, header validation, region-aware copying and cover writes
 * run on [ioDispatcher]. Navigation cancels and invalidates the current operation: even a provider
 * that finishes blocking I/O after cancellation cannot publish into a newer step. Cancellation is
 * never translated into a user-facing error.
 */
class OnboardingViewModel(
    application: Application,
    private val treeFactory: (Context) -> DolphinTree? = { DolphinTree.fromPersisted(it) },
    private val dolphinCheck: (Context) -> Boolean = DolphinLauncher::isDolphinInstalled,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
  private val app = application
  private val _state = MutableStateFlow(OnboardingState())
  val state: StateFlow<OnboardingState> = _state.asStateFlow()

  private var work: Job? = null
  private var generation = 0L

  /** Selects Dolphin's provider when installed, otherwise the external-storage user folder. */
  val treeDeepLink: Uri
    get() =
        if (_state.value.dolphinInstalled) {
          DocumentsContract.buildTreeDocumentUri("org.dolphinemu.dolphinemu.user", "root")
        } else {
          DocumentsContract.buildTreeDocumentUri(
              "primary",
              "Android/data/${DolphinLauncher.DOLPHIN_PACKAGE}/files",
          )
        }

  fun next() {
    when (_state.value.step) {
      OnboardingStep.Welcome -> navigateTo(OnboardingStep.Dolphin)
      OnboardingStep.Dolphin ->
          if (_state.value.dolphinInstalled) navigateTo(OnboardingStep.Storage)
      else -> Unit
    }
  }

  fun back() {
    _state.value.step.previous()?.let(::navigateTo)
  }

  /** Starts a fresh wizard when Settings explicitly requests setup again. */
  fun restart() {
    cancelWork()
    _state.value = OnboardingState()
  }

  /** Called on step entry, browser return and manual re-check; never retains an activity. */
  fun checkDolphin() {
    if (_state.value.step != OnboardingStep.Dolphin) return
    _state.value = _state.value.copy(dolphinInstalled = dolphinCheck(app), hasCheckedDolphin = true)
  }

  fun clearStorageError() {
    _state.value = _state.value.copy(storageError = null)
  }

  fun clearRomError() {
    _state.value = _state.value.copy(romError = null)
  }

  fun selectStorage(uri: Uri?) {
    if (uri == null || _state.value.step != OnboardingStep.Storage) return
    cancelWork()
    val request = generation
    clearStorageError()
    work = viewModelScope.launch {
      try {
        withContext(ioDispatcher) {
          DolphinTree.validate(uri).getOrThrow()
          val tree = DolphinTree(app, uri)
          tree.persistUriPermission()
          DolphinTree.persist(app, tree)
          tree.wheelWitchDir // Bootstrap directories before entering the ROM step.
        }
        if (isCurrent(request, OnboardingStep.Storage)) navigateTo(OnboardingStep.Rom)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Storage setup failed")
        if (isCurrent(request, OnboardingStep.Storage)) {
          _state.value = _state.value.copy(storageError = storageError(e))
        }
      }
    }
  }

  fun selectRom(uri: Uri?) {
    if (uri == null || _state.value.step != OnboardingStep.Rom) return
    cancelWork()
    val request = generation
    _state.value =
        _state.value.copy(
            romError = null,
            rom = OnboardingRomState(activity = OnboardingRomActivity.Verifying),
        )
    work = viewModelScope.launch {
      var writingCover = false
      try {
        val tree = withContext(ioDispatcher) { treeFactory(app) }
        if (tree == null) {
          if (isCurrent(request, OnboardingStep.Rom)) {
            _state.value =
                _state.value.copy(romError = app.getString(R.string.onboarding_storage_required))
          }
          return@launch
        }
        val rom =
            withContext(ioDispatcher) {
              val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "rom.iso"
              val bytes =
                  app.contentResolver.openInputStream(uri)?.use { it.readUpTo(ROM_HEADER_BYTES) }
                      ?: throw IOException("Cannot open $uri")
              if (!GameTypeParser.checkValidity(name, bytes)) null
              else {
                val info = GameTypeParser.parseGameInfo(name, bytes)
                (info.gameId ?: "RMCP01") to File(name).extension.ifEmpty { "iso" }.lowercase()
              }
            }
        if (!isCurrent(request, OnboardingStep.Rom)) return@launch
        if (rom == null) {
          _state.value =
              _state.value.copy(romError = app.getString(R.string.onboarding_iso_invalid))
          return@launch
        }
        _state.value =
            _state.value.copy(rom = OnboardingRomState(activity = OnboardingRomActivity.Copying))
        withContext(ioDispatcher) { tree.copyRomFromSource(uri, rom.first, rom.second) }
        writingCover = true
        withContext(ioDispatcher) { tree.writeRrCover() }
        if (isCurrent(request, OnboardingStep.Rom)) navigateTo(OnboardingStep.Complete)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, if (writingCover) "Metadata write failed" else "ROM import failed")
        if (isCurrent(request, OnboardingStep.Rom)) {
          val fallback =
              if (writingCover) R.string.onboarding_metadata_write_failed
              else R.string.onboarding_iso_copy_failed
          _state.value = _state.value.copy(romError = e.message ?: app.getString(fallback))
        }
      } finally {
        finishRomWork(request)
      }
    }
  }

  /** Reuse a validated existing ROM, writing the same cover as a newly copied ROM. */
  fun confirmExistingRom() {
    val current = _state.value
    if (
        current.step != OnboardingStep.Rom ||
            current.rom.existingRomName == null ||
            current.rom.activity != OnboardingRomActivity.Idle
    )
        return
    cancelWork()
    val request = generation
    _state.value =
        current.copy(
            romError = null,
            rom = current.rom.copy(activity = OnboardingRomActivity.Copying),
        )
    work = viewModelScope.launch {
      try {
        val tree = withContext(ioDispatcher) { treeFactory(app) }
        if (tree == null) {
          if (isCurrent(request, OnboardingStep.Rom)) {
            _state.value =
                _state.value.copy(romError = app.getString(R.string.onboarding_storage_required))
          }
          return@launch
        }
        withContext(ioDispatcher) { tree.writeRrCover() }
        if (isCurrent(request, OnboardingStep.Rom)) navigateTo(OnboardingStep.Complete)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Cover write failed on ROM confirm")
        if (isCurrent(request, OnboardingStep.Rom)) {
          _state.value =
              _state.value.copy(
                  romError = e.message ?: app.getString(R.string.onboarding_metadata_write_failed),
              )
        }
      } finally {
        finishRomWork(request)
      }
    }
  }

  private fun navigateTo(step: OnboardingStep) {
    cancelWork()
    _state.value =
        _state.value.copy(
            step = step,
            storageError = null,
            romError = null,
            rom = OnboardingRomState(),
        )
    when (step) {
      OnboardingStep.Dolphin -> checkDolphin()
      OnboardingStep.Rom -> scanExistingRom()
      else -> Unit
    }
  }

  private fun scanExistingRom() {
    val request = generation
    _state.value =
        _state.value.copy(rom = OnboardingRomState(activity = OnboardingRomActivity.Scanning))
    work = viewModelScope.launch {
      try {
        val name =
            withContext(ioDispatcher) {
              val tree = treeFactory(app) ?: return@withContext null
              tree.romDir
                  .listFiles()
                  .firstOrNull { file ->
                    ensureActive()
                    val name = file.name ?: return@firstOrNull false
                    if (
                        File(name).extension.uppercase() !in ROM_EXTENSIONS ||
                            !file.isFile ||
                            !file.exists()
                    ) {
                      return@firstOrNull false
                    }
                    val bytes =
                        tree.resolver.openInputStream(file.uri)?.use {
                          it.readUpTo(ROM_HEADER_BYTES)
                        }
                    bytes != null && GameTypeParser.checkValidity(name, bytes)
                  }
                  ?.name
            }
        if (isCurrent(request, OnboardingStep.Rom)) {
          _state.value = _state.value.copy(rom = OnboardingRomState(existingRomName = name))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.tag(TAG).w(e, "ROM scan failed")
      } finally {
        finishRomWork(request)
      }
    }
  }

  private fun cancelWork() {
    generation++
    work?.cancel()
    work = null
  }

  private fun isCurrent(request: Long, step: OnboardingStep): Boolean =
      request == generation && _state.value.step == step

  private fun finishRomWork(request: Long) {
    if (isCurrent(request, OnboardingStep.Rom)) {
      _state.value =
          _state.value.copy(rom = _state.value.rom.copy(activity = OnboardingRomActivity.Idle))
    }
  }

  private fun storageError(error: Exception): String =
      when (error) {
        is InvalidTreeUriException ->
            when (error.reason) {
              InvalidTreeReason.SubfolderExternal ->
                  app.getString(
                      R.string.onboarding_storage_subfolder_external_format,
                      DolphinPaths.expectedTreeId(),
                  )
              InvalidTreeReason.SubfolderInternal ->
                  app.getString(R.string.onboarding_storage_subfolder_internal)
              else -> app.getString(R.string.onboarding_storage_wrong_folder)
            }
        else -> error.message ?: app.getString(R.string.onboarding_storage_read_failed)
      }

  /** Best-effort SAF display name, leaving the URI/generic fallback to the import action. */
  private fun queryDisplayName(uri: Uri): String? = DocumentFile.fromSingleUri(app, uri)?.name

  companion object {
    private const val TAG = "Onboarding"
    private const val ROM_HEADER_BYTES = 4096
    private val ROM_EXTENSIONS = setOf("ISO", "RVZ", "WBFS")

    /** Resolves only the application from the screen's ViewModel creation extras. */
    val Factory: ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        OnboardingViewModel(app)
      }
    }
  }
}
