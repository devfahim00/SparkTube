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

    /** Personalized picks pinned to the top of the home feed (user-usage based). */
    private const val PERSONALIZED_SLOTS = 6

    /** Max items per channel in the feed (variety guard). */
    private const val MAX_PER_CHANNEL = 3

    /** Budget for the parallel related-list fetches, so the feed stays snappy. */
    private const val RELATED_FETCH_TIMEOUT_MS = 10_000L

    /**
     * Personalized home feed:
     *  1. the first [PERSONALIZED_SLOTS] rows are chosen purely from the
     *     user's own usage ("because you watched …" candidates, topped up
     *     with the best profile-matching trending items);
     *  2. everything after them is a randomized discovery zone — the
     *     region's trending mix, shuffled on every load, with a per-channel
     *     cap so no single channel floods the list.
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

        // 2. Personalized zone: exactly PERSONALIZED_SLOTS rows driven by the
        //    user's profile — best related picks first, then the strongest
        //    profile-matching trending items fill any empty slots.
        val personalized = mutableListOf<StreamInfoItem>()
        val pickedUrls = HashSet<String>()
        candidates
            .map { it to RecommendEngine.score(it, snap) }
            .sortedByDescending { (_, score) -> score }
            .take(PERSONALIZED_SLOTS)
            .forEach { (item, _) ->
                pickedUrls.add(item.url)
                personalized.add(item)
            }
        if (personalized.size < PERSONALIZED_SLOTS) {
            trending
                .filter { it.url !in pickedUrls }
                .sortedByDescending { RecommendEngine.score(it, snap) }
                .take(PERSONALIZED_SLOTS - personalized.size)
                .forEach { item ->
                    pickedUrls.add(item.url)
                    personalized.add(item)
                }
        }

        // 3. Random zone: everything else, shuffled fresh on every load,
        //    with the per-channel variety cap applied first.
        val rest = trending.filter { it.url !in pickedUrls }
        val perChannel = HashMap<String, Int>()
        val cappedRest = rest.filter { item ->
            val key = item.uploaderName?.trim()?.lowercase().orEmpty()
                .ifEmpty { item.url }
            val count = perChannel.getOrDefault(key, 0)
            if (count >= MAX_PER_CHANNEL) {
                false
            } else {
                perChannel[key] = count + 1
                true
            }
        }.shuffled()

        personalized + cappedRest
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

    /** Full stream details for playback. */
    suspend fun streamInfo(url: String): StreamInfo =
        withContext(Dispatchers.IO) {
            StreamInfo.getInfo(service, url)
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
}
