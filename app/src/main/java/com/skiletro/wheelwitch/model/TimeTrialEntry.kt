package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

@Immutable
data class TimeTrialTrack(
    val id: Int,
    val name: String,
    val courseId: Int,
    val category: String,
    val laps: Int,
    val supportsGlitch: Boolean,
    val sortOrder: Int,
    val isHidden: Boolean,
)

@Immutable
data class TimeTrialSubmission(
    val id: Int,
    val trackId: Int,
    val trackName: String,
    val ttProfileId: Int,
    val playerName: String,
    val countryCode: Int,
    val countryAlpha2: String?,
    val countryName: String?,
    val cc: Int,
    val finishTimeMs: Long,
    val finishTimeDisplay: String,
    val vehicleId: Int,
    val characterId: Int,
    val controllerType: Int,
    val driftType: Int,
    val shroomless: Boolean,
    val glitch: Boolean,
    val isFlap: Boolean,
    val driftCategory: Int,
    val miiName: String?,
    val lapCount: Int,
    val lapSplitsMs: List<Long>?,
    val lapSplitsDisplay: List<String>?,
    val fastestLapMs: Long?,
    val fastestLapDisplay: String?,
    val dateSet: String,
    val submittedAt: String?,
    val rank: Int?,
)

@Immutable
data class TimeTrialLeaderboardResponse(
    val track: TimeTrialTrack,
    val cc: Int,
    val glitchAllowed: Boolean,
    val shroomless: Boolean?,
    val vehicleFilter: String?,
    val isFlap: Boolean,
    val submissions: List<TimeTrialSubmission>,
    val totalSubmissions: Int,
    val currentPage: Int,
    val pageSize: Int,
    val totalPages: Int,
    val fastestLapMs: Long?,
    val fastestLapDisplay: String?,
)
