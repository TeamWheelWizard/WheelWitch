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
 * Owns the bug-report snapshot. [LogViewerUiState.copyRequest] increments on each copy so repeated
 * copies can retrigger the UI snackbar.
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

  /** Refreshes the report on construction and whenever the viewer opens. */
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
