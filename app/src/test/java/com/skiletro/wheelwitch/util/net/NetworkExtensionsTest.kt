package com.skiletro.wheelwitch.util.net

import com.google.common.truth.Truth.assertThat
import java.net.HttpURLConnection
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NetworkExtensionsTest {
  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `fetchUrl bounds non-success response body in error`() {
    val body = "x".repeat(5_000)
    server.enqueue(
        MockResponse.Builder().code(HttpURLConnection.HTTP_UNAVAILABLE).body(body).build(),
    )
    val url = server.url("/long-error").toString()

    val error = assertThrows<IllegalStateException> { fetchUrl(url, OkHttpClient()) }

    assertThat(error.message).contains(url)
    assertThat(error.message).contains("503")
    assertThat(error.message).doesNotContain(body)
    assertThat(error.message!!.length).isLessThan(500)
  }
}
