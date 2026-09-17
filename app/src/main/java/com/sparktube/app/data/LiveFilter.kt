package com.sparktube.app.data

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * SparkTube never shows live streams anywhere: not in feeds, not in search
 * results, not in the player. Ended-live (post live) content is hidden too.
 */
object LiveFilter {

    fun isLiveType(type: StreamType?): Boolean = type == StreamType.LIVE_STREAM
        || type == StreamType.AUDIO_LIVE_STREAM
        || type == StreamType.POST_LIVE_STREAM
        || type == StreamType.POST_LIVE_AUDIO_STREAM

    fun isLive(item: StreamInfoItem): Boolean = isLiveType(item.streamType)

    fun isLive(info: StreamInfo): Boolean = isLiveType(info.streamType)

    /** Keeps only normal (non-live) stream items. */
    fun sanitize(items: List<InfoItem>): List<StreamInfoItem> =
        items.filterIsInstance<StreamInfoItem>()
            .filter { !isLive(it) }
}
