package com.skiletro.wheelwitch.util.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DropboxAuthTest {
  private lateinit var server: MockWebServer
  private lateinit var auth: DropboxAuth

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
    auth = DropboxAuth(client = OkHttpClient())
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `authorize url encodes semantic query parameters`() {
    val (url, pending) = auth.authorizeUrl()
    val parsed = url.toHttpUrl()
    assertThat(parsed.queryParameter("response_type")).isEqualTo("code")
    assertThat(parsed.queryParameter("client_id")).isNotNull()
    assertThat(parsed.queryParameter("redirect_uri")).isEqualTo("com.skiletro.wheelwitch://dropbox")
    assertThat(parsed.queryParameter("scope"))
        .isEqualTo("account_info.read files.metadata.read files.content.read files.content.write")
    assertThat(parsed.queryParameter("code_challenge")).isNotEmpty()
    assertThat(parsed.queryParameter("state")).isNotEmpty()
    assertThat(parsed.queryParameter("state")).isNotEqualTo(parsed.queryParameter("code_challenge"))
    assertThat(pending.state).isEqualTo(parsed.queryParameter("state"))
    assertThat(parsed.queryParameter("code_challenge_method")).isEqualTo("S256")
    assertThat(parsed.queryParameter("token_access_type")).isEqualTo("offline")
    assertThat(pending.verifier).isNotEmpty()
  }

  @Test
  fun `exchange parses tokens`() = runTest {
    server.enqueue(
        MockResponse.Builder()
            .body(
                """{"access_token":"at","refresh_token":"rt","expires_in":14400,"account_id":"dbid:1"}"""
            )
            .build()
    )
    val pending = DropboxAuth.PendingAuth(verifier = "v", redirectUri = "r", state = "state")
    val result = auth.exchangeCodeAt(server.url("/oauth2/token"), pending, "the-code")
    assertThat(result.getOrNull()!!.accessToken).isEqualTo("at")
    assertThat(result.getOrNull()!!.refreshToken).isEqualTo("rt")
  }

  @Test
  fun `refresh parses tokens`() = runTest {
    server.enqueue(MockResponse.Builder().body("""{"access_token":"at2"}""").build())
    val result = auth.refreshAt(server.url("/oauth2/token"), "rt")
    assertThat(result.getOrNull()!!.accessToken).isEqualTo("at2")
  }

  @Test
  fun `exchange http error returns bounded typed failure`() = runTest {
    server.enqueue(
        MockResponse.Builder().code(400).body("x".repeat(5_000)).build(),
    )
    val pending = DropboxAuth.PendingAuth(verifier = "v", redirectUri = "r", state = "state")
    val result = auth.exchangeCodeAt(server.url("/oauth2/token"), pending, "bad")

    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(DropboxAuthException::class.java)
    assertThat(requireNotNull(error).message!!.length).isLessThan(600)
  }
}
