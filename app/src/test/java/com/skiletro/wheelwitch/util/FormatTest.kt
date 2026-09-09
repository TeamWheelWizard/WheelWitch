package com.skiletro.wheelwitch.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FormatTest {

    @Test
    fun `formatBytesPerSecond returns B per s under 1 KB`() {
        assertThat(formatBytesPerSecond(0L)).isEqualTo("0 B/s")
        assertThat(formatBytesPerSecond(512L)).isEqualTo("512 B/s")
        assertThat(formatBytesPerSecond(1023L)).isEqualTo("1023 B/s")
    }

    @Test
    fun `formatBytesPerSecond returns KB per s under 1 MB`() {
        assertThat(formatBytesPerSecond(1024L)).isEqualTo("1.00 KB/s")
        assertThat(formatBytesPerSecond(1536L)).isEqualTo("1.50 KB/s")
        assertThat(formatBytesPerSecond(1024L * 1023L)).isEqualTo("1023.00 KB/s")
    }

    @Test
    fun `formatBytesPerSecond returns MB per s under 1 GB`() {
        assertThat(formatBytesPerSecond(1024L * 1024L)).isEqualTo("1.00 MB/s")
        assertThat(formatBytesPerSecond(1024L * 1024L * 5L + 512L * 1024L)).isEqualTo("5.50 MB/s")
    }

    @Test
    fun `formatBytesPerSecond returns GB per s at and above 1 GB`() {
        assertThat(formatBytesPerSecond(1024L * 1024L * 1024L)).isEqualTo("1.00 GB/s")
        assertThat(formatBytesPerSecond(1024L * 1024L * 1024L * 2L)).isEqualTo("2.00 GB/s")
    }

    @Test
    fun `formatDownloadProgress shows both sides when total is known`() {
        assertThat(formatDownloadProgress(512L * 1024L, 2L * 1024L * 1024L))
            .isEqualTo("512 KB / 2.00 MB")
    }

    @Test
    fun `formatDownloadProgress shows only downloaded when total is unknown`() {
        assertThat(formatDownloadProgress(1536L, 0L)).isEqualTo("1 KB")
        assertThat(formatDownloadProgress(1536L, -1L)).isEqualTo("1 KB")
    }

    @Test
    fun `formatScore always prints two decimal places`() {
        assertThat(formatScore(76.4)).isEqualTo("76.40")
        assertThat(formatScore(0.0)).isEqualTo("0.00")
        assertThat(formatScore(9.0)).isEqualTo("9.00")
        assertThat(formatScore(1234.567)).isEqualTo("1234.57")
    }
}
