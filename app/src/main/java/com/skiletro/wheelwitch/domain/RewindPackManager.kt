package com.skiletro.wheelwitch.domain

import android.content.Context
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.ExtractingPhase
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.network.VersionFileParser
import com.skiletro.wheelwitch.util.io.DownloadProgress
import com.skiletro.wheelwitch.util.io.FileDownloader
import com.skiletro.wheelwitch.util.io.ParallelDownloadProgress
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the install/update flow for the Retro Rewind Pack.
 *
 * Reads the local pack version from [DolphinTree.readVersion] (the
 * `pack/RetroRewind6/version.txt` inside the SAF tree) and compares
 * it against the server manifest; performs full or incremental
 * installs by downloading the pack zip to [Context.getCacheDir]
 * (where `java.io.File` works) and then streaming it into the SAF
 * tree via [DolphinTree.extractZipToPack]. After a successful
 * extract, the version file is only written if the pack zip's own
 * `version.txt` is missing or stale (typical for hotfix zips that
 * don't ship a new `version.txt`), and the `rr_autostartfile.xml`
 * metadata in `rom/` is always re-templated with the installed
 * version so Dolphin's launch-descriptor UI shows the right value.
 *
 * The downloader is [FileDownloader], the hand-rolled OkHttp wrapper.
 * The pack zip is multi-MB so we use the
 * [HttpClientProvider.largeDownloadClient] (60s read timeout).
 */
class RewindPackManager(
  private val context: Context,
  private val tree: DolphinTree,
) {
  /**
   * Reads the local pack version and the server manifest and returns
   * the appropriate [PackStatus]. If the server is unreachable and a
   * local version exists, returns [PackStatus.CheckFailed] so the UI
   * can surface a retry affordance (distinct from
   * [PackStatus.UpToDate], which implies the version is up to date).
   * If the server is unreachable and there is no local version,
   * returns [PackStatus.NotInstalled] so the user is routed to
   * onboarding.
   */
  suspend fun checkStatus(): PackStatus = withContext(Dispatchers.IO) {
    val local = tree.readVersion()
    val server = VersionFileParser.fetchServerInfo().getOrNull()
    if (server == null) {
      Timber.tag(TAG).w("Server info unavailable; localVersion=%s", local)
      return@withContext if (local != null) {
        PackStatus.CheckFailed(local)
      } else {
        PackStatus.NotInstalled
      }
    }
    when {
      local == null -> {
        Timber.tag(TAG).d("Not installed; server latest=%s", server.latestVersion)
        PackStatus.NotInstalled
      }
      local >= server.latestVersion -> {
        Timber.tag(TAG)
          .d("Up to date: local=%s server=%s", local, server.latestVersion)
        PackStatus.UpToDate(local, server.latestVersion)
      }
      else -> {
        Timber.tag(TAG)
          .i("Update available: %s -> %s", local, server.latestVersion)
        PackStatus.UpdateAvailable(local, server.latestVersion, server)
      }
    }
  }

  /**
   * Downloads the full pack zip and extracts it into the SAF tree.
   * Always overwrites the local install. Use this for a fresh install
   * or to recover from a corrupted state.
   *
   * Wrapped in [withContext] [Dispatchers.IO] because the initial
   * server-info call hits the network; without it, a [viewModelScope]
   * caller (which defaults to `Dispatchers.Main.immediate`) trips
   * Android's `NetworkOnMainThreadException` strict-mode check.
   */
  suspend fun installLatest(onProgress: (InstallProgress) -> Unit): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val server = VersionFileParser.fetchServerInfo().getOrThrow()
        Timber.tag(TAG).i("Starting full install of %s", server.latestVersion)
        performInstall(VersionFileParser.getFullZipUrl(), onProgress)
        if (tree.readVersion() != server.latestVersion) {
          tree.writeVersion(server.latestVersion)
        }
        writeRrMetadataSafe(server.latestVersion)
      }
    }

  /**
   * Performs the smallest set of incremental updates that takes the
   * local pack from its current version to the server's latest
   * version. Falls back to a full reinstall if the local version is
   * missing or older than [MIN_INCREMENTAL_VERSION]. The pack format
   * changed incompatibly around that point, so partial updates are
   * not safe.
   *
   * Same `Dispatchers.IO` wrapper as [installLatest]. See the kdoc
   * there for why.
   */
  suspend fun update(onProgress: (InstallProgress) -> Unit): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val local = tree.readVersion()
        val server = VersionFileParser.fetchServerInfo().getOrThrow()
        val minVersion =
          SemVersion.parse(MIN_INCREMENTAL_VERSION)
            ?: error("Invalid MIN_INCREMENTAL_VERSION constant")
        if (local == null || local < minVersion) {
          Timber.tag(TAG)
            .i("Local version %s is below %s; doing full reinstall", local, minVersion)
          performInstall(VersionFileParser.getFullZipUrl(), onProgress)
        } else {
          val steps =
            server.allUpdates
              .filter { it.version > local && it.version <= server.latestVersion }
              .sortedBy { it.version }
          Timber.tag(TAG)
            .i(
              "Applying %d incremental update steps from %s to %s",
              steps.size,
              local,
              server.latestVersion,
            )
          for (step in steps) {
            performInstall(step.url, onProgress)
          }
        }
        if (tree.readVersion() != server.latestVersion) {
          tree.writeVersion(server.latestVersion)
        }
        writeRrMetadataSafe(server.latestVersion)
      }
    }

  /**
   * Performs a fresh full install from the server's full zip URL.
   * Use this to recover from a corrupted or inconsistent state that
   * incremental updates cannot fix.
   *
   * Same [Dispatchers.IO] wrapper as [installLatest]. See the kdoc
   * there for why.
   */
  suspend fun reinstall(onProgress: (InstallProgress) -> Unit): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val server = VersionFileParser.fetchServerInfo().getOrThrow()
        Timber.tag(TAG).i("Starting full reinstall of %s", server.latestVersion)
        performInstall(VersionFileParser.getFullZipUrl(), onProgress)
        if (tree.readVersion() != server.latestVersion) {
          tree.writeVersion(server.latestVersion)
        }
        writeRrMetadataSafe(server.latestVersion)
      }
    }

  /**
   * Phased progress emitted by [performInstall]. Lets the caller
   * distinguish between the network-bound download phase (which
   * carries a [DownloadProgress] with byte counts) and the
   * disk-bound extraction phase (which carries a file count).
   */
  sealed class InstallProgress {
    /** Zip is being fetched from the update server. */
    data class Downloading(val progress: DownloadProgress) : InstallProgress()

    /**
     * Zip is being unpacked into the SAF tree.
     *
     * - [phase] tells the caller whether the directory pre-pass
     *   (the slow part) is running or the per-file writes have
     *   started.
     * - [filesDone] / [filesTotal] are the file count basis for the
     *   determinate bar.
     * - [currentFile] is the entry currently being written, or
     *   null between files and during the pre-pass.
     * - [bytesDone] / [bytesTotal] are the uncompressed byte counts
     *   carried for future display / ETA use.
     */
    data class Extracting(
      val phase: ExtractingPhase,
      val filesDone: Int,
      val filesTotal: Int,
      val currentFile: String?,
      val bytesDone: Long,
      val bytesTotal: Long,
    ) : InstallProgress()
  }

  /**
   * Downloads a pack zip to [Context.getCacheDir], extracts it into
   * the SAF tree's pack directory, and deletes the temp file. The
   * [onProgress] callback fires for both phases. First the
   * per-byte download progress, then the per-file extraction
   * progress.
   *
   * Uses [FileDownloader.downloadInParallel] which issues a HEAD
   * probe and falls back to single-stream if the server doesn't
   * support byte ranges. The downloader accepts
   * [com.skiletro.wheelwitch.util.io.ParallelDownloadProgress]
   * (structurally identical to [DownloadProgress] with one extra
   * `activeChunks` field) which we project to the UI's
   * [DownloadProgress] shape so the consumer doesn't change.
   */
  private suspend fun performInstall(
    url: String,
    onProgress: (InstallProgress) -> Unit,
  ) {
    val zipFile = File(context.cacheDir, PACK_ZIP_NAME)
    FileDownloader.downloadInParallel(
      url = url,
      targetFile = zipFile,
      onProgress = { pp -> onProgress(InstallProgress.Downloading(pp.toDownloadProgress())) },
      client = HttpClientProvider.largeDownloadClient,
    )
    try {
      tree.extractZipToPack(zipFile) { ep ->
        onProgress(
          InstallProgress.Extracting(
            phase = ep.phase,
            filesDone = ep.filesDone,
            filesTotal = ep.filesTotal,
            currentFile = ep.currentFile,
            bytesDone = ep.bytesDone,
            bytesTotal = ep.bytesTotal,
          )
        )
      }
    } finally {
      zipFile.delete()
    }
  }

  /**
   * Writes the templated `rr_autostartfile.xml` metadata with [version],
   * swallowing and logging any throwable so a metadata write failure
   * never rolls back a successful install. The version.txt under
   * `pack/RetroRewind6/` is the load-bearing state for the launcher;
   * this is cosmetic for Dolphin's launch-descriptor UI, so we'd
   * rather report "installed" and log the metadata miss than surface
   * a red screen for a one-line XML refresh.
   */
  private suspend fun writeRrMetadataSafe(version: SemVersion) {
    try {
      tree.writeRrMetadata(version)
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Failed to write rr metadata for version %s", version)
    }
  }

  private fun ParallelDownloadProgress.toDownloadProgress(): DownloadProgress =
    DownloadProgress(
      progress = progress,
      bytesPerSecond = bytesPerSecond,
      bytesDownloaded = bytesDownloaded,
      totalBytes = totalBytes,
    )

  private companion object {
    /** Local versions older than this get a full reinstall, not an incremental update. */
    const val MIN_INCREMENTAL_VERSION = "3.2.6"

    /** Cached pack zip filename inside [Context.getCacheDir]. */
    const val PACK_ZIP_NAME = "RetroRewind.zip"

    const val TAG = "RewindPack"
  }
}
