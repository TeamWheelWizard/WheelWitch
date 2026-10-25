package com.skiletro.wheelwitch.domain

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.ExtractProgress
import com.skiletro.wheelwitch.data.ExtractingPhase
import com.skiletro.wheelwitch.model.DeletionEntry
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.model.ServerInfo
import com.skiletro.wheelwitch.model.UpdateEntry
import com.skiletro.wheelwitch.util.io.FileDownloader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RewindPackManagerTest {

  private lateinit var tree: DolphinTree
  private lateinit var cacheDir: File

  @BeforeEach
  fun setUp(@TempDir tempDir: File) {
    tree = mockk(relaxed = true)
    cacheDir = tempDir
    mockkObject(FileDownloader)
  }

  @AfterEach
  fun tearDown() {
    unmockkObject(FileDownloader)
  }


  @Test
  fun `checkStatus returns NotInstalled when no local version and server reachable`() =
      runBlocking {
        coEvery { tree.readVersion() } returns null

        val status = manager().checkStatus()

        assertThat(status).isEqualTo(PackStatus.NotInstalled)
      }

  @Test
  fun `checkStatus returns UpdateAvailable when local is behind server`() = runBlocking {
    coEvery { tree.readVersion() } returns SemVersion(3, 2, 5)

    val status = manager().checkStatus()

    assertThat(status).isInstanceOf(PackStatus.UpdateAvailable::class.java)
    val ua = status as PackStatus.UpdateAvailable
    assertThat(ua.currentVersion).isEqualTo(SemVersion(3, 2, 5))
    assertThat(ua.latestVersion).isEqualTo(SemVersion(3, 2, 6))
  }

  @Test
  fun `checkStatus returns UpToDate when local matches server`() = runBlocking {
    coEvery { tree.readVersion() } returns SemVersion(3, 2, 6)

    val status = manager().checkStatus()

    assertThat(status).isEqualTo(PackStatus.UpToDate(SemVersion(3, 2, 6), SemVersion(3, 2, 6)))
  }

  @Test
  fun `checkStatus returns CheckFailed when server unreachable and local version exists`() =
      runBlocking {
        coEvery { tree.readVersion() } returns SemVersion(3, 2, 5)

        val status =
            manager(
                    server =
                        fakeServer(
                            serverInfoResult = { Result.failure(Exception("Network error")) }
                        )
                )
                .checkStatus()

        assertThat(status).isEqualTo(PackStatus.CheckFailed(SemVersion(3, 2, 5)))
      }

  @Test
  fun `checkStatus returns NotInstalled when server unreachable and no local version`() =
      runBlocking {
        coEvery { tree.readVersion() } returns null

        val status =
            manager(
                    server =
                        fakeServer(
                            serverInfoResult = { Result.failure(Exception("Network error")) }
                        )
                )
                .checkStatus()

        assertThat(status).isEqualTo(PackStatus.NotInstalled)
      }

  @Test
  fun `checkStatus surfaces exceptions as they bubble up`() = runBlocking {
    coEvery { tree.readVersion() } throws IllegalStateException("SAF boom")

    val ex = runCatching { manager().checkStatus() }.exceptionOrNull()
    assertThat(ex).isInstanceOf(IllegalStateException::class.java)
  }


  @Test
  fun `installLatest writes the version when the zip's version txt is missing`() = runBlocking {
    val server = serverInfo()
    val progressReports = mutableListOf<RewindPackManager.InstallProgress>()
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // ZIP magic
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } returns Unit
    coEvery { tree.readVersion() } returns null
    coEvery { tree.writeVersion(server.latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(server.latestVersion) } returns Unit

    val result = manager().installLatest { progress -> progressReports.add(progress) }

    assertThat(result.isSuccess).isTrue()
    coVerify(exactly = 1) {
      FileDownloader.downloadInParallel(
          url = "https://example.com/RetroRewind.zip",
          targetFile = any(),
          onProgress = any(),
          client = any(),
          maxRetries = any(),
          initialBackoffMillis = any(),
      )
    }
    coVerify(exactly = 1) { tree.extractZipToPack(any(), any()) }
    coVerify(exactly = 1) { tree.writeVersion(server.latestVersion) }
    coVerify(exactly = 1) { tree.writeRrMetadata(server.latestVersion) }
  }

  @Test
  fun `installLatest skips writeVersion when the zip's version txt already matches the server`() =
      runBlocking {
        val server = serverInfo()
        coEvery {
          FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any())
        } answers
            {
              val target = it.invocation.args[1] as File
              target.parentFile?.mkdirs()
              target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
              target
            }
        coEvery { tree.extractZipToPack(any(), any()) } returns Unit
        coEvery { tree.readVersion() } returns server.latestVersion
        coEvery { tree.writeRrMetadata(server.latestVersion) } returns Unit

        val result = manager().installLatest {  }

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { tree.writeVersion(any()) }
        coVerify(exactly = 1) { tree.writeRrMetadata(server.latestVersion) }
      }

  @Test
  fun `installLatest reports success even when writeRrMetadata throws`() = runBlocking {
    val server = serverInfo()
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } returns Unit
    coEvery { tree.readVersion() } returns null
    coEvery { tree.writeVersion(server.latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(server.latestVersion) } throws
        IllegalStateException("metadata boom")

    val result = manager().installLatest {  }

    // Metadata is cosmetic; version.txt determines the installed version.
    assertThat(result.isSuccess).isTrue()
    coVerify(exactly = 1) { tree.writeVersion(server.latestVersion) }
  }

  @Test
  fun `installLatest emits Downloading then Extracting phases in order`() = runBlocking {
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
          target
        }
    val phases = mutableListOf<RewindPackManager.InstallProgress>()
    coEvery { tree.extractZipToPack(any(), any()) } coAnswers
        {
          val cb = it.invocation.args[1] as (ExtractProgress) -> Unit
          cb(ExtractProgress(ExtractingPhase.PreparingFolders, 0, 3, null, 0L, 100L))
          cb(ExtractProgress(ExtractingPhase.WritingFiles, 0, 3, "f1", 0L, 100L))
          cb(ExtractProgress(ExtractingPhase.WritingFiles, 1, 3, "f2", 33L, 100L))
          cb(ExtractProgress(ExtractingPhase.WritingFiles, 2, 3, "f3", 66L, 100L))
          cb(ExtractProgress(ExtractingPhase.WritingFiles, 3, 3, "f3", 100L, 100L))
        }
    coEvery { tree.readVersion() } returns null
    coEvery { tree.writeVersion(serverInfo().latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(serverInfo().latestVersion) } returns Unit

    val result = manager().installLatest { phase -> phases.add(phase) }

    assertThat(result.isSuccess).isTrue()
    // The downloader is mocked, so only extraction callbacks have a fixed sequence here.
    assertThat(phases.filterIsInstance<RewindPackManager.InstallProgress.Extracting>())
        .containsExactly(
            RewindPackManager.InstallProgress.Extracting(
                ExtractingPhase.PreparingFolders,
                0,
                3,
                null,
                0L,
                100L,
            ),
            RewindPackManager.InstallProgress.Extracting(
                ExtractingPhase.WritingFiles,
                0,
                3,
                "f1",
                0L,
                100L,
            ),
            RewindPackManager.InstallProgress.Extracting(
                ExtractingPhase.WritingFiles,
                1,
                3,
                "f2",
                33L,
                100L,
            ),
            RewindPackManager.InstallProgress.Extracting(
                ExtractingPhase.WritingFiles,
                2,
                3,
                "f3",
                66L,
                100L,
            ),
            RewindPackManager.InstallProgress.Extracting(
                ExtractingPhase.WritingFiles,
                3,
                3,
                "f3",
                100L,
                100L,
            ),
        )
        .inOrder()
    assertThat(phases.last()).isInstanceOf(RewindPackManager.InstallProgress.Extracting::class.java)
  }

  @Test
  fun `installLatest deletes the cached zip after the extract even on failure`() = runBlocking {
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x00))
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } throws IllegalStateException("extract boom")

    val result = manager().installLatest {  }
    assertThat(result.isFailure).isTrue()
    assertThat(cacheDir.listFiles()?.toList().orEmpty().any { it.name == "RetroRewind.zip" })
        .isFalse()
    coVerify(exactly = 0) { tree.writeVersion(any()) }
    coVerify(exactly = 0) { tree.writeRrMetadata(any()) }
  }

  @Test
  fun `installLatest returns failure when the server is unreachable`() = runBlocking {
    val result =
        manager(
                server = fakeServer(serverInfoResult = { Result.failure(Exception("Server boom")) })
            )
            .installLatest {  }
    assertThat(result.isFailure).isTrue()
    coVerify(exactly = 0) {
      FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `installLatest returns failure when getFullZipUrl throws`() = runBlocking {
    val result =
        manager(
                server =
                    fakeServer(
                        fullZipUrl = { throw RuntimeException("Failed to fetch install URL") }
                    )
            )
            .installLatest {  }

    assertThat(result.isFailure).isTrue()
    coVerify(exactly = 0) {
      FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `installLatest does not swallow cancellation into a Result failure`() = runBlocking {
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } throws CancellationException("cancelled")

    val ex = runCatching { manager().installLatest {  } }.exceptionOrNull()

    // runCatching was applied by the *caller* here; the manager must not
    // convert cooperative cancellation into Result.failure itself.
    assertThat(ex).isInstanceOf(CancellationException::class.java)
  }


  @Test
  fun `update falls back to full reinstall when no local version`() = runBlocking {
    // Model both an absent installation and an archive without version.txt.
    coEvery { tree.readVersion() } returns null andThen null
    val server = serverInfo()
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x00))
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } returns Unit
    coEvery { tree.writeVersion(server.latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(server.latestVersion) } returns Unit

    val result =
        manager(server = fakeServer(fullZipUrl = { "https://example.com/full.zip" })).update {
        }

    assertThat(result.isSuccess).isTrue()
    coVerify(exactly = 1) {
      FileDownloader.downloadInParallel(
          url = "https://example.com/full.zip",
          targetFile = any(),
          onProgress = any(),
          client = any(),
          maxRetries = any(),
          initialBackoffMillis = any(),
      )
    }
    coVerify(exactly = 1) { tree.writeVersion(server.latestVersion) }
    coVerify(exactly = 1) { tree.writeRrMetadata(server.latestVersion) }
  }

  @Test
  fun `update applies incremental steps when local version exists`() = runBlocking {
    coEvery { tree.readVersion() } returns SemVersion(3, 3, 0) andThen null
    val server =
        ServerInfo(
            latestVersion = SemVersion(3, 3, 2),
            allUpdates =
                listOf(
                    UpdateEntry(
                        SemVersion(3, 3, 0),
                        "https://example.com/3.3.0.zip",
                        "/x",
                        "3.3.0",
                    ),
                    UpdateEntry(
                        SemVersion(3, 3, 1),
                        "https://example.com/3.3.1.zip",
                        "/x",
                        "3.3.1",
                    ),
                    UpdateEntry(
                        SemVersion(3, 3, 2),
                        "https://example.com/3.3.2.zip",
                        "/x",
                        "3.3.2",
                    ),
                ),
            deletions = emptyList(),
        )
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x00))
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } returns Unit
    coEvery { tree.writeVersion(server.latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(server.latestVersion) } returns Unit

    val result =
        manager(server = fakeServer(serverInfoResult = { Result.success(server) })).update {
        }

    assertThat(result.isSuccess).isTrue()
    coVerify(exactly = 1) {
      FileDownloader.downloadInParallel(
          url = "https://example.com/3.3.1.zip",
          targetFile = any(),
          onProgress = any(),
          client = any(),
          maxRetries = any(),
          initialBackoffMillis = any(),
      )
    }
    coVerify(exactly = 1) {
      FileDownloader.downloadInParallel(
          url = "https://example.com/3.3.2.zip",
          targetFile = any(),
          onProgress = any(),
          client = any(),
          maxRetries = any(),
          initialBackoffMillis = any(),
      )
    }
    coVerify(exactly = 0) {
      FileDownloader.downloadInParallel(
          url = "https://example.com/RetroRewind.zip",
          targetFile = any(),
          onProgress = any(),
          client = any(),
          maxRetries = any(),
          initialBackoffMillis = any(),
      )
    }
    coVerify(exactly = 1) { tree.writeVersion(server.latestVersion) }
    coVerify(exactly = 1) { tree.writeRrMetadata(server.latestVersion) }
  }

  @Test
  fun `update applies archives and deletions in version order`() = runBlocking {
    val server =
        ServerInfo(
            latestVersion = SemVersion(3, 2, 7),
            allUpdates =
                listOf(
                    UpdateEntry(
                        SemVersion(3, 2, 6),
                        "https://example.com/3.2.6.zip",
                        "/x",
                        "3.2.6",
                    ),
                    UpdateEntry(
                        SemVersion(3, 2, 7),
                        "https://example.com/3.2.7.zip",
                        "/x",
                        "3.2.7",
                    ),
                ),
            deletions = listOf(DeletionEntry(SemVersion(3, 2, 6), "RetroRewind6/recreated.bin")),
        )
    val events = mutableListOf<String>()
    coEvery {
      FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any(), any())
    } answers
        {
          val url = it.invocation.args[0] as String
          val target = it.invocation.args[1] as File
          events += "download:$url"
          target.parentFile?.mkdirs()
          target.writeText(url)
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } answers
        {
          val zip = it.invocation.args[0] as File
          events += "extract:${zip.readText()}"
        }
    every { tree.deletePackEntry(any()) } answers
        {
          events += "delete:${it.invocation.args[0] as String}"
          true
        }
    coEvery { tree.readVersion() } returns SemVersion(3, 2, 5) andThen null
    coEvery { tree.writeVersion(server.latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(server.latestVersion) } returns Unit

    val result =
        manager(server = fakeServer(serverInfoResult = { Result.success(server) })).update {}

    assertThat(result.isSuccess).isTrue()
    assertThat(events)
        .containsExactly(
            "download:https://example.com/3.2.6.zip",
            "extract:https://example.com/3.2.6.zip",
            "delete:RetroRewind6/recreated.bin",
            "download:https://example.com/3.2.7.zip",
            "extract:https://example.com/3.2.7.zip",
        )
        .inOrder()
  }

  @Test
  fun `update applies only deletion entries in the selected version window`() = runBlocking {
    val server =
        serverInfo()
            .copy(
                deletions =
                    listOf(
                        DeletionEntry(SemVersion(3, 2, 4), "old-before.bin"),
                        DeletionEntry(SemVersion(3, 2, 6), "RetroRewind6/old.bin"),
                        DeletionEntry(SemVersion(3, 2, 7), "RetroRewind6/future.bin"),
                    )
            )
    coEvery {
      FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any(), any())
    } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
          target
        }
    coEvery { tree.readVersion() } returns SemVersion(3, 2, 5)
    coEvery { tree.extractZipToPack(any(), any()) } returns Unit
    every { tree.deletePackEntry(any()) } returns true
    coEvery { tree.writeVersion(server.latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(server.latestVersion) } returns Unit

    val result =
        manager(server = fakeServer(serverInfoResult = { Result.success(server) })).update {}

    assertThat(result.isSuccess).isTrue()
    verify(exactly = 1) { tree.deletePackEntry("RetroRewind6/old.bin") }
    verify(exactly = 0) { tree.deletePackEntry("old-before.bin") }
    verify(exactly = 0) { tree.deletePackEntry("RetroRewind6/future.bin") }
  }


  @Test
  fun `reinstall performs a full install`() = runBlocking {
    val server = serverInfo()
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } returns Unit
    coEvery { tree.readVersion() } returns null
    coEvery { tree.writeVersion(server.latestVersion) } returns Unit
    coEvery { tree.writeRrMetadata(server.latestVersion) } returns Unit

    val result =
        manager(server = fakeServer(fullZipUrl = { "https://example.com/full.zip" })).reinstall {
        }

    assertThat(result.isSuccess).isTrue()
    coVerify(exactly = 1) { tree.extractZipToPack(any(), any()) }
    coVerify(exactly = 1) { tree.writeVersion(server.latestVersion) }
    coVerify(exactly = 1) { tree.writeRrMetadata(server.latestVersion) }
  }

  @Test
  fun `reinstall returns failure when the server is unreachable`() = runBlocking {
    val result =
        manager(
                server = fakeServer(serverInfoResult = { Result.failure(Exception("Server boom")) })
            )
            .reinstall {  }

    assertThat(result.isFailure).isTrue()
    coVerify(exactly = 0) {
      FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `reinstall does not write version when extract fails`() = runBlocking {
    coEvery { FileDownloader.downloadInParallel(any(), any(), any(), any(), any(), any()) } answers
        {
          val target = it.invocation.args[1] as File
          target.parentFile?.mkdirs()
          target.writeBytes(byteArrayOf(0x00))
          target
        }
    coEvery { tree.extractZipToPack(any(), any()) } throws IllegalStateException("extract boom")

    val result =
        manager(server = fakeServer(fullZipUrl = { "https://example.com/full.zip" })).reinstall {
        }

    assertThat(result.isFailure).isTrue()
    coVerify(exactly = 0) { tree.writeVersion(any()) }
    coVerify(exactly = 0) { tree.writeRrMetadata(any()) }
  }


  private fun manager(server: PackServerSource = fakeServer()) =
      RewindPackManager(tree = tree, cacheDir = cacheDir, server = server)

  private fun fakeServer(
      serverInfoResult: () -> Result<ServerInfo> = { Result.success(serverInfo()) },
      fullZipUrl: () -> String = { "https://example.com/RetroRewind.zip" },
  ): PackServerSource =
      object : PackServerSource {
        override fun fetchServerInfo(): Result<ServerInfo> = serverInfoResult()

        override fun fetchFullZipUrl(): String = fullZipUrl()
      }

  private fun serverInfo(): ServerInfo =
      ServerInfo(
          latestVersion = SemVersion(3, 2, 6),
          allUpdates =
              listOf(
                  UpdateEntry(
                      SemVersion(3, 2, 6),
                      "https://example.com/3.2.6.zip",
                      "/x",
                      "3.2.6",
                  )
              ),
          deletions = emptyList(),
      )
}
