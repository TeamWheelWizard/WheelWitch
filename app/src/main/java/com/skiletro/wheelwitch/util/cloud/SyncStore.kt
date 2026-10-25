package com.skiletro.wheelwitch.util.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme
import androidx.security.crypto.MasterKey
import com.skiletro.wheelwitch.util.prefs.Prefs
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import java.util.UUID
import timber.log.Timber

/**
 * Persists OAuth credentials and sync bookkeeping. [create] uses encrypted preferences;
 * if Keystore initialization fails, only non-sensitive bookkeeping remains available and
 * credentials are never written to ordinary preferences.
 */
class SyncStore(
    private val prefs: SharedPreferences,
    val secureStorageAvailable: Boolean = true,
) {
  private var cachedDeviceId: String? = null

  /** Per-install random UUID, generated once and persisted. */
  fun deviceId(): String {
    cachedDeviceId?.let {
      return it
    }
    val existing = prefs.getString(PrefsKeys.SYNC_DEVICE_ID_KEY, null)
    val id = existing ?: UUID.randomUUID().toString()
    if (existing == null) prefs.edit().putString(PrefsKeys.SYNC_DEVICE_ID_KEY, id).apply()
    cachedDeviceId = id
    return id
  }

  /** Current OAuth tokens; null = never connected, disconnected, or secure storage unavailable. */
  fun tokens(): DropboxAuth.AuthTokens? {
    if (!secureStorageAvailable) return null
    val access = prefs.getString(PrefsKeys.SYNC_ACCESS_TOKEN_KEY, null) ?: return null
    return DropboxAuth.AuthTokens(
        accessToken = access,
        refreshToken = prefs.getString(PrefsKeys.SYNC_REFRESH_TOKEN_KEY, null),
        expiresAtMillis = prefs.getLong(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY, Long.MAX_VALUE),
        accountEmail = prefs.getString(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY, null),
    )
  }

  /** Saves credentials only when encrypted storage is available; false means fail closed. */
  fun saveTokens(tokens: DropboxAuth.AuthTokens): Boolean {
    if (!secureStorageAvailable) return false
    prefs
        .edit()
        .putString(PrefsKeys.SYNC_ACCESS_TOKEN_KEY, tokens.accessToken)
        .putString(PrefsKeys.SYNC_REFRESH_TOKEN_KEY, tokens.refreshToken)
        .putLong(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY, tokens.expiresAtMillis)
        .putString(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY, tokens.accountEmail)
        .apply()
    return true
  }

  fun clearTokens() {
    prefs
        .edit()
        .remove(PrefsKeys.SYNC_ACCESS_TOKEN_KEY)
        .remove(PrefsKeys.SYNC_REFRESH_TOKEN_KEY)
        .remove(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY)
        .remove(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY)
        .apply()
  }

  /** Dropbox `rev` of `save.zip` at the last successful sync. */
  var storedRev: String?
    get() = prefs.getString(PrefsKeys.SYNC_STORED_REV_KEY, null)
    set(value) {
      prefs.edit().putString(PrefsKeys.SYNC_STORED_REV_KEY, value).apply()
    }

  /** Canonical content hash at the last successful sync. */
  var storedHash: String?
    get() = prefs.getString(PrefsKeys.SYNC_STORED_HASH_KEY, null)
    set(value) {
      prefs.edit().putString(PrefsKeys.SYNC_STORED_HASH_KEY, value).apply()
    }

  /** Marks a launched session awaiting sync; also cleared on no-change sync or disconnect. */
  var sessionPendingPush: Boolean
    get() = prefs.getBoolean(PrefsKeys.SYNC_SESSION_PENDING_KEY, false)
    set(value) {
      prefs.edit().putBoolean(PrefsKeys.SYNC_SESSION_PENDING_KEY, value).apply()
    }

  /** Auto-sync toggle; default on. */
  var autoSyncEnabled: Boolean
    get() = prefs.getBoolean(PrefsKeys.SYNC_AUTO_ENABLED_KEY, true)
    set(value) {
      prefs.edit().putBoolean(PrefsKeys.SYNC_AUTO_ENABLED_KEY, value).apply()
    }

  /** Wall clock of the last successful sync, for the status line. */
  var lastSyncAtMillis: Long
    get() = prefs.getLong(PrefsKeys.SYNC_LAST_SYNC_KEY, 0L)
    set(value) {
      prefs.edit().putLong(PrefsKeys.SYNC_LAST_SYNC_KEY, value).apply()
    }

  /** One-time "cloud save found" prompt guard for fresh devices. */
  var cloudPromptShown: Boolean
    get() = prefs.getBoolean(PrefsKeys.SYNC_CLOUD_PROMPT_SHOWN_KEY, false)
    set(value) {
      prefs.edit().putBoolean(PrefsKeys.SYNC_CLOUD_PROMPT_SHOWN_KEY, value).apply()
    }

  companion object {
    private const val TAG = "SyncStore"

    /**
     * Production entry point: Keystore-backed encrypted prefs. If Keystore creation fails, retain
     * only non-sensitive bookkeeping in ordinary prefs and permanently disable credential writes
     * for this store instance.
     */
    fun create(context: Context): SyncStore =
        runCatching {
              val masterKey =
                  MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
              val prefs =
                  EncryptedSharedPreferences.create(
                      context,
                      PrefsKeys.SYNC_PREFS,
                      masterKey,
                      PrefKeyEncryptionScheme.AES256_SIV,
                      PrefValueEncryptionScheme.AES256_GCM,
                  )
              SyncStore(prefs)
            }
            .getOrElse {
              Timber.tag(TAG).w(it, "Encrypted prefs unavailable; cloud credentials disabled")
              val prefs = Prefs.sync(context)
              prefs
                  .edit()
                  .remove(PrefsKeys.SYNC_ACCESS_TOKEN_KEY)
                  .remove(PrefsKeys.SYNC_REFRESH_TOKEN_KEY)
                  .remove(PrefsKeys.SYNC_TOKEN_EXPIRES_KEY)
                  .remove(PrefsKeys.SYNC_ACCOUNT_EMAIL_KEY)
                  .apply()
              SyncStore(prefs, secureStorageAvailable = false)
            }
  }
}
