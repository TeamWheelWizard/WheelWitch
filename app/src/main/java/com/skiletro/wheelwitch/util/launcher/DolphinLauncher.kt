package com.skiletro.wheelwitch.util.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import com.skiletro.wheelwitch.data.DolphinConfig
import com.skiletro.wheelwitch.data.DolphinPaths
import com.skiletro.wheelwitch.data.DolphinTree
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Builds Dolphin's `dolphin-game-mod-descriptor` and launches it through `AutoStartFile`.
 * Descriptor paths use [DolphinPaths] to address the same layout written through [DolphinTree].
 * Paths must be readable by Dolphin; constructing them does not verify access or existence.
 */
object DolphinLauncher {
  const val DOLPHIN_PACKAGE = "org.dolphinemu.dolphinemu"
  const val DOLPHIN_MAIN_ACTIVITY = "$DOLPHIN_PACKAGE.ui.main.MainActivity"

  /**
   * Launch descriptor filename, stored in `WheelWitch/rom/`.
   */
  const val RR_JSON_NAME = "rr_autostartfile.json"

  /** Intent extra key Dolphin's MainActivity reads for the auto-start file. */
  const val LAUNCH_EXTRA_NAME = "AutoStartFile"

  /** `display-name` value written into the launch descriptor. */
  const val DISPLAY_NAME = "Retro Rewind"

  /** `type` value for the `dolphin-game-mod-descriptor` schema. */
  const val DESCRIPTOR_TYPE = "dolphin-game-mod-descriptor"

  /** `version` value for the `dolphin-game-mod-descriptor` schema. */
  const val DESCRIPTOR_VERSION = 1

  /** Default Riivolution XML path under the pack root. */
  const val DEFAULT_XML_REL_PATH = "riivolution/RetroRewind6.xml"

  /** Page users are sent to when their Dolphin build is outdated. */
  const val DOLPHIN_DOWNLOAD_URL = "https://dolphin-emu.org/download/"

  /** ROM file extensions that count as a valid launch ROM. */
  val ROM_EXTENSIONS = setOf("iso", "rvz", "wbfs")

  private const val TAG = "DolphinLauncher"

  /** Returns true if the Dolphin package is installed on the device. */
  fun isDolphinInstalled(context: Context): Boolean =
    try {
      context.packageManager.getPackageInfo(DOLPHIN_PACKAGE, 0)
      true
    } catch (e: Exception) {
      Timber.tag(TAG).d(e, "Dolphin package not installed")
      false
    }

  /**
   * Returns the installed Dolphin's `versionName`, or null when the
   * package is not installed or its version is unreadable. The value
   * feeds [com.skiletro.wheelwitch.model.DolphinVersion.parse] for the
   * outdated-build warning.
   */
  fun dolphinVersionName(context: Context): String? =
    try {
      context.packageManager.getPackageInfo(DOLPHIN_PACKAGE, 0).versionName
    } catch (e: Exception) {
      Timber.tag(TAG).d(e, "Dolphin package not installed; no version")
      null
    }

  /**
   * Builds the descriptor without I/O. [baseFilePath] and [packRootPath] must be filesystem
   * paths readable by Dolphin; [xmlRelPath] is resolved beneath the pack root.
   */
  fun buildLaunchJson(
    baseFilePath: String,
    packRootPath: String,
    xmlRelPath: String = DEFAULT_XML_REL_PATH,
    packChoice: Int = 1,
    myStuffChoice: Int = 2,
    separateSavegameChoice: Int = 2,
  ): String {
    val xmlFullPath = "$packRootPath/$xmlRelPath"
    val options =
      JSONArray()
        .put(optionObject("Pack", packChoice))
        .put(optionObject("My Stuff", myStuffChoice))
        .put(optionObject("Separate Savegame", separateSavegameChoice))
    val patch =
      JSONObject()
        .put("options", options)
        .put("root", packRootPath)
        .put("xml", xmlFullPath)
    val patches = JSONArray().put(patch)
    val riivolution = JSONObject().put("patches", patches)
    return JSONObject()
      .put("base-file", baseFilePath)
      .put("display-name", DISPLAY_NAME)
      .put("riivolution", riivolution)
      .put("type", DESCRIPTOR_TYPE)
      .put("version", DESCRIPTOR_VERSION)
      .toString()
  }

  /**
   * Writes the descriptor through SAF and starts Dolphin with its physical path.
   * All exceptions are captured in [Result]; success only confirms that the intent was started,
   * not that Dolphin loaded the descriptor.
   */
  fun launch(
    context: Context,
    tree: DolphinTree,
    romFile: DocumentFile,
    xmlRelPath: String = DEFAULT_XML_REL_PATH,
  ): Result<Unit> =
    runCatching {
      if (!isDolphinInstalled(context)) {
        throw ActivityNotFoundException("Dolphin emulator is not installed")
      }
      val romName = romFile.name ?: error("ROM file has no name")
      val baseFilePath =
        File(DolphinPaths.romDir(context), romName).absolutePath
      val packRootPath = DolphinPaths.packDir(context).absolutePath
      val jsonPath = DolphinPaths.rrJsonFile(context).absolutePath

      val json = buildLaunchJson(baseFilePath, packRootPath, xmlRelPath)
      tree.writeLaunchJson(json)

      Timber.tag(TAG)
        .i("Launching RR via AutoStartFile=%s base-file=%s", jsonPath, baseFilePath)

      context.startActivity(buildDolphinIntent(jsonPath))
    }

  /**
   * Builds the explicit Dolphin intent with a physical descriptor path and NEW_TASK for
   * non-Activity contexts.
   */
  fun buildDolphinIntent(jsonPath: String): Intent =
    Intent().apply {
      component = ComponentName(DOLPHIN_PACKAGE, DOLPHIN_MAIN_ACTIVITY)
      putExtra(LAUNCH_EXTRA_NAME, jsonPath)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

  /**
   * Outcome of the local launch attempt. Descriptor loading errors inside Dolphin are not
   * observable here. The fallback is attempted only when local setup or intent dispatch fails.
   */
  sealed class LaunchResult {
    /** The descriptor intent was dispatched; Dolphin has not confirmed loading it. */
    data object AutoStarted : LaunchResult()

    /**
     * A bare Dolphin launch was attempted after [cause]. Its success is not checked.
     */
    data class FallbackStarted(val cause: Throwable) : LaunchResult()

    /** Dolphin is not installed; nothing was started. */
    data object DolphinNotInstalled : LaunchResult()

    /** Storage is not configured (no SAF tree). */
    data object StorageNotConfigured : LaunchResult()

    /** No ROM file is present in `rom/`. */
    data object NoRom : LaunchResult()
  }

  /**
   * Uses the persisted SAF tree and attempts descriptor launch after best-effort game INI
   * setup and ROM-library registration. Missing Dolphin, storage, or ROM returns a specific
   * outcome. Setup or dispatch failure after ROM selection attempts a bare Dolphin launch.
   */
  fun launchRetroRewind(context: Context): LaunchResult {
    if (!isDolphinInstalled(context)) return LaunchResult.DolphinNotInstalled
    val tree =
      DolphinTree.fromPersisted(context) ?: return LaunchResult.StorageNotConfigured
    return launchRetroRewind(context, tree)
  }

  /**
   * Uses an existing [tree] rather than looking up the persisted SAF URI.
   */
  fun launchRetroRewind(context: Context, tree: DolphinTree): LaunchResult {
    if (!isDolphinInstalled(context)) return LaunchResult.DolphinNotInstalled

    // Force-disable cheats and RetroAchievements for online safety
    try {
      tree.ensureRmcGameInis()
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Failed to write game INI settings")
    }

    val rom = pickRomFile(tree) ?: return LaunchResult.NoRom
    val romName = rom.name ?: return LaunchResult.NoRom
    return try {
      registerRomPathInConfig(context, tree, romName)
      launch(context, tree, rom).getOrThrow()
      LaunchResult.AutoStarted
    } catch (e: Throwable) {
      Timber.tag(TAG).w(e, "Auto-start failed; falling back to bare Dolphin launch")
      startDolphin(context)
      LaunchResult.FallbackStarted(e)
    }
  }

  /**
   * Registers `WheelWitch/rom` in Dolphin's library using its document-provider URI.
   * Writes `Config/Dolphin.ini` only when [DolphinConfig.upsert] changes the content.
   */
  fun registerRomPathInConfig(
    context: Context,
    tree: DolphinTree,
    romFileName: String,
  ) {
    val ext = romFileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (ext !in ROM_EXTENSIONS) {
      throw IllegalArgumentException("Unexpected ROM extension: $romFileName")
    }
    val relPath = "WheelWitch/rom"
    val uri = DolphinConfig.dolphinUserTreeUri(relPath)
    val existing = tree.readConfigIni().orEmpty()
    val updated = DolphinConfig.upsert(existing, uri)
    if (updated != existing) {
      tree.writeConfigIni(updated)
      Timber.tag(TAG)
        .i("Registered %s in Dolphin.ini (added ISOPath entry)", uri)
    } else {
      Timber.tag(TAG).d("Dolphin.ini already contains %s; no edit needed", uri)
    }
  }

  /**
   * Returns the first ISO/RVZ/WBFS entry from [DolphinTree.romDir] in provider iteration order,
   * or null. No sorting or file-content validation is performed.
   */
  fun pickRomFile(tree: DolphinTree): DocumentFile? =
    tree.romDir.listFiles()?.firstOrNull { file ->
      val name = file.name ?: return@firstOrNull false
      val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
      ext in ROM_EXTENSIONS
    }

  /**
   * Starts Dolphin without `AutoStartFile`. Returns false if the package lookup or intent
   * dispatch fails; cannot observe errors inside Dolphin.
   */
  fun startDolphin(context: Context): Boolean {
    if (!isDolphinInstalled(context)) return false
    val intent =
      Intent().apply {
        component = ComponentName(DOLPHIN_PACKAGE, DOLPHIN_MAIN_ACTIVITY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return runCatching { context.startActivity(intent) }.isSuccess
  }

  private fun optionObject(optionName: String, choice: Int): JSONObject =
    JSONObject()
      .put("choice", choice)
      .put("option-name", optionName)
      .put("section-name", DISPLAY_NAME)
}
