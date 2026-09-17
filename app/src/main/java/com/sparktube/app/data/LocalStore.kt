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

/**
 * Tiny JSON-in-SharedPreferences store for watch history and favorites.
 * No accounts needed: everything stays on the device.
 */
object LocalStore {

    private const val KEY_HISTORY = "history_json"
    private const val KEY_FAVORITES = "favorites_json"
    private const val MAX_HISTORY = 200
    private const val MAX_FAVORITES = 500

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
}
