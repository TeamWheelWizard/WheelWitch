package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/**
 * One row of the VR leaderboard.
 *
 * [player] carries the shared per-player shape (VR, name, Mii data)
 * that the "player profile" endpoint also returns; [rank], [friendCode]
 * and [miiImageBase64] are leaderboard-specific.
 *
 * [miiImageBase64] is a pre-rendered PNG image, whereas
 * [PlayerLeaderboardData.miiData] is the raw Base64-encoded RFL (Mii
 * binary) payload that the client can re-render via the Mii image
 * service. Either may be null.
 */
@Immutable
data class LeaderboardEntry(
    val rank: Int,
    val friendCode: String,
    val player: PlayerLeaderboardData,
    val miiImageBase64: String?
)

data class LeaderboardResponse(
    val entries: List<LeaderboardEntry>,
    val page: Int,
    val hasMore: Boolean
)