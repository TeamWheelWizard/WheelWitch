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
    const val WHEELWITCH_TREE_URI_KEY = "wheelwitch_tree_uri"
    const val SELECTED_SLOT_KEY = "selected_slot"
    const val SELECTED_REGION_KEY = "selected_region"
    const val LAST_LEADERBOARD_VR_KEY = "last_leaderboard_vr"
    const val THEME_MODE_KEY = "theme_mode"
    const val APP_THEME_KEY = "app_theme"
    const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"
    const val LOGGING_TO_FILE_KEY = "logging_to_file"
    const val LAST_BACKUP_TIMESTAMP_KEY = "last_backup_timestamp"
    const val LAST_BACKUP_RR_TIMESTAMP_KEY = "last_backup_rr_timestamp"
    const val GAME_INI_NOTICE_SHOWN_KEY = "game_ini_notice_shown"
}
