package com.skiletro.wheelwitch.model

import androidx.annotation.Keep

/** Decision output of [com.skiletro.wheelwitch.domain.SaveSyncEngine]. */
@Keep
sealed interface SyncAction {
  /** Local + cloud unchanged. */
  data object Noop : SyncAction

  /** Cloud newer, local clean. */
  data object Pull : SyncAction

  /** Local changed, cloud unchanged. */
  data object Push : SyncAction

  /** Both changed — user decides. */
  data object Conflict : SyncAction

  /** Cloud empty, local has saves. */
  data object FirstUpload : SyncAction

  /** Never synced on this install, both sides have data. */
  data object CloudFoundFreshDevice : SyncAction
}
