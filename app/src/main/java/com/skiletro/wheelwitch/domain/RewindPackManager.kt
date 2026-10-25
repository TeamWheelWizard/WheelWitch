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
 * Installs full or incremental packs through a cached ZIP into Dolphin's SAF tree.
 *
 * After all extraction/deletion steps succeed, synchronizes `pack/RetroRewind6/version.txt` with
 * the target version if needed and attempts a non-fatal metadata refresh. Extraction may itself
 * replace `version.txt`; failures do not roll back files or versions already written.
 */
class RewindPackManager(
    private val tree: DolphinTree,
    private val cacheDir: File,
    private val server: PackServerSource = DefaultPackServer,
) {
  /**
   * Reads the local pack version and the server manifest and returns the appropriate [PackStatus].
   * If the server is unreachable and a local version exists, returns [PackStatus.CheckFailed] so
   * the UI can surface a retry affordance (distinct from [PackStatus.UpToDate], which implies the
   * version is up to date). If the server is unreachable and there is no local version, returns
   * [PackStatus.NotInstalled] so the user is routed to onboarding.
   */
  suspend fun checkStatus(): PackStatus =
      withContext(Dispatchers.IO) {
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
            Timber.tag(TAG).d("Up to date: local=%s server=%s", local, info.latestVersion)
            PackStatus.UpToDate(local, info.latestVersion)
          }
          else -> {
            Timber.tag(TAG).i("Update available: %s -> %s", local, info.latestVersion)
            PackStatus.UpdateAvailable(local, info.latestVersion, info)
          }
        }
      }

  /**
   * Downloads and overlays the full pack, preserving protected user data. Existing files absent
   * from the archive are not removed.
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
   * Applies manifest updates newer than the local version through the latest version, in version
   * order. Each version's extractions precede its deletions. Falls back to a full overlay when the
   * local version is missing.
   */
  suspend fun update(onProgress: (InstallProgress) -> Unit): Result<Unit> =
      runInstall(
          fetchUrl = { progress ->
            val local = tree.readVersion()
            val info = server.fetchServerInfo().getOrThrow()
            if (local == null) {
              Timber.tag(TAG).i("Local version missing; doing full reinstall")
              performInstall(server.fetchFullZipUrl(), progress)
            } else {
              val steps =
                  info.allUpdates
                      .filter { it.version > local && it.version <= info.latestVersion }
                      .sortedBy { it.version }
              val deletions =
                  info.deletions
                      .filter { it.version > local && it.version <= info.latestVersion }
                      .sortedBy { it.version }
              Timber.tag(TAG)
                  .i(
                      "Applying %d incremental update steps and %d deletions from %s to %s",
                      steps.size,
                      deletions.size,
                      local,
                      info.latestVersion,
                  )
              val stepsByVersion = steps.groupBy { it.version }
              val deletionsByVersion = deletions.groupBy { it.version }
              val versions = (stepsByVersion.keys + deletionsByVersion.keys).sorted()
              for (version in versions) {
                stepsByVersion[version].orEmpty().forEach { step ->
                  performInstall(step.url, progress)
                }
                deletionsByVersion[version].orEmpty().forEach { deletion ->
                  if (!tree.deletePackEntry(deletion.path)) {
                    Timber.tag(TAG).d("Pack deletion target absent or rejected: %s", deletion.path)
                  }
                }
              }
            }
            info.latestVersion
          },
          onProgress = onProgress,
      )

  /**
   * Overlays the server's full ZIP to replace damaged pack files. Does not remove files absent
   * from the archive.
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
   * Runs installation steps on [Dispatchers.IO], then synchronizes the version and refreshes
   * metadata. Installation failures become [Result.failure]; cancellation from installation steps
   * is rethrown. The metadata refresh handles its own exceptions.
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

  /** Download byte progress or extraction snapshots forwarded from [DolphinTree]. */
  sealed class InstallProgress {
    /** Zip is being fetched from the update server. */
    data class Downloading(val progress: DownloadProgress) : InstallProgress()

    /**
     * Zip is being unpacked into the SAF tree.
     *
     * - [phase] distinguishes folder preparation from per-file writes.
     * - [filesDone] / [filesTotal] count completed and accepted files.
     * - [currentFile] identifies the file before and after its write; null during preparation.
     * - [bytesDone] / [bytesTotal] use uncompressed ZIP sizes, with unknown sizes treated as zero.
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
   * Downloads to [cacheDir], then extracts into the SAF pack directory. Progress callbacks run on
   * the I/O context. The cached ZIP is deleted after extraction succeeds or fails.
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

  /** Logs metadata refresh exceptions without failing installation, including cancellation here. */
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
    const val PACK_ZIP_NAME = "RetroRewind.zip"

    const val TAG = "RewindPack"
  }
}

private object DefaultPackServer : PackServerSource {
  override fun fetchServerInfo(): Result<ServerInfo> = VersionFileParser.fetchServerInfo()

  override fun fetchFullZipUrl(): String = VersionFileParser.getFullZipUrl()
}
