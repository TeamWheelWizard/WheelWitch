package com.skiletro.wheelwitch.util.cloud

import com.dropbox.core.DbxDownloader
import com.dropbox.core.DbxException
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DbxUserFilesRequests
import com.dropbox.core.v2.files.DeleteResult
import com.dropbox.core.v2.files.DownloadError
import com.dropbox.core.v2.files.DownloadErrorException
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.GetMetadataError
import com.dropbox.core.v2.files.GetMetadataErrorException
import com.dropbox.core.v2.files.LookupError
import com.dropbox.core.v2.files.UploadBuilder
import com.dropbox.core.v2.files.UploadError
import com.dropbox.core.v2.files.UploadErrorException
import com.dropbox.core.v2.files.UploadWriteFailed
import com.dropbox.core.v2.files.WriteConflictError
import com.dropbox.core.v2.files.WriteError
import com.dropbox.core.v2.files.WriteMode
import com.dropbox.core.v2.users.DbxUserUsersRequests
import com.dropbox.core.v2.users.FullAccount
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DropboxApiTest {
  private val client = mockk<DbxClientV2>()
  private val files = mockk<DbxUserFilesRequests>()
  private val api = DropboxApi(client)

  /** Dropbox revs are 9+ hex chars; the SDK's builder validates both. */
  private fun fileMeta(rev: String = "000000001", name: String = "save.zip"): FileMetadata =
      FileMetadata.newBuilder(name, "id1", Date(1000L), Date(5000L), rev, 10L).build()

  private fun downloaderOf(bytes: ByteArray): DbxDownloader<FileMetadata> {
    val downloader = mockk<DbxDownloader<FileMetadata>>()
    every { downloader.getInputStream() } returns ByteArrayInputStream(bytes)
    every { downloader.close() } just Runs
    return downloader
  }

  private fun uploadBuilderReturning(meta: FileMetadata): UploadBuilder {
    val builder = mockk<UploadBuilder>()
    every { builder.withMode(any<WriteMode>()) } returns builder
    every { builder.uploadAndFinish(any<InputStream>()) } returns meta
    return builder
  }

  @Test
  fun `fetchSaveMeta maps rev and server modified`() = runTest {
    every { client.files() } returns files
    every { files.getMetadata(DropboxApi.SAVE_PATH) } returns fileMeta("000000009")
    val meta = api.fetchSaveMeta()
    assertThat(meta).isNotNull()
    assertThat(meta!!.rev).isEqualTo("000000009")
    assertThat(meta.serverModifiedMillis).isEqualTo(5000L)
  }

  @Test
  fun `fetchSaveMeta returns null when cloud zip absent`() = runTest {
    every { client.files() } returns files
    every { files.getMetadata(DropboxApi.SAVE_PATH) } throws
        GetMetadataErrorException(
            "req",
            "not found",
            null,
            GetMetadataError.path(LookupError.NOT_FOUND),
        )
    assertThat(api.fetchSaveMeta()).isNull()
  }

  @Test
  fun `fetchSaveMeta propagates non-not-found errors`() = runTest {
    every { client.files() } returns files
    every { files.getMetadata(DropboxApi.SAVE_PATH) } throws DbxException("network boom")
    assertThrows<DbxException> { api.fetchSaveMeta() }
  }

  @Test
  fun `uploadSaveZip returns new rev`() = runTest {
    every { client.files() } returns files
    val mode = slot<WriteMode>()
    every { files.uploadBuilder(DropboxApi.SAVE_PATH) } returns
        uploadBuilderReturning(fileMeta("000000002")).also { builder ->
          every { builder.withMode(capture(mode)) } returns builder
        }
    val result = api.uploadSaveZip(byteArrayOf(1), expectedRev = "000000001")
    assertThat(result.getOrThrow().rev).isEqualTo("000000002")
    assertThat(result.getOrThrow().serverModifiedMillis).isEqualTo(5000L)
    assertThat(mode.captured).isEqualTo(WriteMode.update("000000001"))
  }

  @Test
  fun `first upload uses add mode`() = runTest {
    every { client.files() } returns files
    val mode = slot<WriteMode>()
    val builder = uploadBuilderReturning(fileMeta("000000002"))
    every { builder.withMode(capture(mode)) } returns builder
    every { files.uploadBuilder(DropboxApi.SAVE_PATH) } returns builder
    assertThat(api.uploadSaveZip(byteArrayOf(1), expectedRev = null).isSuccess).isTrue()
    assertThat(mode.captured).isEqualTo(WriteMode.ADD)
  }

  @Test
  fun `conditional collision returns write conflict`() = runTest {
    every { client.files() } returns files
    val builder = uploadBuilderReturning(fileMeta("000000002"))
    every { files.uploadBuilder(DropboxApi.SAVE_PATH) } returns builder
    every { builder.uploadAndFinish(any<InputStream>()) } throws
        UploadErrorException(
            "req",
            "conflict",
            null,
            UploadError.path(
                UploadWriteFailed(WriteError.conflict(WriteConflictError.FILE), "session"),
            ),
        )
    val result = api.uploadSaveZip(byteArrayOf(1), expectedRev = "000000001")
    assertThat(result.exceptionOrNull()).isInstanceOf(DropboxWriteConflictException::class.java)
  }

  @Test
  fun `downloadSaveZip returns bytes`() = runTest {
    every { client.files() } returns files
    every { files.download(DropboxApi.SAVE_PATH, null) } returns downloaderOf(byteArrayOf(7, 8))
    assertThat(api.downloadSaveZip().getOrThrow()).isEqualTo(byteArrayOf(7, 8))
  }

  @Test
  fun `fetchAccountEmail returns current account email`() = runTest {
    val users = mockk<DbxUserUsersRequests>()
    val account = mockk<FullAccount>()
    every { client.users() } returns users
    every { users.getCurrentAccount() } returns account
    every { account.email } returns "racer@example.com"
    assertThat(api.fetchAccountEmail().getOrThrow()).isEqualTo("racer@example.com")
  }

  @Test
  fun `readLock parses lock json`() = runTest {
    every { client.files() } returns files
    every { files.download(DropboxApi.LOCK_PATH, null) } returns
        downloaderOf("""{"deviceId":"dev1","startedAt":123}""".toByteArray())
    val lock = api.readLock()
    assertThat(lock).isNotNull()
    assertThat(lock!!.deviceId).isEqualTo("dev1")
    assertThat(lock.startedAtMillis).isEqualTo(123)
    assertThat(lock.serverModifiedMillis).isEqualTo(0L)
  }

  @Test
  fun `readLock returns null when lock absent`() = runTest {
    every { client.files() } returns files
    every { files.download(DropboxApi.LOCK_PATH, null) } throws
        DownloadErrorException("req", "not found", null, DownloadError.path(LookupError.NOT_FOUND))
    assertThat(api.readLock()).isNull()
  }

  @Test
  fun `readLock returns null on corrupt lock json`() = runTest {
    every { client.files() } returns files
    every { files.download(DropboxApi.LOCK_PATH, null) } returns
        downloaderOf("not json".toByteArray())
    assertThat(api.readLock()).isNull()
  }

  @Test
  fun `readLockWithServerTime stamps server modified time`() = runTest {
    every { client.files() } returns files
    every { files.download(DropboxApi.LOCK_PATH, null) } returns
        downloaderOf("""{"deviceId":"dev1","startedAt":123}""".toByteArray())
    every { files.getMetadata(DropboxApi.LOCK_PATH) } returns fileMeta("0000000a1", "lock.json")
    val lock = api.readLockWithServerTime()
    assertThat(lock).isNotNull()
    assertThat(lock!!.deviceId).isEqualTo("dev1")
    assertThat(lock.serverModifiedMillis).isEqualTo(5000L)
  }

  @Test
  fun `readState parses state json`() = runTest {
    every { client.files() } returns files
    every { files.download(DropboxApi.STATE_PATH, null) } returns
        downloaderOf("""{"deviceId":"dev2","syncedAt":456,"contentHash":"h9"}""".toByteArray())
    val state = api.readState()
    assertThat(state).isNotNull()
    assertThat(state!!.lastUploaderDevice).isEqualTo("dev2")
    assertThat(state.syncedAtMillis).isEqualTo(456L)
    assertThat(state.contentHash).isEqualTo("h9")
  }

  @Test
  fun `readState returns null on corrupt json`() = runTest {
    every { client.files() } returns files
    every { files.download(DropboxApi.STATE_PATH, null) } returns
        downloaderOf("garbage".toByteArray())
    assertThat(api.readState()).isNull()
  }

  @Test
  fun `writeState uploads the state json`() = runTest {
    every { client.files() } returns files
    val builder = uploadBuilderReturning(fileMeta("0000000a2", "state.json"))
    every { files.uploadBuilder(DropboxApi.STATE_PATH) } returns builder
    val captured = slot<InputStream>()
    every { builder.uploadAndFinish(capture(captured)) } returns fileMeta("0000000a2", "state.json")

    val result = api.writeState(CloudState("dev1", 123L, "hash1"))

    assertThat(result.isSuccess).isTrue()
    val json = captured.captured.readBytes().toString(Charsets.UTF_8)
    assertThat(json).contains("\"deviceId\":\"dev1\"")
    assertThat(json).contains("\"syncedAt\":123")
    assertThat(json).contains("\"contentHash\":\"hash1\"")
  }

  @Test
  fun `writeLock uploads lock json with device id`() = runTest {
    every { client.files() } returns files
    val builder = uploadBuilderReturning(fileMeta("0000000a1", "lock.json"))
    every { files.uploadBuilder(DropboxApi.LOCK_PATH) } returns builder
    val captured = slot<InputStream>()
    every { builder.uploadAndFinish(capture(captured)) } returns fileMeta("0000000a1", "lock.json")

    val result = api.writeLock("dev-abc")

    assertThat(result.isSuccess).isTrue()
    val json = captured.captured.readBytes().toString(Charsets.UTF_8)
    assertThat(json).contains("\"deviceId\":\"dev-abc\"")
    assertThat(json).contains("\"startedAt\":")
  }

  @Test
  fun `deleteLock succeeds`() = runTest {
    every { client.files() } returns files
    every { files.deleteV2(DropboxApi.LOCK_PATH) } returns
        DeleteResult(fileMeta("0000000a1", "lock.json"))
    assertThat(api.deleteLock().isSuccess).isTrue()
    verify { files.deleteV2(DropboxApi.LOCK_PATH) }
  }
}
