package com.sparktube.app.data

import com.sparktube.app.util.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

data class PageResult(
    val items: List<StreamInfoItem>,
    val nextPage: Page?
)

data class ChannelPageResult(
    val items: List<ChannelEntry>,
    val nextPage: Page?
)

data class ChannelUi(
    val url: String,
    val name: String,
    val avatarUrl: String,
    val subscriberCount: Long,
    val description: String
)

/**
 * All YouTube access goes through this repository.
 * Every list returned here is already filtered: live streams never make it out.
 */
object YtRepository {

    private val service: StreamingService get() = ServiceList.YouTube

    /**
     * Short-lived cache for [streamInfo]: the watch page, related-fetch and
     * playback resolve paths can all ask for the same URL within seconds of
     * each other (e.g. opening a video re-resolves it right after the home
     * feed's related fetch already did). A 3-minute TTL cuts that duplicate
     * extraction work while staying well clear of googlevideo's signed
     * playback URLs going stale (they're valid for hours, not minutes), so
     * cached entries are never served after their underlying stream links
     * could plausibly have expired.
     */
    private const val STREAM_INFO_CACHE_TTL_MS = 3 * 60 * 1000L
    private const val STREAM_INFO_CACHE_MAX = 30
    private data class CachedStreamInfo(val info: StreamInfo, val atMs: Long)
    private val streamInfoCache =
        object : LinkedHashMap<String, CachedStreamInfo>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStreamInfo>?): Boolean =
                size > STREAM_INFO_CACHE_MAX
        }

    /**
     * YouTube removed the classic global trending feed in 2025, but the
     * region-aware trending surfaces still exist. The home feed blends the
     * country-based trending kiosks (trailers, popular episodes, gaming) into
     * one region-aware suggestion list.
     */
    private val HOME_KIOSKS = listOf(
        "trending_movies_and_shows",
        "trending_podcasts_episodes",
        "trending_gaming"
    )

    /** Applies the country selection globally so feeds/search follow it. */
    suspend fun applyCountry(countryCode: String) = withContext(Dispatchers.IO) {
        NewPipe.setPreferredContentCountry(ContentCountry(countryCode))
    }

    /** Country based suggestion feed used on the Home tab. */
    suspend fun trending(countryCode: String): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(countryCode))

            var lastError: Exception? = null
            val lists = HOME_KIOSKS.mapNotNull { kioskId ->
                try {
                    fetchKiosk(kioskId)
                } catch (e: Exception) {
                    lastError = e
                    emptyList()
                }
            }.filter { it.isNotEmpty() }

            if (lists.isEmpty()) {
                throw lastError ?: IOException("No content available for this region")
            }

            // Round-robin blend so the feed feels varied and general.
            val blended = mutableListOf<StreamInfoItem>()
            val seen = mutableSetOf<String>()
            val maxSize = lists.maxOf { it.size }
            for (index in 0 until maxSize) {
                for (list in lists) {
                    if (index < list.size && list[index].url !in seen) {
                        seen.add(list[index].url)
                        blended.add(list[index])
                    }
                }
            }
            blended
        }

    /** Max items per channel at the head of the feed (variety guard). */
    private const val MAX_PER_CHANNEL = 3

    /** Budget for the parallel related-list fetches, so the feed stays snappy. */
    private const val RELATED_FETCH_TIMEOUT_MS = 10_000L

    /**
     * Fully personalized home feed: EVERY row is ranked by the user's own
     * usage profile (plays, searches, favorites, subscriptions, downloads).
     *
     *  1. the pool is the region's trending blend plus "because you watched"
     *     related candidates pulled from the recently watched videos;
     *  2. recently watched videos themselves are excluded (already seen);
     *  3. the whole pool is scored with [RecommendEngine.score] and sorted
     *     best-match first — there is no random zone any more;
     *  4. a soft per-channel cap keeps one channel from flooding the top:
     *     overflow items spill to the tail in score order instead of being
     *     dropped, so no video is lost.
     *
     * Fresh installs (no signals) fall back to the plain trending blend.
     */
    suspend fun personalizedFeed(
        countryCode: String,
        snap: RecommendEngine.Snapshot
    ): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        val trending = trending(countryCode)
        if (!snap.hasSignal) {
            return@withContext trending
        }

        // 1. Related-of-recent candidates, deduped against trending + watched.
        val seen = trending.map { it.url }.toHashSet()
        val watched = snap.recentWatchedUrls.toHashSet()
        val candidates = mutableListOf<StreamInfoItem>()
        if (snap.relatedSources.isNotEmpty()) {
            withTimeoutOrNull(RELATED_FETCH_TIMEOUT_MS) {
                coroutineScope {
                    snap.relatedSources.map { sourceUrl ->
                        async { runCatching { related(sourceUrl) }.getOrDefault(emptyList()) }
                    }.awaitAll()
                }
            }?.forEach { list ->
                list.forEach { item ->
                    if (item.url !in seen && item.url !in watched) {
                        seen.add(item.url)
                        candidates.add(item)
                    }
                }
            }
        }

        // 2. One ranked pool: related candidates + trending, minus anything
        //    the user just watched, all scored by the usage profile.
        val pool = (candidates + trending).filter { it.url !in watched }
        if (pool.isEmpty()) {
            // Extremely heavy viewer: everything is excluded. Show the plain
            // blend rather than an empty feed.
            return@withContext trending
        }
        val ranked = pool
            .map { it to RecommendEngine.score(it, snap) }
            .sortedByDescending { (_, score) -> score }

        // 3. Soft per-channel cap: the first MAX_PER_CHANNEL items of any
        //    channel stay in score order at the head; the rest keep their
        //    score order but spill to the tail.
        val perChannel = HashMap<String, Int>()
        val head = mutableListOf<StreamInfoItem>()
        val tail = mutableListOf<StreamInfoItem>()
        ranked.forEach { (item, _) ->
            val key = item.uploaderName?.trim()?.lowercase().orEmpty()
                .ifEmpty { item.url }
            val count = perChannel.getOrDefault(key, 0)
            if (count >= MAX_PER_CHANNEL) {
                tail.add(item)
            } else {
                perChannel[key] = count + 1
                head.add(item)
            }
        }

        head + tail
    }

    /**
     * Trending music: uses YouTube Charts when the selected country supports
     * it, otherwise falls back to a music search for that region.
     */
    suspend fun musicTrending(countryCode: String): PageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(countryCode))
            try {
                val info = KioskInfo.getInfo(
                    service, "https://charts.youtube.com/charts/TrendingVideos"
                )
                PageResult(LiveFilter.sanitize(info.relatedItems), null)
            } catch (e: Exception) {
                val handler = service.searchQHFactory.fromQuery(
                    "top songs",
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    ""
                )
                executeSearch(handler, null)
            }
        }

    /** Video search. Live results are stripped before they reach the UI. */
    suspend fun searchVideos(query: String, page: Page? = null): PageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(AppPrefs.countryOrDefault))
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                ""
            )
            executeSearch(handler, page)
        }

    /** YouTube Music songs search used by genre chips on the Music tab. */
    suspend fun searchMusic(query: String, page: Page? = null): PageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(AppPrefs.countryOrDefault))
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                ""
            )
            executeSearch(handler, page)
        }

    /** Channel-only search for the Channels tab of the search screen. */
    suspend fun searchChannels(query: String, page: Page? = null): ChannelPageResult =
        withContext(Dispatchers.IO) {
            NewPipe.setPreferredContentCountry(ContentCountry(AppPrefs.countryOrDefault))
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.CHANNELS),
                ""
            )
            executeChannelSearch(handler, page)
        }

    /** Full stream details for playback. */
    suspend fun streamInfo(url: String): StreamInfo =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            synchronized(streamInfoCache) {
                streamInfoCache[url]
            }?.let { cached ->
                if (now - cached.atMs < STREAM_INFO_CACHE_TTL_MS) return@withContext cached.info
            }
            val info = StreamInfo.getInfo(service, url)
            synchronized(streamInfoCache) {
                streamInfoCache[url] = CachedStreamInfo(info, now)
            }
            info
        }

    /** Search keyword suggestions shown while the user types. */
    suspend fun suggestions(query: String): List<String> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                emptyList()
            } else {
                runCatching {
                    service.suggestionExtractor.suggestionList(query)
                }.getOrDefault(emptyList())
            }
        }

    /** Channel header: name, avatar, subscriber count. */
    suspend fun channelInfo(url: String): ChannelUi =
        withContext(Dispatchers.IO) {
            val info = ChannelInfo.getInfo(service, url)
            ChannelUi(
                url = info.url ?: url,
                name = info.name ?: "",
                avatarUrl = info.avatars.maxByOrNull { it.height }?.url.orEmpty(),
                subscriberCount = info.subscriberCount,
                description = info.description.orEmpty()
            )
        }

    /** First page of a channel's videos tab (live filtered out). */
    suspend fun channelVideos(channelUrl: String): Pair<List<StreamInfoItem>, Page?> =
        withContext(Dispatchers.IO) {
            val info = ChannelInfo.getInfo(service, channelUrl)
            val tab = info.tabs.firstOrNull { handler ->
                handler.contentFilters.firstOrNull() == ChannelTabs.VIDEOS
            } ?: return@withContext emptyList<StreamInfoItem>() to null
            val page = ChannelTabInfo.getInfo(service, tab)
            LiveFilter.sanitize(page.relatedItems) to page.nextPage
        }

    /** Next page of a channel's videos tab. */
    suspend fun channelVideosMore(
        channelUrl: String,
        page: Page
    ): Pair<List<StreamInfoItem>, Page?> =
        withContext(Dispatchers.IO) {
            val info = ChannelInfo.getInfo(service, channelUrl)
            val tab = info.tabs.firstOrNull { handler ->
                handler.contentFilters.firstOrNull() == ChannelTabs.VIDEOS
            } ?: return@withContext emptyList<StreamInfoItem>() to null
            val result = ChannelTabInfo.getMoreItems(service, tab, page)
            LiveFilter.sanitize(result.items) to result.nextPage
        }

    /** Related videos for a stream (used for the watch page + radio autoplay). */
    suspend fun related(url: String): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            runCatching { streamInfo(url).relatedItems }
                .getOrDefault(emptyList())
                .let { LiveFilter.sanitize(it) }
        }

    private fun fetchKiosk(kioskId: String): List<StreamInfoItem> {
        val extractor = service.kioskList.getExtractorById(kioskId, null)
        extractor.fetchPage()
        val items = extractor.initialPage.items
        return LiveFilter.sanitize(items.filterIsInstance<InfoItem>())
    }

    private fun executeSearch(
        handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler,
        page: Page?
    ): PageResult {
        return if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            PageResult(LiveFilter.sanitize(info.relatedItems), info.nextPage)
        } else {
            val result = SearchInfo.getMoreItems(service, handler, page)
            PageResult(LiveFilter.sanitize(result.items), result.nextPage)
        }
    }

    private fun executeChannelSearch(
        handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler,
        page: Page?
    ): ChannelPageResult {
        val raw: List<InfoItem>
        val nextPage: Page?
        if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            raw = info.relatedItems
            nextPage = info.nextPage
        } else {
            val result = SearchInfo.getMoreItems(service, handler, page)
            raw = result.items
            nextPage = result.nextPage
        }
        val seen = HashSet<String>()
        val channels = raw.filterIsInstance<ChannelInfoItem>()
            .filter { !it.url.isNullOrBlank() && seen.add(it.url!!) }
            .map { item ->
                ChannelEntry(
                    url = item.url.orEmpty(),
                    name = item.name.orEmpty(),
                    avatarUrl = item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                    subscriberCount = item.subscriberCount
                )
            }
        return ChannelPageResult(channels, nextPage)
    }
}
