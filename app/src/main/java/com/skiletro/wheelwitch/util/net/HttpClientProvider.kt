package com.skiletro.wheelwitch.util.net

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP clients with a 15s connect timeout and 15s ([client]) or 60s
 * ([largeDownloadClient]) read timeout. [probeClient] uses 3s connect/read timeouts without
 * redirects. Dispatcher limits govern asynchronous calls, not blocking `execute()` calls.
 */
object HttpClientProvider {
    val client: OkHttpClient by lazy { buildClient(readTimeoutSeconds = 15) }

    val largeDownloadClient: OkHttpClient by lazy { buildClient(readTimeoutSeconds = 60) }

    val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private fun buildClient(readTimeoutSeconds: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MAX_REQUESTS
                    maxRequestsPerHost = MAX_REQUESTS_PER_HOST
                }
            )
            .build()

    private const val MAX_REQUESTS: Int = 16
    private const val MAX_REQUESTS_PER_HOST: Int = 8
    private const val PROBE_TIMEOUT_SECONDS: Long = 3
}
