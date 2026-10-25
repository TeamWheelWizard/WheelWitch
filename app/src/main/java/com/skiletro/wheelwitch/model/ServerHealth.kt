package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/**
 * One named check in the `/api/health` response.
 *
 * The parser maps healthy/unhealthy to "ok"/"error" and lowercases other statuses.
 * [message] is the description when nonempty.
 */
@Immutable
data class HealthCheckItem(
    val status: String,
    val message: String?
)

/**
 * Parsed `/api/health` response. Each subsystem field is null when the
 * corresponding check is absent from the server response.
 */
@Immutable
data class ServerHealth(
    val status: String,
    val database: HealthCheckItem?,
    val postgresql: HealthCheckItem?,
    val retroWfcApi: HealthCheckItem?,
    val memory: MemoryInfo?
)

/**
 * Memory subsystem check.
 *
 * The parser extracts [used] from the free-text description, or leaves it null when unmatched.
 */
@Immutable
data class MemoryInfo(
    val status: String,
    val used: String?
)