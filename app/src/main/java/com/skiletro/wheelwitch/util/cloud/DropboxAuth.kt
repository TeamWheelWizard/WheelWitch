package com.skiletro.wheelwitch.util.cloud

import android.net.Uri
import com.skiletro.wheelwitch.BuildConfig
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * PKCE + redirect plumbing for the WheelSync Dropbox OAuth flow.
 *
 * The redirect URI is a custom scheme (`com.skiletro.wheelwitch://dropbox`) handled by
 * [com.skiletro.wheelwitch.MainActivity]. Incoming redirect intents are fed to [offer] and land in
 * [redirectFlow], which the cloud-sync view-model collects to finish the token exchange.
 */
object DropboxRedirect {
  const val REDIRECT_URI = "com.skiletro.wheelwitch://dropbox"

  /** Last OAuth redirect Uri received; null until one arrives. */
  val redirectFlow = MutableStateFlow<Uri?>(null)

  /** Feed an incoming intent Uri; true when it is our redirect. */
  fun offer(uri: Uri?): Boolean {
    if (uri == null) return false
    val isOurs = uri.toString().startsWith(REDIRECT_URI)
    if (isOurs) redirectFlow.value = uri
    return isOurs
  }

  /** Clears the current redirect (used when starting a new flow). */
  fun reset() {
    redirectFlow.value = null
  }
}

/**
 * Dropbox OAuth2 authorization-code flow with PKCE. No client secret ever ships in the APK; the app
 * key is a public identifier.
 *
 * Token-endpoint calls use the caller's [OkHttpClient] (default: [HttpClientProvider.client]).
 * [exchangeCodeAt] and [refreshAt] are internal seams the tests point at a MockWebServer.
 *
 * Refresh responses may omit `refresh_token` and `expires_in`; callers merge the fields they care
 * about onto the previously stored tokens rather than replacing them wholesale.
 */
class DropboxAuth(
    private val client: OkHttpClient = HttpClientProvider.client,
    private val appKey: String = BuildConfig.DROPBOX_APP_KEY,
) {
  /** PKCE state for one authorize round-trip. */
  data class PendingAuth(val verifier: String, val redirectUri: String)

  /**
   * Tokens from a token-endpoint response. [accountEmail] is null unless the endpoint response
   * includes it (it normally does not — fetching it needs a separate `users/get_current_account`
   * call).
   */
  data class AuthTokens(
      val accessToken: String,
      val refreshToken: String?,
      val expiresAtMillis: Long,
      val accountEmail: String?,
  )

  /**
   * Builds the authorize URL to open in a Custom Tab together with the [PendingAuth] whose verifier
   * must accompany the code exchange.
   */
  fun authorizeUrl(): Pair<String, PendingAuth> {
    val verifier = randomToken()
    val challenge = base64Url(sha256(verifier.toByteArray()))
    val pending = PendingAuth(verifier, DropboxRedirect.REDIRECT_URI)
    val url =
        "https://www.dropbox.com/oauth2/authorize".toHttpUrl()
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", appKey)
            .addQueryParameter("redirect_uri", pending.redirectUri)
            .addQueryParameter(
                "scope",
                "account_info.read files.metadata.read files.content.read files.content.write",
            )
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("token_access_type", "offline")
            .build()
            .toString()
    return url to pending
  }

  /** Exchanges an authorize code for tokens at the Dropbox token endpoint. */
  suspend fun exchangeCode(pending: PendingAuth, code: String): Result<AuthTokens> =
      exchangeCodeAt(TOKEN_ENDPOINT, pending, code)

  /** Refreshes the access token using [refreshToken]. */
  suspend fun refresh(refreshToken: String): Result<AuthTokens> =
      refreshAt(TOKEN_ENDPOINT, refreshToken)

  internal suspend fun exchangeCodeAt(
      endpoint: HttpUrl,
      pending: PendingAuth,
      code: String,
  ): Result<AuthTokens> =
      withContext(Dispatchers.IO) {
        runCatching {
          val body =
              FormBody.Builder()
                  .add("grant_type", "authorization_code")
                  .add("code", code)
                  .add("redirect_uri", pending.redirectUri)
                  .add("client_id", appKey)
                  .add("code_verifier", pending.verifier)
                  .build()
          parseTokens(request(body, endpoint))
        }
      }

  internal suspend fun refreshAt(endpoint: HttpUrl, refreshToken: String): Result<AuthTokens> =
      withContext(Dispatchers.IO) {
        runCatching {
          val body =
              FormBody.Builder()
                  .add("grant_type", "refresh_token")
                  .add("refresh_token", refreshToken)
                  .add("client_id", appKey)
                  .build()
          parseTokens(request(body, endpoint))
        }
      }

  private fun request(body: FormBody, endpoint: HttpUrl): String {
    client.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { response ->
      val text = response.body?.string().orEmpty()
      check(response.isSuccessful) { "Dropbox token endpoint ${response.code}: $text" }
      return text
    }
  }

  private fun parseTokens(raw: String): AuthTokens {
    val obj = JSONObject(raw)
    return AuthTokens(
        accessToken = obj.getString("access_token"),
        refreshToken = if (obj.has("refresh_token")) obj.getString("refresh_token") else null,
        expiresAtMillis = System.currentTimeMillis() + obj.optLong("expires_in", 0) * 1000,
        accountEmail = if (obj.has("email")) obj.getString("email") else null,
    )
  }

  private fun randomToken(): String {
    val bytes = ByteArray(64)
    SecureRandom().nextBytes(bytes)
    return base64Url(bytes)
  }

  private fun sha256(bytes: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(bytes)

  private fun base64Url(bytes: ByteArray): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  private companion object {
    val TOKEN_ENDPOINT: HttpUrl = "https://api.dropbox.com/oauth2/token".toHttpUrl()
  }
}
