package com.sparktube.app.util

import android.content.Context
import android.net.Uri
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Exports / imports the user's local data: watch + music history, favorites
 * (with their music flag), search history, subscriptions, the recommendation
 * play log and all app settings — everything needed so a fresh install picks
 * up exactly where the old one left off. Download file records are skipped
 * (their files cannot move between devices).
 */
object BackupManager {

    private const val LOCAL_PREFS = "sparktube_local"
    private const val RECO_PREFS = "sparktube_reco"
    private const val APP_PREFS = "sparktube_prefs"

    private val EXPORTED_LOCAL_KEYS = listOf(
        "history_json",
        "music_history_json",
        "favorites_json",
        "subscriptions_json",
        "searches_json",
        "music_searches_json"
    )

    suspend fun exportTo(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
                .put("app", "SparkTube")
                .put("version", 1)
                .put("exportedAt", System.currentTimeMillis())

            val local = JSONObject()
            val localSp = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
            EXPORTED_LOCAL_KEYS.forEach { key ->
                localSp.getString(key, null)?.let { local.put(key, it) }
            }
            root.put("local", local)

            val reco = JSONObject()
            context.getSharedPreferences(RECO_PREFS, Context.MODE_PRIVATE)
                .getString("plays_json", null)?.let { reco.put("plays_json", it) }
            root.put("reco", reco)

            val prefs = JSONObject()
            context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                when (v) {
                    is String -> prefs.put(k, v)
                    is Boolean -> prefs.put(k, v)
                    is Int -> prefs.put(k, v)
                    is Long -> prefs.put(k, v)
                    is Float -> prefs.put(k, v.toDouble())
                }
            }
            root.put("prefs", prefs)

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun importFrom(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            } ?: return@withContext false
            val root = JSONObject(text)

            // ---- lists: merge (existing first, then imported, dedup by url) ----
            val localSp = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
            mergeEntries(localSp, "history_json", root.optJSONObject("local")?.optString("history_json"))
            mergeEntries(localSp, "music_history_json", root.optJSONObject("local")?.optString("music_history_json"))
            mergeEntries(localSp, "favorites_json", root.optJSONObject("local")?.optString("favorites_json"))
            mergeChannels(localSp, root.optJSONObject("local")?.optString("subscriptions_json"))
            mergeSearches(localSp, "searches_json", root.optJSONObject("local")?.optString("searches_json"))
            mergeSearches(localSp, "music_searches_json", root.optJSONObject("local")?.optString("music_searches_json"))

            // ---- recommendation play log: merge by url, keep the larger count ----
            root.optJSONObject("reco")?.optString("plays_json")?.takeIf { it.isNotBlank() }?.let { raw ->
                val imported = runCatching { JSONArray(raw) }.getOrNull()
                if (imported != null) {
                    val recoSp = context.getSharedPreferences(RECO_PREFS, Context.MODE_PRIVATE)
                    val current = runCatching {
                        JSONArray(recoSp.getString("plays_json", "[]"))
                    }.getOrNull() ?: JSONArray()
                    val byUrl = LinkedHashMap<String, JSONObject>()
                    for (i in 0 until current.length()) {
                        val o = current.getJSONObject(i)
                        byUrl[o.optString("url")] = o
                    }
                    for (i in 0 until imported.length()) {
                        val o = imported.getJSONObject(i)
                        val url = o.optString("url")
                        val existing = byUrl[url]
                        if (existing == null || o.optInt("count", 1) > existing.optInt("count", 1)) {
                            byUrl[url] = o
                        }
                    }
                    val merged = JSONArray()
                    var n = 0
                    byUrl.values.forEach { o ->
                        if (n++ < 60) merged.put(o)
                    }
                    recoSp.edit().putString("plays_json", merged.toString()).apply()
                }
            }

            // ---- settings: fill only what the backup carries ----
            root.optJSONObject("prefs")?.let { prefs ->
                val sp = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                val editor = sp.edit()
                prefs.keys().forEach { key ->
                    when (val v = prefs.get(key)) {
                        is String -> editor.putString(key, v)
                        is Boolean -> editor.putBoolean(key, v)
                        is Int -> editor.putInt(key, v)
                        is Long -> editor.putLong(key, v)
                        is Double -> editor.putFloat(key, v.toFloat())
                    }
                }
                editor.apply()
            }

            // Let the home feed know the profile changed.
            RecommendEngine.bumpGeneration(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun mergeEntries(sp: android.content.SharedPreferences, key: String, importedRaw: String?) {
        if (importedRaw.isNullOrBlank()) return
        val imported = runCatching { JSONArray(importedRaw) }.getOrNull() ?: return
        val current = runCatching { JSONArray(sp.getString(key, "[]")) }.getOrNull() ?: JSONArray()
        val seen = HashSet<String>()
        val merged = JSONArray()
        for (i in 0 until current.length()) {
            val o = current.getJSONObject(i)
            if (seen.add(o.optString("url"))) merged.put(o)
        }
        for (i in 0 until imported.length()) {
            val o = imported.getJSONObject(i)
            if (seen.add(o.optString("url"))) merged.put(o)
        }
        sp.edit().putString(key, merged.toString()).apply()
    }

    private fun mergeChannels(sp: android.content.SharedPreferences, importedRaw: String?) {
        if (importedRaw.isNullOrBlank()) return
        val imported = runCatching { JSONArray(importedRaw) }.getOrNull() ?: return
        val current = runCatching {
            JSONArray(sp.getString("subscriptions_json", "[]"))
        }.getOrNull() ?: JSONArray()
        val seen = HashSet<String>()
        val merged = JSONArray()
        for (i in 0 until current.length()) {
            val o = current.getJSONObject(i)
            if (seen.add(o.optString("url"))) merged.put(o)
        }
        for (i in 0 until imported.length()) {
            val o = imported.getJSONObject(i)
            if (seen.add(o.optString("url"))) merged.put(o)
        }
        sp.edit().putString("subscriptions_json", merged.toString()).apply()
    }

    private fun mergeSearches(sp: android.content.SharedPreferences, key: String, importedRaw: String?) {
        if (importedRaw.isNullOrBlank()) return
        val imported = runCatching { JSONArray(importedRaw) }.getOrNull() ?: return
        val current = runCatching {
            JSONArray(sp.getString(key, "[]"))
        }.getOrNull() ?: JSONArray()
        val seen = HashSet<String>()
        val merged = JSONArray()
        for (i in 0 until current.length()) {
            val s = current.optString(i)
            if (seen.add(s.lowercase())) merged.put(s)
        }
        for (i in 0 until imported.length()) {
            val s = imported.optString(i)
            if (seen.add(s.lowercase())) merged.put(s)
        }
        // Trim to the store's cap (newest first).
        val trimmed = JSONArray()
        for (i in 0 until minOf(merged.length(), 20)) trimmed.put(merged.get(i))
        sp.edit().putString(key, trimmed.toString()).apply()
    }
}
