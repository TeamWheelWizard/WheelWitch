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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.BuildConfig
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.ui.components.SettingsCategoryHeader
import com.skiletro.wheelwitch.ui.components.SettingsItem
import com.skiletro.wheelwitch.ui.theme.AppTheme
import com.skiletro.wheelwitch.ui.theme.ThemeMode
import com.skiletro.wheelwitch.ui.theme.buttonShape
import com.skiletro.wheelwitch.util.formatBytes
import com.skiletro.wheelwitch.util.io.cacheSize
import com.skiletro.wheelwitch.util.mii.MiiFaceCache
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import com.skiletro.wheelwitch.viewmodel.CloudSyncViewModel
import com.skiletro.wheelwitch.viewmodel.MiiMakerViewModel
import com.skiletro.wheelwitch.viewmodel.PackUpdateViewModel
import com.skiletro.wheelwitch.viewmodel.SaveDataViewModel
import com.skiletro.wheelwitch.viewmodel.SyncStatus
import com.skiletro.wheelwitch.viewmodel.SyncUiState
import com.skiletro.wheelwitch.viewmodel.UiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings overlay. Sections, in order: Retro Rewind (pack + Mii Channel WAD), Save Data (saves +
 * cloud sync), Appearance, Advanced, About.
 */
@Composable
fun SettingsScreen(
    packUpdate: PackUpdateViewModel,
    miiMaker: MiiMakerViewModel,
    saveData: SaveDataViewModel,
    cloudSync: CloudSyncViewModel,
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
  val cloudSyncState by cloudSync.uiState.collectAsState()
  val autoSyncEnabled by cloudSync.autoSyncEnabled.collectAsState()

  var showWadDeleteConfirm by remember { mutableStateOf(false) }

  if (showWadDeleteConfirm) {
    AlertDialog(
        onDismissRequest = { showWadDeleteConfirm = false },
        title = { Text(stringResource(R.string.settings_delete_wad_dialog_title)) },
        text = { Text(stringResource(R.string.settings_delete_wad_dialog_body)) },
        confirmButton = {
          Button(
              onClick = {
                miiMaker.deleteWad()
                showWadDeleteConfirm = false
              },
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
    ScreenHeader(
        title = stringResource(R.string.settings_title),
        onBack = onClose,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      item { PackSection(packUpdate = packUpdate) }
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
            cloudSync = cloudSync,
            cloudSyncState = cloudSyncState,
            autoSyncEnabled = autoSyncEnabled,
        )
      }
      item {
        AppearanceSection(
            appTheme = appTheme,
            onChangeAppTheme = onChangeAppTheme,
            themeMode = themeMode,
            onChangeThemeMode = onChangeThemeMode,
        )
      }
      item {
        AdvancedSection(
            onOpenLogViewer = onOpenLogViewer,
            onRelaunchOnboarding = onRelaunchOnboarding,
        )
      }
      item { AboutSection() }
      item { Spacer(modifier = Modifier.height(24.dp)) }
    }
  }
}

/**
 * Save Data section: unified backup / restore / delete over RR saves, the Mii DB, Pulsar pul files,
 * and ghosts, plus the cloud sync rows that sync those backups.
 */
@Composable
private fun SaveDataSection(
    saveData: SaveDataViewModel,
    hasAnySave: Boolean,
    cloudSync: CloudSyncViewModel,
    cloudSyncState: SyncUiState,
    autoSyncEnabled: Boolean,
) {
  val lastBackup by saveData.lastBackupTimestamp.collectAsState()
  val lastBackupLabel = remember(lastBackup) { saveData.formatLastBackup() }
  var pendingBackup by remember { mutableStateOf(false) }
  var pendingRestore by remember { mutableStateOf(false) }
  var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var showRestoreConfirm by remember { mutableStateOf(false) }

  val backupLauncher =
      rememberLauncherForActivityResult(
          contract = ActivityResultContracts.CreateDocument("application/zip")
      ) { uri ->
        if (uri != null) saveData.backup(uri)
        pendingBackup = false
      }
  val restoreLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          pendingRestoreUri = uri
          showRestoreConfirm = true
        }
        pendingRestore = false
      }

  if (showDeleteConfirm) {
    AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text(stringResource(R.string.settings_save_data_delete_confirm_title)) },
        text = { Text(stringResource(R.string.settings_save_data_delete_confirm_message)) },
        confirmButton = {
          Button(
              onClick = {
                saveData.delete()
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
                uri?.let { saveData.restore(it) }
                pendingRestoreUri = null
                showRestoreConfirm = false
              },
          ) {
            Text(stringResource(R.string.settings_save_data_restore))
          }
        },
        dismissButton = {
          TextButton(
              onClick = {
                pendingRestoreUri = null
                showRestoreConfirm = false
              }
          ) {
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

  SettingsCategoryHeader(stringResource(R.string.settings_save_data_section))

  SettingsItem(
      icon = ImageVector.vectorResource(R.drawable.ic_save),
      title = stringResource(R.string.settings_save_data_title),
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

  CloudSyncSection(
      cloudSync = cloudSync,
      state = cloudSyncState,
      autoSyncEnabled = autoSyncEnabled,
  )
}

/**
 * Cloud sync rows within the Save Data section: Dropbox connection, automatic sync, manual sync.
 */
@Composable
private fun CloudSyncSection(
    cloudSync: CloudSyncViewModel,
    state: SyncUiState,
    autoSyncEnabled: Boolean,
) {
  var showDisconnectConfirm by remember { mutableStateOf(false) }
  val connected = state as? SyncUiState.Connected
  val statusLabel = connected?.let {
    stringResource(
        when (it.status) {
          SyncStatus.Idle -> R.string.sync_status_idle
          SyncStatus.Syncing -> R.string.sync_status_syncing
          SyncStatus.Error -> R.string.sync_status_error
          SyncStatus.ReconnectNeeded -> R.string.sync_status_reconnect
        }
    )
  }
  val lastSyncLabel =
      connected
          ?.lastSyncAtMillis
          ?.takeIf { it > 0 }
          ?.let {
            stringResource(
                R.string.sync_last_sync_format,
                DateFormat.getDateTimeInstance().format(Date(it)),
            )
          }
  val connectedSummary =
      listOfNotNull(
              connected?.accountEmail ?: stringResource(R.string.sync_account_email_unavailable),
              statusLabel,
              lastSyncLabel,
          )
          .joinToString(" · ")
  val summary =
      when (state) {
        SyncUiState.NotConfigured -> stringResource(R.string.sync_not_configured)
        SyncUiState.SecureStorageUnavailable ->
            stringResource(R.string.sync_secure_storage_unavailable)
        SyncUiState.Disconnected -> null
        is SyncUiState.Connected -> connectedSummary
      }

  if (showDisconnectConfirm) {
    AlertDialog(
        onDismissRequest = { showDisconnectConfirm = false },
        title = { Text(stringResource(R.string.sync_disconnect_confirm_title)) },
        text = { Text(stringResource(R.string.sync_disconnect_confirm_message)) },
        confirmButton = {
          Button(
              onClick = {
                cloudSync.disconnect()
                showDisconnectConfirm = false
              }
          ) {
            Text(stringResource(R.string.sync_disconnect))
          }
        },
        dismissButton = {
          TextButton(onClick = { showDisconnectConfirm = false }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }

  SettingsItem(
      icon = ImageVector.vectorResource(R.drawable.ic_cached),
      title = stringResource(R.string.sync_section_title),
      summary = summary,
      trailing = {
        when (state) {
          SyncUiState.NotConfigured,
          SyncUiState.SecureStorageUnavailable -> Unit
          SyncUiState.Disconnected -> {
            TextButton(onClick = cloudSync::connect, shape = buttonShape) {
              Text(stringResource(R.string.sync_connect))
            }
          }
          is SyncUiState.Connected -> {
            if (state.status == SyncStatus.ReconnectNeeded) {
              TextButton(onClick = cloudSync::connect, shape = buttonShape) {
                Text(stringResource(R.string.sync_connect))
              }
            }
          }
        }
      },
  )

  if (connected != null) {
    SettingsItem(
        icon = ImageVector.vectorResource(R.drawable.ic_cached),
        title = stringResource(R.string.sync_auto_toggle),
        trailing = {
          Switch(
              checked = autoSyncEnabled,
              onCheckedChange = cloudSync::setAutoSync,
          )
        },
    )
    SettingsItem(
        icon = ImageVector.vectorResource(R.drawable.ic_refresh),
        title = stringResource(R.string.sync_now),
        trailing = {
          TextButton(
              onClick = cloudSync::syncNow,
              enabled = connected.status != SyncStatus.Syncing,
              shape = buttonShape,
          ) {
            Text(stringResource(R.string.sync_now))
          }
        },
    )
    SettingsItem(
        icon = ImageVector.vectorResource(R.drawable.ic_exit_to_app),
        title = stringResource(R.string.sync_disconnect),
        trailing = {
          TextButton(onClick = { showDisconnectConfirm = true }, shape = buttonShape) {
            Text(
                text = stringResource(R.string.sync_disconnect),
                color = MaterialTheme.colorScheme.error,
            )
          }
        },
    )
  }
}

/** Retro Rewind section: pack version + full reinstall, and the Mii Channel WAD install. */
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
          Button(
              onClick = {
                packUpdate.reinstall()
                showReinstallConfirm = false
              }
          ) {
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

  SettingsCategoryHeader(stringResource(R.string.settings_rr_section))
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

/** Mii Channel WAD row within the Retro Rewind section: install or delete the cached WAD. */
@Composable
private fun MiiMakerSection(
    hasWad: Boolean,
    isInstallingWad: Boolean,
    miiMakerError: String?,
    onInstall: () -> Unit,
    onRequestDelete: () -> Unit,
) {
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
              Text(
                  stringResource(R.string.settings_delete),
                  color = MaterialTheme.colorScheme.error,
              )
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

/** Diagnostic rows within the Advanced section: on-disk log toggle and bug report launcher. */
@Composable
private fun LoggingSection(onOpenLogViewer: () -> Unit) {
  val context = LocalContext.current
  val loggingPrefs = remember { Prefs.main(context) }
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
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) {
          Text(stringResource(R.string.settings_report), fontWeight = FontWeight.Medium)
        }
      },
  )
}

/**
 * Advanced section: diagnostics (log file, bug report), Mii face cache, and the relaunch-onboarding
 * escape hatch.
 */
@Composable
private fun AdvancedSection(
    onOpenLogViewer: () -> Unit,
    onRelaunchOnboarding: () -> Unit,
) {
  SettingsCategoryHeader(stringResource(R.string.settings_advanced))
  LoggingSection(onOpenLogViewer)
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
