package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/** Describes the current state of the Retro Rewind Pack on disk relative to the update server. */
@Immutable
sealed class PackStatus {
    /** No parseable local pack version was found. */
    data object NotInstalled : PackStatus()

    /**
     * Server info could not be fetched or parsed. [installedVersion] is the locally recorded
     * version, not a validation of all pack files.
     */
    data class CheckFailed(val installedVersion: SemVersion?) : PackStatus()

    /** A newer version is available on the server. */
    data class UpdateAvailable(
        val currentVersion: SemVersion,
        val latestVersion: SemVersion,
        val serverInfo: ServerInfo
    ) : PackStatus()

    /** Local version is at least the latest server version. */
    data class UpToDate(val currentVersion: SemVersion, val latestVersion: SemVersion) :
        PackStatus()
}
