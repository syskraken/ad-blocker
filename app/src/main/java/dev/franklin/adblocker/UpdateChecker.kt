package dev.franklin.adblocker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer release has been published.
 *
 * Apps installed outside Play get no automatic updates, so this is the only way
 * a user finds out a new build exists. It only ever *reports* — installing an
 * APK from inside the app would need REQUEST_INSTALL_PACKAGES, so the download
 * is handed to the browser instead.
 */
object UpdateChecker {

    // Tied to the repository name; renaming the repo breaks this URL.
    private const val LATEST_RELEASE =
        "https://api.github.com/repos/syskraken/ad-blocker/releases/latest"

    class Release(val version: String, val pageUrl: String, val apkUrl: String?)

    sealed class Result {
        object UpToDate : Result()
        class Available(val release: Release) : Result()

        /** The endpoint 404s until a release is published — drafts do not count. */
        object NoReleases : Result()
        class Failed(val reason: String) : Result()
    }

    /** Blocking network call. */
    fun check(currentVersion: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TripoleFlux (Android)")
            }

            when (val code = connection.responseCode) {
                404 -> Result.NoReleases
                in 200..299 -> parse(connection.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                403 -> Result.Failed("rate limited by GitHub, try later")
                else -> Result.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(body: String, currentVersion: String): Result {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
            ?: return Result.Failed("release has no tag")

        var apkUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
        }

        val release = Release(
            version = normalise(tag),
            pageUrl = json.optString("html_url"),
            apkUrl = apkUrl,
        )

        return if (compareVersions(release.version, currentVersion) > 0) {
            Result.Available(release)
        } else {
            Result.UpToDate
        }
    }

    /**
     * Compares dotted versions numerically, so 1.10 correctly beats 1.9 where a
     * string comparison would not. A release suffix marks a pre-release, making
     * 1.0 newer than 1.0-beta. Returns >0 when [a] is newer than [b].
     */
    fun compareVersions(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)

        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }

        val suffixA = suffix(a)
        val suffixB = suffix(b)
        return when {
            suffixA.isEmpty() && suffixB.isNotEmpty() -> 1
            suffixA.isNotEmpty() && suffixB.isEmpty() -> -1
            else -> suffixA.compareTo(suffixB)
        }
    }

    private fun normalise(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    private fun numbers(version: String): List<Int> =
        normalise(version)
            .substringBefore('-')
            .split('.')
            .map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun suffix(version: String): String {
        val normalised = normalise(version)
        val dash = normalised.indexOf('-')
        return if (dash >= 0) normalised.substring(dash + 1) else ""
    }
}
