package com.sparktube.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VideoEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long
)

data class ChannelEntry(
    val url: String,
    val name: String,
    val avatarUrl: String,
    val subscriberCount: Long
)

/** A finished or in-progress download tracked by the app. */
data class DownloadRecord(
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    /** AUDIO | VIDEO | AV */
    val type: String,
    val quality: String,
    val status: String,
    /** Local file paths (one for AUDIO/VIDEO, two for AV pairs). */
    val filePaths: List<String>,
    val downloadIds: List<Long>
)

/**
 * Tiny JSON-in-SharedPreferences store for watch history, favorites,
 * subscriptions and downloads. No accounts needed: everything stays on
 * the device.
 */
object LocalStore {

    private const val KEY_HISTORY = "history_json"
    private const val KEY_FAVORITES = "favorites_json"
    private const val KEY_SUBSCRIPTIONS = "subscriptions_json"
    private const val KEY_DOWNLOADS = "downloads_json"
    private const val KEY_SEARCHES = "searches_json"
    private const val MAX_HISTORY = 200
    private const val MAX_FAVORITES = 500
    private const val MAX_SUBSCRIPTIONS = 200
    private const val MAX_SEARCHES = 20

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("sparktube_local", Context.MODE_PRIVATE)

    // ----- History -----

    fun history(context: Context): List<VideoEntry> =
        read(prefs(context), KEY_HISTORY)

    fun addToHistory(context: Context, entry: VideoEntry) {
        val ctx = context.applicationContext
        val list = history(ctx).toMutableList()
        list.removeAll { it.url == entry.url }
        list.add(0, entry)
        while (list.size > MAX_HISTORY) {
            list.removeAt(list.size - 1)
        }
        write(prefs(ctx), KEY_HISTORY, list)
    }

    fun removeFromHistory(context: Context, url: String) {
        val ctx = context.applicationContext
        val list = history(ctx).toMutableList()
        list.removeAll { it.url == url }
        write(prefs(ctx), KEY_HISTORY, list)
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    // ----- Search history -----

    /** Recent search queries, newest first (case preserved, deduplicated). */
    fun searches(context: Context): List<String> =
        readSearches(prefs(context), KEY_SEARCHES)

    fun addSearch(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val ctx = context.applicationContext
        val list = readSearches(ctx, KEY_SEARCHES).toMutableList()
        list.removeAll { it.equals(q, ignoreCase = true) }
        list.add(0, q)
        while (list.size > MAX_SEARCHES) {
            list.removeAt(list.size - 1)
        }
        prefs(ctx).edit().putString(KEY_SEARCHES, JSONArray(list).toString()).apply()
    }

    fun removeSearch(context: Context, query: String) {
        val ctx = context.applicationContext
        val list = readSearches(ctx, KEY_SEARCHES).toMutableList()
        list.removeAll { it == query }
        prefs(ctx).edit().putString(KEY_SEARCHES, JSONArray(list).toString()).apply()
    }

    fun clearSearches(context: Context) {
        prefs(context).edit().remove(KEY_SEARCHES).apply()
    }

    // ----- Favorites -----

    fun favorites(context: Context): List<VideoEntry> =
        read(prefs(context), KEY_FAVORITES)

    fun isFavorite(context: Context, url: String): Boolean =
        favorites(context).any { it.url == url }

    /** @return the new favorite state for the entry. */
    fun toggleFavorite(context: Context, entry: VideoEntry): Boolean {
        val ctx = context.applicationContext
        val list = favorites(ctx).toMutableList()
        val exists = list.any { it.url == entry.url }
        if (exists) {
            list.removeAll { it.url == entry.url }
            write(prefs(ctx), KEY_FAVORITES, list)
            return false
        }
        list.add(0, entry)
        while (list.size > MAX_FAVORITES) {
            list.removeAt(list.size - 1)
        }
        write(prefs(ctx), KEY_FAVORITES, list)
        return true
    }

    fun clearFavorites(context: Context) {
        prefs(context).edit().remove(KEY_FAVORITES).apply()
    }

    // ----- Subscriptions -----

    fun subscriptions(context: Context): List<ChannelEntry> =
        readChannels(prefs(context), KEY_SUBSCRIPTIONS)

    fun isSubscribed(context: Context, channelUrl: String): Boolean =
        subscriptions(context).any { it.url == channelUrl }

    /** @return the new subscribed state for the channel. */
    fun toggleSubscription(context: Context, entry: ChannelEntry): Boolean {
        val ctx = context.applicationContext
        val list = subscriptions(ctx).toMutableList()
        val exists = list.any { it.url == entry.url }
        if (exists) {
            list.removeAll { it.url == entry.url }
            writeChannels(prefs(ctx), KEY_SUBSCRIPTIONS, list)
            return false
        }
        list.add(0, entry)
        writeChannels(prefs(ctx), KEY_SUBSCRIPTIONS, list)
        return true
    }

    // ----- Downloads registry -----

    fun downloads(context: Context): List<DownloadRecord> =
        readDownloads(prefs(context))

    fun addDownload(context: Context, record: DownloadRecord) {
        val ctx = context.applicationContext
        val list = downloads(ctx).toMutableList()
        list.removeAll { it.videoId == record.videoId && it.type == record.type && it.quality == record.quality }
        list.add(0, record)
        writeDownloads(prefs(ctx), list)
    }

    fun updateDownloadStatus(context: Context, downloadId: Long, status: String) {
        val ctx = context.applicationContext
        val list = downloads(ctx).toMutableList()
        val idx = list.indexOfFirst { record -> record.downloadIds.contains(downloadId) }
        if (idx >= 0) {
            val updated = list[idx].copy(status = status)
            list[idx] = updated
            writeDownloads(prefs(ctx), list)
        }
    }

    fun removeDownload(context: Context, record: DownloadRecord) {
        val ctx = context.applicationContext
        val list = downloads(ctx).toMutableList()
        list.removeAll { it.videoId == record.videoId && it.type == record.type && it.quality == record.quality }
        writeDownloads(prefs(ctx), list)
    }

    // ----- JSON helpers -----

    private fun read(sp: android.content.SharedPreferences, key: String): List<VideoEntry> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                VideoEntry(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    uploader = o.optString("uploader"),
                    thumbnailUrl = o.optString("thumb"),
                    durationSec = o.optLong("duration")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(sp: android.content.SharedPreferences, key: String, list: List<VideoEntry>) {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("uploader", entry.uploader)
                    .put("thumb", entry.thumbnailUrl)
                    .put("duration", entry.durationSec)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    // ----- Channel JSON helpers -----

    private fun readChannels(sp: android.content.SharedPreferences, key: String): List<ChannelEntry> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.getJSONObject(idx)
                ChannelEntry(
                    url = o.optString("url"),
                    name = o.optString("name"),
                    avatarUrl = o.optString("avatar"),
                    subscriberCount = o.optLong("subs")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeChannels(sp: android.content.SharedPreferences, key: String, list: List<ChannelEntry>) {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("name", entry.name)
                    .put("avatar", entry.avatarUrl)
                    .put("subs", entry.subscriberCount)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun readSearches(sp: android.content.SharedPreferences, key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx -> arr.optString(idx) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ----- Download JSON helpers -----

    private fun readDownloads(sp: android.content.SharedPreferences): List<DownloadRecord> {
        val raw = sp.getString(KEY_DOWNLOADS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.getJSONObject(idx)
                DownloadRecord(
                    videoId = o.optString("videoId"),
                    title = o.optString("title"),
                    uploader = o.optString("uploader"),
                    thumbnailUrl = o.optString("thumb"),
                    type = o.optString("type"),
                    quality = o.optString("quality"),
                    status = o.optString("status"),
                    filePaths = o.optJSONArray("files")?.let { files ->
                        (0 until files.length()).map { files.optString(it) }
                    } ?: emptyList(),
                    downloadIds = o.optJSONArray("ids")?.let { ids ->
                        (0 until ids.length()).map { ids.optLong(it) }
                    } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeDownloads(sp: android.content.SharedPreferences, list: List<DownloadRecord>) {
        val arr = JSONArray()
        list.forEach { record ->
            arr.put(
                JSONObject()
                    .put("videoId", record.videoId)
                    .put("title", record.title)
                    .put("uploader", record.uploader)
                    .put("thumb", record.thumbnailUrl)
                    .put("type", record.type)
                    .put("quality", record.quality)
                    .put("status", record.status)
                    .put("files", JSONArray(record.filePaths))
                    .put("ids", JSONArray(record.downloadIds))
            )
        }
        sp.edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }
}
