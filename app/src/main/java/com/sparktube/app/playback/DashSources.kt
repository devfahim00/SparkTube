package com.sparktube.app.playback

import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream

/**
 * Adaptive ("YouTube-like") start.
 *
 * WHY: playing ONE fixed-quality file as a single progressive stream means the
 * first frame waits for a long-lived connection to a throttled / paced CDN
 * URL, the quality never adapts, and the only cure was a reactive 10-second
 * watchdog that dropped a tier at a time (the "quality switches a few times,
 * then it plays" symptom). The official player instead talks DASH: it loads
 * the tiny init + index (sidx) ranges, then small per-segment ranges, and its
 * bandwidth estimator picks the quality per segment — so the first frame needs
 * only a few hundred KB and a slow line simply gets a lower quality.
 *
 * YouTube's adaptive streams already ARE DASH-ready files (fragmented MP4 /
 * WebM with an init range and an index range, both reported by the extractor),
 * so we describe them to ExoPlayer with an on-demand DASH manifest built in
 * memory: one video AdaptationSet (all heights of ONE codec family, so quality
 * can switch seamlessly) + one audio AdaptationSet. Nothing is fetched to make
 * the manifest. If anything looks unusable this returns null and the caller
 * uses the plain progressive path exactly as before.
 */
@OptIn(UnstableApi::class)
object DashSources {

    private const val TAG = "DashSources"

    fun create(
        dataSourceFactory: DataSource.Factory,
        item: MediaItem,
        videoStreams: List<VideoStream>,
        audio: AudioStream?,
        durationSec: Long
    ): MediaSource? {
        return try {
            val xml = buildManifest(videoStreams, audio, durationSec) ?: return null
            val manifest = DashManifestParser().parse(
                Uri.parse("https://sparktube.invalid/manifest.mpd"),
                ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
            )
            DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, item)
        } catch (e: Exception) {
            Log.w(TAG, "Adaptive manifest unavailable, using progressive: ${e.message}")
            null
        }
    }

    private fun hasRanges(initStart: Int, initEnd: Int, indexStart: Int, indexEnd: Int) =
        initStart >= 0 && initEnd > initStart && indexStart > 0 && indexEnd > indexStart

    private fun usable(v: VideoStream): Boolean =
        v.isUrl && !v.content.isNullOrBlank() && v.height > 0 && v.bitrate > 0 &&
            !v.codec.isNullOrBlank() && v.format?.mimeType != null &&
            hasRanges(v.initStart, v.initEnd, v.indexStart, v.indexEnd)

    private fun isAvc(v: VideoStream) = v.codec.orEmpty().startsWith("avc1")

    private fun isVp9(v: VideoStream) =
        v.codec.orEmpty().let { it.startsWith("vp9") || it.startsWith("vp09") }

    private fun buildManifest(
        videoStreams: List<VideoStream>,
        audio: AudioStream?,
        durationSec: Long
    ): String? {
        if (durationSec <= 0 || audio == null) return null
        if (!audio.isUrl || audio.content.isNullOrBlank() || audio.format?.mimeType == null ||
            !hasRanges(audio.initStart, audio.initEnd, audio.indexStart, audio.indexEnd)
        ) return null

        // ONE codec family so the quality can switch seamlessly: H.264 (hardware
        // decoded everywhere) unless VP9 offers clearly more heights.
        val ok = videoStreams.filter { usable(it) }
        val avc = ok.filter { isAvc(it) }
        val vp9 = ok.filter { isVp9(it) }
        val family = if (vp9.map { it.height }.distinct().size >
            avc.map { it.height }.distinct().size
        ) vp9 else avc
        // Same height + fps twice: keep the one with the higher bitrate.
        val reps = family
            .groupBy { it.height to it.fps }
            .map { (_, list) -> list.maxByOrNull { it.bitrate }!! }
            .sortedBy { it.height * 1000 + it.fps }
        if (reps.size < 2) return null   // nothing to adapt between: plain path is fine

        val duration = "PT${durationSec}S"
        val sb = StringBuilder(2048)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
        sb.append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
        sb.append("mediaPresentationDuration=\"").append(duration).append("\" minBufferTime=\"PT1.5S\">")
        sb.append("<Period id=\"0\" duration=\"").append(duration).append("\">")

        // Video
        sb.append("<AdaptationSet id=\"0\" mimeType=\"").append(reps.first().format!!.mimeType)
            .append("\" subsegmentAlignment=\"true\" startWithSAP=\"1\">")
        reps.forEach { v ->
            sb.append("<Representation id=\"").append(v.itag).append("\" codecs=\"")
                .append(TextUtils.htmlEncode(v.codec)).append("\" bandwidth=\"").append(v.bitrate)
                .append("\" width=\"").append(if (v.width > 0) v.width else v.height * 16 / 9)
                .append("\" height=\"").append(v.height).append("\"")
            if (v.fps > 0) sb.append(" frameRate=\"").append(v.fps).append("\"")
            sb.append(">")
            appendBase(sb, v.content, v.initStart, v.initEnd, v.indexStart, v.indexEnd)
            sb.append("</Representation>")
        }
        sb.append("</AdaptationSet>")

        // Audio (single stream: the selected / default dubbing track)
        val audioBandwidth = when {
            audio.bitrate > 0 -> audio.bitrate
            audio.averageBitrate > 0 -> audio.averageBitrate * 1000
            else -> 128_000
        }
        sb.append("<AdaptationSet id=\"1\" mimeType=\"").append(audio.format!!.mimeType)
            .append("\" subsegmentAlignment=\"true\" startWithSAP=\"1\">")
        sb.append("<Representation id=\"").append(audio.itag).append("\" bandwidth=\"")
            .append(audioBandwidth).append("\"")
        if (!audio.codec.isNullOrBlank()) {
            sb.append(" codecs=\"").append(TextUtils.htmlEncode(audio.codec)).append("\"")
        }
        sb.append(">")
        appendBase(sb, audio.content, audio.initStart, audio.initEnd, audio.indexStart, audio.indexEnd)
        sb.append("</Representation></AdaptationSet>")

        sb.append("</Period></MPD>")
        return sb.toString()
    }

    private fun appendBase(
        sb: StringBuilder,
        url: String,
        initStart: Int,
        initEnd: Int,
        indexStart: Int,
        indexEnd: Int
    ) {
        sb.append("<BaseURL>").append(TextUtils.htmlEncode(url)).append("</BaseURL>")
        sb.append("<SegmentBase indexRange=\"").append(indexStart).append('-').append(indexEnd)
            .append("\"><Initialization range=\"").append(initStart).append('-').append(initEnd)
            .append("\"/></SegmentBase>")
    }
}
