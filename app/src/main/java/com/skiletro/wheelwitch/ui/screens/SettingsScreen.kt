package com.skiletro.wheelwitch.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.BuildConfig
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.ui.components.SettingsCategoryHeader
import com.skiletro.wheelwitch.ui.components.SettingsItem
import com.skiletro.wheelwitch.ui.theme.AppTheme
import com.skiletro.wheelwitch.ui.theme.ThemeMode
import com.skiletro.wheelwitch.ui.theme.buttonShape
import com.skiletro.wheelwitch.util.mii.MiiFaceCache
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import com.skiletro.wheelwitch.util.io.cacheSize
import com.skiletro.wheelwitch.util.formatBytes
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.viewmodel.MiiMakerViewModel
import com.skiletro.wheelwitch.viewmodel.PackUpdateViewModel
import com.skiletro.wheelwitch.viewmodel.SaveDataViewModel
import com.skiletro.wheelwitch.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings overlay. Sections, in order: Appearance, Mii Maker, Save
 * Data, Logging, Advanced, About.
 *
 * The Save Data section was promoted from the Licenses screen so the
 * Licenses UI can be a pure 2x2 viewer: region selection, backup,
 * restore and delete live here. The Save Data section is only
 * rendered when the user has at least one save file; it stays
 * available even if the user has no current save so the region
 * selector can be used to retarget the Licenses view.
 */
@Composable
fun SettingsScreen(
  packUpdate: PackUpdateViewModel,
  miiMaker: MiiMakerViewModel,
  saveData: SaveDataViewModel,
  onClose: () -> Unit,
  onOpenLogViewer: () -> Unit,
  appTheme: AppTheme,
  onChangeAppTheme: (AppTheme) -> Unit,
  themeMode: ThemeMode,
  onChangeThemeMode: (ThemeMode) -> Unit,
  onRelaunchOnboarding: () -> Unit,
) {
  val hasWad by miiMaker.hasWad.collectAsState()
  val isInstallingWad by miiMaker.isInstallingWad.collectAsState()
  val miiMakerError by miiMaker.miiMakerError.collectAsState()
  val hasAnySave by saveData.hasAnySave.collectAsState()
  val hasRRSave by saveData.hasRRSave.collectAsState()

  var showWadDeleteConfirm by remember { mutableStateOf(false) }

  if (showWadDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showWadDeleteConfirm = false },
      title = { Text(stringResource(R.string.settings_delete_wad_dialog_title)) },
      text = { Text(stringResource(R.string.settings_delete_wad_dialog_body)) },
      confirmButton = {
        Button(
          onClick = { miiMaker.deleteWad(); showWadDeleteConfirm = false },
        ) {
          Text(stringResource(R.string.settings_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { showWadDeleteConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onClose) {
        Icon(
          imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_back),
          contentDescription = stringResource(R.string.cd_back),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = stringResource(R.string.settings_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      item {
        AppearanceSection(
          appTheme = appTheme,
          onChangeAppTheme = onChangeAppTheme,
          themeMode = themeMode,
          onChangeThemeMode = onChangeThemeMode,
        )
      }
      item {
        MiiMakerSection(
          hasWad = hasWad,
          isInstallingWad = isInstallingWad,
          miiMakerError = miiMakerError,
          onInstall = miiMaker::installMiiMakerWad,
          onRequestDelete = { showWadDeleteConfirm = true },
        )
      }
      item {
        SaveDataSection(
          saveData = saveData,
          hasAnySave = hasAnySave,
          hasRRSave = hasRRSave,
        )
      }
      item { PackSection(packUpdate = packUpdate) }
      item { LoggingSection(onOpenLogViewer = onOpenLogViewer) }
      item {
        AdvancedSection(
          onRelaunchOnboarding = onRelaunchOnboarding,
        )
      }
      item { AboutSection() }
      item { Spacer(modifier = Modifier.height(24.dp)) }
    }
  }
}

/** Save Data section: unified backup / restore / delete over every user-data file. */
@Composable
private fun SaveDataSection(saveData: SaveDataViewModel, hasAnySave: Boolean, hasRRSave: Boolean) {
  val lastBackup by saveData.lastBackupTimestamp.collectAsState()
  val lastBackupLabel = remember(lastBackup) { saveData.formatLastBackup() }
  var pendingBackup by remember { mutableStateOf(false) }
  var pendingRestore by remember { mutableStateOf(false) }
  var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var showRestoreConfirm by remember { mutableStateOf(false) }

  val lastBackupRR by saveData.lastBackupRRTimestamp.collectAsState()
  val lastBackupRRLabel = remember(lastBackupRR) { saveData.formatLastBackupRR() }
  var pendingBackupRR by remember { mutableStateOf(false) }
  var pendingRestoreRR by remember { mutableStateOf(false) }
  var pendingRestoreRRUri by remember { mutableStateOf<Uri?>(null) }
  var showDeleteRRConfirm by remember { mutableStateOf(false) }
  var showRestoreRRConfirm by remember { mutableStateOf(false) }

  val backupLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
      if (uri != null) saveData.backupAll(uri)
      pendingBackup = false
    }
  val restoreLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        pendingRestoreUri = uri
        showRestoreConfirm = true
      }
      pendingRestore = false
    }
  val backupRRLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
      if (uri != null) saveData.backupRR(uri)
      pendingBackupRR = false
    }
  val restoreRRLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        pendingRestoreRRUri = uri
        showRestoreRRConfirm = true
      }
      pendingRestoreRR = false
    }

  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text(stringResource(R.string.settings_save_data_delete_confirm_title)) },
      text = { Text(stringResource(R.string.settings_save_data_delete_confirm_message)) },
      confirmButton = {
        Button(
          onClick = {
            saveData.deleteAll()
            showDeleteConfirm = false
          },
        ) {
          Text(stringResource(R.string.settings_save_data_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (showRestoreConfirm) {
    val uri = pendingRestoreUri
    AlertDialog(
      onDismissRequest = {
        showRestoreConfirm = false
        pendingRestoreUri = null
      },
      title = { Text(stringResource(R.string.settings_save_data_restore_confirm_title)) },
      text = { Text(stringResource(R.string.settings_save_data_restore_confirm_message)) },
      confirmButton = {
        Button(
          enabled = uri != null,
          onClick = {
            uri?.let { saveData.restoreAll(it) }
            pendingRestoreUri = null
            showRestoreConfirm = false
          },
        ) {
          Text(stringResource(R.string.settings_save_data_restore))
        }
      },
      dismissButton = {
        TextButton(onClick = {
          pendingRestoreUri = null
          showRestoreConfirm = false
        }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (showDeleteRRConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteRRConfirm = false },
      title = { Text(stringResource(R.string.settings_save_data_rr_delete_confirm_title)) },
      text = { Text(stringResource(R.string.settings_save_data_rr_delete_confirm_message)) },
      confirmButton = {
        Button(
          onClick = {
            saveData.deleteRR()
            showDeleteRRConfirm = false
          },
        ) {
          Text(stringResource(R.string.settings_save_data_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteRRConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (showRestoreRRConfirm) {
    val uri = pendingRestoreRRUri
    AlertDialog(
      onDismissRequest = {
        showRestoreRRConfirm = false
        pendingRestoreRRUri = null
      },
      title = { Text(stringResource(R.string.settings_save_data_rr_restore_confirm_title)) },
      text = { Text(stringResource(R.string.settings_save_data_rr_restore_confirm_message)) },
      confirmButton = {
        Button(
          enabled = uri != null,
          onClick = {
            uri?.let { saveData.restoreRR(it) }
            pendingRestoreRRUri = null
            showRestoreRRConfirm = false
          },
        ) {
          Text(stringResource(R.string.settings_save_data_restore))
        }
      },
      dismissButton = {
        TextButton(onClick = {
          pendingRestoreRRUri = null
          showRestoreRRConfirm = false
        }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  LaunchedEffect(pendingBackup) {
    if (pendingBackup) {
      val fileName = "wheelwitch-save-${System.currentTimeMillis()}.zip"
      backupLauncher.launch(fileName)
    }
  }
  LaunchedEffect(pendingRestore) {
    if (pendingRestore) {
      restoreLauncher.launch(arrayOf("application/zip", "*/*"))
    }
  }
  LaunchedEffect(pendingBackupRR) {
    if (pendingBackupRR) {
      val fileName = "wheelwitch-rr-save-${System.currentTimeMillis()}.zip"
      backupRRLauncher.launch(fileName)
    }
  }
  LaunchedEffect(pendingRestoreRR) {
    if (pendingRestoreRR) {
      restoreRRLauncher.launch(arrayOf("application/zip", "*/*"))
    }
  }

  SettingsCategoryHeader(stringResource(R.string.settings_save_data_section))

  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_save),
    title = stringResource(R.string.settings_save_data_section),
    summary =
      when {
        !hasAnySave -> stringResource(R.string.settings_save_data_no_save)
        lastBackupLabel != null ->
          stringResource(R.string.settings_save_data_last_backup_format, lastBackupLabel)
        else -> null
      },
    trailing = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
          onClick = { pendingBackup = true },
          enabled = hasAnySave,
          shape = buttonShape,
        ) {
          Text(stringResource(R.string.settings_save_data_backup))
        }
        TextButton(
          onClick = { pendingRestore = true },
          enabled = hasAnySave,
          shape = buttonShape,
        ) {
          Text(stringResource(R.string.settings_save_data_restore))
        }
        TextButton(
          onClick = { showDeleteConfirm = true },
          enabled = hasAnySave,
          shape = buttonShape,
        ) {
          Text(
            text = stringResource(R.string.settings_save_data_delete),
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
  )

  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_save),
    title = stringResource(R.string.settings_save_data_rr_section),
    summary =
      when {
        !hasRRSave -> stringResource(R.string.settings_save_data_rr_no_save)
        lastBackupRRLabel != null ->
          stringResource(R.string.settings_save_data_rr_last_backup_format, lastBackupRRLabel)
        else -> null
      },
    trailing = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
          onClick = { pendingBackupRR = true },
          enabled = hasRRSave,
          shape = buttonShape,
        ) {
          Text(stringResource(R.string.settings_save_data_backup))
        }
        TextButton(
          onClick = { pendingRestoreRR = true },
          enabled = hasRRSave,
          shape = buttonShape,
        ) {
          Text(stringResource(R.string.settings_save_data_restore))
        }
        TextButton(
          onClick = { showDeleteRRConfirm = true },
          enabled = hasRRSave,
          shape = buttonShape,
        ) {
          Text(
            text = stringResource(R.string.settings_save_data_delete),
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
  )
}

/** Pack section: current version and full reinstall button. */
@Composable
private fun PackSection(packUpdate: PackUpdateViewModel) {
  val state by packUpdate.state.collectAsState()
  val packStatus = (state as? UiState.Ready)?.status
  val version = packStatus?.let {
    when (it) {
      is PackStatus.UpToDate -> it.currentVersion
      is PackStatus.UpdateAvailable -> it.currentVersion
      is PackStatus.CheckFailed -> it.installedVersion
      is PackStatus.NotInstalled -> null
    }
  }
  val isInstalled = version != null
  var showReinstallConfirm by remember { mutableStateOf(false) }

  if (showReinstallConfirm) {
    AlertDialog(
      onDismissRequest = { showReinstallConfirm = false },
      title = { Text(stringResource(R.string.settings_pack_reinstall_dialog_title)) },
      text = { Text(stringResource(R.string.settings_pack_reinstall_dialog_body)) },
      confirmButton = {
        Button(onClick = { packUpdate.reinstall(); showReinstallConfirm = false }) {
          Text(stringResource(R.string.action_reinstall))
        }
      },
      dismissButton = {
        TextButton(onClick = { showReinstallConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  SettingsCategoryHeader(stringResource(R.string.settings_pack_section))
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_cached),
    title = stringResource(R.string.topbar_subtitle),
    summary =
      if (isInstalled) {
        stringResource(R.string.settings_pack_version_installed, version.toString())
      } else {
        stringResource(R.string.settings_pack_not_installed)
      },
    trailing = {
      if (isInstalled) {
        TextButton(
          onClick = { showReinstallConfirm = true },
          enabled = state !is UiState.Installing,
          shape = buttonShape,
        ) {
          Text(stringResource(R.string.action_reinstall))
        }
      }
    },
  )
  Spacer(modifier = Modifier.height(4.dp))
}

/** Appearance section: app theme picker and dark-mode picker. */
@Composable
private fun AppearanceSection(
  appTheme: AppTheme,
  onChangeAppTheme: (AppTheme) -> Unit,
  themeMode: ThemeMode,
  onChangeThemeMode: (ThemeMode) -> Unit,
) {
  SettingsCategoryHeader(stringResource(R.string.settings_appearance))
  var showAppThemeDropdown by remember { mutableStateOf(false) }
  var showThemeDropdown by remember { mutableStateOf(false) }
  val appThemeLabel = stringResource(appTheme.labelRes)
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_palette),
    title = stringResource(R.string.settings_app_theme),
    summary = appThemeLabel,
    trailing = {
      Box {
        TextButton(
          onClick = { showAppThemeDropdown = true },
          shape = buttonShape,
        ) {
          Text(text = appThemeLabel)
        }
        DropdownMenu(
          expanded = showAppThemeDropdown,
          onDismissRequest = { showAppThemeDropdown = false },
        ) {
          AppTheme.entries.forEach { theme ->
            DropdownMenuItem(
              text = { Text(stringResource(theme.labelRes)) },
              onClick = {
                onChangeAppTheme(theme)
                showAppThemeDropdown = false
              },
            )
          }
        }
      }
    },
  )
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_nightlight),
    title = stringResource(R.string.settings_dark_mode),
    summary =
      when (themeMode) {
        ThemeMode.Light -> stringResource(R.string.settings_always_light)
        ThemeMode.Dark -> stringResource(R.string.settings_always_dark)
        ThemeMode.Oled -> stringResource(R.string.settings_oled)
        ThemeMode.System -> stringResource(R.string.settings_follow_system)
      },
    trailing = {
      Box {
        TextButton(
          onClick = { showThemeDropdown = true },
          shape = buttonShape,
        ) {
          Text(
            text =
              when (themeMode) {
                ThemeMode.Light -> stringResource(R.string.settings_theme_light)
                ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
                ThemeMode.Oled -> stringResource(R.string.settings_theme_oled)
                ThemeMode.System -> stringResource(R.string.settings_theme_system)
              }
          )
        }
        DropdownMenu(
          expanded = showThemeDropdown,
          onDismissRequest = { showThemeDropdown = false },
        ) {
          ThemeMode.entries.forEach { mode ->
            DropdownMenuItem(
              text = {
                Text(
                  when (mode) {
                    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
                    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
                    ThemeMode.Oled -> stringResource(R.string.settings_theme_oled)
                    ThemeMode.System -> stringResource(R.string.settings_theme_system)
                  }
                )
              },
              onClick = {
                onChangeThemeMode(mode)
                showThemeDropdown = false
              },
            )
          }
        }
      }
    },
  )
}

/** Mii Channel WAD section: install or delete the cached WAD. */
@Composable
private fun MiiMakerSection(
  hasWad: Boolean,
  isInstallingWad: Boolean,
  miiMakerError: String?,
  onInstall: () -> Unit,
  onRequestDelete: () -> Unit,
) {
  SettingsCategoryHeader(stringResource(R.string.settings_mii_maker_section))
  val wadStatus =
    if (hasWad) stringResource(R.string.status_installed)
    else stringResource(R.string.status_not_installed)
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_face_up),
    title = stringResource(R.string.settings_mii_channel_wad),
    summary = miiMakerError ?: wadStatus,
    summaryColor =
      if (miiMakerError != null) MaterialTheme.colorScheme.error
      else MaterialTheme.colorScheme.onSurfaceVariant,
    trailing = {
      when {
        isInstallingWad -> {
          Text(
            stringResource(R.string.settings_installing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        hasWad -> {
          TextButton(onClick = onRequestDelete) {
            Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error)
          }
        }
        else -> {
          Button(
            onClick = onInstall,
            shape = buttonShape,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
          ) {
            Text(stringResource(R.string.action_install))
          }
        }
      }
    },
  )
}

/** Logging section: toggle the on-disk log file and open the Log Viewer. */
@Composable
private fun LoggingSection(onOpenLogViewer: () -> Unit) {
  val context = LocalContext.current
  val loggingPrefs = remember { Prefs.main(context) }
  SettingsCategoryHeader(stringResource(R.string.settings_logging))
  var loggingToFile by remember {
    mutableStateOf(loggingPrefs.getBoolean(PrefsKeys.LOGGING_TO_FILE_KEY, false))
  }
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_save),
    title = stringResource(R.string.settings_logging_to_file),
    summary = stringResource(R.string.settings_logging_to_file_sub),
    trailing = {
      Switch(
        checked = loggingToFile,
        onCheckedChange = { enabled ->
          loggingToFile = enabled
          loggingPrefs.edit().putBoolean(PrefsKeys.LOGGING_TO_FILE_KEY, enabled).apply()
        },
      )
    },
  )
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_bug_report),
    title = stringResource(R.string.settings_report_bug),
    summary = stringResource(R.string.settings_report_bug_sub),
    trailing = {
      Button(
        onClick = onOpenLogViewer,
        shape = buttonShape,
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
      ) {
        Text(stringResource(R.string.settings_report), fontWeight = FontWeight.Medium)
      }
    },
  )
}

/** Advanced section: Mii face cache row + relaunch-onboarding escape hatch. */
@Composable
private fun AdvancedSection(
  onRelaunchOnboarding: () -> Unit,
) {
  SettingsCategoryHeader(stringResource(R.string.settings_advanced))
  MiiCacheRow()
  RelaunchOnboardingRow(onRelaunchOnboarding)
}

@Composable
private fun MiiCacheRow() {
  var miiCacheSizeBytes by remember { mutableStateOf(MiiFaceCache.cacheSize()) }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  // The initial read above is on the main thread; the on-disk
  // walk can be a few hundred `stat()` calls for a 50 MB cache
  // with many small files. Re-read on `Dispatchers.IO` once on
  // first composition and re-read again after every clear so
  // the summary line shows the post-clear value.
  LaunchedEffect(Unit) {
    val updated = withContext(Dispatchers.IO) { MiiFaceCache.cacheSize() }
    miiCacheSizeBytes = updated
  }
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_cached),
    title = stringResource(R.string.settings_mii_face_cache),
    summary = formatBytes(miiCacheSizeBytes),
    trailing = {
      TextButton(
        onClick = {
          scope.launch {
            withContext(Dispatchers.IO) { MiiFaceCache.clear() }
            miiCacheSizeBytes = 0
          }
        },
        enabled = miiCacheSizeBytes > 0,
        shape = buttonShape,
      ) {
        Text(
          text = stringResource(R.string.settings_clear),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
  )
  Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun RelaunchOnboardingRow(onRelaunch: () -> Unit) {
  SettingsItem(
    icon = ImageVector.vectorResource(R.drawable.ic_exit_to_app),
    title = stringResource(R.string.settings_onboarding),
    summary = stringResource(R.string.settings_relaunch_onboarding),
    trailing = {
      TextButton(onClick = onRelaunch, shape = buttonShape) {
        Text(stringResource(R.string.settings_relaunch))
      }
    },
  )
}

/** About section: app name + version + tagline. */
@Composable
private fun AboutSection() {
  SettingsCategoryHeader(stringResource(R.string.settings_about))
  val version =
    if (BuildConfig.DEBUG)
      stringResource(
        R.string.settings_version_debug,
        BuildConfig.VERSION_NAME,
        BuildConfig.GIT_HASH,
      )
    else stringResource(R.string.settings_version_release, BuildConfig.VERSION_NAME)
  Box(modifier = Modifier.focusable()) {
    SettingsItem(
      icon = ImageVector.vectorResource(R.drawable.ic_info),
      title = stringResource(R.string.settings_wheel_witch),
      summary =
        stringResource(
          R.string.settings_about_summary,
          version,
          stringResource(R.string.settings_app_subtitle),
        ),
      trailing = null,
    )
  }
}
