package com.skiletro.wheelwitch.viewmodel

import androidx.compose.runtime.Immutable
import com.skiletro.wheelwitch.model.LeaderboardEntry
import com.skiletro.wheelwitch.model.RaceStats
import com.skiletro.wheelwitch.model.Room
import com.skiletro.wheelwitch.model.ServerConnectivity
import com.skiletro.wheelwitch.model.ServerHealth
import com.skiletro.wheelwitch.model.TimeTrialSubmission
import com.skiletro.wheelwitch.model.TimeTrialTrack

/**
 * Top-level page in the Online Menu. [Hub] is the landing screen that
 * links to the others; the rest trigger a fetch when navigated to.
 */
enum class OnlineMenuPage {
  Hub,
  Rooms,
  Leaderboard,
  Health,
  RaceStats,
  TimeTrial,
  ;

  /** Shared-transition key for the page's title element. */
  val titleSharedKey: String
    get() = "online_title_$name"
}

/**
 * Shared shape for the four sub-screen state machines in [OnlineViewModel].
 * Each sub-screen starts at [Idle], transitions to [Loading] on fetch,
 * and ends at [Success] or [Error].
 */
@Immutable
sealed class RoomsState {
  data object Idle : RoomsState()
  data object Loading : RoomsState()
  data class Success(
    val rooms: List<Room>,
    val playerCount: Int?,
    val serverConnectivity: ServerConnectivity,
  ) : RoomsState()

  data class Error(val message: String) : RoomsState()
}

@Immutable
sealed class LeaderboardState {
  data object Idle : LeaderboardState()
  data object Loading : LeaderboardState()
  data class Success(val entries: List<LeaderboardEntry>, val hasMore: Boolean, val page: Int) :
    LeaderboardState()

  data class Error(val message: String) : LeaderboardState()
}

@Immutable
sealed class HealthState {
  data object Idle : HealthState()
  data object Loading : HealthState()
  data class Success(val health: ServerHealth) : HealthState()
  data class Error(val message: String) : HealthState()
}

@Immutable
sealed class RaceStatsState {
  data object Idle : RaceStatsState()
  data object Loading : RaceStatsState()
  data class Success(val stats: RaceStats, val lastRefreshedAt: Long) : RaceStatsState()
  data class Error(val message: String) : RaceStatsState()
}

@Immutable
sealed class TimeTrialState {
  data object Idle : TimeTrialState()
  data object Loading : TimeTrialState()
  data class Success(val tracks: List<TimeTrialTrack>) : TimeTrialState()
  data class Error(val message: String) : TimeTrialState()
}

@Immutable
sealed class TrackLeaderboardState {
  data object Idle : TrackLeaderboardState()
  data object Loading : TrackLeaderboardState()
  data class Success(
    val submissions: List<TimeTrialSubmission>,
    val currentPage: Int,
    val totalPages: Int,
    val fastestLapMs: Long?,
    val fastestLapDisplay: String?,
  ) : TrackLeaderboardState()

  data class Error(val message: String) : TrackLeaderboardState()
}
