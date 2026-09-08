package com.skiletro.wheelwitch.util.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Returns true when the device has an active network with internet capability. */
fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Blocking HTTP GET returning the response body as a string.
 * Throws on non-2xx responses or an empty/missing body. Used by the
 * metadata-style fetchers (RWFC endpoints, GitHub releases).
 */
fun fetchUrl(urlString: String, client: OkHttpClient = HttpClientProvider.client): String {
    val request = Request.Builder().url(urlString).build()
    client.newCall(request).execute().use { response ->
        val body = response.body?.string() ?: error("Empty response from $urlString")
        if (!response.isSuccessful) {
            Timber.tag(NETWORK_TAG).w("%s returned %d", urlString, response.code)
            error("$urlString returned ${response.code}: $body")
        }
        return body
    }
}

const val NETWORK_TAG = "Network"
