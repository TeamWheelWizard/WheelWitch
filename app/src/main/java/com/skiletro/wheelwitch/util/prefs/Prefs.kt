package com.skiletro.wheelwitch.util.prefs

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences lookup helpers keyed by [PrefsKeys] names. */
object Prefs {
  /** App-wide preferences: storage URI, last server version, theme, onboarding, My Stuff mode, ISO path. */
  fun main(context: Context): SharedPreferences =
      context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

  /** Settings screen state: theme picker choices that survive process death. */
  fun settings(context: Context): SharedPreferences =
      context.getSharedPreferences(PrefsKeys.SETTINGS_PREFS, Context.MODE_PRIVATE)

  /** Race-stats JSON cache: stores the last successful `/api/racestats/global` response. */
  fun raceStatsCache(context: Context): SharedPreferences =
      context.getSharedPreferences(PrefsKeys.RACE_STATS_PREFS, Context.MODE_PRIVATE)

  /** Per-player leaderboard cache: stores the last successful `/api/leaderboard/player/<fc>/` responses. */
  fun leaderboardCache(context: Context): SharedPreferences =
      context.getSharedPreferences(PrefsKeys.LEADERBOARD_CACHE_PREFS, Context.MODE_PRIVATE)
}
