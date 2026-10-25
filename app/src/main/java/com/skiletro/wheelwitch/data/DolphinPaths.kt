package com.skiletro.wheelwitch.data

import android.content.Context
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import java.io.File
import kotlin.text.Regex.Companion.escape

/**
 * Physical paths shared by launch descriptors and Dolphin configuration. SAF I/O uses
 * [DolphinTree]; these paths are for consumers that require filesystem paths.
 */
object DolphinPaths {
  const val DOLPHIN_PACKAGE: String = DolphinLauncher.DOLPHIN_PACKAGE

  /** Subpath under Dolphin's `files/` dir where WheelWitch keeps the pack, ROM, and launch descriptor. */
  const val WHEELWITCH_SUBPATH: String = "WheelWitch"

  /**
   * Absolute filesystem path of Dolphin's per-user folder.
   *
   * Computed via the package-swap trick. [Context.getExternalFilesDir]
   * is called with a `null` type, which returns
   * `/storage/emulated/<userId>/Android/data/<calling_pkg>/files`. The
   * calling package segment is then replaced with [DOLPHIN_PACKAGE].
   * This assumes WheelWitch and Dolphin are in the same user, which is
   * the common case; the work-profile mismatch is a known limitation.
   *
   * @throws IllegalStateException if [Context.getExternalFilesDir] returns null.
   */
  fun physicalRoot(context: Context): String {
    val ourPath =
      context.getExternalFilesDir(null)?.absolutePath
        ?: error("External files dir unavailable")
    return ourPath.replaceFirst(Regex(escape(context.packageName)), DOLPHIN_PACKAGE)
  }

  /** The directory where WheelWitch stores its pack, ROM, and launch descriptor. */
  fun wheelWitchDir(context: Context): File = File(physicalRoot(context), WHEELWITCH_SUBPATH)

  /** Extraction root; archive entry paths are relative to this directory. */
  fun packDir(context: Context): File = File(wheelWitchDir(context), "pack")

  /** Directory containing the user-picked Mario Kart Wii ROM, renamed to `<GAMEID>.<ext>`. */
  fun romDir(context: Context): File = File(wheelWitchDir(context), "rom")

  /** Path to the launch descriptor that Riivolution reads to launch Retro Rewind. */
  fun rrJsonFile(context: Context): File = File(romDir(context), "rr_autostartfile.json")

  /** Path to Dolphin's `Dolphin.ini` (where the `ISOPathN` block lives). */
  fun configIni(context: Context): File = File(physicalRoot(context), "Config/Dolphin.ini")

  /** Dolphin's virtual NAND root. Used by the unified save backup to reach user data. */
  fun wiiDir(context: Context): File = File(physicalRoot(context), "Wii")

  fun shared2Dir(context: Context): File = File(physicalRoot(context), "Wii/shared2")

  /** `Wii/shared2/menu/FaceLib/` — home of the system-wide Mii database (`RFL_DB.dat`). */
  fun faceLibDir(context: Context): File =
    File(physicalRoot(context), "Wii/shared2/menu/FaceLib")

  fun pulsarDir(context: Context): File = File(physicalRoot(context), "Wii/shared2/Pulsar")

  /** `Wii/shared2/Pulsar/RetroRewind6/` — Retro Rewind's Pulsar subdir (rating, game settings, ghosts). */
  fun pulsarRrDir(context: Context): File =
    File(physicalRoot(context), "Wii/shared2/Pulsar/RetroRewind6")

  /** `pack/riivolution/save/RetroWFC/` — per-region `rksys.dat` parent directory. */
  fun rrSaveDir(context: Context): File =
    File(packDir(context), "riivolution/save/RetroWFC")

  fun nandTitleDir(context: Context, titleId: String): File =
    File(physicalRoot(context), "Wii/title/$titleId")

  /**
   * Directory for a vanilla MKW save at
   * `Wii/title/00010004/<regionAsHex>/data/`.
   */
  fun vanillaSaveDir(context: Context, region: SaveManager.Region): File =
    File(nandTitleDir(context, SaveManager.TITLE_ID), "${region.hexCode()}/data")

  /**
   * Directory for a patched-ISO save at
   * `Wii/title/00010004/524d4352/data/`.
   */
  fun patchedIsoSaveDir(context: Context): File =
    File(nandTitleDir(context, SaveManager.TITLE_ID), "${SaveManager.PATCHED_ISO_HEX_ID}/data")

  /** Primary-storage SAF tree ID; [DolphinTree.validate] also accepts Dolphin's provider root. */
  fun expectedTreeId(): String = "primary:Android/data/$DOLPHIN_PACKAGE/files"
}
