package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mutually exclusive confirmations before launching Dolphin. */
enum class LaunchPrompt {
  CheckInProgress,
  GameIniNotice,
}

/**
 * Owns launch confirmations and the launch job independently of HomeScreen composition. The UI
 * renders [prompt] and consumes [messages]; session marking runs on main before Dolphin I/O.
 */
class HomeLaunchViewModel(
    application: Application,
    private val launchGame: suspend () -> DolphinLauncher.LaunchResult = {
      DolphinLauncher.launchRetroRewind(application)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
  private val prefs = Prefs.main(application)
  private val _prompt = MutableStateFlow<LaunchPrompt?>(null)
  val prompt = _prompt.asStateFlow()
  private val messageEvents = Channel<String>(Channel.BUFFERED)
  val messages = messageEvents.receiveAsFlow()
  private var launchJob: Job? = null

  fun requestLaunch(isChecking: Boolean, beginSession: () -> Unit) {
    if (launchJob?.isActive == true || _prompt.value != null) return
    when {
      isChecking -> _prompt.value = LaunchPrompt.CheckInProgress
      !prefs.getBoolean(PrefsKeys.GAME_INI_NOTICE_SHOWN_KEY, false) ->
          _prompt.value = LaunchPrompt.GameIniNotice
      else -> launch(beginSession)
    }
  }

  /** Overriding a running check launches without acknowledging the separate Game.ini notice. */
  fun confirmLaunch(beginSession: () -> Unit) {
    val confirmation = _prompt.value ?: return
    if (confirmation == LaunchPrompt.GameIniNotice) {
      prefs.edit().putBoolean(PrefsKeys.GAME_INI_NOTICE_SHOWN_KEY, true).apply()
    }
    _prompt.value = null
    launch(beginSession)
  }

  fun dismissPrompt() {
    _prompt.value = null
  }

  private fun launch(beginSession: () -> Unit) {
    if (launchJob?.isActive == true) return
    launchJob = viewModelScope.launch {
      beginSession()
      val result = withContext(ioDispatcher) { launchGame() }
      val message =
          when (result) {
            DolphinLauncher.LaunchResult.AutoStarted -> null
            is DolphinLauncher.LaunchResult.FallbackStarted -> R.string.home_launch_fallback
            DolphinLauncher.LaunchResult.DolphinNotInstalled ->
                R.string.home_launch_dolphin_not_installed
            DolphinLauncher.LaunchResult.StorageNotConfigured ->
                R.string.error_storage_not_configured
            DolphinLauncher.LaunchResult.NoRom -> R.string.home_launch_no_rom
          }
      if (message != null) messageEvents.send(getApplication<Application>().getString(message))
    }
  }

  companion object {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
      initializer {
        HomeLaunchViewModel(
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        )
      }
    }
  }
}
