package com.skiletro.wheelwitch.data

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.util.io.ZipSafety
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Unified save data backup/restore and per-region helpers for the Licenses viewer.
 *
 * ## Per-region read API (Licenses viewer)
 *
 * The Licenses screen still needs to know which regions are present and to read each region's
 * `rksys.dat` bytes for parsing, so [Region], [regionFromRomFileName], [listRegions], [hasSave],
 * and [readSave] remain. They share a single path layout under
 * `pack/riivolution/save/RetroWFC/<region>/rksys.dat` driven by
 * [com.skiletro.wheelwitch.data.DolphinTree.packDir].
 *
 * ## Unified save data backup/restore (Settings)
 *
 * The Settings Save Data section exposes three actions that touch the user's save data in a single
 * zip:
 * ```
 * wheelwitch-save-<millis>.zip
 * ├── manifest.json            (includes the source pack "rrVersion")
 * ├── RetroWFC/
 * │   ├── RMCP/rksys.dat
 * │   ├── RMCE/rksys.dat
 * │   └── RMCJ/rksys.dat      (all regions, missing ones skipped)
 * └── Wii/shared2/
 *     ├── menu/FaceLib/RFL_DB.dat
 *     └── Pulsar/RetroRewind6/
 *         ├── RRRating.pul
 *         ├── RRGameSettings.pul
 *         ├── RRSettings.pul
 *         └── Ghosts/<...>     (recursive, individual files only)
 * ```
 *
 * Vanilla NAND saves and the patched-ISO save are NOT included in new backups, but restore still
 * accepts them so older backups keep round-tripping.
 *
 * The manifest records the Retro Rewind pack version ([DolphinTree .readVersion]) the backup was
 * taken from as `rrVersion`. `RRGameSettings.pul` is reset by the pack on every update, so restore
 * only writes it when the backup's `rrVersion` matches the currently installed pack version —
 * otherwise the current file is left untouched. Restore of every other entry is unconditional.
 *
 * The format is the `wheelwitch-save` v2 zip. Restore reads `manifest.json` first, refuses anything
 * whose `type` isn't `wheelwitch-save` or whose `version` is higher than the app supports, then
 * writes every other entry to the right place under Dolphin's user folder. Paths are validated
 * against [userDataPathPrefixes] to refuse zip-slip entries.
 */
class InvalidBackupException(
    val reason: Reason,
    message: String,
    val version: Int = 0,
) : IOException(message) {
  enum class Reason {
    MissingManifest,
    WrongType,
    TooNew,
  }
}

object SaveManager {
  /** Wii MKW region codes. The [code] is the 4-character ROM-file prefix. */
  enum class Region(val code: String) {
    PAL("RMCP"),
    USA("RMCE"),
    JPN("RMCJ"),
    KOR("RMCK");

    fun hexCode(): String =
        code.codePoints().toArray().joinToString("") { cp ->
          cp.toString(16).lowercase().padStart(2, '0')
        }
  }

  /** Filename of the save file inside the region directory. */
  const val SAVE_FILE_NAME = "rksys.dat"

  /** Wii title ID for game channels (also used by patched ISOs). */
  const val TITLE_ID = "00010004"

  /**
   * Hex-encoded game ID for patched Retro Rewind ISOs (`RMCR`). Dolphin stores its NAND save at
   * `Wii/title/00010004/524d4352/data/rksys.dat` when the ISO has been patched (the game ID changes
   * from `RMCx` to `RMCR`).
   */
  const val PATCHED_ISO_HEX_ID = "524d4352"

  /**
   * Path prefix for the per-region `rksys.dat` tree under `<pack>/riivolution/save/`. Kept as a
   * `const val` so the pack extraction carve-out
   * ([com.skiletro.wheelwitch.data.DolphinTree.writeZipEntry]) can reference it without an
   * allocation per entry. The full set of user-data prefixes (including the Mii DB and Pulsar
   * paths) lives in [userDataPathPrefixes].
   */
  const val SAVE_PRESERVE_PREFIX = "riivolution/save/"

  /**
   * Path prefixes inside the pack directory whose files are owned by the user, not the pack. The
   * pack extraction ([com.skiletro.wheelwitch.data.DolphinTree.extractZipToPack]) skips any zip
   * entry that starts with one of these prefixes so an update never clobbers user save data, the
   * Mii DB, Pulsar settings, or ghost data. The trailing slash makes the match precise.
   */
  val userDataPathPrefixes: List<String> =
      listOf(
          SAVE_PRESERVE_PREFIX,
          "Wii/shared2/menu/FaceLib/",
          "Wii/shared2/Pulsar/RetroRewind6/",
      )

  /**
   * Names of the well-known user-data files outside the `riivolution/save/` tree. Held in one place
   * so [hasAnySave], [backup], and [delete] stay in lock-step.
   */
  object UserDataPaths {
    /** System-wide Mii database at `Wii/shared2/menu/FaceLib/`. */
    const val RFL_DB = "RFL_DB.dat"

    /** Pulsar pul files at `Wii/shared2/Pulsar/RetroRewind6/`. */
    val PUL_FILES: List<String> = listOf("RRRating.pul", "RRGameSettings.pul", "RRSettings.pul")

    /** Ghosts directory under `Wii/shared2/Pulsar/RetroRewind6/`. */
    const val GHOSTS_DIR = "Ghosts"
  }

  /**
   * The Pulsar game-settings file that the pack resets on every update. Restore only writes it when
   * the backup's `rrVersion` matches the installed pack version.
   */
  private const val GAME_SETTINGS_PUL = "RRGameSettings.pul"

  /** Version of the `wheelwitch-save` zip format emitted by [backup]. */
  const val BACKUP_FORMAT_VERSION: Int = 2

  /** Type tag stored in `manifest.json`; restore rejects anything else. */
  const val BACKUP_TYPE: String = "wheelwitch-save"

  /**
   * Maps a ROM file name to its [Region]. Returns null if [name] does not start with any known
   * region code. Match is case-insensitive so the helper tolerates the rare mixed-case pick (the
   * ROM is always uppercased on copy in [DolphinTree.copyRomFromSource] but defensive parsing is
   * cheap).
   */
  fun regionFromRomFileName(name: String): Region? =
      Region.entries.firstOrNull { name.startsWith(it.code, ignoreCase = true) }

  /**
   * Returns the regions for which the user has a ROM in [DolphinTree.romDir]. Order matches the
   * iteration order of [DocumentFile.listFiles], which is platform-dependent but stable for a given
   * picker result.
   */
  fun listRegions(tree: DolphinTree): List<Region> {
    val files = tree.romDir.listFiles()
    return files.mapNotNull { regionFromRomFileName(it.name ?: "") }.distinct()
  }

  /**
   * True when a `rksys.dat` exists at `<packRoot>/riivolution/save/RetroWFC/<region>/rksys.dat`.
   * Intermediate directories are created on demand if missing.
   */
  fun hasSave(tree: DolphinTree, region: Region): Boolean {
    val file = saveFile(tree, region) ?: return false
    return file.exists()
  }

  /**
   * Reads the raw `rksys.dat` bytes for [region] (null if absent). Used by
   * [com.skiletro.wheelwitch.viewmodel.SaveDataViewModel] to feed [RksysParser]. Idempotent and
   * side-effect free.
   */
  suspend fun readSave(tree: DolphinTree, region: Region): ByteArray? =
      withContext(Dispatchers.IO) { readSaveBytes(tree, region) }

  // --- unified save data ------------------------------------------------

  /**
   * True when any file the unified backup covers is present: a per-region `rksys.dat`, the Mii DB,
   * any Pulsar pul file, or the Ghosts directory. Cheap, single-shot, no side effects. Drives the
   * Save Data section's enabled/disabled state.
   */
  fun hasAnySave(tree: DolphinTree): Boolean {
    if (Region.entries.any { hasSave(tree, it) }) return true
    if (tree.faceLibDir?.findFile(UserDataPaths.RFL_DB)?.exists() == true) return true
    val pulsar = tree.pulsarRrDir
    if (pulsar != null) {
      if (UserDataPaths.PUL_FILES.any { pulsar.findFile(it)?.exists() == true }) return true
      val ghosts = pulsar.findFile(UserDataPaths.GHOSTS_DIR)
      if (ghosts != null && ghosts.exists() && ghosts.isDirectory) {
        if (!ghosts.listFiles().isNullOrEmpty()) return true
      }
    }
    return false
  }

  /**
   * Snapshots the user's save data (all regions' RR `rksys.dat`, the Mii DB, all Pulsar pul files,
   * and the Ghosts directory) into a single zip at [dest] (typically from
   * `ACTION_CREATE_DOCUMENT`). The zip is prefixed with a `manifest.json` describing what was
   * included, stamped with the installed Retro Rewind pack version ([DolphinTree.readVersion]) as
   * `rrVersion`.
   *
   * Missing files are silently skipped — the backup is partial-tolerant so a fresh install with no
   * Retro Rewind data still produces a valid zip that round-trips. Returns a [BackupSummary] with
   * the per-section counts.
   */
  suspend fun backup(tree: DolphinTree, dest: Uri): Result<BackupSummary> =
      withContext(Dispatchers.IO) {
        val result = runCatching {
          val output =
              tree.resolver.openOutputStream(dest)
                  ?: throw IOException("Cannot open output stream for $dest")
          output.use { stream ->
            ZipOutputStream(stream).use { zip ->
              // The restore reads the zip sequentially with
              // ZipInputStream (no seek). The manifest must be the
              // first entry so the restore can validate it before
              // processing the data. To populate the manifest with
              // real counts, do a pre-pass that collects what's
              // available, then write manifest + data in a single
              // stream.
              val availableRegions = Region.entries.filter { hasSave(tree, it) }
              val faceLibFile = tree.faceLibDir?.findFile(UserDataPaths.RFL_DB)
              val faceLibAvailable =
                  faceLibFile != null && faceLibFile.exists() && faceLibFile.isFile
              val pulsar = tree.pulsarRrDir
              val availablePul =
                  if (pulsar != null) {
                    UserDataPaths.PUL_FILES.filter { name ->
                      val f = pulsar.findFile(name)
                      f != null && f.exists() && f.isFile
                    }
                  } else emptyList()
              val ghostCount = countGhostFiles(tree)
              val rrVersion = tree.readVersion()?.toString()

              writeManifest(
                  zip,
                  BackupManifest(
                      regions = availableRegions.map { it.code },
                      rrVersion = rrVersion,
                      faceLib = faceLibAvailable,
                      pulsar = availablePul,
                      ghosts = ghostCount,
                  ),
              )

              val includedRegions = mutableListOf<String>()
              for (region in availableRegions) {
                val file = saveFile(tree, region)
                if (file != null && file.exists() && file.isFile) {
                  val bytes = readDolphinBytes(tree.resolver, file) ?: continue
                  writeZipBytes(zip, "RetroWFC/${region.code}/${SAVE_FILE_NAME}", bytes)
                  includedRegions.add(region.code)
                }
              }
              if (faceLibAvailable && faceLibFile != null) {
                readDolphinBytes(tree.resolver, faceLibFile)?.let { bytes ->
                  writeZipBytes(zip, "Wii/shared2/menu/FaceLib/${UserDataPaths.RFL_DB}", bytes)
                }
              }
              if (pulsar != null) {
                for (name in availablePul) {
                  val file = pulsar.findFile(name)
                  if (file != null && file.exists() && file.isFile) {
                    val bytes = readDolphinBytes(tree.resolver, file) ?: continue
                    writeZipBytes(zip, "Wii/shared2/Pulsar/RetroRewind6/$name", bytes)
                  }
                }
                val ghosts = pulsar.findFile(UserDataPaths.GHOSTS_DIR)
                recursiveCopyToStream(
                    tree.resolver,
                    ghosts,
                    zip,
                    "Wii/shared2/Pulsar/RetroRewind6/${UserDataPaths.GHOSTS_DIR}",
                )
              }
              val summary =
                  BackupSummary(
                      rksys = includedRegions.size,
                      faceLib = faceLibAvailable,
                      pulsar = availablePul.size,
                      ghosts = ghostCount,
                  )
              Timber.tag(TAG).i("Backed up user save data to %s: %s", dest, summary)
              summary
            }
          }
        }
        if (result.isFailure) {
          val failure = result.exceptionOrNull()!!
          withContext(NonCancellable) {
            try {
              if (!DocumentsContract.deleteDocument(tree.resolver, dest)) {
                Timber.tag(TAG).w("Provider refused to delete partial backup destination %s", dest)
              }
            } catch (cleanupFailure: Exception) {
              Timber.tag(TAG)
                  .w(cleanupFailure, "Failed to delete partial backup destination %s", dest)
            }
          }
          if (failure is CancellationException) throw failure
        }
        result
      }

  /**
   * Reads the user-picked [source] zip (typically from `ACTION_OPEN_DOCUMENT`) and overwrites the
   * user's save data in place. The zip's `manifest.json` is validated first; zips without a
   * matching `type` / `version` are rejected with a descriptive error. Per-entry paths are
   * validated against [userDataPathPrefixes] to refuse zip-slip-style entries that point outside
   * the protected user-data directories.
   *
   * `RRGameSettings.pul` is only written when the backup's `rrVersion` matches the currently
   * installed pack version ([DolphinTree.readVersion]); the pack resets that file on every update,
   * so restoring a stale one would corrupt the current install's game settings. Every other entry
   * (including the vanilla NAND saves and patched-ISO saves found in older backups) is restored
   * unconditionally.
   *
   * Intermediate directories are created on demand. Missing source files are silently skipped (the
   * backup may have been taken before some files existed).
   */
  suspend fun restore(tree: DolphinTree, source: Uri): Result<RestoreSummary> =
      withContext(Dispatchers.IO) {
        runCatching {
          val input =
              tree.resolver.openInputStream(source)
                  ?: throw IOException("Cannot open input stream for $source")
          input.use { stream ->
            ZipInputStream(stream).use { zip ->
              val backupRrVersion = readAndValidateManifest(zip)?.let { SemVersion.parse(it) }
              val installedVersion = tree.readVersion()
              val restoreGameSettings =
                  backupRrVersion != null &&
                      installedVersion != null &&
                      backupRrVersion == installedVersion
              var rksys = 0
              var vanillaSaves = 0
              var patchedIso = false
              var faceLib = false
              var pulsar = 0
              var ghosts = 0
              while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val entryName = entry.name
                val target = resolveRestoreTarget(tree, entryName)
                if (target == null) {
                  Timber.tag(TAG).w("Skipping unknown zip entry: %s", entryName)
                  continue
                }
                val parent = target.first
                val name = target.second
                if (
                    entryName == "Wii/shared2/Pulsar/RetroRewind6/$GAME_SETTINGS_PUL" &&
                        !restoreGameSettings
                ) {
                  Timber.tag(TAG)
                      .i(
                          "Skipping RRGameSettings.pul: backup rrVersion %s != installed %s",
                          backupRrVersion,
                          installedVersion,
                      )
                  continue
                }
                val bytes = zip.readBytes()
                writeDolphinBytes(tree.resolver, parent, name, bytes)
                when {
                  entryName.startsWith("RetroWFC/") && name == SAVE_FILE_NAME -> rksys++
                  entryName.startsWith("Wii/title/$TITLE_ID/") &&
                      !entryName.startsWith("Wii/title/$TITLE_ID/$PATCHED_ISO_HEX_ID/data") &&
                      name == SAVE_FILE_NAME -> vanillaSaves++
                  entryName.startsWith("Wii/title/$TITLE_ID/$PATCHED_ISO_HEX_ID/data") &&
                      name == SAVE_FILE_NAME -> patchedIso = true
                  entryName.startsWith("Wii/shared2/menu/FaceLib/") &&
                      name == UserDataPaths.RFL_DB -> faceLib = true
                  entryName.startsWith("Wii/shared2/Pulsar/RetroRewind6/") &&
                      name in UserDataPaths.PUL_FILES -> pulsar++
                  entryName.startsWith(
                      "Wii/shared2/Pulsar/RetroRewind6/${UserDataPaths.GHOSTS_DIR}/"
                  ) -> ghosts++
                }
              }
              val summary = RestoreSummary(rksys, vanillaSaves, patchedIso, faceLib, pulsar, ghosts)
              Timber.tag(TAG).i("Restored user save data from %s: %s", source, summary)
              summary
            }
          }
        }
      }

  /**
   * Wipes every file the unified backup covers: all regions' `rksys.dat`, the Mii DB, all Pulsar
   * pul files, and the contents of the Ghosts directory. The parent directories are left in place
   * (Dolphin/Pulsar may recreate them on next launch). Idempotent.
   */
  suspend fun delete(tree: DolphinTree): Result<Unit> =
      withContext(Dispatchers.IO) {
        runCatching {
          for (region in Region.entries) {
            saveFile(tree, region)?.let { if (it.exists()) it.delete() }
          }
          tree.faceLibDir?.findFile(UserDataPaths.RFL_DB)?.let { if (it.exists()) it.delete() }
          val pulsar = tree.pulsarRrDir
          if (pulsar != null) {
            for (name in UserDataPaths.PUL_FILES) {
              pulsar.findFile(name)?.let { if (it.exists()) it.delete() }
            }
            val ghosts = pulsar.findFile(UserDataPaths.GHOSTS_DIR)
            if (ghosts != null && ghosts.exists() && ghosts.isDirectory) {
              for (child in ghosts.listFiles()) recursiveDelete(child)
            }
          }
          Timber.tag(TAG).i("Deleted all user save data")
        }
      }

  // --- per-region internals ---------------------------------------------

  private fun readSaveBytes(tree: DolphinTree, region: Region): ByteArray? {
    val file = saveFile(tree, region)
    if (file == null || !file.exists()) return null
    return readDolphinBytes(tree.resolver, file)
  }

  private fun saveFile(tree: DolphinTree, region: Region): DocumentFile? {
    val dir = saveDir(tree, region)
    return dir.findFile(SAVE_FILE_NAME)
  }

  private fun saveDir(tree: DolphinTree, region: Region): DocumentFile =
      navigateOrCreate(tree.packDir, listOf("riivolution", "save", "RetroWFC", region.code))

  // --- shared internals --------------------------------------------------

  /**
   * Resolves a [DocumentFile] parent + filename for a backup zip entry. Returns null when the entry
   * path isn't under one of the [userDataPathPrefixes] or the known NAND paths (zip-slip defence).
   * Creates intermediate directories as needed.
   */
  private fun resolveRestoreTarget(
      tree: DolphinTree,
      entryName: String,
  ): Pair<DocumentFile, String>? {
    if (!ZipSafety.isSafeEntryName(entryName)) return null
    val normalized = ZipSafety.normalizeEntryName(entryName)
    if (normalized.startsWith("RetroWFC/")) {
      val rest = normalized.removePrefix("RetroWFC/")
      val parts = rest.split('/').filter { it.isNotEmpty() }
      if (parts.size != 2 || parts[0] !in Region.entries.map { it.code }) return null
      val region = Region.entries.first { it.code == parts[0] }
      val dir = saveDir(tree, region)
      return dir to parts[1]
    }
    if (normalized.startsWith("Wii/title/$TITLE_ID/")) {
      if (normalized.startsWith("Wii/title/$TITLE_ID/$PATCHED_ISO_HEX_ID/data")) {
        // Patched ISO: Wii/title/00010004/524d4352/data/<filename>
        val rest = normalized.removePrefix("Wii/title/$TITLE_ID/")
        val parts = rest.split('/').filter { it.isNotEmpty() }
        if (parts.size != 3 || parts[0] != PATCHED_ISO_HEX_ID || parts[1] != "data") return null
        val dir =
            navigateOrCreate(
                tree.root,
                listOf("Wii", "title", TITLE_ID, PATCHED_ISO_HEX_ID, "data"),
            )
        return dir to parts[2]
      }
      // Vanilla save: Wii/title/00010004/<regionAsHex>/data/<filename>
      val rest = normalized.removePrefix("Wii/title/$TITLE_ID/")
      val parts = rest.split('/').filter { it.isNotEmpty() }
      if (parts.size != 3 || parts[1] != "data") return null
      val region = parts[0]
      if (region !in Region.entries.map { it.hexCode() }) return null
      val dir = navigateOrCreate(tree.root, listOf("Wii", "title", TITLE_ID, region, "data"))
      return dir to parts[2]
    }
    if (normalized.startsWith("Wii/shared2/menu/FaceLib/")) {
      val name = normalized.removePrefix("Wii/shared2/menu/FaceLib/")
      if (name.contains('/')) return null
      val dir =
          tree.faceLibDir
              ?: navigateOrCreate(tree.root, listOf("Wii", "shared2", "menu", "FaceLib"))
      return dir to name
    }
    if (normalized.startsWith("Wii/shared2/Pulsar/RetroRewind6/")) {
      val rest = normalized.removePrefix("Wii/shared2/Pulsar/RetroRewind6/")
      val parts = rest.split('/').filter { it.isNotEmpty() }
      if (parts.isEmpty()) return null
      val baseDir =
          tree.pulsarRrDir
              ?: navigateOrCreate(tree.root, listOf("Wii", "shared2", "Pulsar", "RetroRewind6"))
      if (parts.size == 1) {
        return baseDir to parts[0]
      }
      if (parts.first() != UserDataPaths.GHOSTS_DIR) return null
      val parent = navigateOrCreate(baseDir, parts.dropLast(1))
      return parent to parts.last()
    }
    return null
  }

  /**
   * Recursively counts every file under the Ghosts directory, used to fill in the backup manifest.
   * Returns 0 when Ghosts is absent.
   */
  private fun countGhostFiles(tree: DolphinTree): Int {
    val ghosts = tree.pulsarRrDir?.findFile(UserDataPaths.GHOSTS_DIR) ?: return 0
    if (!ghosts.exists() || !ghosts.isDirectory) return 0
    return countFilesRecursive(ghosts)
  }

  private fun countFilesRecursive(dir: DocumentFile): Int {
    var count = 0
    for (child in dir.listFiles() ?: emptyArray()) {
      if (child.isDirectory) count += countFilesRecursive(child) else if (child.isFile) count++
    }
    return count
  }

  /**
   * Finds and validates the manifest entry, returning the backup's `rrVersion` stamp (null when the
   * backup predates version stamping).
   */
  private fun readAndValidateManifest(zip: ZipInputStream): String? {
    val manifestEntry =
        findManifestEntry(zip)
            ?: throw InvalidBackupException(
                InvalidBackupException.Reason.MissingManifest,
                "Selected file is not a WheelWitch save backup (no manifest.json)",
            )
    val raw = zip.readBytes().toString(Charsets.UTF_8)
    val obj = JSONObject(raw)
    val type = obj.optString("type")
    val version = obj.optInt("version", 0)
    if (type != BACKUP_TYPE) {
      throw InvalidBackupException(
          InvalidBackupException.Reason.WrongType,
          "Selected file is not a WheelWitch save backup (type='$type')",
      )
    }
    if (version > BACKUP_FORMAT_VERSION) {
      throw InvalidBackupException(
          InvalidBackupException.Reason.TooNew,
          "Backup was created with a newer version of Wheel Witch (format v$version); update the app to restore it.",
          version = version,
      )
    }
    return if (obj.has("rrVersion")) obj.getString("rrVersion") else null
  }

  private fun findManifestEntry(zip: ZipInputStream): ZipEntry? {
    while (true) {
      val entry = zip.nextEntry ?: return null
      if (entry.name == "manifest.json") return entry
    }
  }

  private fun writeZipBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    zip.putNextEntry(ZipEntry(name).apply { size = bytes.size.toLong() })
    zip.write(bytes)
    zip.closeEntry()
  }

  private fun writeManifest(zip: ZipOutputStream, manifest: BackupManifest) {
    val obj = JSONObject()
    obj.put("version", BACKUP_FORMAT_VERSION)
    obj.put("type", BACKUP_TYPE)
    obj.put("createdAt", System.currentTimeMillis())
    manifest.rrVersion?.let { obj.put("rrVersion", it) }
    val contents = JSONObject()
    contents.put("retroWfc", org.json.JSONArray(manifest.regions))
    contents.put("vanillaSaves", org.json.JSONArray(emptyList<String>()))
    contents.put("patchedIso", false)
    contents.put("faceLib", manifest.faceLib)
    contents.put("pulsar", org.json.JSONArray(manifest.pulsar))
    contents.put("ghosts", manifest.ghosts)
    obj.put("contents", contents)
    writeZipBytes(zip, "manifest.json", obj.toString(2).encodeToByteArray())
  }

  /**
   * Internal manifest payload. The `version` and `type` tags live in the JSON envelope; the
   * [BackupSummary] / [RestoreSummary] returned to the view-model is a flat count projection for UI
   * use. The `rrVersion` stamp records which Retro Rewind pack version the backup was taken from
   * (null when the pack was not installed).
   */
  internal data class BackupManifest(
      val regions: List<String>,
      val rrVersion: String?,
      val faceLib: Boolean,
      val pulsar: List<String>,
      val ghosts: Int,
  )

  /** Counts returned by [backup] for UI feedback. */
  data class BackupSummary(
      val rksys: Int,
      val faceLib: Boolean,
      val pulsar: Int,
      val ghosts: Int,
  )

  /** Counts returned by [restore] for UI feedback. */
  data class RestoreSummary(
      val rksys: Int,
      val vanillaSaves: Int,
      val patchedIso: Boolean,
      val faceLib: Boolean,
      val pulsar: Int,
      val ghosts: Int,
  )

  private const val TAG = "SaveManager"
}
