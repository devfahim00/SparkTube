package com.sparktube.app.ui.common

import com.sparktube.app.data.VideoEntry
import com.sparktube.app.util.Formatters
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * One flat UI model so feeds, search results, history and favorites can all
 * share the same adapter.
 */
data class VideoUiModel(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val viewCount: Long,
    val uploadDate: String
) {
    val durationLabel: String get() = Formatters.formatDuration(durationSec)
    val viewsLabel: String get() = Formatters.formatViewsLabel(viewCount)
}

fun StreamInfoItem.toUiModel(): VideoUiModel = VideoUiModel(
    url = url.orEmpty(),
    title = name.orEmpty(),
    uploader = uploaderName ?: "",
    thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
    durationSec = duration,
    viewCount = viewCount,
    uploadDate = Formatters.formatRelativeTime(textualUploadDate)
)

fun StreamInfo.toUiModel(): VideoUiModel = VideoUiModel(
    url = url.orEmpty(),
    title = name.orEmpty(),
    uploader = uploaderName ?: "",
    thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
    durationSec = duration,
    viewCount = viewCount,
    uploadDate = ""
)

fun VideoEntry.toUiModel(): VideoUiModel = VideoUiModel(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    viewCount = -1L,
    uploadDate = ""
)

fun VideoUiModel.toEntry(): VideoEntry = VideoEntry(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec
)

fun VideoUiModel.toQueueEntry(isMusic: Boolean = false): com.sparktube.app.playback.QueueEntry =
    com.sparktube.app.playback.QueueEntry(
        url = url,
        title = title,
        uploader = uploader,
        thumbnailUrl = thumbnailUrl,
        durationSec = durationSec,
        isMusic = isMusic
    )
