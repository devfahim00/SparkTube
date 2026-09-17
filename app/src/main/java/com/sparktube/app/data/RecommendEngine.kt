package com.sparktube.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.ln
import kotlin.math.pow

/**
 * Lightweight on-device recommendation engine.
 *
 * It quietly collects usage signals — played videos and search queries — and
 * combines them with the local watch history, favorites and subscriptions
 * (all already stored by [LocalStore]) into two affinity profiles:
 *
 *  * keyword weights: what the user's content "is about" (title words,
 *    search words, subscription names, with recency decay)
 *  * channel weights: which uploaders the user prefers
 *
 * Nothing ever leaves the device. The home feed uses [snapshot] +
 * [score] to re-rank the region's trending blend and to mix in
 * "because you watched …" candidates taken from the related lists of
 * recently watched videos.
 */
object RecommendEngine {

    private const val PREFS = "sparktube_reco"
    private const val KEY_PLAYS = "plays_json"
    private const val KEY_GEN = "generation"

    private const val MAX_PLAYS = 60

    /** One logged play of a video (count = how many times it was played). */
    private data class Play(
        val url: String,
        val title: String,
        val uploader: String,
        val ts: Long,
        val count: Int
    )

    /**
     * Everything the ranking needs, computed once per feed load.
     */
    data class Snapshot(
        val keywordWeights: Map<String, Double>,
        val channelWeights: Map<String, Double>,
        /** Recently played / watched video urls (newest first). */
        val recentWatchedUrls: List<String>,
        /** Videos whose related lists feed the personalized section (max 4). */
        val relatedSources: List<String>,
        val hasSignal: Boolean
    )

    // ----- Signal collection -----

    /** Called by PlaybackCenter every time a video or song actually starts. */
    fun logPlay(context: Context, url: String, title: String, uploader: String) {
        if (url.isBlank() || url.startsWith("file://")) return
        val ctx = context.applicationContext
        val plays = readPlays(ctx).toMutableList()
        val existing = plays.indexOfFirst { it.url == url }
        val updated = if (existing >= 0) {
            val old = plays.removeAt(existing)
            old.copy(ts = now(), count = old.count + 1)
        } else {
            Play(url, title, uploader, now(), 1)
        }
        plays.add(0, updated)
        while (plays.size > MAX_PLAYS) plays.removeAt(plays.size - 1)
        writePlays(ctx, plays)
        bump(ctx)
    }

    /**
     * Called by SearchActivity for every executed search. The query itself is
     * stored by [LocalStore] as the user-facing search history; this only
     * bumps the profile generation so the home feed refreshes.
     */
    fun logSearch(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        LocalStore.addSearch(context, q)
        bump(context)
    }

    /** Wipes play signals (called when the user clears the watch history). */
    fun clearPlays(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit().remove(KEY_PLAYS).apply()
        bump(ctx)
    }

    /** Wipes search signals (called when the user clears the search history). */
    fun clearSearches(context: Context) {
        LocalStore.clearSearches(context)
        bump(context)
    }

    /** Cheap change counter so Home knows when the profile changed. */
    fun generation(context: Context): Int = prefs(context).getInt(KEY_GEN, 0)

    /** Public bump for restores (import backup) so the home feed refreshes. */
    fun bumpGeneration(context: Context) {
        bump(context)
    }

    private fun bump(context: Context) {
        prefs(context).edit().putInt(KEY_GEN, generation(context) + 1).apply()
    }

    // ----- Profile -----

    fun snapshot(context: Context): Snapshot {
        val ctx = context.applicationContext
        val keywords = HashMap<String, Double>()
        val channels = HashMap<String, Double>()

        fun boostKeywords(text: String?, weight: Double) {
            if (text.isNullOrBlank()) return
            tokenize(text).forEach { keywords[it] = (keywords[it] ?: 0.0) + weight }
        }

        fun boostChannel(uploader: String?, weight: Double) {
            val key = uploader?.trim()?.lowercase()
            if (key.isNullOrEmpty()) return
            channels[key] = (channels[key] ?: 0.0) + weight
        }

        // Searches: the strongest statement of intent, newest first with decay.
        LocalStore.searches(ctx).take(12).forEachIndexed { index, search ->
            tokenize(search).forEach {
                keywords[it] = (keywords[it] ?: 0.0) + 3.0 * decay(index)
            }
        }

        // Favorites: titles + uploaders the user explicitly saved.
        LocalStore.favorites(ctx).forEach { fav ->
            boostKeywords(fav.title, 2.5)
            boostChannel(fav.uploader, 2.0)
        }

        // Subscriptions: strongest channel signal.
        LocalStore.subscriptions(ctx).forEach { sub ->
            boostChannel(sub.name, 4.0)
            boostKeywords(sub.name, 1.5)
        }

        // Play log: counts and recency matter.
        val recentUrls = LinkedHashSet<String>()
        readPlays(ctx).forEach { play ->
            boostKeywords(play.title, 1.2 + 0.3 * ln(1.0 + play.count))
            boostChannel(play.uploader, 1.5)
            if (play.url.startsWith("http")) recentUrls.add(play.url)
        }

        // Watch history: a weaker, broader echo of the play log.
        LocalStore.history(ctx).forEach { entry ->
            boostKeywords(entry.title, 1.0)
            boostChannel(entry.uploader, 1.0)
            if (entry.url.startsWith("http")) recentUrls.add(entry.url)
        }

        val recent = recentUrls.toList()
        return Snapshot(
            keywordWeights = keywords,
            channelWeights = channels,
            recentWatchedUrls = recent,
            relatedSources = recent.take(4),
            hasSignal = keywords.isNotEmpty() || channels.isNotEmpty()
        )
    }

    /** How well a candidate matches the profile (higher = better). */
    fun score(item: StreamInfoItem, snap: Snapshot): Double {
        var score = 0.0
        val title = item.name.orEmpty()
        if (title.isNotBlank()) {
            tokenize(title).forEach { token -> score += snap.keywordWeights[token] ?: 0.0 }
        }
        item.uploaderName?.trim()?.lowercase()?.let { channel ->
            snap.channelWeights[channel]?.let { score += it }
        }
        return score
    }

    // ----- Internals -----

    private fun now(): Long = System.currentTimeMillis()

    private fun decay(index: Int): Double = 0.92.pow(index)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lower-cased words (any script) of length >= 3, without noise words. */
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.length >= 3 && it !in STOPWORDS }

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "you", "your", "from", "this", "that",
        "video", "videos", "official", "full", "hd", "new", "best", "top",
        "song", "songs", "music", "audio", "lyrics", "live", "part", "feat",
        "ft", "official", "video", "mv", "clip", "shorts", "trailer", "how",
        "what", "why", "when", "who", "are", "was", "has", "have", "not",
        "but", "can", "will", "one", "two", "all", "out", "get", "let",
        "episode", "ep", "season", "vs", "vol", "bonus", "online", "free",
        "watch", "subscribe", "channel", "like", "share"
    )

    private fun readPlays(context: Context): List<Play> {
        val raw = prefs(context).getString(KEY_PLAYS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Play(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    uploader = o.optString("uploader"),
                    ts = o.optLong("ts"),
                    count = o.optInt("count", 1)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writePlays(context: Context, plays: List<Play>) {
        val arr = JSONArray()
        plays.forEach { p ->
            arr.put(
                JSONObject()
                    .put("url", p.url)
                    .put("title", p.title)
                    .put("uploader", p.uploader)
                    .put("ts", p.ts)
                    .put("count", p.count)
            )
        }
        prefs(context).edit().putString(KEY_PLAYS, arr.toString()).apply()
    }
}
