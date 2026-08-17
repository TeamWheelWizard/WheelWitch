package com.skiletro.wheelwitch.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skiletro.wheelwitch.ui.theme.AppTheme
import com.skiletro.wheelwitch.ui.theme.ThemeMode
import com.skiletro.wheelwitch.model.DolphinVersion
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import com.skiletro.wheelwitch.viewmodel.AppUpdateViewModel
import com.skiletro.wheelwitch.viewmodel.LogViewerViewModel
import com.skiletro.wheelwitch.viewmodel.MiiMakerViewModel
import com.skiletro.wheelwitch.viewmodel.OnlineViewModel
import com.skiletro.wheelwitch.viewmodel.PackUpdateViewModel
import com.skiletro.wheelwitch.viewmodel.SaveDataViewModel

/** Duration of the onboarding fade-in/out crossfade. */
private const val ONBOARDING_TRANSITION_MS = 300

/**
 * Top-level composable: chooses between the onboarding wizard and the
 * home / settings overlay stack.
 */
@Composable
fun MainScreen(
  packUpdate: PackUpdateViewModel = viewModel(factory = PackUpdateViewModel.Factory),
  miiMaker: MiiMakerViewModel = viewModel(),
  onlineViewModel: OnlineViewModel = viewModel(),
  saveData: SaveDataViewModel = viewModel(factory = SaveDataViewModel.factory(packUpdate)),
  logViewer: LogViewerViewModel = viewModel(factory = LogViewerViewModel.Factory),
  appUpdate: AppUpdateViewModel = viewModel(factory = AppUpdateViewModel.Factory),
  appTheme: AppTheme = AppTheme.Hex,
  onChangeAppTheme: (AppTheme) -> Unit = {},
  themeMode: ThemeMode = ThemeMode.System,
  onChangeThemeMode: (ThemeMode) -> Unit = {},
) {
  val context = LocalContext.current
  val onboardingPrefs = remember { Prefs.settings(context) }
  var onboardingComplete by remember {
    mutableStateOf(onboardingPrefs.getBoolean(PrefsKeys.ONBOARDING_COMPLETED_KEY, false))
  }

  var showSettings by remember { mutableStateOf(false) }
  var showLogViewer by remember { mutableStateOf(false) }
  var showDolphinOutdatedDialog by remember { mutableStateOf(false) }
  var outdatedDolphinVersion by remember { mutableStateOf<String?>(null) }
  var dolphinWarningChecked by remember { mutableStateOf(false) }

  LaunchedEffect(onboardingComplete) {
    if (!onboardingComplete || dolphinWarningChecked) return@LaunchedEffect
    dolphinWarningChecked = true
    val versionName = DolphinLauncher.dolphinVersionName(context)
    val version = DolphinVersion.parse(versionName)
    if (version != null && !version.isAtLeastMinimum()) {
      outdatedDolphinVersion = versionName
      showDolphinOutdatedDialog = true
    }
  }

  BackHandler(enabled = showSettings) {
    showSettings = false
  }

  BackHandler(enabled = showLogViewer) {
    showLogViewer = false
  }

  Box(Modifier.fillMaxSize()) {
    AnimatedContent(
      targetState = onboardingComplete,
      transitionSpec = {
        fadeIn(tween(ONBOARDING_TRANSITION_MS)) togetherWith
          fadeOut(tween(ONBOARDING_TRANSITION_MS))
      },
      label = "onboarding_transition",
    ) { completed ->
      if (completed) {
        // HomeScreen is removed from composition when settings is open (if-guard);
        // SettingsScreen uses AnimatedVisibility so it can slide in/out.
        if (!showSettings) {
          HomeScreen(
            packUpdate = packUpdate,
            miiMaker = miiMaker,
            onlineViewModel = onlineViewModel,
            saveData = saveData,
            appUpdate = appUpdate,
            onOpenSettings = { showSettings = true },
            showDolphinOutdatedDialog = showDolphinOutdatedDialog,
            outdatedDolphinVersion = outdatedDolphinVersion,
            onDismissDolphinOutdatedDialog = { showDolphinOutdatedDialog = false },
          )
        }

        AnimatedVisibility(
          visible = showSettings && !showLogViewer,
          enter = slideInVertically() + fadeIn(),
          exit = slideOutVertically() + fadeOut(),
        ) {
          SettingsScreen(
            packUpdate = packUpdate,
            miiMaker = miiMaker,
            saveData = saveData,
            onClose = { showSettings = false },
            onOpenLogViewer = { showLogViewer = true },
            appTheme = appTheme,
            onChangeAppTheme = onChangeAppTheme,
            themeMode = themeMode,
            onChangeThemeMode = onChangeThemeMode,
            onRelaunchOnboarding = {
              onboardingPrefs
                .edit()
                .putBoolean(PrefsKeys.ONBOARDING_COMPLETED_KEY, false)
                .apply()
              onboardingComplete = false
              showSettings = false
            },
          )
        }

        AnimatedVisibility(
          visible = showLogViewer,
          enter = slideInVertically() + fadeIn(),
          exit = slideOutVertically() + fadeOut(),
        ) {
          LogViewerScreen(
            viewModel = logViewer,
            onClose = { showLogViewer = false },
          )
        }
      } else {
        OnboardingScreen(
          onComplete = {
            onboardingPrefs
              .edit()
              .putBoolean(PrefsKeys.ONBOARDING_COMPLETED_KEY, true)
              .apply()
            onboardingComplete = true
            // The VM was constructed before any tree URI was
            // persisted, so its cached manager is null. Now that
            // onboarding has written a valid URI to prefs, ask
            // the VM to re-evaluate and run a fresh checkStatus.
            packUpdate.refreshManager()
          },
        )
      }
    }
  }
}
