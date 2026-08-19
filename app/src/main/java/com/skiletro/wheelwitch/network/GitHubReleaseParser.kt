package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Fetches the newest WheelWitch APK release version from GitHub.
 *
 * Stable releases are published as non-prereleases, so GitHub's `/releases/latest`
 * endpoint returns the newest supported version. The semver lives in the release
 * `name` (e.g. `WheelWitch 1.0.0`), not the tag.
 */
object GitHubReleaseParser {
    /** Human-facing page for the newest WheelWitch APK. */
    const val RELEASES_PAGE_URL = "https://github.com/skiletro/WheelWitch/releases"

    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/skiletro/WheelWitch/releases/latest"

    /** Mirrors the version-extraction regex used by `obtainium.json`. */
    private val versionRegex = Regex("""(\d+\.\d+\.\d+)""")

    private val httpClient get() = HttpClientProvider.client

    /**
     * Fetches the newest published release version, or a failure result if the
     * request fails or no parseable release exists.
     */
    fun fetchLatestReleaseVersion(): Result<SemVersion> = runCatching {
        parseLatestReleaseVersion(fetchUrl(GITHUB_RELEASES_API_URL))
            ?: error("No published WheelWitch release found")
    }

    /**
     * Extracts the newest stable release version from a GitHub `/releases/latest`
     * JSON object. Reads its `name` and pulls the first `M.m.p` triple. Returns
     * null when the payload is malformed, empty, or the release title carries no
     * version.
     */
    internal fun parseLatestReleaseVersion(json: String): SemVersion? {
        val name = runCatching { JSONObject(json).optString("name") }.getOrNull()
        val match = name?.let { versionRegex.find(it) } ?: return null
        return SemVersion.parse(match.groupValues[1])
    }

    /** Blocking HTTP GET. Throws on non-2xx. */
    private fun fetchUrl(urlString: String): String {
        val request = Request.Builder().url(urlString).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                Timber.tag("Network").w("%s returned %d", urlString, response.code)
                error("$urlString returned ${response.code}: $body")
            }
            return body
        }
    }
}
