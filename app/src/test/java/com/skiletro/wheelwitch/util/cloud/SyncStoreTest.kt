package com.skiletro.wheelwitch.util.cloud

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import org.junit.jupiter.api.Test

class SyncStoreTest {

  private class StoreFixture(
      val store: SyncStore,
      val prefs: SharedPreferences,
      val editor: SharedPreferences.Editor,
  )

  private fun store(): StoreFixture {
    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    // Editor is fluent: stub every setter to return the editor itself so
    // chained production calls land on the same mock instance.
    every { editor.putString(any(), any()) } returns editor
    every { editor.putBoolean(any(), any()) } returns editor
    every { editor.putLong(any(), any()) } returns editor
    every { editor.remove(any()) } returns editor
    val prefs =
        mockk<SharedPreferences> {
          every { edit() } returns editor
          every { getString(any(), any()) } answers { secondArg() }
          every { getBoolean(any(), any()) } answers { secondArg() }
          every { getLong(any(), any()) } answers { secondArg() }
        }
    return StoreFixture(SyncStore(prefs), prefs, editor)
  }

  @Test
  fun `deviceId generated once`() {
    val f = store()
    every { f.prefs.getString(PrefsKeys.SYNC_DEVICE_ID_KEY, null) } returns null andThen "abc"
    val first = f.store.deviceId()
    assertThat(first).isNotEmpty()
    verify(exactly = 1) { f.editor.putString(PrefsKeys.SYNC_DEVICE_ID_KEY, first) }
    // Second call is served from the in-memory cache, not prefs.
    assertThat(f.store.deviceId()).isEqualTo(first)
    verify(exactly = 1) { f.editor.putString(PrefsKeys.SYNC_DEVICE_ID_KEY, any()) }
  }

  @Test
  fun `deviceId reused when persisted`() {
    val f = store()
    every { f.prefs.getString(PrefsKeys.SYNC_DEVICE_ID_KEY, null) } returns "existing"
    assertThat(f.store.deviceId()).isEqualTo("existing")
    verify(exactly = 0) { f.editor.putString(PrefsKeys.SYNC_DEVICE_ID_KEY, any()) }
  }

  @Test
  fun `auto sync defaults on`() {
    val f = store()
    assertThat(f.store.autoSyncEnabled).isTrue()
  }

  @Test
  fun `session flag write uses the session key`() {
    val f = store()
    f.store.sessionPendingPush = true
    verify { f.editor.putBoolean(PrefsKeys.SYNC_SESSION_PENDING_KEY, true) }
  }

  @Test
  fun `tokens null when never set`() {
    val f = store()
    assertThat(f.store.tokens()).isNull()
  }

  @Test
  fun `secure storage unavailable refuses token persistence`() {
    val f = store()
    val unavailable = SyncStore(f.prefs, secureStorageAvailable = false)
    val tokens = DropboxAuth.AuthTokens("at", "rt", 123L, "user@example.com")
    assertThat(unavailable.saveTokens(tokens)).isFalse()
    assertThat(unavailable.tokens()).isNull()
    verify(exactly = 0) { f.editor.putString(PrefsKeys.SYNC_ACCESS_TOKEN_KEY, any()) }
    verify(exactly = 0) { f.editor.putString(PrefsKeys.SYNC_REFRESH_TOKEN_KEY, any()) }
  }

  @Test
  fun `tokens round trip`() {
    val f = store()
    val tokens =
        DropboxAuth.AuthTokens(
            accessToken = "at",
            refreshToken = "rt",
            expiresAtMillis = 123L,
            accountEmail = "user@example.com",
        )
    f.store.saveTokens(tokens)
    verify {
      f.editor.putString(PrefsKeys.SYNC_ACCESS_TOKEN_KEY, "at")
      f.editor.putString(PrefsKeys.SYNC_REFRESH_TOKEN_KEY, "rt")
      f.editor.putLong(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY, 123L)
      f.editor.putString(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY, "user@example.com")
      f.editor.apply()
    }
    every { f.prefs.getString(PrefsKeys.SYNC_ACCESS_TOKEN_KEY, null) } returns "at"
    every { f.prefs.getString(PrefsKeys.SYNC_REFRESH_TOKEN_KEY, null) } returns "rt"
    every { f.prefs.getLong(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY, any()) } returns 123L
    every { f.prefs.getString(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY, null) } returns "user@example.com"
    assertThat(f.store.tokens()).isEqualTo(tokens)
  }

  @Test
  fun `tokens survive null refresh and email`() {
    val f = store()
    every { f.prefs.getString(PrefsKeys.SYNC_ACCESS_TOKEN_KEY, null) } returns "at"
    every { f.prefs.getString(PrefsKeys.SYNC_REFRESH_TOKEN_KEY, null) } returns null
    every { f.prefs.getLong(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY, any()) } returns Long.MAX_VALUE
    every { f.prefs.getString(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY, null) } returns null
    assertThat(f.store.tokens())
        .isEqualTo(
            DropboxAuth.AuthTokens(
                accessToken = "at",
                refreshToken = null,
                expiresAtMillis = Long.MAX_VALUE,
                accountEmail = null,
            )
        )
  }

  @Test
  fun `clearTokens removes all token keys`() {
    val f = store()
    f.store.clearTokens()
    verify {
      f.editor.remove(PrefsKeys.SYNC_ACCESS_TOKEN_KEY)
      f.editor.remove(PrefsKeys.SYNC_REFRESH_TOKEN_KEY)
      f.editor.remove(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY)
      f.editor.remove(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY)
    }
  }

  @Test
  fun `bookkeeping properties use their keys`() {
    val f = store()
    f.store.storedRev = "r1"
    f.store.storedHash = "h1"
    f.store.autoSyncEnabled = false
    f.store.lastSyncAtMillis = 42L
    f.store.cloudPromptShown = true
    verifyAll {
      f.editor.apply()
      f.editor.putString(PrefsKeys.SYNC_STORED_REV_KEY, "r1")
      f.editor.putString(PrefsKeys.SYNC_STORED_HASH_KEY, "h1")
      f.editor.putBoolean(PrefsKeys.SYNC_AUTO_ENABLED_KEY, false)
      f.editor.putLong(PrefsKeys.SYNC_LAST_SYNC_KEY, 42L)
      f.editor.putBoolean(PrefsKeys.SYNC_CLOUD_PROMPT_SHOWN_KEY, true)
    }
  }

  @Test
  fun `bookkeeping defaults`() {
    val f = store()
    assertThat(f.store.storedRev).isNull()
    assertThat(f.store.storedHash).isNull()
    assertThat(f.store.sessionPendingPush).isFalse()
    assertThat(f.store.lastSyncAtMillis).isEqualTo(0L)
    assertThat(f.store.cloudPromptShown).isFalse()
  }
}
