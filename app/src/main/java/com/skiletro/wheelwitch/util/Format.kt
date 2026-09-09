package com.skiletro.wheelwitch.util

/**
 * Formats [bytes] as a human-readable size string.
 *
 * - `< 1 KB` -> "N B"
 * - `< 1 MB` -> "N KB" (integer division; rolls to MB at >= 1 MiB)
 * - else -> "M.NN MB"
 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * Formats [bytesPerSecond] as a human-readable transfer rate with adaptive units.
 *
 * - `< 1 KB/s` -> "N B/s"
 * - `< 1 MB/s` -> "N.NN KB/s"
 * - `< 1 GB/s` -> "N.NN MB/s"
 * - else -> "N.NN GB/s"
 */
fun formatBytesPerSecond(bytesPerSecond: Long): String = when {
    bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
    bytesPerSecond < 1024L * 1024L -> "%.2f KB/s".format(bytesPerSecond / 1024.0)
    bytesPerSecond < 1024L * 1024L * 1024L -> "%.2f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    else -> "%.2f GB/s".format(bytesPerSecond / (1024.0 * 1024.0 * 1024.0))
}

/**
 * Formats a download's transferred vs total size as "X / Y" using [formatBytes] for each side.
 * When [totalBytes] is 0 or negative (Content-Length missing), the total side is omitted.
 */
fun formatDownloadProgress(bytesDownloaded: Long, totalBytes: Long): String =
    if (totalBytes > 0L) {
        "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
    } else {
        formatBytes(bytesDownloaded)
    }

/**
 * Formats a rank [score] with two decimal places, e.g. 76.4 -> "76.40".
 */
fun formatScore(score: Double): String = "%.2f".format(score)
