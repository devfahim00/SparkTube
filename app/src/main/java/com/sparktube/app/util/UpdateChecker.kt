package com.sparktube.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub release checker: compares the running version against
 * devfahim00/SparkTube's latest release and surfaces title + changelog.
 */
object UpdateChecker {

    data class Release(
        val tag: String,
        val name: String,
        val body: String,
        val apkUrl: String?,
        val htmlUrl: String
    )

    private const val LATEST_URL =
        "https://api.github.com/repos/devfahim00/SparkTube/releases/latest"

    /** Once per process: the silent on-open check must not nag repeatedly. */
    @Volatile
    var silentCheckDone = false

    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(text)
            val tag = json.optString("tag_name", "").ifBlank { return@withContext null }
            val apk = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
            }
            Release(
                tag = tag,
                name = json.optString("name").ifBlank { tag },
                body = json.optString("body"),
                apkUrl = apk,
                htmlUrl = json.optString(
                    "html_url",
                    "https://github.com/devfahim00/SparkTube/releases"
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    /** "v2.1.0" / "2.1" vs "2.0.0" — numeric part comparison, longer wins ties. */
    fun isNewer(current: String, tag: String): Boolean {
        val cur = current.removePrefix("v").removePrefix("V")
        val tg = tag.removePrefix("v").removePrefix("V")
        val curParts = cur.split('.').map { it.toIntOrNull() ?: 0 }
        val tgParts = tg.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(curParts.size, tgParts.size)
        for (i in 0 until n) {
            val c = curParts.getOrElse(i) { 0 }
            val t = tgParts.getOrElse(i) { 0 }
            if (t != c) return t > c
        }
        return false
    }

    /** Opens the release APK (or the release page) in the browser. */
    fun openDownload(context: Context, release: Release) {
        val url = release.apkUrl ?: release.htmlUrl
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
