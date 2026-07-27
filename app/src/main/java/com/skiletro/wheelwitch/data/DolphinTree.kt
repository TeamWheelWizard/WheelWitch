package com.skiletro.wheelwitch.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.data.DolphinTree.Companion.GAME_INI_NOTICE
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
        output.use { o -> i.copyTo(o) }
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
            .filter { ZipSafety.isSafeEntryName(it.name) }
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
          val currentFile = entry.name
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
            parent = byPath.getValue(parentParts(entry.name)),
            getChild = getChild,
            fileName = entry.name.substringAfterLast('/'),
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
        val parts = file.name.split('/').filter { it.isNotEmpty() }
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

  private fun parentParts(entryName: String): List<String> {
    val parts = entryName.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 1) emptyList() else parts.dropLast(1)
  }

  /**
   * Writes [content] as [LAUNCH_JSON_NAME] under the rom dir,
   * replacing any existing file. The returned [DocumentFile] is the
   * new launch descriptor.
   */
  fun writeLaunchJson(content: String): DocumentFile {
    val existing = romDir.findFile(LAUNCH_JSON_NAME)
    if (existing != null) existing.delete()
    val file =
      romDir.createFile("application/json", LAUNCH_JSON_NAME)
        ?: error("Cannot create $LAUNCH_JSON_NAME in rom/")
    val output = resolver.openOutputStream(file.uri)
      ?: error("Cannot open output stream for $LAUNCH_JSON_NAME")
    output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    return file
  }

  /**
   * Reads the [LAUNCH_JSON_NAME] contents from the rom dir if present,
   * or null if the launch descriptor has not been written yet.
   */
  fun readLaunchJson(): String? {
    val file = romDir.findFile(LAUNCH_JSON_NAME) ?: return null
    val input = resolver.openInputStream(file.uri) ?: return null
    return input.use { it.readBytes().toString(Charsets.UTF_8) }
  }

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
      val existing = romDir.findFile(METADATA_XML_NAME)
      if (existing != null) existing.delete()
      val file =
        romDir.createFile("text/xml", METADATA_XML_NAME)
          ?: error("Cannot create $METADATA_XML_NAME in rom/")
      val output =
        resolver.openOutputStream(file.uri)
          ?: error("Cannot open output stream for $METADATA_XML_NAME")
      output.use { it.write(rendered.encodeToByteArray()) }
    }

  /**
   * Reads the `version.txt` file at `pack/RetroRewind6/version.txt`
   * and parses it as a [SemVersion]. Returns null when the file is
   * missing or unparseable; both are treated as "no local version".
   */
  fun readVersion(): SemVersion? {
    val file = retroRewindDir.findFile(VERSION_FILE_NAME) ?: return null
    val text =
      resolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: return null
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
      val existing = retroRewindDir.findFile(VERSION_FILE_NAME)
      if (existing != null) existing.delete()
      val file =
        retroRewindDir.createFile("text/plain", VERSION_FILE_NAME)
          ?: error("Cannot create $RETRO_REWIND_DIR_NAME/$VERSION_FILE_NAME")
      val output =
        resolver.openOutputStream(file.uri)
          ?: error("Cannot open output stream for $RETRO_REWIND_DIR_NAME/$VERSION_FILE_NAME")
      output.use { it.write(version.toString().encodeToByteArray()) }
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
    val file = configDir.findFile(fileName) ?: return null
    return resolver.openInputStream(file.uri)
      ?.use { it.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF") }
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
    val existing = configDir.findFile(fileName)
    if (existing != null) existing.delete()
    val file =
      configDir.createFile("text/plain", fileName)
        ?: error("Cannot create $fileName")
    val output =
      resolver.openOutputStream(file.uri)
        ?: error("Cannot open output stream for $fileName")
    output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    return file
  }

  /**
   * Reads `GameSettings/<gamePrefix>.ini` if it exists, or null when
   * the file is absent. Used by [ensureRmcGameInis] to inspect and
   * merge existing per-game settings before writing.
   */
  fun readGameIni(gamePrefix: String): String? {
    val file = gameSettingsDir.findFile("$gamePrefix.ini") ?: return null
    return resolver.openInputStream(file.uri)?.use {
      it.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }
  }

  /**
   * Writes [content] as `GameSettings/<gamePrefix>.ini`, replacing
   * any existing file of the same name.
   */
  fun writeGameIni(gamePrefix: String, content: String) {
    val fileName = "$gamePrefix.ini"
    gameSettingsDir.findFile(fileName)?.delete()
    val file =
      gameSettingsDir.createFile("text/plain", fileName)
        ?: error("Cannot create $fileName in GameSettings/")
    val output =
      resolver.openOutputStream(file.uri)
        ?: error("Cannot open output stream for $fileName")
    output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
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
    var requiredSuffix = "\n$GAME_INI_NOTICE"
    // The order of these sections is important here for the force-disabling to work.
    requiredSuffix = addIniKeyValueAtTheEnd(requiredSuffix, "[Core]", CHEATS_KEY, FORCE_DISABLE_VALUE)
    // If the force-disabling should not be as aggressive, the following single line could be
    // commented out (it breaks some game-specific settings in the "General" section as well,
    // but this should not matter, as those settings are usually globally configured).
    requiredSuffix = addIniKeyValueAtTheEnd(requiredSuffix, "[Dolphin.Core]", CHEATS_KEY, FORCE_DISABLE_VALUE)
    requiredSuffix =
      addIniKeyValueAtTheEnd(requiredSuffix, "[Achievements.Achievements]", ACHIEVEMENTS_KEY, FORCE_DISABLE_VALUE)
    val existing = readGameIni(RMC_PREFIX).orEmpty()
    var content = existing
    // Remove our required settings if they were already present to work with the user-specific settings.
    content = content.removeSuffix(requiredSuffix)
    // Perform section rename operations on the settings not configured by Wheel Witch to preserve
    // the required ordering of our sections.
    content = renameSection(content, "[Core]", "[Core]")
    content = renameSection(content, "[Dolphin.Core]", "[Core]")
    content = renameSection(content, "[Achievements.Achievements]", "[Achievements.Achievements]")
    // Remove stuff we should not need or want anymore, the user has been warned.
    content = removeAllBelowNotice(content)
    content = removeIniKeyInSection(content, "[Core]", CHEATS_KEY)
    content = removeIniKeyInSection(content, "[Dolphin.Core]", CHEATS_KEY)
    content = removeIniKeyInSection(content, "[Achievements.Achievements]", ACHIEVEMENTS_KEY)
    // Finally, append our required settings.
    content += requiredSuffix
    if (content != existing) {
      writeGameIni(RMC_PREFIX, content)
    }

    // Fix the RetroAchievements global config as it overrides the game-specific config
    val existingAchievements = readConfigIni(ACHIEVEMENTS_INI_NAME).orEmpty()
    var achievementsContent = existingAchievements
    achievementsContent = removeIniKeyInSection(achievementsContent, "[Achievements]", ACHIEVEMENTS_KEY)
    // The RetroAchievements disabling seems a bit broken, as Dolphin often needs to be closed
    // and re-opened if the global setting was enabled.
    achievementsContent = addIniKeyValue(achievementsContent, "[Achievements]", ACHIEVEMENTS_KEY, FORCE_DISABLE_VALUE)
    if (existingAchievements != achievementsContent) {
      writeConfigIni(achievementsContent, ACHIEVEMENTS_INI_NAME)
    }

    // Strip EnableCheats and Achievements.Enable from other RMC*.ini files
    // that could override our force-disable.
    val siblings =
      gameSettingsDir.listFiles().filter { file ->
        val name = file.name ?: return@filter false
        name.startsWith(RMC_PREFIX) &&
          name.endsWith(".ini") &&
          name != "$RMC_PREFIX.ini"
      }
    for (sibling in siblings) {
      val name = sibling.name ?: continue
      val siblingContent =
        resolver.openInputStream(sibling.uri)?.use {
          it.readBytes().toString(Charsets.UTF_8)
        }
          ?: continue
      // For good measure, remove the cheats and RetroAchievements settings from all files which
      // could override the base GameINI file.
      var cleaned = removeIniKeyInSection(siblingContent, "[Core]", CHEATS_KEY)
      cleaned = removeIniKeyInSection(cleaned, "[Dolphin.Core]", CHEATS_KEY)
      cleaned = removeIniKeyInSection(cleaned, "[Achievements.Achievements]", ACHIEVEMENTS_KEY)
      if (cleaned != siblingContent) {
        sibling.delete()
        val newFile =
          gameSettingsDir.createFile("text/plain", name)
            ?: error("Cannot recreate $name in GameSettings/")
        val output =
          resolver.openOutputStream(newFile.uri)
            ?: error("Cannot open output stream for $name")
        output.use { it.write(cleaned.toByteArray(Charsets.UTF_8)) }
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
    val existing = romDir.findFile(fileName)
    if (existing != null) existing.delete()
    val file =
      romDir.createFile(mime, fileName)
        ?: error("Cannot create $fileName in rom/")
    val output =
      resolver.openOutputStream(file.uri)
        ?: error("Cannot open output stream for $fileName")
    appContext.resources.openRawResource(resId).use { input ->
      output.use { out -> input.copyTo(out) }
    }
  }

  private fun writeZipEntry(
    entry: ZipEntry,
    parent: DocumentFile,
    getChild: (DocumentFile, String) -> DocumentFile?,
    fileName: String,
    input: InputStream,
  ) {
    if (SaveManager.userDataPathPrefixes.any { entry.name.startsWith(it) }) {
      Timber.tag(TAG).d("Skipping user data path: %s", entry.name)
      return
    }
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

    /** Game-ID prefix used for the shared Mario Kart Wii GameINI. */
    const val RMC_PREFIX = "RMC"

    /** Notice added to the auto-generated RMC GameINI file. */
    const val GAME_INI_NOTICE =
      "# Auto-generated by Wheel Witch. Do NOT touch any of the values below, they might get deleted."

    /** Key of the "Dolphin.Core" section of INI files which determines whether cheats are enabled. */
    const val CHEATS_KEY = "EnableCheats"

    /**
     * Key of the "Achievements.Achievements" section of INI files
     * which determines whether RetroAchievements are enabled.
     */
    const val ACHIEVEMENTS_KEY = "Enabled"

    /** The boolean string we use to force-disable settings in Dolphin's INIs. */
    const val FORCE_DISABLE_VALUE = "False"

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
          ?: return Result.failure(IllegalArgumentException("Not a tree URI: $treeUri"))

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
      val parentHint =
        when {
          treeUri.authority == "com.android.externalstorage.documents" &&
            treeId.startsWith("${DolphinPaths.expectedTreeId()}/") ->
            "You picked a subfolder of the Dolphin user folder. To use WheelWitch, pick the parent: ${DolphinPaths.expectedTreeId()}"
          treeUri.authority == "org.dolphinemu.dolphinemu.user" &&
            treeId.startsWith("root/") ->
            "You picked a subfolder of Dolphin's storage. To use WheelWitch, pick the root folder (root/)."
          else -> null
        }

      return Result.failure(
        IllegalArgumentException(
          if (parentHint != null) "$baseMessage $parentHint" else baseMessage
        )
      )
    }

    /**
     * Reconstructs the persisted [DolphinTree] from
     * [PrefsKeys.WHEELWITCH_TREE_URI_KEY], or returns null if the URI
     * is missing or no longer valid (e.g. the user revoked the SAF
     * grant). When the grant is lost, the persisted URI is cleared so
     * the UI can route to onboarding.
     */
    fun fromPersisted(context: Context): DolphinTree? {
      val prefs = Prefs.main(context)
      val uriString = prefs.getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null)
      if (uriString == null) {
        Timber.tag(TAG)
          .i("fromPersisted: no tree URI under %s; user has not completed onboarding",
            PrefsKeys.WHEELWITCH_TREE_URI_KEY)
        return null
      }
      return try {
        val uri = Uri.parse(uriString)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        Timber.tag(TAG)
          .i("fromPersisted: rebuilding tree from authority=%s docId=%s",
            uri?.authority, docId)
        val tree = DolphinTree(context, uri)
        Timber.tag(TAG)
          .i("fromPersisted: tree built ok, wheelWitchDir=%s", tree.wheelWitchDir.uri)
        tree
      } catch (e: Exception) {
        Timber.tag(TAG)
          .e(e, "fromPersisted: rebuild failed for uriString=%s; clearing pref", uriString)
        prefs.edit().remove(PrefsKeys.WHEELWITCH_TREE_URI_KEY).apply()
        null
      }
    }

    /** Stores [tree]'s URI under [PrefsKeys.WHEELWITCH_TREE_URI_KEY] for [fromPersisted]. */
    fun persist(context: Context, tree: DolphinTree) {
      Prefs.main(context)
        .edit()
        .putString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, tree.treeUri.toString())
        .apply()
      Timber.tag(TAG)
        .i("persist: stored tree URI authority=%s", tree.treeUri.authority)
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

/** Writes [bytes] to [name] under [parent], replacing any existing file. */
internal fun writeDolphinBytes(
  resolver: ContentResolver,
  parent: DocumentFile,
  name: String,
  bytes: ByteArray,
): DocumentFile {
  parent.findFile(name)?.delete()
  val file =
    parent.createFile("application/octet-stream", name)
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
  writeFilesRecursive(resolver, dir, out, basePath)
}

private fun writeFilesRecursive(
  resolver: ContentResolver,
  dir: DocumentFile,
  out: java.util.zip.ZipOutputStream,
  basePath: String,
) {
  val children = dir.listFiles()
  for (child in children) {
    val name = child.name ?: continue
    val entryName = if (basePath.isEmpty()) name else "$basePath/$name"
    if (child.isDirectory) {
      writeFilesRecursive(resolver, child, out, entryName)
    } else if (child.isFile) {
      out.putNextEntry(java.util.zip.ZipEntry(entryName))
      val bytes = readDolphinBytes(resolver, child) ?: ByteArray(0)
      out.write(bytes)
      out.closeEntry()
    }
  }
}

/**
 * Ensures [key] = [value] exists under [section] in the INI
 * [content] by inserting a line for it in a correct position.
 */
internal fun addIniKeyValue(
  content: String,
  section: String,
  key: String,
  value: String,
): String {
  val lines = content.lines()
  val outLines = mutableListOf<String>()
  val kvLine = "$key = $value"
  var added = false

  for (line in lines) {
    outLines.add(line)
    extractIniSection(line)?.takeIf { !added && it == section }?.let {
      outLines.add(kvLine)
      added = true
    }
  }

  if (!added) {
    if (lines.isNotEmpty() && lines.last().isNotBlank()) outLines.add("")
    outLines.add(section)
    outLines.add(kvLine)
    outLines.add("")
  }

  return outLines.joinToString("\n")
}

/**
 * Ensures [key] = [value] exists under [section] in the INI
 * [content] by appending lines at the end for it.
 */
internal fun addIniKeyValueAtTheEnd(
  content: String,
  section: String,
  key: String,
  value: String,
): String {
  val lines = content.lines().toMutableList()
  val kvLine = "$key = $value"

  if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
  lines.add(section)
  lines.add(kvLine)
  lines.add("")
  return lines.joinToString("\n")
}

/**
 * Checks whether the [line] of an INI file assigns to a key matching the [keyPredicate].
 */
internal fun hasIniKeyMatching(line: String, keyPredicate: (String) -> Boolean): Boolean {
  if (line.isEmpty() || line.first() == '#') {
    return false
  }

  val end = line.indexOf('=')
  if (end < 0) {
    return false
  }

  val keyOfLine = line.substring(0, end).trim()
  return keyPredicate(keyOfLine)
}

/**
 * Checks whether the [line] of an INI file assigns to [key] in a case-insensitive manner.
 */
internal fun hasIniKey(line: String, key: String): Boolean {
  return hasIniKeyMatching(line) { keyOfLine -> keyOfLine.equals(key, ignoreCase = true) }
}

/**
 * Removes all lines from [content] beginning with the known Wheel Witch GameINI notice, and
 * returns the resulting string.
 */
internal fun removeAllBelowNotice(content: String): String {
  val lines = content.lines()
  val noticeIdx = lines.indexOf(GAME_INI_NOTICE)
  return (if (noticeIdx < 0) lines else lines.subList(0, noticeIdx)).joinToString("\n")
}

/**
 * Returns the section name including the brackets if [line] declares an INI section,
 * or null otherwise.
 */
internal fun extractIniSection(line: String): String? {
  val sectionEnd = line.indexOf(']')
  if (sectionEnd < 0 || line[0] != '[') {
    return null
  }

  return line.substring(0, sectionEnd + 1)
}

/**
 * Renames all occurrences of a section [oldSection] (case-insensitive match) to [newSection]
 * and returns the result.
 */
internal fun renameSection(
  content: String,
  oldSection: String,
  newSection: String,
): String {
  val lines = content.lines()
  val outLines = mutableListOf<String>()

  for (line in lines) {
    extractIniSection(line)?.let { currentSection ->
      if (currentSection.equals(oldSection, ignoreCase = true)) {
        outLines.add(line.replaceFirst(oldSection, newSection, ignoreCase = true))
        continue
      }
    }
    outLines.add(line)
  }

  return outLines.joinToString("\n")
}

/**
 * Removes every line matching [key] (case-insensitive key name)
 * within [section] in the INI [content]. Returns the
 * modified content, or the original if nothing changed.
 */
internal fun removeIniKeyInSection(
  content: String,
  section: String,
  key: String,
): String {
  val lines = content.lines()
  val outLines = mutableListOf<String>()

  var currentSection: String? = null
  for (line in lines) {
    extractIniSection(line)?.let { section ->
      currentSection = section
    } ?: run {
      if (section.equals(currentSection, ignoreCase = true) && hasIniKey(line, key)) {
        continue
      }
    }
    outLines.add(line)
  }

  return outLines.joinToString("\n")
}

/** Recursively deletes [dir] (children first). Missing [dir] is a no-op. */
internal fun recursiveDelete(dir: DocumentFile?) {
  if (dir == null || !dir.exists()) return
  if (dir.isDirectory) {
    for (child in dir.listFiles()) recursiveDelete(child)
  }
  dir.delete()
}
