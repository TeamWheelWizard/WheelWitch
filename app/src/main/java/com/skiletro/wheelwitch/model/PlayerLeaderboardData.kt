package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/**
 * Player data returned by `/api/leaderboard/player/<fc>/` and embedded in [LeaderboardEntry].
 *
 * [name] and [miiData] are nullable because the API supplies them
 * conditionally; consumers fall back to local save data when absent.
 */
@Immutable
data class PlayerLeaderboardData(
  val vr: Int,
  val name: String?,
  val miiData: String?,
)