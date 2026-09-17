package com.sparktube.app.data

import com.sparktube.app.util.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.kiosk.KioskInfo
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
