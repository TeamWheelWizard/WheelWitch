package com.skiletro.wheelwitch.domain

import com.skiletro.wheelwitch.model.SyncAction
import com.skiletro.wheelwitch.util.cloud.CloudLock
import com.skiletro.wheelwitch.util.cloud.CloudSaveMeta

/**
 * Pure WheelSync decision logic — the decision table from the spec. No Android dependencies; clock
 * injected for lock-TTL tests.
 */
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
   * True when [lock] belongs to another device and its `serverModified` is within [LOCK_TTL_MILLIS]
   * of now. Staleness uses the Dropbox server timestamp so client clock skew can't fake freshness.
   * Future server timestamps are conservatively treated as an active lock.
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
