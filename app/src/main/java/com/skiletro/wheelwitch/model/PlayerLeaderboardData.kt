package com.skiletro.wheelwitch.model

/**
 * The shared per-player leaderboard data returned both by the
 * profile endpoint (`/api/leaderboard/player/<fc>/`) and embedded in
 * each [LeaderboardEntry.row] of the leaderboard.
 *
 * [name] and [miiData] are nullable because the API supplies them
 * conditionally; consumers fall back to local save data when absent.
 */
data class PlayerLeaderboardData(
  val vr: Int,
  val name: String?,
  val miiData: String?,
)