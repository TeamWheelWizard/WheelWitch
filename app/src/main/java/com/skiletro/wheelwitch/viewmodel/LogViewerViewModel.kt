package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.skiletro.wheelwitch.util.launcher.BugReportLauncher
import com.skiletro.wheelwitch.util.log.LogExporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state for the [LogViewerViewModel]. */
@Immutable
data class LogViewerUiState(
  val isLoading: Boolean = true,
  val logText: String = "",
  val copyRequest: Long = 0L,
)

/**
 * Owns the log-viewer state for the bug-report flow.
 *
 * [logText] is the full report rendered by [LogExporter.exportToString]
 * — the same text the "share as file" option exports — so the user
 * reads exactly what gets sent. [copyRequest] is incremented on every
 * copy so the screen can re-trigger its snackbar for repeated copies.
 *
 * Tests can swap the [logLoader], [clipboard], and [ioDispatcher] to
 * inject fakes without touching [LogExporter] / the clipboard service.
 */
class LogViewerViewModel(
  application: Application,
  private val logLoader: suspend (Context) -> String = { LogExporter.exportToString(it) },
  private val clipboard: (String) -> Unit = { text -> BugReportLauncher.copyToClipboard(application, text) },
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
  private val app = application

  private val _state = MutableStateFlow(LogViewerUiState())
  val state: StateFlow<LogViewerUiState> = _state.asStateFlow()

  init {
    reload()
  }

  /**
   * (Re-)renders the log report into [state]. Called from [init] and
   * again whenever the Log Viewer screen opens, so the snapshot is
   * fresh after new log entries have been captured.
   */
  fun reload() {
    viewModelScope.launch {
      _state.value = _state.value.copy(isLoading = true)
      val text = withContext(ioDispatcher) { logLoader(app) }
      _state.value = _state.value.copy(isLoading = false, logText = text)
    }
  }

  /** Copies the full log text to the clipboard and bumps [LogViewerUiState.copyRequest]. */
  fun copyToClipboard() {
    clipboard(state.value.logText)
    _state.value = _state.value.copy(copyRequest = _state.value.copyRequest + 1)
  }

  companion object {
    /**
     * [ViewModelProvider.Factory] for the composition root. The default
     * [ViewModelProvider] for [AndroidViewModel] only handles a single
     * `(Application)` constructor, so the injected [logLoader] /
     * [clipboard] parameters require a custom factory.
     */
    val Factory: ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val app =
          this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        LogViewerViewModel(app)
      }
    }
  }
}
