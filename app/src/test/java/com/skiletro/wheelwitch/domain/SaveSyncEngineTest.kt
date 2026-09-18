package com.skiletro.wheelwitch.domain

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.SyncAction
import com.skiletro.wheelwitch.util.cloud.CloudLock
import com.skiletro.wheelwitch.util.cloud.CloudSaveMeta
import org.junit.jupiter.api.Test

class SaveSyncEngineTest {
  private val engine = SaveSyncEngine { 1_000_000L }

  private fun meta(rev: String) = CloudSaveMeta(rev, 900_000L)

  @Test
  fun `nothing changed noop`() {
    val action = engine.decide(meta("r1"), "r1", "h1", "h1")
    assertThat(action).isEqualTo(SyncAction.Noop)
  }

  @Test
  fun `remote only pull`() {
    val action = engine.decide(meta("r2"), "r1", "h1", "h1")
    assertThat(action).isEqualTo(SyncAction.Pull)
  }

  @Test
  fun `local only push`() {
    val action = engine.decide(meta("r1"), "r1", "h2", "h1")
    assertThat(action).isEqualTo(SyncAction.Push)
  }

  @Test
  fun `both changed conflict`() {
    val action = engine.decide(meta("r2"), "r1", "h2", "h1")
    assertThat(action).isEqualTo(SyncAction.Conflict)
  }

  @Test
  fun `fresh device no local pulls`() {
    val action = engine.decide(meta("r1"), null, null, null)
    assertThat(action).isEqualTo(SyncAction.Pull)
  }

  @Test
  fun `fresh device with local saves prompts`() {
    val action = engine.decide(meta("r1"), null, "h1", null)
    assertThat(action).isEqualTo(SyncAction.CloudFoundFreshDevice)
  }

  @Test
  fun `first upload when cloud empty`() {
    val action = engine.decide(null, null, "h1", null)
    assertThat(action).isEqualTo(SyncAction.FirstUpload)
  }

  @Test
  fun `no cloud no local noop`() {
    val action = engine.decide(null, null, null, null)
    assertThat(action).isEqualTo(SyncAction.Noop)
  }

  @Test
  fun `own lock ignored`() {
    val lock = CloudLock("dev-me", 100L, 999_999L)
    assertThat(engine.isLockFresh(lock, "dev-me")).isFalse()
  }

  @Test
  fun `foreign recent lock is fresh`() {
    val lock = CloudLock("dev-other", 100L, 999_999L) // serverModified 1s before now
    assertThat(engine.isLockFresh(lock, "dev-me")).isTrue()
  }

  @Test
  fun `foreign stale lock ignored`() {
    val lock = CloudLock("dev-other", 100L, 1_000_000L - SaveSyncEngine.LOCK_TTL_MILLIS - 1)
    assertThat(engine.isLockFresh(lock, "dev-me")).isFalse()
  }
}
