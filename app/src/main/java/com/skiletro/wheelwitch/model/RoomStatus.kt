package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/**
 * Whether the RWFC game server is reachable.
 *
 * - [Online]: device has internet and the server responded.
 * - [Offline]: device has internet but the server did not respond.
 * - [NoInternet]: device itself has no network connectivity.
 * - [Unknown]: connectivity has not been checked yet.
 */
@Immutable
sealed interface ServerConnectivity {
    data object Online : ServerConnectivity
    data object Offline : ServerConnectivity
    data object NoInternet : ServerConnectivity
    data object Unknown : ServerConnectivity
}

/** A room on the RWFC multiplayer server parsed from the rooms API. */
@Immutable
data class Room(
    val id: String,
    val players: List<Player>,
    val averageVR: Int,
    val trackName: String?,
    val roomType: String,
    val isPublic: Boolean,
    val isJoinable: Boolean
)

/**
 * Mii appearance data from the rooms API.
 *
 * [data] is the Base64-encoded RFL (Mii binary) payload; [name] is the
 * Mii's display name.
 */
@Immutable
data class MiiData(
    val data: String,
    val name: String
)

/**
 * A player in a room parsed from the rooms API.
 *
 * [vr] is the player's Versus Rating; [br] is their Battle Rating. Both
 * are Mario Kart Wii online ranking points.
 */
@Immutable
data class Player(
    val name: String,
    val friendCode: String,
    val vr: Int,
    val br: Int,
    val isOpenHost: Boolean,
    val mii: MiiData?
)
