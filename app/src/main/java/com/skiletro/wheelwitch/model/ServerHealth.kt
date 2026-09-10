package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/**
 * One named check in the `/api/health` response.
 *
 * [status] is normalized to one of "ok", "error", or "degraded" by the
 * health parser; [message] is the raw description, if any.
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
 * The RWFC API embeds memory usage in the check's free-text description
 * rather than a structured field, so only [used] is populated by the
 * health parser.
 */
@Immutable
data class MemoryInfo(
    val status: String,
    val used: String?
)