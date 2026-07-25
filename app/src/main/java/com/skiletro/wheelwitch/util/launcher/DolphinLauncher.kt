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
 * Constants and helpers for the Dolphin Emulator package.
 *
 * [buildLaunchJson] is the pure JSON-shape helper that produces the
 * `dolphin-game-mod-descriptor` body Riivolution reads. [launch]
 * combines the descriptor with the physical path of the user-picked
 * ROM and fires an [Intent] at Dolphin's main activity with the
 * `AutoStartFile` extra pointing at the on-disk JSON.
 *
 * Path consistency invariant: the descriptor's `base-file`,
 * `root`, and `xml` fields, plus the `AutoStartFile` extra, all derive
 * from the same [DolphinPaths.physicalRoot] call. This guarantees
 * Riivolution can `open(2)` every path the descriptor references.
 */
object DolphinLauncher {
  const val DOLPHIN_PACKAGE = "org.dolphinemu.dolphinemu"
  const val DOLPHIN_MAIN_ACTIVITY = "$DOLPHIN_PACKAGE.ui.main.MainActivity"

  /**
   * Filename of the launch descriptor at the WheelWitch root. Also
   * re-exported as [com.skiletro.wheelwitch.data.DolphinTree.LAUNCH_JSON_NAME]
   * so there is one source of truth.
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

  /** ROM file extensions that count as a valid launch ROM. */
  private val ROM_EXTENSIONS = setOf("iso", "rvz", "wbfs")

  /** Tag used by Timber in this object's log lines. */
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
   * Build the `dolphin-game-mod-descriptor` JSON body that Riivolution
   * reads when launching Retro Rewind. Pure: no I/O, no Android state.
   *
   * The output uses the field names, ordering, and choice values
   * that Dolphin's `dolphin-game-mod-descriptor` schema expects. The
   * three path fields (`base-file`, `root`, `xml`) are derived from
   * the caller's `baseFilePath` and `packRootPath`. For the launch
   * to succeed, all three must come from the same `physicalRoot`
   * call (the Path consistency invariant). `xml` is the join of
   * `packRootPath` and [xmlRelPath], so passing the same pack root
   * for both `root` and the prefix of `xml` is automatic.
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
   * Writes the launch descriptor to the SAF tree and fires the
   * Dolphin intent with [LAUNCH_EXTRA_NAME] pointing at the
   * descriptor's physical path.
   *
   * Steps:
   * 1. Compute `base-file` (the ROM's physical path under
   *    [DolphinPaths.romDir]) and `root` (the pack dir).
   * 2. Build the descriptor with [buildLaunchJson] and write it via
   *    [DolphinTree.writeLaunchJson].
   * 3. Fire an intent at [DOLPHIN_MAIN_ACTIVITY] with
   *    [LAUNCH_EXTRA_NAME] = the JSON's physical path.
   *
   * Returns a failure (instead of throwing) if Dolphin isn't
   * installed, if the external files dir is unavailable, or if the
   * SAF write throws.
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
   * Builds the [Intent] that launches Dolphin's main activity with
   * the launch-descriptor's physical path. Extracted from [launch]
   * so the intent shape can be tested directly with a real [Intent]
   * instance (mocking `Context.startActivity` on Android's abstract
   * `Context.startActivity` drops the argument in MockK).
   */
  fun buildDolphinIntent(jsonPath: String): Intent =
    Intent().apply {
      component = ComponentName(DOLPHIN_PACKAGE, DOLPHIN_MAIN_ACTIVITY)
      putExtra(LAUNCH_EXTRA_NAME, jsonPath)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

  /**
   * Outcome of [launchRetroRewind]. The auto-start path is the
   * preferred UX; RR launches with the descriptor pre-loaded. The
   * fallback path is fired when the auto-start attempt fails (e.g.
   * the `AutoStartFile` extra throws "can't find content or path"
   * inside Dolphin). We start Dolphin bare so the user can pick RR
   * from the library; the WheelWitch-managed `ISOPathN` entry keeps
   * RR in that library.
   */
  sealed class LaunchResult {
    /** Auto-start path worked; RR is launching with the descriptor. */
    data object AutoStarted : LaunchResult()

    /**
     * Auto-start failed but we started Dolphin without the extra so
     * the user can launch RR from the library. [cause] carries the
     * original failure for logging.
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
   * High-level entry point used by the home screen launch button.
   * Performs the full pre-launch sequence in order:
   *
   * 1. Validate Dolphin is installed.
   * 2. Reconstruct the [DolphinTree] from the persisted SAF URI.
   * 3. Pick the launch ROM (the first ISO/RVZ/WBFS in
   *    `tree.romDir`, alphabetically by file name).
   * 4. Upsert the rom directory into Dolphin's `Config/Dolphin.ini`
   *    so RR shows up in the library.
   * 5. Write the launch descriptor and fire the `AutoStartFile`
   *    intent via [launch].
   * 6. On any failure, fall back to starting Dolphin without the
   *    extra and return [LaunchResult.FallbackStarted]. The user
   *    can still launch RR from the library.
   */
  fun launchRetroRewind(context: Context): LaunchResult {
    if (!isDolphinInstalled(context)) return LaunchResult.DolphinNotInstalled
    val tree =
      DolphinTree.fromPersisted(context) ?: return LaunchResult.StorageNotConfigured
    return launchRetroRewind(context, tree)
  }

  /**
   * Overload that takes an explicit [tree]. Useful for callers that
   * already hold a tree (tests, composables that construct it
   * inline) and want to skip the [DolphinTree.fromPersisted] lookup.
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
   * Reads `Config/Dolphin.ini` from [tree], upserts the
   * `content://org.dolphinemu.dolphinemu.user/tree/root%2FWheelWitch%2From`
   * URI (the rom directory the user picked via SAF), and writes the
   * file back. The URI is the one Dolphin's own SAF provider expects.
   * See [DolphinConfig.dolphinUserTreeUri]. This is the bridge that
   * makes "RR shows up in Dolphin's library" work.
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
   * Picks the launch ROM. Returns the first ISO/RVZ/WBFS file in
   * [DolphinTree.romDir] in iteration order, or null when no
   * recognized ROM is present. Multi-region setups surface one ROM
   * per region; this picks the first by name for the launch.
   */
  fun pickRomFile(tree: DolphinTree): DocumentFile? =
    tree.romDir.listFiles()?.firstOrNull { file ->
      val name = file.name ?: return@firstOrNull false
      val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
      ext in ROM_EXTENSIONS
    }

  /**
   * Bare Dolphin launch: no `AutoStartFile` extra. Used as the
   * fallback when the auto-start path throws inside Dolphin. Returns
   * true if the intent was started, false if Dolphin isn't installed.
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
