package com.skiletro.wheelwitch.model

import androidx.annotation.Keep

/** Decision output of [com.skiletro.wheelwitch.domain.SaveSyncEngine]. */
@Keep
sealed interface SyncAction {
  /** Neither side changed, or neither side has saves. */
  data object Noop : SyncAction

  /** Cloud changed while local is clean, or a fresh install has no local saves. */
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
