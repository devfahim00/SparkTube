package com.sparktube.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.LiveFilter
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.data.YtRepository
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.util.AppPrefs
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Best-effort height: itag height or parsed from the resolution string ("1080p60"). */
fun VideoStream.effectiveHeight(): Int =
    if (height > 0) height else StreamCatalog.resolutionHeight(resolution)

/** One item in the play queue (resolved lazily when it starts playing). */
data class QueueEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val isMusic: Boolean = false
)

/** A group of audio streams that share the same dubbing language. */
data class AudioTrackGroup(
    val id: String,
    val label: String,
    val isOriginal: Boolean,
    val streams: List<AudioStream>
) {
    val best: AudioStream? get() = streams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
}

/** Cleaned-up stream catalogue for the currently loaded item. */
class StreamCatalog(info: StreamInfo) {

    val name: String = info.name ?: ""
    val uploaderUrl: String = info.uploaderUrl ?: ""
    val uploaderName: String = info.uploaderName ?: ""
    val uploaderAvatarUrl: String = info.uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty()
    val viewCount: Long = info.viewCount
    val durationSec: Long = info.duration

    /** Video-only adaptive streams (higher qualities), distinct heights, best first. */
    val videoOnly: List<VideoStream> = info.videoOnlyStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }
        .distinctBy { it.effectiveHeight() }
        .sortedByDescending { it.effectiveHeight() }

    /** Progressive (muxed) streams, fallback only. */
    val muxed: List<VideoStream> = info.videoStreams
        .filter { it.isUrl && it.effectiveHeight() > 0 }
        .distinctBy { it.effectiveHeight() }
        .sortedByDescending { it.effectiveHeight() }

    /** Audio streams grouped into dubbing languages. */
    val audioTracks: List<AudioTrackGroup> = info.audioStreams
        .filter { it.isUrl }
        .groupBy { it.audioTrackId ?: it.audioTrackName ?: "default" }
        .map { (id, streams) ->
            val first = streams.first()
            AudioTrackGroup(
                id = id,
                label = first.audioTrackName?.takeIf { it.isNotBlank() }
                    ?: if (id == "default") "Default" else id,
                isOriginal = first.audioTrackType == AudioTrackType.ORIGINAL,
                streams = streams.sortedByDescending { it.averageBitrate }
            )
        }
        .sortedWith(compareByDescending<AudioTrackGroup> { it.isOriginal }.thenBy { it.label })

    /** Related videos for the watch page (already live-filtered). */
    val related: List<StreamInfoItem> = LiveFilter.sanitize(info.relatedItems)

    val hasVideo: Boolean get() = videoOnly.isNotEmpty() || muxed.isNotEmpty()

    companion object {
        fun resolutionHeight(resolution: String): Int =
            Regex("(\\d{3,4})").find(resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun qualityLabel(v: VideoStream): String {
        val height = v.effectiveHeight()
        val fps = if (v.fps > 30 && v.fps < 100) "${v.fps}fps" else ""
        val tag = if (v.isVideoOnly) " (video only)" else ""
        return listOf(height.toString() + "p", fps).filter { it.isNotBlank() }.joinToString(" ") + tag
    }
}

/**
 * App-wide playback engine. Owns the single ExoPlayer instance that the
 * watch page, the mini player, the music screen and the media notification
 * all attach to, so playback survives screen changes and app close.
 *
 * Video mode uses a single merged source. Music mode builds a real
 * playlist whose items are resolved on demand, so the media notification
 * gets native previous / next / seek controls.
 */
@OptIn(UnstableApi::class)
object PlaybackCenter {

    enum class Mode { NONE, VIDEO, AUDIO }

    interface Listener {
        fun onItemChanged(entry: QueueEntry?) {}
        fun onResolvingChanged(isResolving: Boolean) {}
        fun onCatalogReady() {}
        fun onPlaybackStateChanged(isPlaying: Boolean) {}
        fun onQueueChanged() {}
        fun onError(message: String) {}
        fun onFavoriteChanged(url: String, isFavorite: Boolean) {}
        fun onAudioOnlyChanged(audioOnly: Boolean) {}

        /** Short user-facing hint ("switched to the default audio track", …). */
        fun onNotice(message: String) {}
    }

    lateinit var appContext: Context
        private set

    val listeners = mutableListOf<Listener>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playerRef: ExoPlayer? = null
    private var resolveJob: Job? = null

    /** Items of the music playlist that have not been resolved yet. */
    private val pendingMusic = ConcurrentHashMap<String, QueueEntry>()
    private val musicItemId = AtomicLong(0L)

    /** Already-resolved audio URLs for music queue entries (entry.url -> Uri). */
    private val musicUriCache = ConcurrentHashMap<String, Uri>()

    private var prefetchJob: Job? = null

    /**
     * App-side controller connection to PlaybackService. Media3 only shows
     * (and keeps updating) the media notification while a MediaController is
     * connected to the MediaSessionService, so the app itself must hold one
     * open. Created on first playback, kept for the lifetime of the process.
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    @Volatile var mode: Mode = Mode.NONE
        private set
    @Volatile var inPip: Boolean = false
    @Volatile var audioOnlyMode: Boolean = false
        private set

    val queue = mutableListOf<QueueEntry>()
    var queueIndex: Int = -1
        private set
    var radioMode: Boolean = false
        private set
    private var usedFallback = false

    var catalog: StreamCatalog? = null
        private set

    /** User selections that survive quality/track switches. */
    var selectedAudioTrackId: String? = null
        private set
    private var selectedHeight: Int? = null

    /** Set when the user explicitly picked "Auto" in the gear menu. */
    private var autoPicked = false

    val selectedQualityHeight: Int? get() = selectedHeight

    /**
     * The quality actually in effect for [c]: an explicit gear-menu pick,
     * otherwise the default-video-quality from settings (what [videoForSelection]
     * really plays). Used by the gear menu so a settings default like 720p
     * shows up as "720p" instead of "Auto".
     */
    fun effectiveSelectedHeight(c: StreamCatalog): Int? {
        selectedHeight?.let { return it }
        if (autoPicked) return null
        val default = AppPrefs.defaultVideoHeightNow(appContext)
        if (default <= 0) return null
        val candidates = c.videoOnly.ifEmpty { c.muxed }
        return candidates.filter { it.effectiveHeight() <= default }
            .maxByOrNull { it.effectiveHeight() }?.effectiveHeight()
            ?: candidates.minByOrNull { it.effectiveHeight() }?.effectiveHeight()
    }

    val selectedAudioTrackLabel: String?
        get() = selectedAudioTrackId?.let { id ->
            catalog?.audioTracks?.firstOrNull { it.id == id }?.label
        }

    val currentEntry: QueueEntry? get() = queue.getOrNull(queueIndex)
    val hasMedia: Boolean get() = currentEntry != null

    /** Current playback speed in effect. */
    var playbackSpeed: Float = 1.0f
        private set

    // ----- Lifecycle -----

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    val player: ExoPlayer
        get() {
            check(this::appContext.isInitialized) { "PlaybackCenter.init() not called" }
            return playerRef ?: ExoPlayer.Builder(appContext)
                // IMPORTANT: player.setMediaItems() (music playlist!) builds
                // its MediaSources through this factory. Without it the
                // player falls back to its own default data source chain,
                // which cannot resolve the virtual sparktube:// queue URIs:
                // every song fails with "MalformedURLException: unknown
                // protocol: sparktube" and playback skips to the next track
                // (which fails the same way).
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                // Fast start WITHOUT the mid-play stall: the previous 1s
                // start threshold made playback begin before the TCP window
                // had ramped up, so the buffer ran dry ~2-4s in, playback
                // froze once and then recovered. A slightly larger start
                // cushion (2.2s) + a much larger re-buffer threshold (5s)
                // removes that freeze, while the bigger max buffer keeps
                // long playback smooth on fluctuating connections.
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            /* minBufferMs = */ 30_000,
                            /* maxBufferMs = */ 90_000,
                            /* bufferForPlaybackMs = */ 2_200,
                            /* bufferForPlaybackAfterRebufferMs = */ 5_000
                        )
                        .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
                        .build()
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
                .also { built ->
                    playerRef = built
                    built.addListener(playerListener)
                }
        }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            notify { it.onPlaybackStateChanged(isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && mode == Mode.VIDEO &&
                AppPrefs.videoAutoplayNext
            ) {
                advance()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mode != Mode.AUDIO) return
            val index = playerRef?.currentMediaItemIndex ?: return
            if (index < 0 || index >= queue.size) return
            queueIndex = index
            currentEntry?.let { entry ->
                notify { it.onItemChanged(entry) }
                if (!entry.url.startsWith("file://")) {
                    resolveCatalog(entry)
                }
            }
            // Resolve the next songs in the background so transitions are instant.
            prefetchAhead()
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlayerFailed(error)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notify(block: (Listener) -> Unit) {
        val snapshot = listeners.toList()
        snapshot.forEach(block)
    }

    private fun ensureService() {
        if (controllerFuture != null) return
        try {
            // A plain startService() is NOT enough: Media3 wires the session
            // player into its notification manager inside addSession(),
            // which only runs when a controller connects (onGetSession).
            // Without this connection background music keeps playing in the
            // app process but no notification - and so no shade / lockscreen
            // controls - ever appears.
            val token =
                SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            controllerFuture = future
            // If connecting failed for good, allow another try on the next
            // playback instead of staying silent forever.
            future.addListener(
                {
                    try {
                        future.get()
                    } catch (_: Exception) {
                        controllerFuture = null
                    }
                },
                ContextCompat.getMainExecutor(appContext)
            )
        } catch (_: Exception) {
            // e.g. binding not allowed right now: retried on next playback.
        }
    }

    /** Kills the current playback instantly so sounds never overlap. */
    private fun stopPlayerNow() {
        playerRef?.run {
            stop()
            clearMediaItems()
        }
    }

    // ----- Commands -----

    /** Starts a video (watch page). Builds a YouTube-like autoplay queue. */
    fun playVideo(entry: QueueEntry, extraQueue: List<QueueEntry> = emptyList()) {
        initFromApp()
        stopPlayerNow()
        mode = Mode.VIDEO
        radioMode = false
        audioOnlyMode = false
        setQueue(listOf(entry) + extraQueue, 0)
        resolveCurrent()
    }

    /**
     * Starts a song (optionally as an endless radio of related tracks).
     */
    fun playMusic(entry: QueueEntry, radio: Boolean) {
        playMusicQueue(listOf(entry), 0, allowRadio = radio)
    }

    /**
     * Starts a fixed music playlist (favorites, downloads, …): exactly
     * these songs play one after another — nothing else is ever mixed in.
     */
    fun playMusicQueue(entries: List<QueueEntry>, startIndex: Int = 0, allowRadio: Boolean = false) {
        initFromApp()
        if (entries.isEmpty()) return
        val start = startIndex.coerceIn(0, entries.size - 1)
        // The player playlist is the tapped song + everything after it, so
        // player indexes and queue indexes always match.
        val playlist = if (start == 0) entries else entries.drop(start)
        stopPlayerNow()
        mode = Mode.AUDIO
        radioMode = allowRadio
        audioOnlyMode = false
        pendingMusic.clear()
        setQueue(playlist, 0)
        resolveJob?.cancel()
        notify { it.onResolvingChanged(true) }
        resolveJob = scope.launch {
            try {
                val first = currentEntry ?: return@launch
                // Downloaded files play straight from disk — no resolving.
                if (first.url.startsWith("file://")) {
                    catalog = null
                    startMusicPlaylistWithRelated()
                    ensureService()
                    notify { it.onItemChanged(currentEntry) }
                    notify { it.onCatalogReady() }
                } else {
                    val info = YtRepository.streamInfo(first.url)
                    if (LiveFilter.isLive(info)) {
                        throw IllegalArgumentException("LIVE_CONTENT")
                    }
                    catalog = StreamCatalog(info)
                    LocalStore.addToMusicHistory(appContext, first.toVideoEntry())
                    RecommendEngine.logPlay(appContext, first.url, first.title, first.uploader)
                    // Prime the audio URL cache: the first song then starts instantly.
                    primeMusicUri(first)
                    startMusicPlaylistWithRelated()
                    ensureService()
                    notify { it.onItemChanged(currentEntry) }
                    notify { it.onCatalogReady() }
                    prefetchAhead()
                }
            } catch (e: Exception) {
                notify { it.onError(if (e.message.isNullOrBlank()) e.javaClass.simpleName else e.message!!) }
            } finally {
                notify { it.onResolvingChanged(false) }
            }
        }
    }

    fun togglePlayPause() {
        val p = playerRef ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        playerRef?.seekTo(positionMs.coerceIn(0, durationMs))
    }

    val positionMs: Long get() = playerRef?.currentPosition?.coerceAtLeast(0L) ?: 0L
    val durationMs: Long get() = playerRef?.duration?.takeIf { it > 0 } ?: 0L
    val isPlaying: Boolean get() = playerRef?.isPlaying ?: false
    val isBuffering: Boolean get() = playerRef?.playbackState == Player.STATE_BUFFERING

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        playerRef?.setPlaybackSpeed(speed)
    }

    /** Music settings: pause between songs instead of auto-advancing. */
    fun applyMusicAutoplay() {
        playerRef?.setPauseAtEndOfMediaItems(!AppPrefs.musicAutoplayNext)
    }

    fun next() {
        when (mode) {
            Mode.AUDIO -> playerRef?.run {
                if (hasNextMediaItem()) seekToNextMediaItem()
            }
            Mode.VIDEO -> if (queueIndex < queue.size - 1) {
                queueIndex++
                resolveCurrent()
            }
            Mode.NONE -> Unit
        }
    }

    fun previous() {
        when (mode) {
            Mode.AUDIO -> playerRef?.run {
                if (hasPreviousMediaItem()) seekToPreviousMediaItem()
                else seekTo(0)
            }
            Mode.VIDEO -> if (queueIndex > 0) {
                queueIndex--
                resolveCurrent()
            }
            Mode.NONE -> Unit
        }
    }

    fun stopPlayback() {
        resolveJob?.cancel()
        prefetchJob?.cancel()
        pendingMusic.clear()
        playerRef?.run {
            stop()
            clearMediaItems()
        }
        mode = Mode.NONE
        queue.clear()
        queueIndex = -1
        catalog = null
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(null) }
        notify { it.onPlaybackStateChanged(false) }
    }

    // ----- Quality / track selection -----

    /**
     * @param height null = auto (best available), -1 = audio only.
     */
    fun setVideoQuality(height: Int?) {
        if (height == -1) {
            audioOnlyMode = true
            selectedHeight = -1
            autoPicked = false
        } else {
            audioOnlyMode = false
            selectedHeight = height
            // Explicit "Auto" beats the settings default for this session.
            autoPicked = height == null
        }
        // Explicit user action: give adaptive streams another chance even
        // if an earlier error had dropped us to a muxed fallback.
        usedFallback = false
        rebuildSource(keepPosition = true)
    }

    fun setAudioTrack(trackId: String) {
        if (catalog?.audioTracks?.none { it.id == trackId } == true) return
        selectedAudioTrackId = trackId
        // Rebuild from scratch so the selected dubbing language is actually
        // used, even if an earlier error had switched us to a muxed stream
        // (those always carry the default audio only).
        usedFallback = false
        rebuildSource(keepPosition = true)
        notify { it.onCatalogReady() }
    }

    /** Turns the current video into an audio-only background stream. */
    fun switchToAudioOnly() {
        audioOnlyMode = true
        rebuildSource(keepPosition = true)
        notify { it.onAudioOnlyChanged(true) }
    }

    /** Rebuilds the full video source after background-audio mode. */
    fun switchBackToVideo() {
        if (mode != Mode.VIDEO) return
        audioOnlyMode = false
        rebuildSource(keepPosition = true)
        notify { it.onAudioOnlyChanged(false) }
    }

    // ----- Favorites -----

    fun toggleFavoriteCurrent(): Boolean {
        val entry = currentEntry ?: return false
        val nowFavorite = LocalStore.toggleFavorite(appContext, entry.toVideoEntry())
        notify { it.onFavoriteChanged(entry.url, nowFavorite) }
        return nowFavorite
    }

    fun isCurrentFavorite(): Boolean {
        val entry = currentEntry ?: return false
        return LocalStore.isFavorite(appContext, entry.url)
    }

    // ----- Local downloads -----

    fun playDownload(record: DownloadRecord) {
        initFromApp()
        if (record.filePaths.isEmpty()) return
        mode = if (record.type == DownloadCenter.TYPE_AUDIO) Mode.AUDIO else Mode.VIDEO
        audioOnlyMode = record.type == DownloadCenter.TYPE_AUDIO
        radioMode = false
        pendingMusic.clear()
        queue.clear()
        queue.add(
            QueueEntry(
                url = "file://" + record.filePaths.first(),
                title = record.title,
                uploader = record.uploader,
                thumbnailUrl = record.thumbnailUrl,
                durationSec = 0L,
                isMusic = record.type == DownloadCenter.TYPE_AUDIO
            )
        )
        queueIndex = 0
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(currentEntry) }

        val sources = record.filePaths
            .filter { File(it).exists() }
            .map { path ->
                progressiveSource(
                    MediaItem.Builder().setUri(Uri.fromFile(File(path))).build()
                )
            }
        if (sources.isEmpty()) {
            notify { it.onError("Downloaded file is missing") }
            return
        }
        val source = if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
        player.run {
            setMediaSource(source, 0L)
            prepare()
            playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
        }
        catalog = null
        notify { it.onCatalogReady() }
        ensureService()
    }

    // ----- Resolution -----

    private fun setQueue(newQueue: List<QueueEntry>, startIndex: Int) {
        queue.clear()
        queue.addAll(newQueue)
        queueIndex = startIndex
        notify { it.onQueueChanged() }
        notify { it.onItemChanged(currentEntry) }
    }

    private fun initFromApp() {
        if (this::appContext.isInitialized) return
        throw IllegalStateException("PlaybackCenter.init(context) must be called in Application.onCreate()")
    }

    /** Video mode: resolve the current item and play it. */
    private fun resolveCurrent() {
        val entry = currentEntry ?: return
        resolveJob?.cancel()
        notify { it.onResolvingChanged(true) }
        resolveJob = scope.launch {
            try {
                val info = YtRepository.streamInfo(entry.url)
                if (LiveFilter.isLive(info)) {
                    throw IllegalArgumentException("LIVE_CONTENT")
                }
                catalog = StreamCatalog(info)
                usedFallback = false
                LocalStore.addToHistory(appContext, entry.toVideoEntry())
                RecommendEngine.logPlay(appContext, entry.url, entry.title, entry.uploader)
                applySources(playbackSpeed)
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                extendQueueFromRelated()
                ensureService()
            } catch (e: Exception) {
                notify { it.onError(if (e.message.isNullOrBlank()) e.javaClass.simpleName else e.message!!) }
            } finally {
                notify { it.onResolvingChanged(false) }
            }
        }
    }

    /** Music mode: resolve metadata for the current song (downloads, radio). */
    private fun resolveCatalog(entry: QueueEntry) {
        resolveJob?.cancel()
        notify { it.onResolvingChanged(true) }
        resolveJob = scope.launch {
            try {
                val info = YtRepository.streamInfo(entry.url)
                if (LiveFilter.isLive(info)) {
                    throw IllegalArgumentException("LIVE_CONTENT")
                }
                catalog = StreamCatalog(info)
                LocalStore.addToMusicHistory(appContext, entry.toVideoEntry())
                RecommendEngine.logPlay(appContext, entry.url, entry.title, entry.uploader)
                // Share the resolved URL with the loading thread (if it has
                // not found it already) so a re-visit starts instantly.
                primeMusicUri(entry)
                notify { it.onItemChanged(currentEntry) }
                notify { it.onCatalogReady() }
                if (radioMode) {
                    extendRadioFromRelated()
                }
            } catch (e: Exception) {
                notify { it.onError(if (e.message.isNullOrBlank()) e.javaClass.simpleName else e.message!!) }
            } finally {
                notify { it.onResolvingChanged(false) }
            }
        }
    }

    /** Appends related items so next/previous + autoplay keep working (video mode). */
    private fun extendQueueFromRelated() {
        val rel = catalog?.related ?: return
        val known = queue.map { it.url }.toSet()
        val additions = rel.filter { it.url !in known }.take(20)
            .map { it.toQueueEntry(isMusic = mode == Mode.AUDIO) }
        if (additions.isEmpty()) return
        queue.addAll(additions)
        notify { it.onQueueChanged() }
    }

    /** Grows the music playlist with related songs before it runs dry. */
    private fun extendRadioFromRelated() {
        val rel = catalog?.related ?: return
        val idx = playerRef?.currentMediaItemIndex ?: queueIndex
        val remaining = (queue.size - idx - 1).coerceAtLeast(0)
        // Only extend when the upcoming part of the queue is nearly empty,
        // otherwise the playlist would grow without bound.
        if (remaining >= 4) return
        val known = queue.map { it.url }.toSet()
        val additions = rel.filter { it.url !in known && !LiveFilter.isLive(it) }
            .take(5)
            .map { it.toQueueEntry(isMusic = true) }
        if (additions.isEmpty()) return
        queue.addAll(additions)
        val items = additions.map { musicMediaItem(it) }
        playerRef?.addMediaItems(items)
        notify { it.onQueueChanged() }
    }

    private fun advance() {
        if (queueIndex < queue.size - 1) {
            next()
        }
        // Queue exhausted: radio already keeps extending while playing.
    }

    private fun onPlayerFailed(error: PlaybackException) {
        // Music mode: skip a broken song so the radio keeps going.
        if (mode == Mode.AUDIO) {
            playerRef?.run {
                if (hasNextMediaItem()) {
                    seekToNextMediaItem()
                    return
                }
            }
            notify { it.onError("Playback error: ${error.errorCodeName}") }
            return
        }
        val c = catalog ?: run {
            notify { it.onError("Playback error: ${error.errorCodeName}") }
            return
        }
        // The selected dubbing track failed to load: retry once with the
        // default audio before giving up on adaptive streams.
        if (selectedAudioTrackId != null && !usedFallback) {
            selectedAudioTrackId = null
            notify { it.onNotice("Selected audio track unavailable - using the default track") }
            rebuildSource(keepPosition = true)
            notify { it.onCatalogReady() }
            return
        }
        // If a high-quality merged source failed, fall back to the best muxed stream.
        if (!usedFallback && c.muxed.isNotEmpty()) {
            usedFallback = true
            // A muxed stream always carries the default audio only.
            val hadTrackChoice = selectedAudioTrackId != null
            selectedAudioTrackId = null
            if (hadTrackChoice) {
                notify { it.onNotice("Playing a lower-quality stream with the default audio track") }
            }
            rebuildSource(keepPosition = true)
            notify { it.onCatalogReady() }
            return
        }
        notify { it.onError("Playback error: ${error.errorCodeName}") }
    }

    /** Builds and applies the media source for the current entry + selections (video mode). */
    private fun applySources(speed: Float) {
        val entry = currentEntry ?: return
        val c = catalog ?: return
        val source = buildSource(entry, c) ?: run {
            notify { it.onError("No playable streams found") }
            return
        }
        player.run {
            setMediaSource(source, 0L)
            prepare()
            playWhenReady = true
            setPlaybackSpeed(speed)
        }
    }

    private fun rebuildSource(keepPosition: Boolean) {
        val entry = currentEntry ?: return
        val c = catalog ?: return
        val position = if (keepPosition) positionMs else 0L
        val wasPlaying = playerRef?.isPlaying ?: true
        val source = buildSource(entry, c) ?: return
        player.run {
            setMediaSource(source, position)
            prepare()
            if (wasPlaying || !keepPosition) playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
        }
    }

    private fun buildSource(entry: QueueEntry, c: StreamCatalog): MediaSource? {
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.uploader)
            .setArtworkUri(entry.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()

        // Music / audio-only background mode: pick the best audio stream.
        if (mode == Mode.AUDIO || audioOnlyMode) {
            val audio = if (mode == Mode.AUDIO) {
                pickMusicAudio(c)
            } else {
                audioForSelection(c) ?: c.audioTracks.firstOrNull()?.best
            }
            if (audio == null && c.muxed.isNotEmpty()) {
                return progressiveSource(MediaItem.Builder().setUri(audioUri(c.muxed.first())).setMediaMetadata(metadata).build())
            }
            if (audio != null) {
                return progressiveSource(MediaItem.Builder().setUri(audioUri(audio)).setMediaMetadata(metadata).build())
            }
            return null
        }

        // Video mode: prefer an adaptive video-only stream merged with audio
        // (that's how qualities above 360p are supported).
        val audio = audioForSelection(c)
        val video = videoForSelection(c)

        if (video != null && video.isVideoOnly && audio != null) {
            val videoItem = MediaItem.Builder().setUri(audioUri(video)).setMediaMetadata(metadata).build()
            val audioItem = MediaItem.Builder().setUri(audioUri(audio)).build()
            return MergingMediaSource(
                progressiveSource(videoItem),
                progressiveSource(audioItem)
            )
        }
        if (video != null) {
            return progressiveSource(MediaItem.Builder().setUri(audioUri(video)).setMediaMetadata(metadata).build())
        }
        if (audio != null) {
            return progressiveSource(MediaItem.Builder().setUri(audioUri(audio)).setMediaMetadata(metadata).build())
        }
        return null
    }

    private fun videoForSelection(c: StreamCatalog): VideoStream? {
        if (usedFallback && c.muxed.isNotEmpty()) return c.muxed.first()
        val height = selectedHeight
        return when {
            height == null -> {
                val default = AppPrefs.defaultVideoHeightNow(appContext)
                if (default > 0 && !autoPicked) {
                    // User default: the chosen quality, or the best one below
                    // it when the exact height is not available.
                    val candidates = c.videoOnly.ifEmpty { c.muxed }
                    candidates.filter { it.effectiveHeight() <= default }
                        .maxByOrNull { it.effectiveHeight() }
                        ?: candidates.minByOrNull { it.effectiveHeight() }
                } else {
                    c.videoOnly.firstOrNull() ?: c.muxed.firstOrNull()
                }
            }
            c.videoOnly.any { it.effectiveHeight() == height } ->
                c.videoOnly.first { it.effectiveHeight() == height }
            c.muxed.any { it.effectiveHeight() == height } ->
                c.muxed.first { it.effectiveHeight() == height }
            else -> c.videoOnly.firstOrNull() ?: c.muxed.firstOrNull()
        }
    }

    private fun audioForSelection(c: StreamCatalog): AudioStream? {
        val selected = selectedAudioTrackId
        return when {
            selected != null -> c.audioTracks.firstOrNull { it.id == selected }?.best
                ?: c.audioTracks.firstOrNull()?.best
            else -> c.audioTracks.firstOrNull()?.best
        }
    }

    /** Picks the default audio track's stream at the user's music quality tier. */
    private fun pickMusicAudio(c: StreamCatalog): AudioStream? {
        val group = c.audioTracks.firstOrNull() ?: return null
        val streams = group.streams.filter { it.isUrl && it.averageBitrate > 0 }
        if (streams.isEmpty()) return group.best
        return when (AppPrefs.musicAudioQuality) {
            AppPrefs.AUDIO_QUALITY_LOW -> streams.last()
            AppPrefs.AUDIO_QUALITY_MEDIUM -> streams[streams.size / 2]
            else -> streams.first()
        }
    }

    private fun audioUri(stream: AudioStream): Uri = Uri.parse(stream.content)
    private fun audioUri(stream: VideoStream): Uri = Uri.parse(stream.content)

    private fun progressiveSource(item: MediaItem): MediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)

    // ----- Music playlist -----

    /**
     * Builds the lazy-resolving playlist on the player: the current song
     * plus (in radio mode) a first batch of related songs so next / previous
     * and the notification controls exist from the very first moment. Each
     * item uses a virtual sparktube:// URI that the resolving data source
     * swaps for the real audio stream URL right before it is opened.
     */
    private fun startMusicPlaylistWithRelated() {
        val first = currentEntry ?: return
        val items = mutableListOf(musicMediaItem(first))
        if (radioMode) {
            catalog?.related
                ?.filter { it.url.isNotBlank() && it.url != first.url && !LiveFilter.isLive(it) }
                ?.take(8)
                ?.forEach { rel ->
                    val qe = rel.toQueueEntry(isMusic = true)
                    if (queue.none { it.url == qe.url }) {
                        queue.add(qe)
                        items.add(musicMediaItem(qe))
                    }
                }
            notify { it.onQueueChanged() }
        } else {
            // Fixed playlist (favorites / downloads): queue the whole list.
            queue.drop(1).forEach { items.add(musicMediaItem(it)) }
        }
        player.run {
            setMediaItems(items, 0, 0L)
            prepare()
            playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
            applyMusicAutoplay()
        }
    }

    private fun musicMediaItem(entry: QueueEntry): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.uploader)
            .setArtworkUri(entry.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()
        // Downloaded songs play straight from disk.
        if (entry.url.startsWith("file://")) {
            return MediaItem.Builder()
                .setUri(Uri.parse(entry.url))
                .setMediaMetadata(metadata)
                .build()
        }
        val key = "music-${musicItemId.incrementAndGet()}"
        // Bounded like musicUriCache: entries now live for the whole session
        // (see resolveMusicUri) instead of being removed after first use, so
        // a very long continuous radio session must not leak indefinitely.
        if (pendingMusic.size > 128) pendingMusic.clear()
        pendingMusic[key] = entry
        return MediaItem.Builder()
            .setUri("sparktube://queue/$key")
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Blocking stream resolution for the playlist (runs on the loading thread).
     * ExoPlayer's ResolvingDataSource can call this more than once for the
     * same media item — a mid-buffer retry, a re-open after a seek, etc. —
     * so the pendingMusic entry must stay around for the life of the
     * playlist item, not just the first successful resolution. Removing it
     * eagerly made every repeat call throw "vanished", which the player
     * treated as a hard playback error and skipped to the next track (which
     * then hit the same problem a few seconds later).
     */
    private fun resolveMusicUri(key: String): Uri {
        val entry = pendingMusic[key]
            ?: throw IOException("Playlist item vanished: $key")
        // Fast path: already resolved (also covers repeat calls for the
        // same item — retries, buffering re-opens, etc).
        musicUriCache[entry.url]?.let { return it }
        val info = runBlocking { YtRepository.streamInfo(entry.url) }
        if (LiveFilter.isLive(info)) {
            throw IOException("LIVE_CONTENT")
        }
        val audio = pickMusicAudio(StreamCatalog(info))
            ?: throw IOException("No audio stream found")
        return cacheMusicUri(entry.url, audio.content)
    }

    /** Caches the resolved audio URL (bounded: a long session must not leak entries). */
    private fun cacheMusicUri(url: String, content: String): Uri {
        if (musicUriCache.size > 64) musicUriCache.clear()
        val uri = Uri.parse(content)
        musicUriCache[url] = uri
        return uri
    }

    /** Stores the selected-quality audio URL of the current catalog for [entry]. */
    private fun primeMusicUri(entry: QueueEntry) {
        val best = catalog?.let { pickMusicAudio(it) } ?: return
        cacheMusicUri(entry.url, best.content)
    }

    /** Resolves the next couple of songs in the background so transitions are instant. */
    private fun prefetchAhead() {
        if (mode != Mode.AUDIO) return
        val idx = playerRef?.currentMediaItemIndex ?: queueIndex
        val targets = ((idx + 1) until minOf(idx + 3, queue.size))
            .mapNotNull { queue.getOrNull(it) }
            .filter { !it.url.startsWith("file://") && !musicUriCache.containsKey(it.url) }
        if (targets.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            targets.forEach { entry ->
                runCatching {
                    val info = YtRepository.streamInfo(entry.url)
                    pickMusicAudio(StreamCatalog(info))?.let { audio ->
                        cacheMusicUri(entry.url, audio.content)
                    }
                }
            }
        }
    }

    val dataSourceFactory: DefaultDataSource.Factory by lazy {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(OkHttpDownloader.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(http) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == "sparktube") {
                val key = "${uri.host ?: ""}${uri.path.orEmpty()}".removePrefix("//")
                    .removePrefix("queue/")
                forceInitialRangeHeader(dataSpec.buildUpon().setUri(resolveMusicUri(key)).build())
            } else {
                forceInitialRangeHeader(dataSpec)
            }
        }
        DefaultDataSource.Factory(appContext, resolving)
    }

    /**
     * YouTube (googlevideo) serves the *first* open of a progressive stream
     * (no Range header: position 0 + unknown length) from a throttled path,
     * which is why videos took ages to start while a seek (which always sends
     * a Range header) started instantly. Forcing `Range: bytes=0-` on that
     * initial open puts it on the same fast path a seek uses. Seeks and
     * re-opens (position != 0) are untouched: DefaultHttpDataSource computes
     * and overrides its own Range header for them after applying our
     * httpRequestHeaders, so this can never corrupt a real range request.
     */
    private fun forceInitialRangeHeader(spec: DataSpec): DataSpec {
        val scheme = spec.uri.scheme
        if (scheme != "http" && scheme != "https") return spec
        // C.LENGTH_UNSET is an int constant (media3) while DataSpec.length is
        // a long - Kotlin needs the explicit widening conversion.
        if (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong()) return spec
        if (spec.httpRequestHeaders.containsKey("Range")) return spec
        val headers = HashMap(spec.httpRequestHeaders)
        headers["Range"] = "bytes=0-"
        return spec.buildUpon().setHttpRequestHeaders(headers).build()
    }

    // ----- View attach helpers -----

    fun attachView(view: PlayerView) {
        view.player = playerRef
    }

    fun detachView(view: PlayerView) {
        if (view.player === playerRef) {
            view.player = null
        }
    }

    fun release() {
        resolveJob?.cancel()
        prefetchJob?.cancel()
        scope.cancel()
        playerRef?.release()
        playerRef = null
    }
}

fun StreamInfoItem.toQueueEntry(isMusic: Boolean = false): QueueEntry = QueueEntry(
    url = url.orEmpty(),
    title = name.orEmpty(),
    uploader = uploaderName ?: "",
    thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
    durationSec = duration,
    isMusic = isMusic
)

fun QueueEntry.toVideoEntry(): VideoEntry = VideoEntry(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    isMusic = isMusic
)
