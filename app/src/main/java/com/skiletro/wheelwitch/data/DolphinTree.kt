package com.skiletro.wheelwitch.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.io.ZipSafety
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Coarse phase of the pack extraction. Emitted via [ExtractProgress] so
 * the UI can show a "Preparing folders…" line during the directory
 * pre-pass (when the typical RPC cost of `findFile`/`createDirectory`
 * is paid all at once) and a "Extracting files…" line with a live
 * current-file name during the per-file write pass.
 */
enum class ExtractingPhase {
  /** Directory pre-pass is running. */
  PreparingFolders,

  /** Files are being written. */
  WritingFiles,
}

enum class InvalidTreeReason {
  NotTreeUri,
  NotDolphinFolder,
  SubfolderExternal,
  SubfolderInternal,
}

class InvalidTreeUriException(
  val reason: InvalidTreeReason,
  message: String,
) : IllegalArgumentException(message)

/**
 * Per-file progress snapshot emitted by [DolphinTree.extractZipToPack].
 * Replaces the previous bare `Int` (file index) callback so the UI can
 * render a meaningful bar and a live file-name readout.
 */
data class ExtractProgress(
  val phase: ExtractingPhase,
  val filesDone: Int,
  val filesTotal: Int,
  val currentFile: String?,
  val bytesDone: Long,
  val bytesTotal: Long,
)

/**
 * SAF-backed wrapper around the Dolphin user folder WheelWitch writes to.
 *
 * Holds a [ContentResolver] and a [DocumentFile] tree and exposes the
 * subdirectories the rest of the app needs:
 *
 * ```
 * <tree root>/
 * ├── WheelWitch/
 * │   ├── pack/
 * │   │   └── RetroRewind6/           : extracted Retro Rewind contents
 * │   │       └── version.txt         : local pack version (mirror of zip's own version.txt)
 * │   └── rom/
 * │       ├── <GAMEID>.<ext>          : user-picked Mario Kart Wii ISO/RVZ/WBFS
 * │       ├── rr_autostartfile.json   : launch descriptor (dolphin-game-mod-descriptor)
 * │       ├── rr_autostartfile.xml    : app-shipped metadata (templated with current version)
 * │       └── rr_autostartfile.cover.png : app-shipped cover banner
 * ├── Config/
 * │   └── Dolphin.ini                 : library-paths config
 * └── Wii/                            : Dolphin's virtual NAND root
 *     └── shared2/
 *         ├── menu/FaceLib/RFL_DB.dat : system-wide Mii database
 *         └── Pulsar/RetroRewind6/    : Retro Rewind's Pulsar config + Ghosts/
 * ```
 *
 * Construction is cheap: lazy subdirectory properties defer their first
 * `findFile` / `createDirectory` until the UI actually asks for them.
 * This keeps the constructor side-effect free and lets tests stub
 * individual directories.
 *
 * The tree URI must be the user-picked SAF grant that matches
 * [DolphinPaths.expectedTreeId]. [Companion.validate] is the gate: the
 * stock SAF picker cannot be restricted to a single folder, so the
 * picker result is checked against the expected tree id before we
 * accept it.
 */
class DolphinTree(context: Context, val treeUri: Uri) {
  val resolver: ContentResolver = context.contentResolver

  /**
   * Context kept for resource lookups (raw resources, package name).
   * The class only needs this to read app-shipped assets, not to do
   * SAF I/O; [resolver] is the SAF counterpart.
   */
  private val appContext: Context = context

  val root: DocumentFile =
    DocumentFile.fromTreeUri(context, treeUri)
      ?: run {
        // Diagnostic: log the URI scheme/authority (no auth tokens) so
        // a bug report can show whether the picker returned an
        // unexpected form. fromTreeUri returns null when the URI is
        // malformed, the tree has been revoked, or the provider is
        // unreachable; all "re-onboard" cases.
        val docId =
          runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        Timber.tag(TAG)
          .e(
            "DocumentFile.fromTreeUri returned null for uri=%s authority=%s docId=%s",
            treeUri,
            treeUri.authority,
            docId,
          )
        error("Invalid tree URI: $treeUri")
      }

  val wheelWitchDir: DocumentFile by lazy {
    findOrCreateDir(root, "WheelWitch")
      ?: error("Cannot create or find WheelWitch/ in Dolphin tree")
  }

  val romDir: DocumentFile by lazy {
    findOrCreateDir(wheelWitchDir, "rom")
      ?: error("Cannot create or find rom/ in WheelWitch dir")
  }

  val packDir: DocumentFile by lazy {
    findOrCreateDir(wheelWitchDir, "pack")
      ?: error("Cannot create or find pack/ in WheelWitch dir")
  }

  /**
   * The Retro Rewind subdirectory under [packDir]. The pack zip
   * extracts into this directory (so a zip entry `RetroRewind6/version.txt`
   * lands at `pack/RetroRewind6/version.txt`), and it's also where the
   * launcher's `riivolution/RetroRewind6.xml` lives. Read/write the
   * `version.txt` here, not at the root of [packDir] — WheelWitch used
   * to write to `pack/version.txt`, which is a path the pack zip
   * never touches, so the local version file drifted from the actual
   * pack version on every incremental update.
   */
  val retroRewindDir: DocumentFile by lazy {
    findOrCreateDir(packDir, RETRO_REWIND_DIR_NAME)
      ?: error("Cannot create or find $RETRO_REWIND_DIR_NAME/ in pack/")
  }

  /**
   * Dolphin's `GameSettings/` directory under the tree root. Holds
   * per-game INI configuration files. WheelWitch writes `RMC.ini`
   * here to force-disable cheats and RetroAchievements on launch.
   */
  val gameSettingsDir: DocumentFile by lazy {
    findOrCreateDir(root, "GameSettings")
      ?: error("Cannot create or find GameSettings/ in Dolphin tree")
  }

  /**
   * The Dolphin's `Wii/` directory under the tree root. Used by the
   * unified save backup to reach `Wii/shared2/...` (Mii DB, Pulsar
   * settings, ghost data). May be null when the user has never run
   * Retro Rewind; the unified backup tolerates that and just skips
   * the corresponding entries.
   */
  val wiiDir: DocumentFile? by lazy { findDir(root, "Wii") }

  /**
   * `Wii/shared2/` under [wiiDir]. Null if [wiiDir] is null or the
   * `shared2/` subdir hasn't been created by Dolphin yet.
   */
  val shared2Dir: DocumentFile? by lazy { findDir(wiiDir, "shared2") }

  /**
   * `Wii/shared2/menu/FaceLib/` under [shared2Dir] — home of the
   * system-wide Mii database (`RFL_DB.dat`).
   */
  val faceLibDir: DocumentFile? by lazy { findDir(findDir(shared2Dir, "menu"), "FaceLib") }

  /**
   * `Wii/shared2/Pulsar/` under [shared2Dir] — Pulsar mod settings.
   */
  val pulsarDir: DocumentFile? by lazy { findDir(shared2Dir, "Pulsar") }

  /**
   * `Wii/shared2/Pulsar/RetroRewind6/` under [pulsarDir] — the
   * Retro Rewind specific Pulsar subdir. Holds the rating pul
   * files and the Ghosts/ directory backed up by the unified save.
   */
  val pulsarRrDir: DocumentFile? by lazy { findDir(pulsarDir, RETRO_REWIND_DIR_NAME) }

  /**
   * Copies the bytes at [source] into [romDir] as `<gameId>.<ext>`.
   * Replaces an existing file of the same name (idempotent re-pick).
   *
   * The caller is responsible for validating the source (e.g. via
   * [com.skiletro.wheelwitch.data.GameTypeParser]) before calling.
   */
  suspend fun copyRomFromSource(source: Uri, gameId: String, ext: String): DocumentFile =
    withContext(Dispatchers.IO) {
      val fileName = "$gameId.$ext"
      romDir.findFile(fileName)?.delete()
      val target =
        romDir.createFile("application/octet-stream", fileName)
          ?: error("Cannot create $fileName in rom/")
      val input = resolver.openInputStream(source) ?: error("Cannot open $source")
      input.use { i ->
        val output =
          resolver.openOutputStream(target.uri)
            ?: error("Cannot open output stream for ${target.uri}")
        output.use { o -> i.copyToWithBuffer(o, COPY_BUFFER_SIZE) }
      }
      target
    }

  /**
   * Extracts a Retro Rewind pack zip into [packDir]. Uses
   * [java.util.zip.ZipFile] (central-directory-based iteration, one
   * seek at the end of the file) instead of a streaming
   * `ZipInputStream` (which re-parses each Local File Header
   * sequentially).
   *
   * Existing files of the same name are replaced (the prior install
   * is overwritten entry by entry). Zip entries under any of
   * [com.skiletro.wheelwitch.data.SaveManager.userDataPathPrefixes]
   * — the per-region `rksys.dat` tree, the Mii DB at
   * `Wii/shared2/menu/FaceLib/`, and the Pulsar config and
   * `Ghosts/` at `Wii/shared2/Pulsar/RetroRewind6/` — are left
   * untouched so the user's save data, Miis, settings, and ghost
   * data survive an update.
   *
   * The flow is:
   * 1. Enumerate the central directory once to get [ZipEntry] objects
   *    with up-front size metadata.
   * 2. Pre-create every unique parent directory with a single
   *    `createDirectory` per dir, cache results in a
   *    `Map<List<String>, DocumentFile>` so the per-file write loop
   *    doesn't pay N `findFile` round-trips per path component.
   * 3. Write each file via [FileChannel.transferFrom] when the
   *    provider returns a [FileOutputStream] (most providers do),
   *    otherwise via a buffered copy with a 256 KB buffer.
   *
   * The [onProgress] callback fires with an [ExtractProgress] snapshot
   * at three points: once at the start of [ExtractingPhase.PreparingFolders],
   * once per created directory (with `filesDone` / `filesTotal` still
   * 0), and once per written file (with the new `currentFile` set).
   */
  suspend fun extractZipToPack(zipFile: File, onProgress: (ExtractProgress) -> Unit) {
    withContext(Dispatchers.IO) {
      ZipFile(zipFile).use { zip ->
        val entries = zip.entries().toList()
        val fileEntries =
          entries
            .filterNot { it.isDirectory }
            .filter { ZipSafety.isSafeEntryName(it.name, SaveManager.userDataPathPrefixes) }
        val filesTotal = fileEntries.size
        val bytesTotal = fileEntries.sumOf { it.size.coerceAtLeast(0L) }

        val byPath: Map<List<String>, DocumentFile> =
          precreateDirectories(fileEntries, onProgress, filesTotal, bytesTotal)
        val childrenByParentUri = mutableMapOf<Uri, MutableMap<String, DocumentFile>>()
        val getChild: (DocumentFile, String) -> DocumentFile? = { parent, fileName ->
          val cachedFiles = childrenByParentUri.getOrPut(parent.uri) {
            val childrenMap = mutableMapOf<String, DocumentFile>()
            val children = parent.listFiles()
            for (child in children) {
              child.name?.let { childFileName ->
                childrenMap[childFileName] = child
              }
            }
            childrenMap
          }
          cachedFiles[fileName]
        }

        var fileIndex = 0
        var bytesDone = 0L
        for (entry in fileEntries) {
          val currentFile = ZipSafety.normalizeEntryName(entry.name)
          onProgress(
            ExtractProgress(
              phase = ExtractingPhase.WritingFiles,
              filesDone = fileIndex,
              filesTotal = filesTotal,
              currentFile = currentFile,
              bytesDone = bytesDone,
              bytesTotal = bytesTotal,
            )
          )
          val entrySize = entry.size.coerceAtLeast(0L)
          writeZipEntry(
            entry = entry,
            parent = byPath.getValue(parentParts(currentFile)),
            getChild = getChild,
            fileName = currentFile.substringAfterLast('/'),
            input = zip.getInputStream(entry),
          )
          bytesDone += entrySize
          fileIndex++
          onProgress(
            ExtractProgress(
              phase = ExtractingPhase.WritingFiles,
              filesDone = fileIndex,
              filesTotal = filesTotal,
              currentFile = currentFile,
              bytesDone = bytesDone,
              bytesTotal = bytesTotal,
            )
          )
        }
      }
    }
  }

  /**
   * Walks the file entries, collects the unique set of parent
   * directory paths, creates them in depth order (parents first so
   * each `createDirectory` only needs one IPC call), and returns a
   * map from path parts to the resulting [DocumentFile]. The empty
   * list is included as the key for `packDir` itself.
   *
   * The caller is responsible for only providing Zip entries with
   * safe entry names.
   */
  private fun precreateDirectories(
    fileEntries: List<ZipEntry>,
    onProgress: (ExtractProgress) -> Unit,
    filesTotal: Int,
    bytesTotal: Long,
  ): Map<List<String>, DocumentFile> {
    // For every file, add every ancestor directory to the set, not just
    // the direct parent. A file at a/b/c.bin contributes ["a"] and
    // ["a", "b"]; the file itself is never a directory. Without this,
    // a zip with files in sibling subtrees (e.g. apps/x/foo.bin and
    // apps/y/bar.bin, no file directly under apps/) would crash the
    // pre-pass with "Key [apps] is missing in the map".
    val uniqueParents = fileEntries
      .flatMap { file ->
        val parts = ZipSafety.normalizeEntryName(file.name).split('/')
        (1 until parts.size).map { parts.take(it) }
      }
      .toSet()
      .sortedBy { it.size }

    onProgress(
      ExtractProgress(
        phase = ExtractingPhase.PreparingFolders,
        filesDone = 0,
        filesTotal = filesTotal,
        currentFile = null,
        bytesDone = 0L,
        bytesTotal = bytesTotal,
      )
    )

    val byPath = mutableMapOf<List<String>, DocumentFile>(emptyList<String>() to packDir)
    for (parts in uniqueParents) {
      if (parts.isEmpty()) continue
      val parent = byPath.getValue(parts.dropLast(1))
      val name = parts.last()
      val existing = parent.findFile(name)
      val dir =
        if (existing != null && existing.isDirectory) {
          existing
        } else {
          parent.createDirectory(name) ?: error("Cannot create $name in pack/")
        }
      byPath[parts] = dir
    }
    return byPath
  }

  private fun parentParts(normalizedEntryName: String): List<String> {
    val parts = normalizedEntryName.split('/')
    return if (parts.size <= 1) emptyList() else parts.dropLast(1)
  }

  /**
   * Writes [content] as [LAUNCH_JSON_NAME] under the rom dir,
   * replacing any existing file. The returned [DocumentFile] is the
   * new launch descriptor.
   */
  fun writeLaunchJson(content: String): DocumentFile =
    writeDolphinBytes(
      resolver = resolver,
      parent = romDir,
      name = LAUNCH_JSON_NAME,
      bytes = content.toByteArray(Charsets.UTF_8),
      mime = "application/json",
    )

  /**
   * Reads the [LAUNCH_JSON_NAME] contents from the rom dir if present,
   * or null if the launch descriptor has not been written yet.
   */
  fun readLaunchJson(): String? =
    readDolphinText(resolver, romDir.findFile(LAUNCH_JSON_NAME))

  /**
   * Copies the app-shipped cover banner into [romDir] as
   * `rr_autostartfile.cover.png`, so it sits alongside the launch
   * descriptor (`rr_autostostfile.json`). Idempotent: replaces an
   * existing file of the same name.
   *
   * Called from the onboarding ROM step right after [copyRomFromSource]
   * succeeds. The banner is tiny so a re-copy on every onboarding is
   * cheap; the cover survives pack updates because [extractZipToPack]
   * only writes to [packDir].
   */
  suspend fun writeRrCover(): Unit = withContext(Dispatchers.IO) {
    copyRawToRomFile(
      resId = R.raw.rr_autostartfile_cover,
      fileName = "rr_autostartfile.cover.png",
      mime = "image/png",
    )
  }

  /**
   * Copies the app-shipped RR metadata into [romDir] as
   * `rr_autostartfile.xml`, with the `{VERSION}` placeholder in the
   * raw resource replaced by [version]. Sits alongside the launch
   * descriptor (`rr_autostartfile.json`). Idempotent: replaces an
   * existing file of the same name.
   *
   * Called from [com.skiletro.wheelwitch.domain.RewindPackManager]
   * after every successful install/update so the version field
   * Dolphin's launch-descriptor UI displays tracks the installed
   * pack version. A throw here is non-fatal for the install: the
   * cover banner and the version.txt under the pack root are the
   * load-bearing state for the launcher; this is cosmetic.
   */
  suspend fun writeRrMetadata(version: SemVersion): Unit =
    withContext(Dispatchers.IO) {
      val template =
        appContext.resources.openRawResource(R.raw.rr_autostartfile).use {
          it.readBytes().toString(Charsets.UTF_8)
        }
      val rendered = template.replace(VERSION_PLACEHOLDER, version.toString())
      writeDolphinBytes(
        resolver = resolver,
        parent = romDir,
        name = METADATA_XML_NAME,
        bytes = rendered.encodeToByteArray(),
        mime = "text/xml",
      )
    }

  /**
   * Reads the `version.txt` file at `pack/RetroRewind6/version.txt`
   * and parses it as a [SemVersion]. Returns null when the file is
   * missing or unparseable; both are treated as "no local version".
   */
  fun readVersion(): SemVersion? {
    val text = readDolphinText(resolver, retroRewindDir.findFile(VERSION_FILE_NAME)) ?: return null
    val parsed = SemVersion.parse(text.trim())
    if (parsed == null) {
      Timber.tag(TAG)
        .w("Could not parse version file contents: %s", text.trim())
    }
    return parsed
  }

  /**
   * Writes [version] to `pack/RetroRewind6/version.txt`, replacing
   * any existing file. Called by
   * [com.skiletro.wheelwitch.domain.RewindPackManager] only when the
   * pack zip's own `version.txt` is missing or stale (typical for
   * hotfix zips that don't ship a new `version.txt`), so a failed
   * extract leaves the previous version on disk.
   */
  suspend fun writeVersion(version: SemVersion): Unit =
    withContext(Dispatchers.IO) {
      writeDolphinBytes(
        resolver = resolver,
        parent = retroRewindDir,
        name = VERSION_FILE_NAME,
        bytes = version.toString().encodeToByteArray(),
        mime = "text/plain",
      )
    }

  /**
   * Reads `Config/[fileName]` (the INI file Dolphin uses for its
   * library paths) and returns its UTF-8 contents, or null if the
   * file does not exist yet. Used by [com.skiletro.wheelwitch.util.launcher.DolphinLauncher]
   * to upsert the WheelWitch `rom/` folder as an `ISOPathN` entry.
   */
  fun readConfigIni(fileName: String = CONFIG_INI_NAME): String? {
    val configDir =
      findOrCreateDir(root, "Config") ?: return null
    return readDolphinText(resolver, configDir.findFile(fileName), stripBom = true)
  }

  /**
   * Writes [content] as `Config/[fileName]`, creating the
   * `Config/` directory and replacing any existing file. Returns the
   * new INI [DocumentFile]. Used by the launch flow to register the
   * WheelWitch `rom/` folder with Dolphin's library.
   */
  fun writeConfigIni(content: String, fileName: String = CONFIG_INI_NAME): DocumentFile {
    val configDir =
      findOrCreateDir(root, "Config")
        ?: error("Cannot create Config/ in Dolphin tree")
    return writeDolphinBytes(
      resolver = resolver,
      parent = configDir,
      name = fileName,
      bytes = content.toByteArray(Charsets.UTF_8),
      mime = "text/plain",
    )
  }

  /**
   * Reads `GameSettings/<gamePrefix>.ini` if it exists, or null when
   * the file is absent. Used by [ensureRmcGameInis] to inspect and
   * merge existing per-game settings before writing.
   */
  fun readGameIni(gamePrefix: String): String? =
    readDolphinText(
      resolver,
      gameSettingsDir.findFile("$gamePrefix.ini"),
      stripBom = true,
    )

  /**
   * Writes [content] as `GameSettings/<gamePrefix>.ini`, replacing
   * any existing file of the same name.
   */
  fun writeGameIni(gamePrefix: String, content: String) {
    val fileName = "$gamePrefix.ini"
    writeDolphinBytes(
      resolver = resolver,
      parent = gameSettingsDir,
      name = fileName,
      bytes = content.toByteArray(Charsets.UTF_8),
      mime = "text/plain",
    )
  }

  /**
   * Ensures the `GameSettings/RMC.ini` file contains the settings
   * that force-disable cheats and RetroAchievements for Mario Kart
   * Wii, and strips conflicting settings from other `RMC*.ini` files.
   *
   * The `[Dolphin.Core] EnableCheats = False` trick overrides the
   * user-facing `[Core] EnableCheats` toggle so it cannot be
   * re-enabled from Dolphin's settings GUI. The same trick does not
   * work for `[Achievements.Achievements]`, so it is only set once.
   *
   * Existing user settings in `RMC.ini` (other keys, other sections)
   * are preserved: the method only ensures the three required key-
   * value pairs are present and correct.
   */
  fun ensureRmcGameInis() {
    ensureRmcGameIni()
    forceDisableAchievementsGlobally()
    stripConflictingKeysFromSiblingGameInis()
  }

  /** Ensures `GameSettings/RMC.ini` contains the force-disable settings. */
  private fun ensureRmcGameIni() {
    val gameIni = DolphinGameIni
    var requiredSuffix = "\n${gameIni.GAME_INI_NOTICE}"
    // The order of these sections is important here for the force-disabling to work.
    requiredSuffix = gameIni.addIniKeyValueAtTheEnd(
      requiredSuffix,
      "[Core]",
      gameIni.CHEATS_KEY,
      gameIni.FORCE_DISABLE_VALUE
    )
    // If the force-disabling should not be as aggressive, the following single line could be
    // commented out (it breaks some game-specific settings in the "General" section as well,
    // but this should not matter, as those settings are usually globally configured).
    requiredSuffix = gameIni.addIniKeyValueAtTheEnd(
      requiredSuffix,
      "[Dolphin.Core]",
      gameIni.CHEATS_KEY,
      gameIni.FORCE_DISABLE_VALUE
    )
    requiredSuffix =
      gameIni.addIniKeyValueAtTheEnd(
        requiredSuffix,
        "[Achievements.Achievements]",
        gameIni.ACHIEVEMENTS_KEY,
        gameIni.FORCE_DISABLE_VALUE
      )
    val existing = readGameIni(gameIni.RMC_PREFIX).orEmpty()
    var content = existing
    // Remove our required settings if they were already present to work with the user-specific settings.
    content = content.removeSuffix(requiredSuffix)
    // Perform section rename operations on the settings not configured by Wheel Witch to preserve
    // the required ordering of our sections.
    content = gameIni.renameSection(content, "[Core]", "[Core]")
    content = gameIni.renameSection(content, "[Dolphin.Core]", "[Core]")
    content = gameIni.renameSection(content, "[Achievements.Achievements]", "[Achievements.Achievements]")
    // Remove stuff we should not need or want anymore, the user has been warned.
    content = gameIni.removeAllBelowNotice(content)
    content = gameIni.removeIniKeyInSection(content, "[Core]", gameIni.CHEATS_KEY)
    content = gameIni.removeIniKeyInSection(content, "[Dolphin.Core]", gameIni.CHEATS_KEY)
    content = gameIni.removeIniKeyInSection(
      content,
      "[Achievements.Achievements]",
      gameIni.ACHIEVEMENTS_KEY
    )
    // Finally, append our required settings.
    content += requiredSuffix
    if (content != existing) {
      writeGameIni(gameIni.RMC_PREFIX, content)
    }
  }

  /** Fixes the global RetroAchievements config as it overrides the game-specific config. */
  private fun forceDisableAchievementsGlobally() {
    val gameIni = DolphinGameIni
    val existingAchievements = readConfigIni(ACHIEVEMENTS_INI_NAME).orEmpty()
    var achievementsContent = existingAchievements
    achievementsContent =
      gameIni.removeIniKeyInSection(achievementsContent, "[Achievements]", gameIni.ACHIEVEMENTS_KEY)
    // The RetroAchievements disabling seems a bit broken, as Dolphin often needs to be closed
    // and re-opened if the global setting was enabled.
    achievementsContent = gameIni.addIniKeyValue(
      achievementsContent,
      "[Achievements]",
      gameIni.ACHIEVEMENTS_KEY,
      gameIni.FORCE_DISABLE_VALUE
    )
    if (existingAchievements != achievementsContent) {
      writeConfigIni(achievementsContent, ACHIEVEMENTS_INI_NAME)
    }
  }

  /**
   * Strips `EnableCheats` and `Achievements.Enabled` from other
   * `RMC*.ini` files that could override the force-disable.
   */
  private fun stripConflictingKeysFromSiblingGameInis() {
    val gameIni = DolphinGameIni
    val siblings =
      gameSettingsDir.listFiles().filter { file ->
        val name = file.name ?: return@filter false
        name.startsWith(gameIni.RMC_PREFIX) &&
          name.endsWith(".ini") &&
          name != "${gameIni.RMC_PREFIX}.ini"
      }
    for (sibling in siblings) {
      val name = sibling.name ?: continue
      val siblingContent = readDolphinText(resolver, sibling) ?: continue
      // For good measure, remove the cheats and RetroAchievements settings from all files which
      // could override the base GameINI file.
      var cleaned = gameIni.removeIniKeyInSection(siblingContent, "[Core]", gameIni.CHEATS_KEY)
      cleaned = gameIni.removeIniKeyInSection(
        cleaned,
        "[Dolphin.Core]",
        gameIni.CHEATS_KEY
      )
      cleaned = gameIni.removeIniKeyInSection(
        cleaned,
        "[Achievements.Achievements]",
        gameIni.ACHIEVEMENTS_KEY
      )
      if (cleaned != siblingContent) {
        writeDolphinBytes(
          resolver = resolver,
          parent = gameSettingsDir,
          name = name,
          bytes = cleaned.toByteArray(Charsets.UTF_8),
          mime = "text/plain",
        )
      }
    }
  }

  /** Persists the SAF grant for [treeUri] across process death. */
  fun persistUriPermission() {
    resolver.takePersistableUriPermission(
      treeUri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
  }

  // --- internals --------------------------------------------------------

  /**
   * Replaces any existing file named [fileName] under [romDir] with
   * the contents of the raw resource at [resId]. Used by
   * [writeRrCover] to copy the app-shipped cover banner.
   */
  private fun copyRawToRomFile(resId: Int, fileName: String, mime: String) {
    val bytes = appContext.resources.openRawResource(resId).use { it.readBytes() }
    writeDolphinBytes(
      resolver = resolver,
      parent = romDir,
      name = fileName,
      bytes = bytes,
      mime = mime,
    )
  }

  private fun writeZipEntry(
    entry: ZipEntry,
    parent: DocumentFile,
    getChild: (DocumentFile, String) -> DocumentFile?,
    fileName: String,
    input: InputStream,
  ) {
    getChild(parent, fileName)?.delete()
    val target =
      parent.createFile("application/octet-stream", fileName)
        ?: error("Cannot create ${entry.name} in pack/")
    val output =
      resolver.openOutputStream(target.uri)
        ?: error("Cannot open output stream for ${target.uri}")
    output.use { o ->
      if (o is FileOutputStream) {
        val size = entry.size.coerceAtLeast(0L)
        val channel = o.channel
        if (size > 0L) {
          channel.transferFrom(input.toReadableByteChannel(), 0L, size)
        } else {
          input.copyToWithBuffer(o, COPY_BUFFER_SIZE)
        }
      } else {
        input.copyToWithBuffer(o, COPY_BUFFER_SIZE)
      }
    }
  }

  private fun InputStream.toReadableByteChannel(): ReadableByteChannel = Channels.newChannel(this)

  private fun InputStream.copyToWithBuffer(out: OutputStream, bufferSize: Int) {
    val buffer = ByteArray(bufferSize)
    while (true) {
      val read = read(buffer)
      if (read == -1) break
      out.write(buffer, 0, read)
    }
  }

  companion object {
    /** Tag used by Timber in this file's log lines. */
    const val TAG = "DolphinTree"

    /** Memoised tree from [fromPersisted]; kept in sync with [memoUri]. */
    private var memoTree: DolphinTree? = null

    /** Persisted URI the memoised tree was built from; null when unset. */
    private var memoUri: String? = null

    /** Buffered-copy buffer used when [FileChannel.transferFrom] is not available. */
    const val COPY_BUFFER_SIZE: Int = 256 * 1024

    /**
     * Filename of the launch descriptor under the rom dir.
     * Re-exported from [com.skiletro.wheelwitch.util.launcher.DolphinLauncher.RR_JSON_NAME].
     * Single source of truth lives in the launcher.
     */
    const val LAUNCH_JSON_NAME = com.skiletro.wheelwitch.util.launcher.DolphinLauncher.RR_JSON_NAME

    /**
     * Name of the Retro Rewind subdirectory inside the pack dir. The
     * pack zip extracts here (so a zip entry `RetroRewind6/version.txt`
     * lands at `pack/RetroRewind6/version.txt`) and it's also where
     * the launcher's `riivolution/RetroRewind6.xml` is rooted.
     */
    const val RETRO_REWIND_DIR_NAME = "RetroRewind6"

    /** Filename of the pack version file under [RETRO_REWIND_DIR_NAME]. */
    const val VERSION_FILE_NAME = "version.txt"

    /** Filename of the `rr_autostartfile.xml` metadata file under the rom dir. */
    const val METADATA_XML_NAME = "rr_autostartfile.xml"

    /**
     * Placeholder in `R.raw.rr_autostartfile` that
     * [DolphinTree.writeRrMetadata] replaces with the current pack
     * version on every install/update.
     */
    const val VERSION_PLACEHOLDER = "{VERSION}"

    /** Filename of Dolphin's `Config/Dolphin.ini` library-paths config. */
    const val CONFIG_INI_NAME = "Dolphin.ini"

    /** Filename of Dolphin's `Config/RetroAchievements.ini` config. */
    const val ACHIEVEMENTS_INI_NAME = "RetroAchievements.ini"

    /**
     * Returns success if [treeUri] points to the Dolphin user folder.
     * Two URI forms are accepted:
     *
     * 1. **Primary external storage**: the stock SAF picker's default:
     *    `content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Forg.dolphinemu.dolphinemu%2Ffiles`.
     *    Document id is `primary:Android/data/org.dolphinemu.dolphinemu/files`.
     * 2. **Dolphin's own SAF provider**: surfaced by the picker on
     *    devices where Dolphin is installed:
     *    `content://org.dolphinemu.dolphinemu.user/tree/root%2F`.
     *    Document id is `root/`. This provider maps to the same
     *    physical folder and is the recommended path on modern
     *    Android. It does not require the legacy
     *    `MANAGE_EXTERNAL_STORAGE` permission.
     *
     * Subfolders of either root are rejected. The WheelWitch
     * subdirectory must be created at the top of Dolphin's user
     * folder, not inside some intermediate location.
     */
    fun validate(treeUri: Uri): Result<Unit> {
      val treeId =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
          ?: return Result.failure(InvalidTreeUriException(InvalidTreeReason.NotTreeUri, "Not a tree URI: $treeUri"))

      // Primary external storage: primary:Android/data/<dolphin>/files
      if (
        treeUri.authority == "com.android.externalstorage.documents" &&
          treeId == DolphinPaths.expectedTreeId()
      ) {
        return Result.success(Unit)
      }

      // Dolphin's own SAF provider root, which maps to the same
      // physical folder. The picker surfaces this on devices where
      // Dolphin is installed; it lets apps write into Dolphin's
      // folder without the legacy MANAGE_EXTERNAL_STORAGE permission.
      if (treeUri.authority == "org.dolphinemu.dolphinemu.user" && treeId == "root/") {
        return Result.success(Unit)
      }

      val baseMessage =
        "Tree $treeUri (authority='${treeUri.authority}', docId='$treeId') " +
          "is not the Dolphin user folder. Expected either primary external storage " +
          "(primary:Android/data/org.dolphinemu.dolphinemu/files) or the Dolphin app's own storage root."
      // Subfolder of an accepted root: give the user a specific hint
      // about which parent to pick, so they don't have to guess.
      // Common case is "root/Dump" or "root/GameSettings" on the
      // Dolphin provider, or "<dolphin>/files/SomeApp" on primary.
      val reason =
        when {
          treeUri.authority == "com.android.externalstorage.documents" &&
            treeId.startsWith("${DolphinPaths.expectedTreeId()}/") ->
            InvalidTreeReason.SubfolderExternal
          treeUri.authority == "org.dolphinemu.dolphinemu.user" &&
            treeId.startsWith("root/") ->
            InvalidTreeReason.SubfolderInternal
          else -> InvalidTreeReason.NotDolphinFolder
        }

      return Result.failure(
        InvalidTreeUriException(
          reason,
          if (reason !== InvalidTreeReason.NotDolphinFolder) "$baseMessage ${parentHint(reason)}" else baseMessage,
        )
      )
    }

    private fun parentHint(reason: InvalidTreeReason): String =
      when (reason) {
        InvalidTreeReason.SubfolderExternal ->
          "You picked a subfolder of the Dolphin user folder. To use WheelWitch, pick the parent: ${DolphinPaths.expectedTreeId()}"
        InvalidTreeReason.SubfolderInternal ->
          "You picked a subfolder of Dolphin's storage. To use WheelWitch, pick the root folder (root/)."
        else -> error("parentHint called for $reason")
      }

    /**
     * Reconstructs the persisted [DolphinTree] from
     * [PrefsKeys.WHEELWITCH_TREE_URI_KEY], or returns null if the URI
     * is missing or no longer valid (e.g. the user revoked the SAF
     * grant). When the grant is lost, the persisted URI is cleared so
     * the UI can route to onboarding.
     *
     * The rebuilt tree is memoised for the process lifetime; repeat
     * calls with the unchanged URI skip the resolver rebuild. The memo
     * is invalidated by [persist] (a new folder pick) and on rebuild
     * failure so a stale grant can never be served a cached tree.
     */
    @Synchronized
    fun fromPersisted(context: Context): DolphinTree? {
      val prefs = Prefs.main(context)
      val uriString = prefs.getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null)
      if (uriString == null) {
        Timber.tag(TAG)
          .i("fromPersisted: no tree URI under %s; user has not completed onboarding",
            PrefsKeys.WHEELWITCH_TREE_URI_KEY)
        memoUri = null
        memoTree = null
        return null
      }
      if (memoUri == uriString) {
        Timber.tag(TAG)
          .i("fromPersisted: reusing memoised tree for authority=%s",
            memoTree?.treeUri?.authority)
        return memoTree
      }
      return try {
        val uri = Uri.parse(uriString)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        Timber.tag(TAG)
          .i("fromPersisted: rebuilding tree from authority=%s docId=%s",
            uri?.authority, docId)
        val tree = DolphinTree(context, uri)
        memoUri = uriString
        memoTree = tree
        Timber.tag(TAG)
          .i("fromPersisted: tree built ok, wheelWitchDir=%s", tree.wheelWitchDir.uri)
        tree
      } catch (e: Exception) {
        Timber.tag(TAG)
          .e(e, "fromPersisted: rebuild failed for uriString=%s; clearing pref", uriString)
        memoUri = null
        memoTree = null
        prefs.edit().remove(PrefsKeys.WHEELWITCH_TREE_URI_KEY).apply()
        null
      }
    }

    /** Stores [tree]'s URI under [PrefsKeys.WHEELWITCH_TREE_URI_KEY] for [fromPersisted]. */
    @Synchronized
    fun persist(context: Context, tree: DolphinTree) {
      Prefs.main(context)
        .edit()
        .putString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, tree.treeUri.toString())
        .apply()
      memoUri = null
      memoTree = null
      Timber.tag(TAG)
        .i("persist: stored tree URI authority=%s", tree.treeUri.authority)
    }

    /** Drops the in-process memo, e.g. in tests that build the tree repeatedly. */
    internal fun resetMemo() {
      memoUri = null
      memoTree = null
    }
  }
}

// --- top-level SAF helpers used by SaveManager and the unified save backup ---
//
// Kept as free functions (not methods on DolphinTree) so a mocked DolphinTree
// in unit tests doesn't intercept them with MockK's relaxed defaults — the real
// implementation runs against the mocked ContentResolver / DocumentFile chain
// the test wires up.

internal fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
  val existing = parent.findFile(name)
  if (existing != null && existing.isDirectory) return existing
  return parent.createDirectory(name)
}

/** Returns the existing child of [parent] named [name] when it's a directory, or null. */
internal fun findDir(parent: DocumentFile?, name: String): DocumentFile? {
  if (parent == null) return null
  val existing = parent.findFile(name) ?: return null
  return if (existing.isDirectory) existing else null
}

/**
 * Walks an existing path under [root] split by [parts] without
 * creating any directories, and returns the file named [fileName]
 * if it exists. Returns null when any intermediate directory or
 * the file itself is missing. Used by
 * [com.skiletro.wheelwitch.data.SaveManager] to discover vanilla and
 * patched-ISO NAND saves without creating empty directories in
 * Dolphin's virtual NAND.
 */
internal fun findNandSaveFile(root: DocumentFile, titleId: String, subdir: String): DocumentFile? {
  val wiiDir = root.findFile("Wii") ?: return null
  if (!wiiDir.isDirectory) return null
  val titleDir = wiiDir.findFile("title") ?: return null
  if (!titleDir.isDirectory) return null
  val titleIdDir = titleDir.findFile(titleId) ?: return null
  if (!titleIdDir.isDirectory) return null
  val regionDir = titleIdDir.findFile(subdir) ?: return null
  if (!regionDir.isDirectory) return null
  val dataDir = regionDir.findFile("data") ?: return null
  if (!dataDir.isDirectory) return null
  val file = dataDir.findFile(com.skiletro.wheelwitch.data.SaveManager.SAVE_FILE_NAME) ?: return null
  return if (file.exists() && file.isFile) file else null
}

/**
 * Walks [parts] under [root], creating intermediate directories as
 * needed. Used by the unified save backup to reach nested user-data
 * paths like `Wii/shared2/menu/FaceLib/`.
 */
internal fun navigateOrCreate(root: DocumentFile, parts: List<String>): DocumentFile {
  var current = root
  for (part in parts) {
    current = findOrCreateDir(current, part) ?: error("Cannot create $part under ${current.uri}")
  }
  return current
}

/** Reads raw bytes from [file], or null when [file] is null or unreadable. */
internal fun readDolphinBytes(resolver: ContentResolver, file: DocumentFile?): ByteArray? {
  if (file == null || !file.exists() || !file.isFile) return null
  val input = resolver.openInputStream(file.uri) ?: return null
  return input.use { it.readBytes() }
}

/** Reads [file] as UTF-8 text, or null when [file] is null or unreadable. */
internal fun readDolphinText(
  resolver: ContentResolver,
  file: DocumentFile?,
  stripBom: Boolean = false,
): String? {
  if (file == null) return null
  val input = resolver.openInputStream(file.uri) ?: return null
  val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
  return if (stripBom) text.removePrefix("\uFEFF") else text
}

/** Writes [bytes] to [name] under [parent], replacing any existing file. */
internal fun writeDolphinBytes(
  resolver: ContentResolver,
  parent: DocumentFile,
  name: String,
  bytes: ByteArray,
  mime: String = "application/octet-stream",
): DocumentFile {
  parent.findFile(name)?.delete()
  val file =
    parent.createFile(mime, name)
      ?: error("Cannot create $name in ${parent.uri}")
  val output =
    resolver.openOutputStream(file.uri)
      ?: error("Cannot open output stream for $name")
  output.use { it.write(bytes) }
  return file
}

/**
 * Recursively walks [dir] and writes every file into [out] as a
 * `ZipEntry` whose name is [basePath] joined with the file's
 * relative path. Missing or non-directory [dir] is a no-op. The
 * JDK's `ZipInputStream` creates directories on the fly during
 * restore, so no explicit directory entries are emitted.
 */
internal fun recursiveCopyToStream(
  resolver: ContentResolver,
  dir: DocumentFile?,
  out: java.util.zip.ZipOutputStream,
  basePath: String,
) {
  if (dir == null || !dir.exists() || !dir.isDirectory) return
  val children = dir.listFiles()
  for (child in children) {
    val name = child.name ?: continue
    val entryName = if (basePath.isEmpty()) name else "$basePath/$name"
    if (child.isDirectory) {
      recursiveCopyToStream(resolver, child, out, entryName)
    } else if (child.isFile) {
      out.putNextEntry(java.util.zip.ZipEntry(entryName))
      val bytes = readDolphinBytes(resolver, child) ?: ByteArray(0)
      out.write(bytes)
      out.closeEntry()
    }
  }
}

/** Recursively deletes [dir] (children first). Missing [dir] is a no-op. */
internal fun recursiveDelete(dir: DocumentFile?) {
  if (dir == null || !dir.exists()) return
  if (dir.isDirectory) {
    for (child in dir.listFiles()) recursiveDelete(child)
  }
  dir.delete()
}
