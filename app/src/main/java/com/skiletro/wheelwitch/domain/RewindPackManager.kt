package com.skiletro.wheelwitch.domain

import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.ExtractingPhase
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.model.ServerInfo
import com.skiletro.wheelwitch.network.VersionFileParser
import com.skiletro.wheelwitch.util.io.DownloadProgress
import com.skiletro.wheelwitch.util.io.FileDownloader
import com.skiletro.wheelwitch.util.io.ParallelDownloadProgress
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the install/update flow for the Retro Rewind Pack.
 *
 * Reads the local pack version from [DolphinTree.readVersion] (the
 * `pack/RetroRewind6/version.txt` inside the SAF tree) and compares
 * it against the server manifest; performs full or incremental
 * installs by downloading the pack zip to [cacheDir] (where
 * `java.io.File` works) and then streaming it into the SAF tree via
 * [DolphinTree.extractZipToPack]. After a successful extract, the
 * version file is only written if the pack zip's own `version.txt` is
 * missing or stale (typical for hotfix zips that don't ship a new
 * `version.txt`), and the `rr_autostartfile.xml` metadata in `rom/` is
 * always re-templated with the installed version so Dolphin's
 * launch-descriptor UI shows the right value.
 *
 * The downloader is [FileDownloader], the hand-rolled OkHttp wrapper.
 * The pack zip is multi-MB so we use the
 * [HttpClientProvider.largeDownloadClient] (60s read timeout).
 */
class RewindPackManager(
  private val tree: DolphinTree,
  private val cacheDir: File,
  private val server: PackServerSource = DefaultPackServer,
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
    val info = server.fetchServerInfo().getOrNull()
    if (info == null) {
      Timber.tag(TAG).w("Server info unavailable; localVersion=%s", local)
      return@withContext if (local != null) {
        PackStatus.CheckFailed(local)
      } else {
        PackStatus.NotInstalled
      }
    }
    when {
      local == null -> {
        Timber.tag(TAG).d("Not installed; server latest=%s", info.latestVersion)
        PackStatus.NotInstalled
      }
      local >= info.latestVersion -> {
        Timber.tag(TAG)
          .d("Up to date: local=%s server=%s", local, info.latestVersion)
        PackStatus.UpToDate(local, info.latestVersion)
      }
      else -> {
        Timber.tag(TAG)
          .i("Update available: %s -> %s", local, info.latestVersion)
        PackStatus.UpdateAvailable(local, info.latestVersion, info)
      }
    }
  }

  /**
   * Downloads the full pack zip and extracts it into the SAF tree.
   * Always overwrites the local install. Use this for a fresh install
   * or to recover from a corrupted state.
   */
  suspend fun installLatest(onProgress: (InstallProgress) -> Unit): Result<Unit> =
    runInstall(
      fetchUrl = { progress ->
        val info = server.fetchServerInfo().getOrThrow()
        Timber.tag(TAG).i("Starting full install of %s", info.latestVersion)
        performInstall(server.fetchFullZipUrl(), progress)
        info.latestVersion
      },
      onProgress = onProgress,
    )

  /**
   * Performs the smallest set of incremental updates that takes the
   * local pack from its current version to the server's latest
   * version. Falls back to a full reinstall if the local version is
   * missing (e.g. first install or corrupted state).
   */
  suspend fun update(onProgress: (InstallProgress) -> Unit): Result<Unit> =
    runInstall(
      fetchUrl = { progress ->
        val local = tree.readVersion()
        val info = server.fetchServerInfo().getOrThrow()
        if (local == null) {
          Timber.tag(TAG)
            .i("Local version missing; doing full reinstall")
          performInstall(server.fetchFullZipUrl(), progress)
        } else {
          val steps =
            info.allUpdates
              .filter { it.version > local && it.version <= info.latestVersion }
              .sortedBy { it.version }
          Timber.tag(TAG)
            .i(
              "Applying %d incremental update steps from %s to %s",
              steps.size,
              local,
              info.latestVersion,
            )
          for (step in steps) {
            performInstall(step.url, progress)
          }
        }
        info.latestVersion
      },
      onProgress = onProgress,
    )

  /**
   * Performs a fresh full install from the server's full zip URL.
   * Use this to recover from a corrupted or inconsistent state that
   * incremental updates cannot fix.
   */
  suspend fun reinstall(onProgress: (InstallProgress) -> Unit): Result<Unit> =
    runInstall(
      fetchUrl = { progress ->
        val info = server.fetchServerInfo().getOrThrow()
        Timber.tag(TAG).i("Starting full reinstall of %s", info.latestVersion)
        performInstall(server.fetchFullZipUrl(), progress)
        info.latestVersion
      },
      onProgress = onProgress,
    )

  /**
   * Shared skeleton for every install path: runs the URL-resolving
   * step on [Dispatchers.IO] (so the step's network calls stay off the
   * main thread), synchronises the local `version.txt` with the
   * installed version, refreshes the `rr_autostartfile.xml` metadata,
   * and folds any failure into a [Result] — always rethrowing
   * [CancellationException] so coroutine cancellation stays intact.
   */
  private suspend fun runInstall(
    fetchUrl: suspend (onProgress: (InstallProgress) -> Unit) -> SemVersion,
    onProgress: (InstallProgress) -> Unit,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val version = fetchUrl(onProgress)
        if (tree.readVersion() != version) {
          tree.writeVersion(version)
        }
        writeRrMetadataSafe(version)
        Result.success(Unit)
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        Result.failure(t)
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
    val zipFile = File(cacheDir, PACK_ZIP_NAME)
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
    /** Cached pack zip filename inside the injected [cacheDir]. */
    const val PACK_ZIP_NAME = "RetroRewind.zip"

    const val TAG = "RewindPack"
  }
}

private object DefaultPackServer : PackServerSource {
  override fun fetchServerInfo(): Result<ServerInfo> = VersionFileParser.fetchServerInfo()

  override fun fetchFullZipUrl(): String = VersionFileParser.getFullZipUrl()
}
