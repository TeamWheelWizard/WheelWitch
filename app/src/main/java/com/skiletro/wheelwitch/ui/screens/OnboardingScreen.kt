package com.skiletro.wheelwitch.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.GameTypeParser
import com.skiletro.wheelwitch.ui.components.PrimaryActionButton
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import com.skiletro.wheelwitch.ui.theme.buttonShape
import com.skiletro.wheelwitch.ui.theme.sectionShape
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Onboarding wizard. The flow is:
 * `Welcome → Dolphin → Storage → Rom → Complete`.
 *
 * - **Dolphin**: confirms [DolphinLauncher.isDolphinInstalled] before
 *   the user is asked to grant access to its user folder. If the
 *   package isn't installed, the step offers a Download button
 *   (opens [DolphinLauncher]'s official download page) and a
 *   re-check button. A lifecycle observer re-runs the check on every
 *   `ON_RESUME` so the UI flips to the installed view automatically
 *   when the user returns from the browser.
 * - **Storage**: fires the SAF `OpenDocumentTree` picker with
 *   `EXTRA_INITIAL_URI` deep-linked at the Dolphin user folder
 *   (`primary:Android/data/org.dolphinemu.dolphinemu/files`). The
 *   pick is validated by [DolphinTree.validate] and persisted via
 *   [DolphinTree.persist].
 * - **Rom**: fires the SAF `OpenDocument` picker filtered to ISO/RVZ/
 *   WBFS. The picked file is validated by [GameTypeParser.checkValidity]
 *   and copied into the SAF tree by [DolphinTree.copyRomFromSource]
 *   (renamed to `<GAMEID>.<ext>` uppercase).
 *
 * The state machine is in-memory; if the user backgrounds the app
 * mid-flow, they restart at the welcome step on return. The
 * [com.skiletro.wheelwitch.util.prefs.PrefsKeys.ONBOARDING_COMPLETED_KEY]
 * gate is only set in the `Complete` step.
 */
@Composable
fun OnboardingScreen(
  onComplete: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var step by remember { mutableStateOf(OnboardingStep.Welcome) }
  var storageError by remember { mutableStateOf<String?>(null) }
  var romError by remember { mutableStateOf<String?>(null) }
  var isRomLoading by remember { mutableStateOf(false) }
  var romStage by remember { mutableStateOf<String?>(null) }
  // Dolphin-check state. Both default to false / false so the
  // first composition of the Dolphin step shows the "not installed"
  // view until the lifecycle observer's first ON_RESUME fires.
  // The observer handles the initial check, the re-check after
  // returning from the browser, and the manual re-check via the
  // "Check Again" button.
  var dolphinInstalled by remember { mutableStateOf(false) }
  var hasCheckedDolphin by remember { mutableStateOf(false) }
  var isCheckingRomPresence by remember { mutableStateOf(false) }
  var existingRomDetected by remember { mutableStateOf<Boolean?>(null) }
  var detectedRomName by remember { mutableStateOf<String?>(null) }

  // Re-check the Dolphin install on every ON_RESUME while the
  // Dolphin step is current. This catches the case where the user
  // taps "Download Dolphin" -> browser opens -> user installs
  // Dolphin -> returns to the app via the system back button.
  // Without the observer the user would have to tap "Check Again"
  // manually. The initial check is covered by ON_RESUME firing
  // for the first display.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME && step == OnboardingStep.Dolphin) {
        dolphinInstalled = DolphinLauncher.isDolphinInstalled(context)
        hasCheckedDolphin = true
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Auto-check when entering the Dolphin step (catches Welcome→Dolphin
  // navigation where no lifecycle event fires). The lifecycle observer
  // above handles the resume-from-browser case.
  LaunchedEffect(step) {
    if (step == OnboardingStep.Dolphin) {
      dolphinInstalled = DolphinLauncher.isDolphinInstalled(context)
      hasCheckedDolphin = true
    }
  }

  // Scan for an existing ROM when the user reaches the Rom step.
  LaunchedEffect(step) {
    if (step == OnboardingStep.Rom) {
      isCheckingRomPresence = true
      existingRomDetected = null
      detectedRomName = null
      try {
        val tree = withContext(Dispatchers.IO) { DolphinTree.fromPersisted(context) }
        if (tree != null) {
          val files = withContext(Dispatchers.IO) { tree.romDir.listFiles() }
          var found = false
          for (file in files) {
            val name = file.name ?: continue
            val ext = java.io.File(name).extension.uppercase(java.util.Locale.getDefault())
            if (ext in setOf("ISO", "RVZ", "WBFS") && file.isFile && file.exists()) {
              val bytes =
                withContext(Dispatchers.IO) {
                  tree.resolver.openInputStream(file.uri)?.use { stream ->
                    ByteArray(4096).also { stream.read(it) }
                  }
                }
              if (bytes != null && GameTypeParser.checkValidity(name, bytes)) {
                detectedRomName = name
                found = true
                break
              }
            }
          }
          existingRomDetected = found
        } else {
          existingRomDetected = false
        }
      } catch (e: Exception) {
        Timber.tag("Onboarding").w(e, "ROM scan failed")
        existingRomDetected = false
      } finally {
        isCheckingRomPresence = false
      }
    }
  }

  // Resolve every error string up here so the activity-result
  // callbacks (which run outside the composition) can use them
  // without calling getString on LocalContext.current; lint
  // rejects that and it's also fragile across recomposition.
  val storageReadFailed = stringResource(R.string.onboarding_storage_read_failed)
  val storageWrongFolder = stringResource(R.string.onboarding_storage_wrong_folder)
  val storageRequired = stringResource(R.string.onboarding_storage_required)
  val isoInvalid = stringResource(R.string.onboarding_iso_invalid)
  val isoCopyFailed = stringResource(R.string.onboarding_iso_copy_failed)
  val metadataWriteFailed = stringResource(R.string.onboarding_metadata_write_failed)
  val romVerifying = stringResource(R.string.onboarding_rom_verifying)
  val romCopying = stringResource(R.string.onboarding_rom_copying)

  // The tree picker. We deep-link to the Dolphin user folder so the
  // user lands at the right place immediately. The picked URI must
  // match the expected tree id (validated by DolphinTree.validate).
  val treeLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri == null) {
        // User cancelled. Stay on the Storage step, no error.
        Timber.tag("Onboarding").i("Storage picker cancelled")
        return@rememberLauncherForActivityResult
      }
      Timber.tag("Onboarding")
        .i("Storage picked: authority=%s", uri.authority)
      val validation = DolphinTree.validate(uri)
      validation
        .onSuccess {
          try {
            val tree = DolphinTree(context, uri)
            tree.persistUriPermission()
            DolphinTree.persist(context, tree)
            // Touch the lazy WheelWitch dir so the bootstrap subdirs
            // exist before we move on to the ROM step. If this throws,
            // the user gets a re-promptable error.
            tree.wheelWitchDir // ensure lazy creation succeeds
            Timber.tag("Onboarding")
              .i("Onboarding storage step complete: tree persisted at %s", tree.wheelWitchDir.uri)
            storageError = null
            step = OnboardingStep.Rom
          } catch (e: Exception) {
            Timber.tag("Onboarding").e(e, "Storage bootstrap failed")
            storageError = e.message ?: storageReadFailed
          }
        }
        .onFailure { e ->
          Timber.tag("Onboarding").w(e, "Picked tree failed validation")
          storageError = e.message ?: storageWrongFolder
        }
    }

  // The ROM picker. OpenDocument returns a content URI; we read
  // enough bytes to validate, then copy via DolphinTree (which
  // re-opens the URI for streaming). application/octet-stream is
  // the closest SAF mime filter; extension-based discrimination
  // happens in GameTypeParser.
  val romLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch {
        isRomLoading = true
        try {
          romStage = romVerifying
          val tree = DolphinTree.fromPersisted(context)
          if (tree == null) {
            romError = storageRequired
            return@launch
          }
          romError = null
          val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "rom.iso"
          val bytes =
            withContext(Dispatchers.IO) {
              context.contentResolver.openInputStream(uri)?.use { stream ->
                // Read enough bytes for the GameTypeParser checks.
                ByteArray(4096).also { stream.read(it) }
              } ?: throw IOException("Cannot open $uri")
            }
          if (!GameTypeParser.checkValidity(displayName, bytes)) {
            romError = isoInvalid
            return@launch
          }
          val info = GameTypeParser.parseGameInfo(displayName, bytes)
          val gameId = info.gameId ?: "RMCP01"
          val ext = File(displayName).extension.ifEmpty { "iso" }.lowercase()

          romStage = romCopying
          // Track whether the ROM copy or the metadata write was the
          // last step to run before a throw, so the catch block can
          // pick the right error message. Declared before the try so
          // the catch can read it on any failure path.
          var metadataFailed = false
          try {
            withContext(Dispatchers.IO) { tree.copyRomFromSource(uri, gameId, ext) }
            metadataFailed = true
            withContext(Dispatchers.IO) { tree.writeRrCover() }
            step = OnboardingStep.Complete
          } catch (e: Exception) {
            Timber.tag("Onboarding").e(e, if (metadataFailed) "Metadata write failed" else "ROM copy failed")
            romError =
              if (metadataFailed) (e.message ?: metadataWriteFailed)
              else (e.message ?: isoCopyFailed)
          }
        } finally {
          isRomLoading = false
          romStage = null
        }
      }
    }

  // Pre-warm the deep-link URI for the tree picker. The picker
  // surfaces different providers depending on whether Dolphin is
  // installed, so we deep-link to the matching one. If Dolphin is
  // installed, the picker shows the Dolphin provider (no
  // MANAGE_EXTERNAL_STORAGE needed) and we land at `root/`. If not,
  // we land on primary external storage. Re-keyed on `dolphinInstalled`
  // so a user who installs Dolphin between the Dolphin step and the
  // Storage step gets the new deep link.
  val treeDeepLink =
    remember(dolphinInstalled) {
      if (dolphinInstalled) {
        DocumentsContract.buildTreeDocumentUri(
          "org.dolphinemu.dolphinemu.user",
          "root",
        )
      } else {
        DocumentsContract.buildTreeDocumentUri(
          "primary",
          "Android/data/${DolphinLauncher.DOLPHIN_PACKAGE}/files",
        )
      }
    }

  BackHandler(enabled = step.previous() != null) {
    storageError = null
    romError = null
    isRomLoading = false
    romStage = null
    step = step.previous() ?: step
  }

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
            OnboardingStep.Welcome ->
              WelcomeStep(onNext = { step = OnboardingStep.Dolphin })
            OnboardingStep.Dolphin ->
              DolphinStep(
                installed = dolphinInstalled,
                hasChecked = hasCheckedDolphin,
                onContinue = { step = OnboardingStep.Storage },
                onDownload = { openDolphinDownloadIntent(context) },
                onCheckAgain = {
                  dolphinInstalled = DolphinLauncher.isDolphinInstalled(context)
                  hasCheckedDolphin = true
                },
              )
            OnboardingStep.Storage ->
              StorageStep(
                onPick = {
                  storageError = null
                  treeLauncher.launch(treeDeepLink)
                },
                error = storageError,
              )
            OnboardingStep.Rom ->
              RomStep(
                onPick = {
                  romError = null
                  // application/octet-stream is the closest SAF mime
                  // filter; GameTypeParser does extension validation.
                  romLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onConfirmRom = {
                  scope.launch {
                    try {
                      romStage = romCopying
                      val tree = DolphinTree.fromPersisted(context)
                      if (tree != null) {
                        withContext(Dispatchers.IO) { tree.writeRrCover() }
                      }
                      step = OnboardingStep.Complete
                    } catch (e: Exception) {
                      Timber.tag("Onboarding").e(e, "Cover write failed on ROM confirm")
                      romError = e.message ?: metadataWriteFailed
                    } finally {
                      romStage = null
                    }
                  }
                },
                error = romError,
                isLoading = isRomLoading,
                isCheckingRom = isCheckingRomPresence,
                existingRomDetected = existingRomDetected,
                detectedRomName = detectedRomName,
                stage = romStage,
              )
            OnboardingStep.Complete -> CompleteStep(onDone = onComplete)
          }
        }
      }
    }
    StepDots(
      current = step.ordinal,
      total = OnboardingStep.TOTAL,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
    )
  }
}

/** Onboarding flow. Five steps; TOTAL drives the dot count. */
private enum class OnboardingStep {
  Welcome,
  Dolphin,
  Storage,
  Rom,
  Complete;

  /** Returns the previous step, or null if this is the first. */
  fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)

  companion object {
    val TOTAL: Int = entries.size
  }
}

/** First onboarding step: greets the user. */
@Composable
private fun WelcomeStep(onNext: () -> Unit) {
  StepCard(
    title =
      "${stringResource(R.string.onboarding_welcome_to)}\n${stringResource(R.string.onboarding_app_name)}",
    titleStyle = MaterialTheme.typography.headlineLarge,
    titleColor = MaterialTheme.colorScheme.primary,
    body = stringResource(R.string.onboarding_welcome_body),
  ) {
    StepPrimaryButton(text = stringResource(R.string.onboarding_get_started), onClick = onNext)
  }
}

/**
 * Second onboarding step: confirms [DolphinLauncher.isDolphinInstalled]
 * before the user is asked to grant access to its folder. If
 * [installed] is true, only the Continue button shows; if false,
 * the Download and Check Again buttons are both visible. [hasChecked]
 * suppresses the "not installed" body until the first check has
 * run, so the user doesn't see a false "not installed" message
 * during the brief window between entering the step and the
 * lifecycle observer's first ON_RESUME.
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
 * Opens Dolphin's official download page in the user's default
 * browser. Website-first per the design decision; avoids the
 * `market://` Play Store intent and the resulting "no Play Store"
 * fallback ladder that the website solves for free.
 */
private fun openDolphinDownloadIntent(context: android.content.Context) {
  val intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://dolphin-emu.org/download/"))
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
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
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

/**
 * Best-effort display-name lookup for a SAF document URI. Falls back
 * to `null` so the caller can use a generic name. DocumentFile is
 * the only public API for this. `DocumentsContract.getTreeDocumentId`
 * doesn't return the leaf name.
 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
  DocumentFile.fromSingleUri(context, uri)?.name

@Preview(showBackground = true, widthDp = 600, heightDp = 400)
@Composable
private fun OnboardingScreenWelcomePreview() {
    WheelWitchPreviewTheme {
        OnboardingScreen(onComplete = {})
    }
}
