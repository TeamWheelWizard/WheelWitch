package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.domain.SaveSyncEngine
import com.skiletro.wheelwitch.util.cloud.CloudSaveMeta
import com.skiletro.wheelwitch.util.cloud.DropboxApi
import com.skiletro.wheelwitch.util.cloud.DropboxAuth
import com.skiletro.wheelwitch.util.cloud.DropboxAuthException
import com.skiletro.wheelwitch.util.cloud.DropboxRedirect
import com.skiletro.wheelwitch.util.cloud.DropboxWriteConflictException
import com.skiletro.wheelwitch.util.cloud.SaveContentHash
import com.skiletro.wheelwitch.util.cloud.SyncStore
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncViewModelTest {
  private val app = mockk<Application>(relaxed = true)
  private val store = mockk<SyncStore>(relaxed = true)
  private val api = mockk<DropboxApi>(relaxed = true)
  private val auth = mockk<DropboxAuth>(relaxed = true)
  private val backup = mockk<suspend () -> ByteArray>()
  private val restore = mockk<suspend (ByteArray) -> Unit>(relaxed = true)
  private val dispatcher = UnconfinedTestDispatcher()
  private val engine = SaveSyncEngine { 1_000_000L }

  @BeforeEach
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    // Simulate a persisted tree by default; individual tests override.
    val mainPrefs = mockk<SharedPreferences>(relaxed = true)
    every { app.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE) } returns mainPrefs
    every { mainPrefs.getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null) } returns
        "content://tree/abc"
    every { store.secureStorageAvailable } returns true
    DropboxRedirect.reset()
  }

  @AfterEach
  fun tearDown() {
    DropboxRedirect.reset()
    Dispatchers.resetMain()
  }

  private fun vm() =
      CloudSyncViewModel(
          app,
          store = store,
          api = { api },
          engine = engine,
          auth = auth,
          zipBytesParam = backup,
          applyZipParam = restore,
          hasLocalSavesParam = { true },
          appKey = "test-key",
          hasTree = { true },
          online = { true },
          ioDispatcher = dispatcher,
      )

  private fun saveZip(value: Byte): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      zip.putNextEntry(ZipEntry("data.dat"))
      zip.write(byteArrayOf(value))
      zip.closeEntry()
    }
    return output.toByteArray()
  }

  private fun configureCorruptCloudPull() {
    val localZip = saveZip(2)
    val corruptCloudZip = saveZip(1).copyOf(30)
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns "r1"
    every { store.storedHash } returns SaveContentHash.canonicalHash(localZip)
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r2", 100L)
    coEvery { api.downloadSaveZip() } returns Result.success(corruptCloudZip)
    coEvery { backup.invoke() } returns localZip
  }

  @Test
  fun `manual sync surfaces corrupt cloud zip as error without applying it`() = runTest {
    configureCorruptCloudPull()
    val model = vm()

    model.syncNow()

    val connected = model.uiState.value as SyncUiState.Connected
    assertThat(connected.status).isEqualTo(SyncStatus.Error)
    coVerify(exactly = 0) { restore.invoke(any()) }
    verify(exactly = 0) { store.storedRev = any() }
    verify(exactly = 0) { store.storedHash = any() }
  }

  @Test
  fun `automatic sync does not crash on corrupt cloud zip`() = runTest {
    configureCorruptCloudPull()
    val model = vm()

    model.onAppResume()

    assertThat(model.uiState.value).isInstanceOf(SyncUiState.Connected::class.java)
    assertThat((model.uiState.value as SyncUiState.Connected).status).isEqualTo(SyncStatus.Error)
  }

  @Test
  fun `disconnected when no tokens`() = runTest {
    every { store.tokens() } returns null
    val state = vm().uiState.value
    assertThat(state).isInstanceOf(SyncUiState.Disconnected::class.java)
  }

  @Test
  fun `not configured when tree absent`() = runTest {
    every { store.tokens() } returns null
    val model =
        CloudSyncViewModel(
            app,
            store = store,
            api = { api },
            appKey = "test-key",
            hasTree = { false },
        )
    assertThat(model.uiState.value).isEqualTo(SyncUiState.NotConfigured)
  }

  @Test
  fun `not configured when app key empty`() = runTest {
    every { store.tokens() } returns null
    val model =
        CloudSyncViewModel(app, store = store, api = { api }, appKey = "", hasTree = { true })
    assertThat(model.uiState.value).isEqualTo(SyncUiState.NotConfigured)
  }

  @Test
  fun `secure storage unavailable disables sync safely`() = runTest {
    every { store.secureStorageAvailable } returns false
    every { store.tokens() } returns null
    val model = vm()
    assertThat(model.uiState.value).isEqualTo(SyncUiState.SecureStorageUnavailable)
  }

  @Test
  fun `onAppResume pulls when remote newer`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns "r1"
    val localHash = SaveContentHash.canonicalHash(byteArrayOf(2))
    every { store.storedHash } returns localHash
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r2", 100L)
    coEvery { api.downloadSaveZip() } returns Result.success(byteArrayOf(1))
    coEvery { backup.invoke() } returns byteArrayOf(2)
    val model = vm()
    model.onAppResume()
    coVerify { api.downloadSaveZip() }
    coVerify { restore.invoke(byteArrayOf(1)) }
    verify { store.storedRev = "r2" }
    verify { store.lastSyncAtMillis = any() }
    val connected = model.uiState.value as SyncUiState.Connected
    assertThat(connected.status).isEqualTo(SyncStatus.Idle)
    assertThat(connected.conflict).isNull()
  }

  @Test
  fun `auto sync setting exposes persisted value and updates`() = runTest {
    every { store.autoSyncEnabled } returns true
    val model = vm()

    assertThat(model.autoSyncEnabled.value).isTrue()
    model.setAutoSync(false)

    assertThat(model.autoSyncEnabled.value).isFalse()
    verify { store.autoSyncEnabled = false }
  }

  @Test
  fun `onAppResume skips when autoSync disabled`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns false
    val model = vm()
    model.onAppResume()
    coVerify(exactly = 0) { api.fetchSaveMeta() }
  }

  @Test
  fun `onAppResume silent skip when offline`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    val model =
        CloudSyncViewModel(
            app,
            store = store,
            api = { api },
            engine = engine,
            auth = auth,
            zipBytesParam = backup,
            applyZipParam = restore,
            hasLocalSavesParam = { true },
            appKey = "test-key",
            hasTree = { true },
            online = { false },
            ioDispatcher = dispatcher,
        )
    model.onAppResume()
    coVerify(exactly = 0) { api.fetchSaveMeta() }
    val connected = model.uiState.value as SyncUiState.Connected
    // Auto path: offline is a silent skip, never an Error surface.
    assertThat(connected.status).isEqualTo(SyncStatus.Idle)
  }

  @Test
  fun `pending session pushes on resume`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns true
    every { store.storedRev } returns "r1"
    every { store.storedHash } returns "h1"
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r1", 100L)
    coEvery { api.uploadSaveZip(any(), any()) } returns Result.success(CloudSaveMeta("r2", 200L))
    coEvery { backup.invoke() } returns byteArrayOf(2)
    val model = vm()
    model.onAppResume()
    coVerify { api.uploadSaveZip(any(), any()) }
    coVerify { api.writeState(any()) }
    coVerify { api.deleteLock() }
    verify { store.sessionPendingPush = false }
    verify { store.storedRev = "r2" }
  }

  @Test
  fun `conditional upload collision preserves pending state and surfaces conflict`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns true
    every { store.storedRev } returns "r1"
    every { store.storedHash } returns "old-hash"
    val localZip = saveZip(2)
    coEvery { backup.invoke() } returns localZip
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r1", 100L)
    coEvery { api.uploadSaveZip(any(), "r1") } returns
        Result.failure(DropboxWriteConflictException("raced"))
    coEvery { api.readState() } returns null
    val model = vm()

    model.onAppResume()

    assertThat((model.uiState.value as SyncUiState.Connected).conflict).isNotNull()
    verify(exactly = 0) { store.sessionPendingPush = false }
    verify(exactly = 0) { store.storedRev = any() }
    coVerify(exactly = 0) { api.writeState(any()) }
  }

  @Test
  fun `conflict surfaces dialog state`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns "r1"
    every { store.storedHash } returns "h1"
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r2", 100L)
    coEvery { backup.invoke() } returns byteArrayOf(2)
    val model = vm()
    model.onAppResume()
    val connected = model.uiState.value as SyncUiState.Connected
    assertThat(connected.conflict).isNotNull()
    model.clearConflict()
    assertThat((model.uiState.value as SyncUiState.Connected).conflict).isNull()
    coVerify(exactly = 0) { api.downloadSaveZip() }
    coVerify(exactly = 0) { api.uploadSaveZip(any(), any()) }
  }

  @Test
  fun `cloud found download clears prompt after successful pull`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns null
    every { store.storedHash } returns null
    every { store.cloudPromptShown } returns false
    val localZip = saveZip(2)
    val cloudZip = saveZip(1)
    coEvery { backup.invoke() } returns localZip
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r1", 100L)
    coEvery { api.downloadSaveZip() } returns Result.success(cloudZip)
    val model = vm()

    model.onAppResume()
    assertThat((model.uiState.value as SyncUiState.Connected).cloudFoundPrompt).isTrue()

    model.resolveConflict(keepLocal = false)

    coVerify { restore.invoke(cloudZip) }
    assertThat((model.uiState.value as SyncUiState.Connected).cloudFoundPrompt).isFalse()
  }

  @Test
  fun `dismissing cloud found prompt persists dismissal`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns null
    every { store.storedHash } returns null
    every { store.cloudPromptShown } returns false
    coEvery { backup.invoke() } returns saveZip(2)
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r1", 100L)
    val model = vm()

    model.onAppResume()
    assertThat((model.uiState.value as SyncUiState.Connected).cloudFoundPrompt).isTrue()

    model.dismissCloudFoundPrompt()

    assertThat((model.uiState.value as SyncUiState.Connected).cloudFoundPrompt).isFalse()
    verify { store.cloudPromptShown = true }
  }

  @Test
  fun `failed restore does not mark cloud pull synced or dismiss prompt`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns null
    every { store.storedHash } returns null
    every { store.cloudPromptShown } returns false
    val localZip = saveZip(2)
    val cloudZip = saveZip(1)
    coEvery { backup.invoke() } returns localZip
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r1", 100L)
    coEvery { api.downloadSaveZip() } returns Result.success(cloudZip)
    coEvery { restore.invoke(cloudZip) } throws IllegalStateException("restore failed")
    val model = vm()

    model.onAppResume()
    model.resolveConflict(keepLocal = false)

    assertThat((model.uiState.value as SyncUiState.Connected).cloudFoundPrompt).isTrue()
    verify(exactly = 0) { store.storedRev = any() }
    verify(exactly = 0) { store.lastSyncAtMillis = any() }
  }

  @Test
  fun `cloud found prompt remains after failed pull`() = runTest {
    every { store.tokens() } returns DropboxAuth.AuthTokens("at", null, Long.MAX_VALUE, null)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns null
    every { store.storedHash } returns null
    every { store.cloudPromptShown } returns false
    coEvery { backup.invoke() } returns saveZip(2)
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r1", 100L)
    coEvery { api.downloadSaveZip() } returns Result.failure(IllegalStateException("offline"))
    val model = vm()

    model.onAppResume()
    assertThat((model.uiState.value as SyncUiState.Connected).cloudFoundPrompt).isTrue()

    model.resolveConflict(keepLocal = false)

    assertThat((model.uiState.value as SyncUiState.Connected).cloudFoundPrompt).isTrue()
  }

  @Test
  fun `resolveConflict keepLocal pushes and clears dialog`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns "r1"
    every { store.storedHash } returns "h1"
    coEvery { api.fetchSaveMeta() } returns CloudSaveMeta("r2", 100L)
    coEvery { api.uploadSaveZip(any(), any()) } returns Result.success(CloudSaveMeta("r3", 300L))
    coEvery { backup.invoke() } returns byteArrayOf(2)
    val model = vm()
    model.onAppResume()
    assertThat(model.uiState.value.let { (it as SyncUiState.Connected).conflict }).isNotNull()
    model.resolveConflict(keepLocal = true)
    coVerify { api.uploadSaveZip(any(), any()) }
    assertThat(model.uiState.value.let { (it as SyncUiState.Connected).conflict }).isNull()
  }

  @Test
  fun `redirect completes connect flow`() = runTest {
    var saved: DropboxAuth.AuthTokens? = null
    every { store.tokens() } answers { saved }
    every { store.saveTokens(any()) } answers
        {
          saved = firstArg()
          true
        }
    val pending = DropboxAuth.PendingAuth("verifier", DropboxRedirect.REDIRECT_URI)
    every { auth.authorizeUrl() } returns
        Pair("https://www.dropbox.com/oauth2/authorize?x=1", pending)
    coEvery { auth.exchangeCode(pending, "abc") } returns
        Result.success(DropboxAuth.AuthTokens("at", "rt", 500L, null))
    coEvery { api.fetchAccountEmail() } returns Result.success("racer@example.com")
    val model = vm()
    model.connect()
    val redirect = mockk<Uri>()
    every { redirect.getQueryParameter("code") } returns "abc"
    model.onRedirect(redirect)
    verify { store.saveTokens(DropboxAuth.AuthTokens("at", "rt", 500L, null)) }
    val connected = model.uiState.value as SyncUiState.Connected
    assertThat(connected.accountEmail).isEqualTo("racer@example.com")
    assertThat(DropboxRedirect.redirectFlow.value).isNull()
  }

  @Test
  fun `beginSession sets flag and writes lock`() = runTest {
    every { store.tokens() } returns mockk(relaxed = true)
    every { store.deviceId() } returns "dev1"
    val model = vm()
    model.beginSession()
    verify { store.sessionPendingPush = true }
    coVerify { api.writeLock("dev1") }
  }

  @Test
  fun `expired token on first fetch refreshes and proceeds`() = runTest {
    every { store.tokens() } returns DropboxAuth.AuthTokens("at1", "rt", 0L, null)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    every { store.storedRev } returns "r1"
    every { store.storedHash } returns "h1"
    coEvery { api.fetchSaveMeta() } throws
        RuntimeException("401 expired") andThen
        CloudSaveMeta("r1", 100L)
    coEvery { api.uploadSaveZip(any(), any()) } returns Result.success(CloudSaveMeta("r2", 200L))
    coEvery { auth.refresh("rt") } returns
        Result.success(DropboxAuth.AuthTokens("at2", null, 999L, null))
    coEvery { backup.invoke() } returns byteArrayOf(2)
    val model = vm()
    model.onAppResume()
    coVerify(exactly = 1) { auth.refresh("rt") }
    coVerify { api.uploadSaveZip(any(), any()) }
    verify { store.saveTokens(DropboxAuth.AuthTokens("at2", "rt", 999L, null)) }
  }

  @Test
  fun `expired token with network refresh failure stays an error`() = runTest {
    every { store.tokens() } returns DropboxAuth.AuthTokens("at1", "rt", 0L, null)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    coEvery { api.fetchSaveMeta() } throws IOException("network unavailable")
    coEvery { auth.refresh("rt") } returns Result.failure(IOException("refresh unavailable"))
    val model = vm()

    model.syncNow()

    coVerify(exactly = 1) { auth.refresh("rt") }
    coVerify(exactly = 1) { api.fetchSaveMeta() }
    val connected = model.uiState.value as SyncUiState.Connected
    assertThat(connected.status).isEqualTo(SyncStatus.Error)
  }

  @Test
  fun `expired token with typed refresh rejection surfaces reconnect`() = runTest {
    every { store.tokens() } returns DropboxAuth.AuthTokens("at1", "rt", 0L, null)
    every { store.autoSyncEnabled } returns true
    every { store.sessionPendingPush } returns false
    coEvery { api.fetchSaveMeta() } throws IOException("401 expired")
    coEvery { auth.refresh("rt") } returns
        Result.failure(DropboxAuthException(401, "invalid_grant"))
    val model = vm()

    model.syncNow()

    coVerify(exactly = 1) { auth.refresh("rt") }
    coVerify(exactly = 1) { api.fetchSaveMeta() }
    val connected = model.uiState.value as SyncUiState.Connected
    assertThat(connected.status).isEqualTo(SyncStatus.ReconnectNeeded)
  }
}
