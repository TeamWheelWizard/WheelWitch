package com.skiletro.wheelwitch.util.mii

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.skiletro.wheelwitch.data.GameTypeParser
import com.skiletro.wheelwitch.util.io.FileDownloader
import com.skiletro.wheelwitch.util.io.ZipSafety
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Downloads and installs the Mii Channel WAD file for use as a Mii Maker in Dolphin. */
object MiiWadInstaller {
    private const val MII_MAKER_ZIP_URL =
        "https://filecache45.gamebanana.com/mods/mii_channel_symbols_-_hacs.zip"
    private const val WAD_FILE_NAME = "Mii Channel Symbols - HACS.wad"
    private const val ZIP_FILE_NAME = "mii_channel_symbols.zip"
    private const val CACHE_DIR_NAME = "mii_maker"
    private const val EXPECTED_ZIP_SHA256 =
        "9fd802a4bd80cda4522817b7e0a95aaa703ad29e6d866156f67bdb86549bb0f6"

    /** Returns the cached WAD file from `cache/mii_maker/`, or null if not yet downloaded. */
    fun getCachedWadFile(context: Context): File? {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        val wad = File(dir, WAD_FILE_NAME)
        return if (wad.exists()) wad else null
    }

    /**
     * Downloads the WAD zip from GameBanana, extracts the `.wad` file, and
     * returns it. Validates WAD magic header before returning.
     *
     * Leaves the extracted WAD at `cache/mii_maker/$WAD_FILE_NAME` (the file
     * [getCachedWadFile] returns). The intermediate zip is deleted after
     * extraction.
     */
    fun downloadAndExtractWad(context: Context): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        cacheDir.mkdirs()

        val zipFile = File(cacheDir, ZIP_FILE_NAME)
        FileDownloader.downloadInParallel(
            url = MII_MAKER_ZIP_URL,
            targetFile = zipFile,
            client = HttpClientProvider.largeDownloadClient,
        )

        verifyZipIntegrity(zipFile, EXPECTED_ZIP_SHA256)

        val wadFile = extractWad(zipFile, cacheDir)
        zipFile.delete()

        return wadFile
    }

    /** Removes the `cache/mii_maker/` directory and everything in it. */
    fun clearCache(context: Context) {
        File(context.cacheDir, CACHE_DIR_NAME).deleteRecursively()
    }

    fun isValidWad(file: File): Boolean {
        return try {
            val bytes = file.readBytes()
            GameTypeParser.checkValidity(file.name, bytes)
        } catch (e: Exception) {
            Timber.tag("MiiWad").w(e, "WAD validation failed for %s", file.absolutePath)
            false
        }
    }

    /** Launches Dolphin with the given WAD file via `ACTION_VIEW` + `FileProvider` content URI. Validates magic header first (0x00 0x00 0x00 0x20). */
    fun launchWadFile(context: Context, wadFile: File): Result<Unit> = runCatching {
        require(isValidWad(wadFile)) { "Cached WAD file is corrupted or incomplete" }
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            wadFile
        )

        val intent = Intent().apply {
            setClassName(DolphinLauncher.DOLPHIN_PACKAGE, DolphinLauncher.DOLPHIN_MAIN_ACTIVITY)
            data = contentUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Timber.tag("MiiWad").d("Launched WAD via %s", contentUri)
    }

    @VisibleForTesting
    fun extractWadForTest(zipFile: File, destDir: File): File = extractWad(zipFile, destDir)

    private fun extractWad(zipFile: File, destDir: File): File {
        val wadFiles = mutableListOf<File>()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!ZipSafety.isSafeEntryName(entry.name)) {
                    Timber.w("Skipping unsafe zip entry: %s", entry.name)
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (!entry.isDirectory && entry.name.endsWith(".wad", ignoreCase = true)) {
                    val outFile = File(destDir, entry.name.substringAfterLast("/"))
                    outFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                    wadFiles.add(outFile)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        // Prefer the HACS variant: it is the symbol-patched build Dolphin needs
        // to run Mii Maker. Fall back to any .wad if it is not present.
        val chosen = wadFiles.firstOrNull { it.name.contains("HACS", ignoreCase = true) }
            ?: wadFiles.firstOrNull()
            ?: error("No .wad file found in the archive")
        Timber.tag("MiiWad").d("Extracted WAD: %s", chosen.name)
        return chosen
    }

    @VisibleForTesting
    fun verifyZipIntegrity(file: File, expectedHash: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            file.delete()
            throw SecurityException(
                "SHA-256 mismatch for ${file.name}: expected $expectedHash, got $actualHash",
            )
        }
    }
}
