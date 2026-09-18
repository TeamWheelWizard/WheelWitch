package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.skiletro.wheelwitch.BuildConfig
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.SaveManager
import com.skiletro.wheelwitch.domain.SaveSyncEngine
import com.skiletro.wheelwitch.model.SyncAction
import com.skiletro.wheelwitch.util.cloud.CloudLock
import com.skiletro.wheelwitch.util.cloud.CloudSaveMeta
import com.skiletro.wheelwitch.util.cloud.CloudState
import com.skiletro.wheelwitch.util.cloud.DropboxApi
import com.skiletro.wheelwitch.util.cloud.DropboxAuth
import com.skiletro.wheelwitch.util.cloud.DropboxRedirect
import com.skiletro.wheelwitch.util.cloud.SaveContentHash
import com.skiletro.wheelwitch.util.cloud.SyncStore
import com.skiletro.wheelwitch.util.net.isNetworkAvailable
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** UI-facing cloud-sync status inside [SyncUiState.Connected]. */
enum class SyncStatus {
  /** No sync running and the last one succeeded. */
  Idle,

  /** A sync round-trip is in flight. */
  Syncing,

  /** The last manual sync failed. */
  Error,

  /** Stored tokens stopped working and refresh failed — user must reconnect. */
  ReconnectNeeded,
}

/** What the UI should render for the cloud-sync feature. */
sealed interface SyncUiState {
  /** No Dropbox app key or no Dolphin tree — sync cannot work here. */
  data object NotConfigured : SyncUiState

  /** Encrypted credential storage is unavailable; cloud sync is disabled safely. */
  data object SecureStorageUnavailable : SyncUiState

  /** App key + tree exist but the account is not connected. */
  data object Disconnected : SyncUiState

  data class Connected(
      val status: SyncStatus,
      val lastSyncAtMillis: Long,
      val lastSyncFromDevice: String?,
      val accountEmail: String?,
      val foreignLock: CloudLock?,
      val conflict: ConflictInfo?,
      val cloudFoundPrompt: Boolean,
  ) : SyncUiState
}

/** Payload behind the conflict dialog (both sides changed since the last sync). */
data class ConflictInfo(
    val localRegions: Int,
    val cloudDevice: String?,
    val cloudAgeMillis: Long?,
)

/**
 * Orchestrates WheelSync: decides pull/push/conflict via [SaveSyncEngine], moves the
 * `wheelwitch-save` zip through [DropboxApi], and exposes dialog state for the overlay UI.
 *
 * All network work happens in [viewModelScope] with [ioDispatcher]; there is no background service
 * (see the WheelSync spec). Local save access goes through the [zipBytes]/[applyZip]/
 * [hasLocalSaves] seams, which stage the unified zip in the app cache so
 * [SaveManager.backupAll]/[restoreAll] are reused verbatim.
 */
class CloudSyncViewModel(
    app: Application,
    private val store: SyncStore = SyncStore.create(app),
    private val api: () -> DropboxApi = { defaultApi(store) },
    private val engine: SaveSyncEngine = SaveSyncEngine(),
    private val auth: DropboxAuth = DropboxAuth(),
    private val zipBytesParam: (suspend () -> ByteArray)? = null,
    private val applyZipParam: (suspend (ByteArray) -> Unit)? = null,
    private val hasLocalSavesParam: (suspend () -> Boolean)? = null,
    private val appKey: String = BuildConfig.DROPBOX_APP_KEY,
    private val hasTree: () -> Boolean = {
      Prefs.main(app).getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null) != null
    },
    private val online: () -> Boolean = { app.applicationContext.isNetworkAvailable() },
    private val openBrowser: (String) -> Unit = { url -> defaultOpenBrowser(app, url) },
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {

  private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.NotConfigured)
  private val _autoSyncEnabled = MutableStateFlow(store.autoSyncEnabled)

  /** What the Settings section and overlay dialogs render. */
  val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

  /** Persisted automatic-sync setting rendered by Settings. */
  val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

  private var status: SyncStatus = SyncStatus.Idle
  private var conflict: ConflictInfo? = null
  private var cloudFoundPrompt: Boolean = false
  private var foreignLock: CloudLock? = null
  private var lastSyncFromDevice: String? = null
  private var syncing: Boolean = false
  private var pendingAuth: DropboxAuth.PendingAuth? = null
  private var redirectJob: Job? = null

  /** Stages the unified save zip bytes; production default uses the app cache. */
  private val zipBytes: suspend () -> ByteArray =
      zipBytesParam ?: { defaultZipBytes(getApplication()) }

  /** Restores cloud zip bytes onto the local save tree. */
  private val applyZipFn: suspend (ByteArray) -> Unit =
      applyZipParam ?: { defaultApplyZip(getApplication(), it) }

  /** True when any local save data exists. */
  private val hasLocalSavesFn: suspend () -> Boolean =
      hasLocalSavesParam ?: { defaultHasLocalSaves(getApplication()) }

  init {
    emit()
  }

  /**
   * Auto-sync entry, called from the home screen's ON_RESUME observer. Silent skip when disabled,
   * offline, or already syncing (per the spec, background failures never nag). A pending Dolphin
   * session forces the push decision path.
   */
  fun onAppResume() {
    runSync(manual = false)
  }

  /** Manual "Sync now": same flow, but failures surface as [SyncStatus.Error]. */
  fun syncNow() {
    runSync(manual = true)
  }

  /**
   * Called right before firing the Dolphin launch intent. Sets the pending-push flag synchronously
   * and writes the advisory cloud lock fire-and-forget — the launch is never delayed by it.
   */
  fun beginSession() {
    store.sessionPendingPush = true
    if (store.tokens() == null) return
    viewModelScope.launch {
      runCatching { api().writeLock(store.deviceId()) }
          .onFailure { Timber.tag(TAG).w(it, "writeLock failed (advisory only)") }
    }
  }

  /** Starts the Dropbox OAuth flow in the system browser. */
  fun connect() {
    if (appKey.isBlank()) {
      emit()
      return
    }
    DropboxRedirect.reset()
    val (url, pending) = auth.authorizeUrl()
    pendingAuth = pending
    openBrowser(url)
    redirectJob?.cancel()
    redirectJob = viewModelScope.launch {
      DropboxRedirect.redirectFlow.collect { uri -> onRedirect(uri) }
    }
  }

  /** Handles one OAuth redirect: exchanges the code and stores the tokens. */
  internal suspend fun onRedirect(uri: Uri?) {
    val code = uri?.getQueryParameter("code")
    val active = pendingAuth
    if (code != null && active != null) {
      DropboxRedirect.reset()
      val tokens = auth.exchangeCode(active, code).getOrNull()
      if (tokens == null) {
        status = SyncStatus.Error
      } else if (!store.saveTokens(tokens)) {
        status = SyncStatus.ReconnectNeeded
        pendingAuth = null
      } else {
        val email = withRetry { it.fetchAccountEmail() }.getOrNull()
        if (email.isNullOrBlank()) {
          if (status != SyncStatus.ReconnectNeeded) status = SyncStatus.Error
        } else {
          val current = store.tokens()
          if (current != null) store.saveTokens(current.copy(accountEmail = email))
        }
        pendingAuth = null
      }
      emit()
    }
  }

  /** Disconnects the account and forgets this install's sync bookkeeping. */
  fun disconnect() {
    store.clearTokens()
    store.storedRev = null
    store.storedHash = null
    lastSyncFromDevice = null
    conflict = null
    cloudFoundPrompt = false
    foreignLock = null
    status = SyncStatus.Idle
    emit()
  }

  /**
   * Resolves a conflict dialog: keep local → push, use cloud → pull.
   * Also serves the cloud-found fresh-device prompt's Download action
   * (force = Pull) — there the conflict state is null by definition,
   * so no early return guard.
   */
  fun resolveConflict(keepLocal: Boolean) {
    runSync(manual = true, force = if (keepLocal) SyncAction.Push else SyncAction.Pull)
  }

  /** Dismisses the one-time "cloud save found" prompt without pulling. */
  fun dismissCloudFoundPrompt() {
    cloudFoundPrompt = false
    emit()
  }

  /**
   * Dismisses a conflict without changing either local or cloud saves.
   * The next sync trigger can surface the conflict again.
   */
  fun clearConflict() {
    conflict = null
    emit()
  }

  /** Persists the auto-sync toggle. */
  fun setAutoSync(enabled: Boolean) {
    store.autoSyncEnabled = enabled
    _autoSyncEnabled.value = enabled
    emit()
  }

  // --- sync core ---------------------------------------------------------

  private fun runSync(manual: Boolean, force: SyncAction? = null) {
    if (syncing) return
    if (appKey.isBlank() || !hasTree()) {
      emit()
      return
    }
    if (!store.secureStorageAvailable) {
      status = SyncStatus.ReconnectNeeded
      emit()
      return
    }
    if (store.tokens() == null) return
    if (!store.autoSyncEnabled && !manual) {
      // The toggle governs automatic sync only; "Sync now" stays live.
      emit()
      return
    }
    if (!online()) {
      if (manual) {
        status = SyncStatus.Error
        emit()
      }
      return
    }
    syncing = true
    status = SyncStatus.Syncing
    emit()
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { syncLoop(manual, force) }
      } finally {
        syncing = false
        if (status == SyncStatus.Syncing) status = SyncStatus.Idle
        emit()
      }
    }
  }

  private suspend fun syncLoop(manual: Boolean, force: SyncAction?) {
    // fetchSaveMeta throws on non-not-found errors, so the fetch is
    // routed through withRetry: an expired access token (the common
    // expiry point — this is the first API call of every sync) gets
    // refresh → retry → success, or surfaces ReconnectNeeded. It is
    // NEVER read as "cloud empty" (null), which could push over a
    // cloud save we cannot see.
    val cloudMeta =
        withRetry { api -> runCatching { api.fetchSaveMeta() } }
            .getOrElse {
              Timber.tag(TAG).w(it, "fetchSaveMeta failed; skipping sync")
              if (manual && status != SyncStatus.ReconnectNeeded) status = SyncStatus.Error
              return
            }
    val anySaves = runCatching { hasLocalSavesFn() }.getOrDefault(false)
    val localZip: ByteArray? =
        if (anySaves) {
          runCatching { zipBytes() }
              .getOrElse {
                Timber.tag(TAG).w(it, "local save staging failed")
                if (manual) status = SyncStatus.Error
                return
              }
        } else null
    val localHash =
        localZip
            ?.takeIf { it.isNotEmpty() }
            ?.let {
              runCatching { SaveContentHash.canonicalHash(it) }.getOrNull()
            }
    val pendingPush = store.sessionPendingPush
    val action =
        when (force) {
          null -> {
            if (
                pendingPush &&
                    localHash != null &&
                    cloudMeta != null &&
                    cloudMeta.rev == store.storedRev &&
                    localHash == store.storedHash
            ) {
              // Session flush with nothing changed since our own last
              // sync: drop the flag without rewriting the cloud.
              store.sessionPendingPush = false
              SyncAction.Noop
            } else {
              engine.decide(cloudMeta, store.storedRev, localHash, store.storedHash)
            }
          }
          else -> force
        }
    when (action) {
      SyncAction.Noop -> {}
      SyncAction.Pull -> cloudMeta?.let { performPull(it, manual) }
      SyncAction.Push,
      SyncAction.FirstUpload -> {
        if (localZip == null) {
          if (manual) status = SyncStatus.Error
          return
        }
        performPush(localZip, manual, cloudMeta?.rev)
      }
      SyncAction.Conflict -> {
        val state = runCatching { api().readState() }.getOrNull()
        conflict =
            ConflictInfo(
                localRegions = localZip?.let(::countRegions) ?: 0,
                cloudDevice = state?.lastUploaderDevice,
                cloudAgeMillis = state?.let { now() - it.syncedAtMillis },
            )
      }
      SyncAction.CloudFoundFreshDevice -> {
        if (!store.cloudPromptShown) {
          cloudFoundPrompt = true
        }
      }
    }
    refreshForeignLock()
  }

  private suspend fun performPull(cloudMeta: CloudSaveMeta, manual: Boolean) {
    val bytes =
        withRetry { it.downloadSaveZip() }
            .getOrElse {
              if (manual && status != SyncStatus.ReconnectNeeded) status = SyncStatus.Error
              return
            }
    runCatching { applyZipFn(bytes) }
        .onFailure {
          Timber.tag(TAG).e(it, "restore from cloud zip failed")
          if (manual) status = SyncStatus.Error
          return
        }
    store.storedRev = cloudMeta.rev
    store.storedHash = SaveContentHash.canonicalHash(bytes)
    store.lastSyncAtMillis = now()
    lastSyncFromDevice = runCatching { api().readState() }.getOrNull()?.lastUploaderDevice
    if (cloudFoundPrompt) store.cloudPromptShown = true
    conflict = null
    cloudFoundPrompt = false
  }

  private suspend fun performPush(zip: ByteArray, manual: Boolean, expectedRev: String?) {
    val hash = SaveContentHash.canonicalHash(zip)
    val upload = withRetry { it.uploadSaveZip(zip, expectedRev) }
    if (upload.isFailure) {
      val error = upload.exceptionOrNull()
      if (error is com.skiletro.wheelwitch.util.cloud.DropboxWriteConflictException) {
        showWriteConflict(zip)
      } else if (manual && status != SyncStatus.ReconnectNeeded) {
        status = SyncStatus.Error
      }
      return
    }
    val meta = upload.getOrThrow()
    // state.json is presentation metadata; a failure here must not
    // fail the push (the zip itself is already committed atomically).
    runCatching { api().writeState(CloudState(store.deviceId(), now(), hash)) }
        .onFailure { Timber.tag(TAG).w(it, "writeState failed (non-fatal)") }
    store.storedRev = meta.rev
    store.storedHash = hash
    store.lastSyncAtMillis = now()
    lastSyncFromDevice = store.deviceId()
    store.sessionPendingPush = false
    // deleteLock is not idempotent (absent = failure) — always fine.
    runCatching { api().deleteLock() }
    conflict = null
  }

  private suspend fun showWriteConflict(zip: ByteArray) {
    val state = runCatching { api().readState() }.getOrNull()
    conflict =
        ConflictInfo(
            localRegions = countRegions(zip),
            cloudDevice = state?.lastUploaderDevice,
            cloudAgeMillis = state?.let { now() - it.syncedAtMillis },
        )
    cloudFoundPrompt = false
  }

  /**
   * Runs [block]; on failure attempts one token refresh and retries once. Still failing after a
   * refresh attempt means the stored tokens are dead → [SyncStatus.ReconnectNeeded].
   */
  private suspend fun <T> withRetry(block: suspend (DropboxApi) -> Result<T>): Result<T> {
    val first = block(api())
    if (first.isSuccess) return first
    if (first.exceptionOrNull() is com.skiletro.wheelwitch.util.cloud.DropboxWriteConflictException) {
      return first
    }
    val tokens = store.tokens() ?: return first
    val refreshToken = tokens.refreshToken ?: return first
    val refreshed =
        auth.refresh(refreshToken).getOrNull()
            ?: run {
              status = SyncStatus.ReconnectNeeded
              return first
            }
    store.saveTokens(
        tokens.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken ?: tokens.refreshToken,
            expiresAtMillis = refreshed.expiresAtMillis,
        )
    )
    val retry = block(api())
    if (retry.isFailure) status = SyncStatus.ReconnectNeeded
    return retry
  }

  /** Best-effort advisory-lock check; TTL judged by server mtime. */
  private suspend fun refreshForeignLock() {
    val lock = runCatching { api().readLockWithServerTime() }.getOrNull()
    foreignLock = lock?.takeIf { engine.isLockFresh(it, store.deviceId()) }
  }

  private fun emit() {
    _uiState.value =
        when {
          appKey.isBlank() || !hasTree() -> SyncUiState.NotConfigured
          !store.secureStorageAvailable -> SyncUiState.SecureStorageUnavailable
          store.tokens() == null -> SyncUiState.Disconnected
          else ->
              SyncUiState.Connected(
                  status = status,
                  lastSyncAtMillis = store.lastSyncAtMillis,
                  lastSyncFromDevice = lastSyncFromDevice,
                  accountEmail = store.tokens()?.accountEmail,
                  foreignLock = foreignLock,
                  conflict = conflict,
                  cloudFoundPrompt = cloudFoundPrompt,
              )
        }
  }

  /** Distinct `RetroWFC/<region>` folders in a save zip, for the conflict dialog. */
  private fun countRegions(zip: ByteArray): Int =
      runCatching {
            val regions = HashSet<String>()
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
              while (true) {
                val entry = z.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("RetroWFC/")) {
                  val region = name.removePrefix("RetroWFC/").substringBefore('/')
                  if (region.isNotEmpty()) regions.add(region)
                }
              }
            }
            regions.size
          }
          .getOrDefault(0)

  // --- production defaults ----------------------------------------------

  /** Stages the unified save zip in the app cache via [SaveManager.backupAll]. */
  private suspend fun defaultZipBytes(context: Context): ByteArray =
      withContext(Dispatchers.IO) {
        val tree = DolphinTree.fromPersisted(context) ?: return@withContext ByteArray(0)
        val file = File(context.cacheDir, ZIP_STAGING_NAME)
        try {
          file.delete()
          file.createNewFile()
          SaveManager.backupAll(tree, Uri.fromFile(file)).getOrThrow()
          file.readBytes()
        } finally {
          file.delete()
        }
      }

  /** Restores cloud zip bytes onto the local tree via [SaveManager.restoreAll]. */
  private suspend fun defaultApplyZip(context: Context, bytes: ByteArray) =
      withContext(Dispatchers.IO) {
        val tree =
            DolphinTree.fromPersisted(context)
                ?: throw IllegalStateException("No Dolphin tree configured")
        val file = File(context.cacheDir, ZIP_STAGING_NAME)
        try {
          file.writeBytes(bytes)
          SaveManager.restoreAll(tree, Uri.fromFile(file)).getOrThrow()
        } finally {
          file.delete()
        }
      }

  /** True when any local save data exists. */
  private suspend fun defaultHasLocalSaves(context: Context): Boolean =
      withContext(Dispatchers.IO) {
        DolphinTree.fromPersisted(context)?.let { SaveManager.hasAnySave(it) } ?: false
      }

  companion object {
    /** Opens the OAuth authorize URL in the system browser. */
    private fun defaultOpenBrowser(context: Context, url: String) {
      runCatching {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
          }
          .onFailure { Timber.tag(TAG).w(it, "no browser available for OAuth") }
    }

    private const val TAG = "CloudSync"
    private const val ZIP_STAGING_NAME = "wheelsync-save.zip"

    /** Builds the SDK client from the stored access token. */
    private fun defaultApi(store: SyncStore): DropboxApi {
      val tokens = store.tokens() ?: error("WheelSync is not connected")
      return DropboxApi(DbxClientV2(DbxRequestConfig("WheelWitch"), tokens.accessToken))
    }

    /** [ViewModelProvider.Factory] for the composition root. */
    val Factory: ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        CloudSyncViewModel(app)
      }
    }
  }
}
