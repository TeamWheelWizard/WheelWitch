package com.skiletro.wheelwitch.domain

import com.skiletro.wheelwitch.model.SyncAction
import com.skiletro.wheelwitch.util.cloud.CloudLock
import com.skiletro.wheelwitch.util.cloud.CloudSaveMeta

/** Pure WheelSync decisions with an injectable clock for lock expiry. */
class SaveSyncEngine(private val clock: () -> Long = System::currentTimeMillis) {

  fun decide(
      cloudMeta: CloudSaveMeta?,
      storedRev: String?,
      localHash: String?,
      storedHash: String?,
  ): SyncAction {
    val localHasSaves = localHash != null
    if (cloudMeta == null) {
      return if (localHasSaves) SyncAction.FirstUpload else SyncAction.Noop
    }
    if (storedRev == null) {
      return if (localHasSaves) SyncAction.CloudFoundFreshDevice else SyncAction.Pull
    }
    val localChanged = localHash != storedHash
    val remoteChanged = cloudMeta.rev != storedRev
    return when {
      localChanged && remoteChanged -> SyncAction.Conflict
      localChanged -> SyncAction.Push
      remoteChanged -> SyncAction.Pull
      else -> SyncAction.Noop
    }
  }

  /**
   * True when [lock] belongs to another device and its server timestamp is less than
   * [LOCK_TTL_MILLIS] old relative to the local clock. Future timestamps are treated as active;
   * local clock skew can affect expiry.
   */
  fun isLockFresh(lock: CloudLock, ourDeviceId: String): Boolean {
    if (lock.deviceId == ourDeviceId) return false
    val age = clock() - lock.serverModifiedMillis
    return age < LOCK_TTL_MILLIS
  }

  companion object {
    const val LOCK_TTL_MILLIS = 2L * 60 * 60 * 1000
  }
}
