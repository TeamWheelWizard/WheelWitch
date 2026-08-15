package com.skiletro.wheelwitch.network

import com.skiletro.wheelwitch.model.SemVersion
import com.skiletro.wheelwitch.util.net.HttpClientProvider
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber

/**
 * Fetches the newest WheelWitch APK release version from GitHub.
 *
 * CI publishes every `dev` push as a GitHub pre-release on the `ci` tag
 * (see `.github/workflows/build.yml`), so `/releases/latest` cannot be used
 * (it excludes pre-releases). The list endpoint with `per_page=1` returns the
 * newest release regardless of pre-release status, and the semver lives in the
 * release `name` (e.g. `WheelWitch 0.301.0 (abc1234)`), not the `ci` tag.
 */
object GitHubReleaseParser {
    /** Human-facing page for the newest WheelWitch APK. */
    const val RELEASES_PAGE_URL = "https://github.com/skiletro/WheelWitch/releases"

    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/skiletro/WheelWitch/releases?per_page=1"

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
     * Extracts the newest release version from a GitHub `/releases` JSON array.
     * Takes the first element, reads its `name`, and pulls the first
     * `M.m.p` triple. Returns null when the payload is malformed, empty, or the
     * release title carries no version.
     */
    internal fun parseLatestReleaseVersion(json: String): SemVersion? {
        val name = runCatching { JSONArray(json).optJSONObject(0)?.optString("name") }.getOrNull()
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
