package com.skiletro.wheelwitch.util

/**
 * Formats sizes with binary (1024-based) thresholds and display labels B, KB, MB.
 * KB uses integer division; MB uses two decimal places.
 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * Formats transfer rates with binary (1024-based) thresholds and B/s through GB/s labels.
 * Units above B/s use two decimal places.
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

/**
 * Formats points still needed for a rank up with a leading plus and two
 * decimal places, e.g. 10.52 -> "+10.52".
 */
fun formatRankPoints(points: Double): String = "+%.2f".format(points)
