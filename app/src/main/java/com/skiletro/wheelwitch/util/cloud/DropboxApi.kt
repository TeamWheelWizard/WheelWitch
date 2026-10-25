package com.skiletro.wheelwitch.util.cloud

import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.GetMetadataErrorException
import com.dropbox.core.v2.files.UploadErrorException
import com.dropbox.core.v2.files.WriteMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Metadata of the cloud `save.zip`: its Dropbox revision and server-side mtime. */
data class CloudSaveMeta(val rev: String, val serverModifiedMillis: Long)

/**
 * Contents of `lock.json` plus its server-side mtime, or 0 when that timestamp is unavailable.
 * Lock staleness must use server time rather than the client-supplied [startedAtMillis].
 */
data class CloudLock(
    val deviceId: String,
    val startedAtMillis: Long,
    val serverModifiedMillis: Long,
)

/** Contents of the cloud `state.json`: who last synced and what content hash they pushed. */
data class CloudState(
    val lastUploaderDevice: String,
    val syncedAtMillis: Long,
    val contentHash: String,
)

/** Conditional Dropbox upload lost race with another device's newer cloud revision. */
class DropboxWriteConflictException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Blocking SDK calls dispatched to IO, with paths relative to Dropbox's app-folder root.
 *
 * [fetchSaveMeta] returns null for a missing path or non-file metadata; other errors propagate.
 * [readLock] and [readState] return null on download or parse failure. [readLockWithServerTime]
 * retains a parsed lock with a zero timestamp if metadata lookup fails.
 * Downloads and mutators return [Result]; a failed response does not prove a remote mutation
 * was not committed.
 */
class DropboxApi(private val client: DbxClientV2) {

  suspend fun fetchSaveMeta(): CloudSaveMeta? =
      withContext(Dispatchers.IO) {
        val meta =
            try {
              client.files().getMetadata(SAVE_PATH)
            } catch (e: GetMetadataErrorException) {
              if (e.errorValue.isPath() && e.errorValue.pathValue.isNotFound()) {
                return@withContext null
              }
              throw e
            }
        if (meta !is FileMetadata) return@withContext null
        CloudSaveMeta(rev = meta.rev, serverModifiedMillis = meta.serverModified.time)
      }

  suspend fun downloadSaveZip(): Result<ByteArray> =
      withContext(Dispatchers.IO) {
        runCatching { downloadBytes(SAVE_PATH) }
      }

  /** Looks up the authenticated Dropbox account email using the account_info.read scope. */
  suspend fun fetchAccountEmail(): Result<String> =
      withContext(Dispatchers.IO) {
        runCatching { client.users().getCurrentAccount().email }
      }

  /**
   * Conditionally uploads save data. [expectedRev] is the revision observed immediately before the
   * decision: existing files use Dropbox's compare-and-swap update mode, while null uses add mode
   * for a first upload. Neither path can overwrite a concurrent writer.
   */
  suspend fun uploadSaveZip(bytes: ByteArray, expectedRev: String?): Result<CloudSaveMeta> =
      withContext(Dispatchers.IO) {
        runCatching {
          val mode = expectedRev?.let(WriteMode::update) ?: WriteMode.ADD
          val meta = uploadBytes(SAVE_PATH, bytes, mode)
          CloudSaveMeta(rev = meta.rev, serverModifiedMillis = meta.serverModified.time)
        }
            .recoverCatching { error ->
              if (error is UploadErrorException && error.isWriteConflict()) {
                throw DropboxWriteConflictException(
                    "Cloud save changed before conditional upload completed",
                    error,
                )
              }
              throw error
            }
      }

  suspend fun readState(): CloudState? =
      withContext(Dispatchers.IO) {
        runCatching { downloadBytes(STATE_PATH) }
            .getOrNull()
            ?.let { bytes ->
              runCatching {
                    val obj = JSONObject(bytes.toString(Charsets.UTF_8))
                    CloudState(
                        lastUploaderDevice = obj.getString("deviceId"),
                        syncedAtMillis = obj.getLong("syncedAt"),
                        contentHash = obj.getString("contentHash"),
                    )
                  }
                  .getOrNull()
            }
      }

  suspend fun writeState(state: CloudState): Result<Unit> =
      withContext(Dispatchers.IO) {
        runCatching {
          val obj =
              JSONObject()
                  .put("deviceId", state.lastUploaderDevice)
                  .put("syncedAt", state.syncedAtMillis)
                  .put("contentHash", state.contentHash)
          uploadJson(STATE_PATH, obj.toString())
        }
      }

  suspend fun writeLock(deviceId: String): Result<Unit> =
      withContext(Dispatchers.IO) {
        runCatching {
          val obj =
              JSONObject().put("deviceId", deviceId).put("startedAt", System.currentTimeMillis())
          uploadJson(LOCK_PATH, obj.toString())
        }
      }

  suspend fun readLock(): CloudLock? =
      withContext(Dispatchers.IO) {
        runCatching { downloadBytes(LOCK_PATH) }
            .getOrNull()
            ?.let { bytes ->
              runCatching {
                    val obj = JSONObject(bytes.toString(Charsets.UTF_8))
                    CloudLock(
                        deviceId = obj.getString("deviceId"),
                        startedAtMillis = obj.getLong("startedAt"),
                        serverModifiedMillis = 0L,
                    )
                  }
                  .getOrNull()
            }
      }

  /**
   * [readLock] plus the lock file's server-modified timestamp, which is what lock TTL decisions
   * must use (see [CloudLock]).
   */
  suspend fun readLockWithServerTime(): CloudLock? =
      withContext(Dispatchers.IO) {
        val lock = readLock() ?: return@withContext null
        val serverModified =
            runCatching {
                  val meta = client.files().getMetadata(LOCK_PATH)
                  (meta as? FileMetadata)?.serverModified?.time ?: 0L
                }
                .getOrDefault(0L)
        lock.copy(serverModifiedMillis = serverModified)
      }

  suspend fun deleteLock(): Result<Unit> =
      withContext(Dispatchers.IO) {
        runCatching {
          client.files().deleteV2(LOCK_PATH)
          Unit
        }
      }


  private fun downloadBytes(path: String): ByteArray {
    val out = ByteArrayOutputStream()
    client.files().download(path, null).use { downloader ->
      downloader.getInputStream().copyTo(out)
    }
    return out.toByteArray()
  }

  private fun uploadBytes(
      path: String,
      bytes: ByteArray,
      mode: WriteMode = WriteMode.OVERWRITE,
  ): FileMetadata =
      client.files().uploadBuilder(path).withMode(mode).uploadAndFinish(bytes.inputStream())

  private fun uploadJson(path: String, json: String) {
    uploadBytes(path, json.toByteArray(Charsets.UTF_8), WriteMode.OVERWRITE)
  }

  private fun UploadErrorException.isWriteConflict(): Boolean =
      errorValue.isPath() && errorValue.pathValue.getReason().isConflict()

  companion object {
    const val SAVE_PATH = "/save.zip"
    const val LOCK_PATH = "/lock.json"
    const val STATE_PATH = "/state.json"
  }
}
