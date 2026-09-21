package com.skiletro.wheelwitch.util.prefs

/** Shared keys/names for SharedPreferences access across the app. */
object PrefsKeys {
  /** App-wide preferences. */
  const val PREFS_NAME = "wheelwitch"

  /** Settings screen state: theme picker choices that survive process death. */
  const val SETTINGS_PREFS = "settings"

  /** Race-stats JSON cache: stores the last successful `/api/racestats/global` response. */
  const val RACE_STATS_PREFS = "race_stats_cache"
  const val RACE_STATS_KEY = "race_stats_json"

  /**
   * Per-player leaderboard cache: stores the last successful `/api/leaderboard/player/<fc>/`
   * responses.
   */
  const val LEADERBOARD_CACHE_PREFS = "leaderboard_cache"
  const val LEADERBOARD_CACHE_KEY = "leaderboard_cache_json"

  /** Per-profile badge cache: stores the last successful `/badges/<pid>` responses. */
  const val BADGE_CACHE_PREFS = "badge_cache"
  const val BADGE_CACHE_KEY = "badge_cache_json"
  const val WHEELWITCH_TREE_URI_KEY = "wheelwitch_tree_uri"
  const val SELECTED_REGION_KEY = "selected_region"
  const val THEME_MODE_KEY = "theme_mode"
  const val APP_THEME_KEY = "app_theme"
  const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"
  const val LOGGING_TO_FILE_KEY = "logging_to_file"
  const val LAST_BACKUP_TIMESTAMP_KEY = "last_backup_timestamp"
  const val GAME_INI_NOTICE_SHOWN_KEY = "game_ini_notice_shown"

  /** Cloud sync: tokens + sync bookkeeping, stored encrypted. */
  const val SYNC_PREFS = "sync_prefs"
  const val SYNC_DEVICE_ID_KEY = "device_id"
  const val SYNC_ACCESS_TOKEN_KEY = "access_token"
  const val SYNC_REFRESH_TOKEN_KEY = "refresh_token"
  const val SYNC_TOKEN_EXPIRES_KEY = "token_expires_at"
  const val SYNC_ACCOUNT_EMAIL_KEY = "account_email"
  const val SYNC_STORED_REV_KEY = "stored_rev"
  const val SYNC_STORED_HASH_KEY = "stored_hash"
  const val SYNC_SESSION_PENDING_KEY = "session_pending_push"
  const val SYNC_AUTO_ENABLED_KEY = "auto_sync_enabled"
  const val SYNC_LAST_SYNC_KEY = "last_sync_at"
  const val SYNC_CLOUD_PROMPT_SHOWN_KEY = "cloud_found_prompt_shown"
}
