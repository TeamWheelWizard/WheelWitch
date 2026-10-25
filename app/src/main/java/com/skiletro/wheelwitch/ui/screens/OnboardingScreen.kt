package com.skiletro.wheelwitch.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.ui.components.PrimaryActionButton
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import com.skiletro.wheelwitch.ui.theme.buttonShape
import com.skiletro.wheelwitch.ui.theme.sectionShape
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import com.skiletro.wheelwitch.viewmodel.OnboardingRomActivity
import com.skiletro.wheelwitch.viewmodel.OnboardingStep
import com.skiletro.wheelwitch.viewmodel.OnboardingViewModel

/**
 * Renders the five-step onboarding wizard owned by [OnboardingViewModel]. The UI owns only SAF
 * activity-result launchers and browser opening; workflow state and storage/ROM I/O live in the
 * ViewModel. Resume notifications re-check Dolphin after returning from its download page.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onboarding: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
  val context = LocalContext.current
  val state by onboarding.state.collectAsStateWithLifecycle()
  val step = state.step
  val rom = state.rom

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, onboarding) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) onboarding.checkDolphin()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val treeLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) {
        onboarding.selectStorage(it)
      }
  val romLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) {
        onboarding.selectRom(it)
      }

  BackHandler(enabled = step.previous() != null) { onboarding.back() }

  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
      AnimatedContent(
          targetState = step,
          modifier = Modifier.fillMaxWidth(),
          transitionSpec = {
            (slideInHorizontally(animationSpec = tween(350), initialOffsetX = { it }) +
                fadeIn(animationSpec = tween(250))) togetherWith
                (slideOutHorizontally(animationSpec = tween(350), targetOffsetX = { -it }) +
                    fadeOut(animationSpec = tween(200)))
          },
          label = "step",
      ) { currentStep ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          when (currentStep) {
            OnboardingStep.Welcome -> WelcomeStep(onNext = onboarding::next)
            OnboardingStep.Dolphin ->
                DolphinStep(
                    installed = state.dolphinInstalled,
                    hasChecked = state.hasCheckedDolphin,
                    onContinue = onboarding::next,
                    onDownload = { openDolphinDownloadIntent(context) },
                    onCheckAgain = onboarding::checkDolphin,
                )
            OnboardingStep.Storage ->
                StorageStep(
                    onPick = {
                      onboarding.clearStorageError()
                      treeLauncher.launch(onboarding.treeDeepLink)
                    },
                    error = state.storageError,
                )
            OnboardingStep.Rom ->
                RomStep(
                    onPick = {
                      onboarding.clearRomError()
                      // application/octet-stream is the closest SAF mime
                      // filter; GameTypeParser does extension validation.
                      romLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onConfirmRom = onboarding::confirmExistingRom,
                    error = state.romError,
                    isLoading =
                        rom.activity == OnboardingRomActivity.Verifying ||
                            rom.activity == OnboardingRomActivity.Copying,
                    isCheckingRom = rom.activity == OnboardingRomActivity.Scanning,
                    existingRomDetected = rom.existingRomName != null,
                    detectedRomName = rom.existingRomName,
                    stage =
                        when (rom.activity) {
                          OnboardingRomActivity.Verifying ->
                              stringResource(R.string.onboarding_rom_verifying)
                          OnboardingRomActivity.Copying ->
                              stringResource(R.string.onboarding_rom_copying)
                          else -> null
                        },
                )
            OnboardingStep.Complete -> CompleteStep(onDone = onComplete)
          }
        }
      }
    }
    StepDots(
        current = step.ordinal,
        total = OnboardingStep.entries.size,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
    )
  }
}

/** First onboarding step: greets the user. */
@Composable
private fun WelcomeStep(onNext: () -> Unit) {
  StepCard(
      title =
          stringResource(
              R.string.onboarding_welcome_title,
              stringResource(R.string.onboarding_welcome_to),
              stringResource(R.string.onboarding_app_name),
          ),
      titleStyle = MaterialTheme.typography.headlineLarge,
      titleColor = MaterialTheme.colorScheme.primary,
      body = stringResource(R.string.onboarding_welcome_body),
  ) {
    StepPrimaryButton(text = stringResource(R.string.onboarding_get_started), onClick = onNext)
  }
}

/**
 * Second onboarding step: confirms [DolphinLauncher.isDolphinInstalled] before the user is asked to
 * grant access to its folder. If [installed] is true, only the Continue button shows; if false, the
 * Download and Check Again buttons are both visible. [hasChecked] suppresses the "not installed"
 * body until the first check has run, so the user doesn't see a false "not installed" message
 * during the brief window between entering the step and the lifecycle observer's first ON_RESUME.
 */
@Composable
private fun DolphinStep(
    installed: Boolean,
    hasChecked: Boolean,
    onContinue: () -> Unit,
    onDownload: () -> Unit,
    onCheckAgain: () -> Unit,
) {
  StepCard(
      title = stringResource(R.string.onboarding_dolphin_title),
      titleStyle = MaterialTheme.typography.headlineSmall,
      body =
          if (installed) {
            stringResource(R.string.onboarding_dolphin_installed_body)
          } else if (hasChecked) {
            stringResource(R.string.onboarding_dolphin_not_installed_body)
          } else {
            // First composition: lifecycle observer hasn't fired yet.
            // Show a neutral message instead of the "not installed"
            // body so the user doesn't see a flash of "Download Dolphin".
            null
          },
  ) {
    if (installed) {
      StepPrimaryButton(
          text = stringResource(R.string.onboarding_continue),
          onClick = onContinue,
      )
    } else {
      StepPrimaryButton(
          text = stringResource(R.string.onboarding_download_dolphin),
          onClick = onDownload,
      )
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedButton(
          onClick = onCheckAgain,
          shape = buttonShape,
          modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        Text(
            text = stringResource(R.string.onboarding_check_again),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

/**
 * Opens Dolphin's official download page in the user's default browser. Website-first per the
 * design decision; avoids the `market://` Play Store intent and the resulting "no Play Store"
 * fallback ladder that the website solves for free.
 */
private fun openDolphinDownloadIntent(context: android.content.Context) {
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dolphin-emu.org/download/"))
  context.startActivity(intent)
}

/** Third onboarding step: SAF tree picker for the Dolphin user folder. */
@Composable
private fun StorageStep(onPick: () -> Unit, error: String?) {
  StepCard(
      title = stringResource(R.string.onboarding_storage_title),
      titleStyle = MaterialTheme.typography.headlineSmall,
      body = stringResource(R.string.onboarding_storage_body),
  ) {
    StepPrimaryButton(text = stringResource(R.string.onboarding_select_folder), onClick = onPick)
    if (error != null) {
      Spacer(modifier = Modifier.height(12.dp))
      ErrorBanner(error)
    }
  }
}

/** Fourth onboarding step: SAF document picker for the MKW ROM. */
@Composable
private fun RomStep(
    onPick: () -> Unit,
    onConfirmRom: () -> Unit,
    error: String?,
    isLoading: Boolean,
    isCheckingRom: Boolean,
    existingRomDetected: Boolean?,
    detectedRomName: String?,
    stage: String?,
) {
  StepCard(
      title = stringResource(R.string.onboarding_iso_title),
      titleStyle = MaterialTheme.typography.headlineSmall,
      body =
          when {
            isCheckingRom -> null
            existingRomDetected == true && detectedRomName != null ->
                stringResource(R.string.onboarding_rom_found_body, detectedRomName)
            else -> stringResource(R.string.onboarding_iso_body)
          },
  ) {
    when {
      isCheckingRom -> {
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.onboarding_rom_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
      }
      existingRomDetected == true -> {
        StepPrimaryButton(
            text = stringResource(R.string.onboarding_rom_use_this_rom),
            onClick = onConfirmRom,
            enabled = !isLoading,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            enabled = !isLoading,
            onClick = onPick,
            shape = buttonShape,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
          Text(
              text = stringResource(R.string.onboarding_rom_pick_different),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
          )
        }
      }
      else -> {
        StepPrimaryButton(
            text = stringResource(R.string.onboarding_select_rom),
            onClick = onPick,
            enabled = !isLoading,
        )
      }
    }
    if (isLoading) {
      Spacer(modifier = Modifier.height(12.dp))
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      if (stage != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    if (error != null) {
      Spacer(modifier = Modifier.height(12.dp))
      ErrorBanner(error)
    }
  }
}

/** Final onboarding step: confirms the user is ready to enter the home screen. */
@Composable
private fun CompleteStep(onDone: () -> Unit) {
  StepCard(
      title = stringResource(R.string.onboarding_complete_title),
      titleStyle = MaterialTheme.typography.headlineLarge,
      titleColor = MaterialTheme.colorScheme.primary,
      body = stringResource(R.string.onboarding_complete_body),
  ) {
    StepPrimaryButton(text = stringResource(R.string.onboarding_open_app), onClick = onDone)
  }
}

/** Red-tinted error message shown below the storage or rom step button. */
@Composable
private fun ErrorBanner(message: String) {
  Text(
      text = message,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
      textAlign = TextAlign.Center,
  )
}

/** Card frame that hosts each onboarding step's title, body, and content. */
@Composable
private fun StepCard(
    title: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = sectionShape,
      color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          text = title,
          style = titleStyle,
          fontWeight = FontWeight.Bold,
          color = titleColor,
      )
      if (body != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      content()
    }
  }
}

@Composable
private fun StepPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
  Spacer(modifier = Modifier.height(16.dp))
  PrimaryActionButton(
      text = text,
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
  )
}

/** Step progress dots rendered at the bottom of the onboarding flow. */
@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    for (i in 0 until total) {
      val isCompleted = i <= current
      val isCurrent = i == current
      val size by
          animateDpAsState(
              targetValue = if (isCurrent) 10.dp else 8.dp,
              animationSpec = tween(300),
              label = "step_dot_size_$i",
          )
      val color by
          animateColorAsState(
              targetValue =
                  if (isCompleted) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant,
              animationSpec = tween(300),
              label = "step_dot_color_$i",
          )
      Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
    }
  }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 400)
@Composable
private fun OnboardingScreenWelcomePreview() {
  WheelWitchPreviewTheme {
    WelcomeStep(onNext = {})
  }
}
