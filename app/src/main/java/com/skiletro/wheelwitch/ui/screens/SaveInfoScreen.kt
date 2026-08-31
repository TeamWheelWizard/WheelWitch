package com.skiletro.wheelwitch.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.ui.components.EmptySaveBody
import com.skiletro.wheelwitch.ui.components.LicenseGrid
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.viewmodel.SaveDataViewModel

/**
 * Licenses screen: 2x2 grid of the 4 license slots for the
 * currently selected region. Tapping a slot selects it (the active
 * license used elsewhere in the app). Region selection lives in
 * the Settings Save Data section; this screen is a pure viewer.
 *
 * The data lives in [SaveDataViewModel]; this composable just
 * observes it. [SaveDataViewModel.refresh] re-parses the save and
 * re-merges the leaderboard for the selected region's 4 slots
 * whenever the pack state transitions to Ready.
 *
 * Auto-refresh: a `DisposableEffect` listens for `Lifecycle.Event.ON_RESUME`
 * and triggers a refresh. The observer receives the current state on
 * subscription, so the first composition also refreshes (matching the
 * pattern used by [OnboardingScreen] for the Dolphin install check and
 * by [HomeScreen] for room refreshes).
 */
@Composable
fun SaveInfoScreen(viewModel: SaveDataViewModel, onClose: () -> Unit) {
  val selectedRegion by viewModel.selectedRegion.collectAsState()
  val mergedLicenses by viewModel.mergedLicenses.collectAsState()
  val scoreResults by viewModel.scoreResults.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val error by viewModel.error.collectAsState()

  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  BackHandler(onBack = onClose)

  LaunchedEffect(error) {
    val message = error
    if (message != null) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      viewModel.clearError()
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshIfStale()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
  ) {
    ScreenHeader(
      title = stringResource(R.string.save_info_title),
      onBack = onClose,
      onRefresh = viewModel::refresh,
    )

    val licenses = selectedRegion?.let { mergedLicenses[it] }
    if (selectedRegion == null || licenses == null) {
      EmptySaveBody(isLoading = isLoading)
    } else {
      LicenseGrid(
        licenses = licenses,
        scoreResults = scoreResults,
        isLoading = isLoading,
      )
    }
  }
}