package com.skiletro.wheelwitch.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.util.io.ZipSafety
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Extraction phase; folder preparation has no per-directory progress updates. */
enum class ExtractingPhase {
  PreparingFolders,

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
 * Extraction snapshot. Counts include only accepted file entries; byte totals use uncompressed ZIP
 * sizes, treating unknown sizes as zero. [currentFile] is null only during folder preparation.
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
 * Holds a [ContentResolver] and a [DocumentFile] tree and exposes the subdirectories the rest of
 * the app needs:
 * ```
 * <tree root>/
 * ├── WheelWitch/
 * │   ├── pack/
 * │   │   └── RetroRewind6/           : extracted Retro Rewind contents
 * │   │       └── version.txt         : installed pack version
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
 * Construction resolves only the SAF root. Required write directories are created lazily; optional
 * user-data directories are looked up afresh on every access so Dolphin writes and restores are
 * visible even when this wrapper is reused. Lookups never create optional directories.
 *
 * [Companion.validate] accepts the primary-storage tree ID from [DolphinPaths.expectedTreeId] or
 * Dolphin's own provider root. The picker cannot be restricted to these roots.
 */
class DolphinTree(context: Context, val treeUri: Uri) {
  val resolver: ContentResolver = context.contentResolver

  private val appContext: Context = context

  val root: DocumentFile =
      DocumentFile.fromTreeUri(context, treeUri)
          ?: run {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
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
    findOrCreateDir(wheelWitchDir, "rom") ?: error("Cannot create or find rom/ in WheelWitch dir")
  }

  val packDir: DocumentFile by lazy {
    findOrCreateDir(wheelWitchDir, "pack") ?: error("Cannot create or find pack/ in WheelWitch dir")
  }

  /** Existing pack directory, without initializing the creating [packDir] property. */
  internal val existingPackDir: DocumentFile?
    get() = findDir(findDir(root, "WheelWitch"), "pack")

  /** Existing ROM directory, without initializing the creating [romDir] property. */
  internal val existingRomDir: DocumentFile?
    get() = findDir(findDir(root, "WheelWitch"), "rom")

  /**
   * Deletes an existing non-user-data file, initializing [packDir] but not entry subdirectories.
   */
  fun deletePackEntry(relativePath: String): Boolean {
    val normalized = ZipSafety.normalizeEntryName(relativePath)
    if (!ZipSafety.isSafeEntryName(normalized, SaveManager.userDataPathPrefixes)) {
      Timber.tag(TAG).w("Skipping unsafe pack deletion: %s", relativePath)
      return false
    }
    val parts = normalized.split('/')
    var parent = packDir
    for (part in parts.dropLast(1)) {
      parent = parent.findFile(part)?.takeIf { it.isDirectory } ?: return false
    }
    val target = parent.findFile(parts.last()) ?: return false
    if (target.isDirectory) return false
    return target.delete()
  }

  /** Pack subtree containing `version.txt` and the launcher's `riivolution/RetroRewind6.xml`. */
  val retroRewindDir: DocumentFile by lazy {
    findOrCreateDir(packDir, RETRO_REWIND_DIR_NAME)
        ?: error("Cannot create or find $RETRO_REWIND_DIR_NAME/ in pack/")
  }

  /**
   * Dolphin's `GameSettings/` directory under the tree root. Holds per-game INI configuration
   * files. WheelWitch writes `RMC.ini` here to force-disable cheats and RetroAchievements on
   * launch.
   */
  val gameSettingsDir: DocumentFile by lazy {
    findOrCreateDir(root, "GameSettings")
        ?: error("Cannot create or find GameSettings/ in Dolphin tree")
  }

  /** Existing virtual NAND root; optional user-data lookups never create directories. */
  val wiiDir: DocumentFile?
    get() = findDir(root, "Wii")

  val shared2Dir: DocumentFile?
    get() = findDir(wiiDir, "shared2")

  /** Home of the system-wide Mii database (`RFL_DB.dat`). */
  val faceLibDir: DocumentFile?
    get() = findDir(findDir(shared2Dir, "menu"), "FaceLib")

  val pulsarDir: DocumentFile?
    get() = findDir(shared2Dir, "Pulsar")

  /** Retro Rewind's Pulsar settings and Ghosts directory, included in save backups. */
  val pulsarRrDir: DocumentFile?
    get() = findDir(pulsarDir, RETRO_REWIND_DIR_NAME)

  /**
   * Copies the bytes at [source] into [romDir] as `<gameId>.<ext>`. Replaces an existing file of
   * the same name (idempotent re-pick).
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
   * Extracts accepted ZIP files into [packDir], replacing existing files entry by entry. Unsafe
   * paths and [SaveManager.userDataPathPrefixes] are skipped to preserve user data.
   *
   * Parent directories are resolved once before writing. [onProgress] runs on [Dispatchers.IO]:
   * once before folder preparation, then immediately before and after each file write. File and
   * byte counts represent completed files, not partial writes; there are no per-directory updates.
   * Extraction is not transactional: failures can leave partially replaced pack contents.
   */
  suspend fun extractZipToPack(zipFile: File, onProgress: (ExtractProgress) -> Unit) {
    withContext(Dispatchers.IO) {
      ZipFile(zipFile).use { zip ->
        val entries = zip.entries().toList()
        val fileEntries =
            entries
                .filterNot { it.isDirectory }
                .mapNotNull { entry ->
                  if (ZipSafety.isSafeEntryName(entry.name, SaveManager.userDataPathPrefixes)) {
                    entry
                  } else {
                    Timber.tag(TAG).w("Skipping unsafe or user-owned ZIP entry: %s", entry.name)
                    null
                  }
                }
        val filesTotal = fileEntries.size
        val bytesTotal = fileEntries.sumOf { it.size.coerceAtLeast(0L) }

        val byPath: Map<List<String>, DocumentFile> =
            precreateDirectories(fileEntries, onProgress, filesTotal, bytesTotal)
        val childrenByParentUri = mutableMapOf<Uri, MutableMap<String, DocumentFile>>()
        val getChild: (DocumentFile, String) -> DocumentFile? = { parent, fileName ->
          val cachedFiles =
              childrenByParentUri.getOrPut(parent.uri) {
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
   * Resolves parent paths in depth order and includes [packDir] under the empty-list key.
   * The caller must provide only ZIP entries with safe names.
   */
  private fun precreateDirectories(
      fileEntries: List<ZipEntry>,
      onProgress: (ExtractProgress) -> Unit,
      filesTotal: Int,
      bytesTotal: Long,
  ): Map<List<String>, DocumentFile> {
    // Include every ancestor so a parent is cached even when it contains no files directly.
    val uniqueParents =
        fileEntries
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
   * Writes [content] as [LAUNCH_JSON_NAME] under the rom dir, replacing any existing file. The
   * returned [DocumentFile] is the new launch descriptor.
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
   * Reads the [LAUNCH_JSON_NAME] contents from the rom dir if present, or null if the launch
   * descriptor has not been written yet.
   */
  fun readLaunchJson(): String? = readDolphinText(resolver, romDir.findFile(LAUNCH_JSON_NAME))

  /**
   * Replaces the cover banner in [romDir]. Pack extraction only writes to [packDir], so updates
   * leave the cover intact.
   */
  suspend fun writeRrCover(): Unit =
      withContext(Dispatchers.IO) {
        copyRawToRomFile(
            resId = R.raw.rr_autostartfile_cover,
            fileName = "rr_autostartfile.cover.png",
            mime = "image/png",
        )
      }

  /**
   * Replaces `rr_autostartfile.xml` in [romDir], substituting [version] for `{VERSION}` in the
   * app-shipped resource. [com.skiletro.wheelwitch.domain.RewindPackManager] treats failures as
   * non-fatal metadata refresh errors.
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
   * Reads the `version.txt` file at `pack/RetroRewind6/version.txt` and parses it as a
   * [SemVersion]. Returns null when the file is missing or unparseable; both are treated as "no
   * local version". Does not create the pack hierarchy.
   */
  fun readVersion(): SemVersion? {
    val directory = findDir(existingPackDir, RETRO_REWIND_DIR_NAME) ?: return null
    val text = readDolphinText(resolver, directory.findFile(VERSION_FILE_NAME)) ?: return null
    val parsed = SemVersion.parse(text.trim())
    if (parsed == null) {
      Timber.tag(TAG).w("Could not parse version file contents: %s", text.trim())
    }
    return parsed
  }

  /**
   * Replaces `pack/RetroRewind6/version.txt`. The pack manager calls this after all extraction and
   * deletion steps succeed if the on-disk version differs from the target. Extraction itself may
   * also overwrite the version file; this is not a rollback mechanism.
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
   * Reads `Config/[fileName]` (the INI file Dolphin uses for its library paths) and returns its
   * UTF-8 contents, or null if the file does not exist yet. Used by
   * [com.skiletro.wheelwitch.util.launcher.DolphinLauncher] to upsert the WheelWitch `rom/` folder
   * as an `ISOPathN` entry.
   */
  fun readConfigIni(fileName: String = CONFIG_INI_NAME): String? {
    val configDir = root.findFile("Config") ?: return null
    if (!configDir.isDirectory) return null
    return readDolphinText(resolver, configDir.findFile(fileName), stripBom = true)
  }

  /**
   * Writes [content] as `Config/[fileName]`, creating the `Config/` directory and replacing any
   * existing file. Returns the new INI [DocumentFile]. Used by the launch flow to register the
   * WheelWitch `rom/` folder with Dolphin's library.
   */
  fun writeConfigIni(content: String, fileName: String = CONFIG_INI_NAME): DocumentFile {
    val configDir =
        findOrCreateDir(root, "Config") ?: error("Cannot create Config/ in Dolphin tree")
    return writeDolphinBytes(
        resolver = resolver,
        parent = configDir,
        name = fileName,
        bytes = content.toByteArray(Charsets.UTF_8),
        mime = "text/plain",
    )
  }

  /** Reads `GameSettings/<gamePrefix>.ini`, creating [gameSettingsDir] if needed. */
  fun readGameIni(gamePrefix: String): String? =
      readDolphinText(
          resolver,
          gameSettingsDir.findFile("$gamePrefix.ini"),
          stripBom = true,
      )

  /**
   * Writes [content] as `GameSettings/<gamePrefix>.ini`, replacing any existing file of the same
   * name.
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
   * Disables cheats and RetroAchievements in `RMC.ini` and the global achievements config, then
   * removes conflicting keys from sibling `RMC*.ini` files. Unrelated settings above the generated
   * notice are retained; everything from the notice onward is replaced.
   */
  fun ensureRmcGameInis() {
    ensureRmcGameIni()
    forceDisableAchievementsGlobally()
    stripConflictingKeysFromSiblingGameInis()
  }

  private fun ensureRmcGameIni() {
    val gameIni = DolphinGameIni
    var requiredSuffix = "\n${gameIni.GAME_INI_NOTICE}"
    // The order of these sections is important here for the force-disabling to work.
    requiredSuffix =
        gameIni.addIniKeyValueAtTheEnd(
            requiredSuffix,
            "[Core]",
            gameIni.CHEATS_KEY,
            gameIni.FORCE_DISABLE_VALUE,
        )
    // Dolphin.Core overrides the global cheats toggle, but can also affect other General settings.
    requiredSuffix =
        gameIni.addIniKeyValueAtTheEnd(
            requiredSuffix,
            "[Dolphin.Core]",
            gameIni.CHEATS_KEY,
            gameIni.FORCE_DISABLE_VALUE,
        )
    requiredSuffix =
        gameIni.addIniKeyValueAtTheEnd(
            requiredSuffix,
            "[Achievements.Achievements]",
            gameIni.ACHIEVEMENTS_KEY,
            gameIni.FORCE_DISABLE_VALUE,
        )
    val existing = readGameIni(gameIni.RMC_PREFIX).orEmpty()
    var content = existing
    // Remove the generated suffix before normalizing user sections and rebuilding it.
    content = content.removeSuffix(requiredSuffix)
    // Move user Core settings ahead of the generated override sections.
    content = gameIni.renameSection(content, "[Core]", "[Core]")
    content = gameIni.renameSection(content, "[Dolphin.Core]", "[Core]")
    content =
        gameIni.renameSection(content, "[Achievements.Achievements]", "[Achievements.Achievements]")
    content = gameIni.removeAllBelowNotice(content)
    content = gameIni.removeIniKeyInSection(content, "[Core]", gameIni.CHEATS_KEY)
    content = gameIni.removeIniKeyInSection(content, "[Dolphin.Core]", gameIni.CHEATS_KEY)
    content =
        gameIni.removeIniKeyInSection(
            content,
            "[Achievements.Achievements]",
            gameIni.ACHIEVEMENTS_KEY,
        )
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
        gameIni.removeIniKeyInSection(
            achievementsContent,
            "[Achievements]",
            gameIni.ACHIEVEMENTS_KEY,
        )
    // Dolphin may need to restart before a change to the global achievements setting takes effect.
    achievementsContent =
        gameIni.addIniKeyValue(
            achievementsContent,
            "[Achievements]",
            gameIni.ACHIEVEMENTS_KEY,
            gameIni.FORCE_DISABLE_VALUE,
        )
    if (existingAchievements != achievementsContent) {
      writeConfigIni(achievementsContent, ACHIEVEMENTS_INI_NAME)
    }
  }

  /**
   * Strips `EnableCheats` and `Achievements.Enabled` from other `RMC*.ini` files that could
   * override the force-disable.
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
      var cleaned = gameIni.removeIniKeyInSection(siblingContent, "[Core]", gameIni.CHEATS_KEY)
      cleaned =
          gameIni.removeIniKeyInSection(
              cleaned,
              "[Dolphin.Core]",
              gameIni.CHEATS_KEY,
          )
      cleaned =
          gameIni.removeIniKeyInSection(
              cleaned,
              "[Achievements.Achievements]",
              gameIni.ACHIEVEMENTS_KEY,
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


  /**
   * Replaces any existing file named [fileName] under [romDir] with the contents of the raw
   * resource at [resId]. Used by [writeRrCover] to copy the app-shipped cover banner.
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
          transferFromFully(channel, input.toReadableByteChannel(), size)
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
    const val TAG = "DolphinTree"

    /** Memoised tree from [fromPersisted]; kept in sync with [memoUri]. */
    private var memoTree: DolphinTree? = null

    /** Persisted URI the memoised tree was built from; null when unset. */
    private var memoUri: String? = null

    /** Buffered-copy buffer used when [FileChannel.transferFrom] is not available. */
    const val COPY_BUFFER_SIZE: Int = 256 * 1024

    /**
     * Filename of the launch descriptor under the rom dir. Re-exported from
     * [com.skiletro.wheelwitch.util.launcher.DolphinLauncher.RR_JSON_NAME]. Single source of truth
     * lives in the launcher.
     */
    const val LAUNCH_JSON_NAME = com.skiletro.wheelwitch.util.launcher.DolphinLauncher.RR_JSON_NAME

    /** Retro Rewind subtree shared by the pack version and launch XML paths. */
    const val RETRO_REWIND_DIR_NAME = "RetroRewind6"

    /** Filename of the pack version file under [RETRO_REWIND_DIR_NAME]. */
    const val VERSION_FILE_NAME = "version.txt"

    /** Filename of the `rr_autostartfile.xml` metadata file under the rom dir. */
    const val METADATA_XML_NAME = "rr_autostartfile.xml"

    /**
     * Placeholder in `R.raw.rr_autostartfile` that [DolphinTree.writeRrMetadata] replaces with the
     * current pack version on every install/update.
     */
    const val VERSION_PLACEHOLDER = "{VERSION}"

    /** Filename of Dolphin's `Config/Dolphin.ini` library-paths config. */
    const val CONFIG_INI_NAME = "Dolphin.ini"

    /** Filename of Dolphin's `Config/RetroAchievements.ini` config. */
    const val ACHIEVEMENTS_INI_NAME = "RetroAchievements.ini"

    /**
     * Returns success if [treeUri] points to the Dolphin user folder. Two URI forms are accepted:
     *
     * 1. **Primary external storage**: the stock SAF picker's default:
     *    `content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Forg.dolphinemu.dolphinemu%2Ffiles`.
     *    Document id is `primary:Android/data/org.dolphinemu.dolphinemu/files`.
     * 2. **Dolphin's own SAF provider**: surfaced by the picker on devices where Dolphin is
     *    installed: `content://org.dolphinemu.dolphinemu.user/tree/root%2F`. Document id is
     *    `root/`. This provider maps to the same physical folder and is the recommended path on
     *    modern Android. It does not require the legacy `MANAGE_EXTERNAL_STORAGE` permission.
     *
     * Subfolders of either root are rejected. The WheelWitch subdirectory must be created at the
     * top of Dolphin's user folder, not inside some intermediate location.
     */
    fun validate(treeUri: Uri): Result<Unit> {
      val treeId =
          runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
              ?: return Result.failure(
                  InvalidTreeUriException(InvalidTreeReason.NotTreeUri, "Not a tree URI: $treeUri")
              )

      if (
          treeUri.authority == "com.android.externalstorage.documents" &&
              treeId == DolphinPaths.expectedTreeId()
      ) {
        return Result.success(Unit)
      }

      if (treeUri.authority == "org.dolphinemu.dolphinemu.user" && treeId == "root/") {
        return Result.success(Unit)
      }

      val baseMessage =
          "Tree $treeUri (authority='${treeUri.authority}', docId='$treeId') " +
              "is not the Dolphin user folder. Expected either primary external storage " +
              "(primary:Android/data/org.dolphinemu.dolphinemu/files) or the Dolphin app's own storage root."
      // Distinguish subfolders so onboarding can direct the user to the accepted root.
      val reason =
          when {
            treeUri.authority == "com.android.externalstorage.documents" &&
                treeId.startsWith("${DolphinPaths.expectedTreeId()}/") ->
                InvalidTreeReason.SubfolderExternal
            treeUri.authority == "org.dolphinemu.dolphinemu.user" && treeId.startsWith("root/") ->
                InvalidTreeReason.SubfolderInternal
            else -> InvalidTreeReason.NotDolphinFolder
          }

      return Result.failure(
          InvalidTreeUriException(
              reason,
              if (reason !== InvalidTreeReason.NotDolphinFolder)
                  "$baseMessage ${parentHint(reason)}"
              else baseMessage,
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
     * Reconstructs the persisted [DolphinTree] from [PrefsKeys.WHEELWITCH_TREE_URI_KEY], or returns
     * null if no URI is stored or reconstruction fails. A failed reconstruction clears the stored
     * URI so the UI can route to onboarding. Reconstruction initializes [wheelWitchDir].
     *
     * The wrapper and SAF root are memoised while the stored URI is unchanged. [persist], a missing
     * stored URI, or reconstruction failure invalidates the memo. Reuse does not revalidate the
     * provider grant; optional user-data directory getters still perform fresh lookups.
     */
    @Synchronized
    fun fromPersisted(context: Context): DolphinTree? {
      val prefs = Prefs.main(context)
      val uriString = prefs.getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null)
      if (uriString == null) {
        Timber.tag(TAG)
            .i(
                "fromPersisted: no tree URI under %s; user has not completed onboarding",
                PrefsKeys.WHEELWITCH_TREE_URI_KEY,
            )
        memoUri = null
        memoTree = null
        return null
      }
      if (memoUri == uriString) {
        Timber.tag(TAG)
            .i(
                "fromPersisted: reusing memoised tree for authority=%s",
                memoTree?.treeUri?.authority,
            )
        return memoTree
      }
      return try {
        val uri = Uri.parse(uriString)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        Timber.tag(TAG)
            .i(
                "fromPersisted: rebuilding tree from authority=%s docId=%s",
                uri?.authority,
                docId,
            )
        val tree = DolphinTree(context, uri)
        memoUri = uriString
        memoTree = tree
        Timber.tag(TAG).i("fromPersisted: tree built ok, wheelWitchDir=%s", tree.wheelWitchDir.uri)
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
      Timber.tag(TAG).i("persist: stored tree URI authority=%s", tree.treeUri.authority)
    }

    /** Drops the in-process memo, e.g. in tests that build the tree repeatedly. */
    internal fun resetMemo() {
      memoUri = null
      memoTree = null
    }
  }
}


internal fun transferFromFully(
    target: FileChannel,
    source: ReadableByteChannel,
    size: Long,
): Long {
  require(size >= 0L) { "size must not be negative" }
  var transferred = 0L
  while (transferred < size) {
    val count = target.transferFrom(source, transferred, size - transferred)
    if (count <= 0L) {
      throw IOException("short transfer: $transferred of $size bytes")
    }
    transferred += count
  }
  return transferred
}

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
 * Finds `Wii/title/<titleId>/<subdir>/data/rksys.dat` without creating NAND directories.
 * Returns null if any directory or the save file is absent.
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
  val file =
      dataDir.findFile(com.skiletro.wheelwitch.data.SaveManager.SAVE_FILE_NAME) ?: return null
  return if (file.exists() && file.isFile) file else null
}

/** Walks [parts] under [root], creating intermediate directories for restore writes. */
internal fun navigateOrCreate(root: DocumentFile, parts: List<String>): DocumentFile {
  var current = root
  for (part in parts) {
    current = findOrCreateDir(current, part) ?: error("Cannot create $part under ${current.uri}")
  }
  return current
}

/** Returns null for a missing/non-file document or a null stream; I/O exceptions propagate. */
internal fun readDolphinBytes(resolver: ContentResolver, file: DocumentFile?): ByteArray? {
  if (file == null || !file.exists() || !file.isFile) return null
  val input = resolver.openInputStream(file.uri) ?: return null
  return input.use { it.readBytes() }
}

/** Reads UTF-8 text; null documents or streams yield null, while I/O exceptions propagate. */
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
  val file = parent.createFile(mime, name) ?: error("Cannot create $name in ${parent.uri}")
  val output = resolver.openOutputStream(file.uri) ?: error("Cannot open output stream for $name")
  output.use { it.write(bytes) }
  return file
}

/**
 * Writes files recursively under [dir] to ZIP paths prefixed by [basePath]. Missing or
 * non-directory [dir] is a no-op. Empty directories are omitted; restore creates parents as needed.
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
