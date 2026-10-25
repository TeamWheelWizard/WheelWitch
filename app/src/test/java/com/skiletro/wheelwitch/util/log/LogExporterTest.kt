package com.skiletro.wheelwitch.util.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class LogExporterTest {

    @Test
    fun `timestamp matches yyyyMMdd-HHmmss pattern`() {
        val dateTime = LocalDateTime.of(2026, 9, 8, 23, 5, 9)
        assertThat(LogExporter.formatTimestamp(dateTime)).isEqualTo("20260908-230509")
    }

    @Test
    fun `concurrent formatting against the shared formatter is safe`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs =
                (0 until 256).map {
                    pool.submit(Callable { LogExporter.formatTimestamp(LocalDateTime.of(2026, 9, 8, 23, 5, 9)) })
                }
            val results = jobs.map { it.get() }
            assertThat(results.toSet()).containsExactly("20260908-230509")
        } finally {
            pool.shutdown()
        }
    }
}