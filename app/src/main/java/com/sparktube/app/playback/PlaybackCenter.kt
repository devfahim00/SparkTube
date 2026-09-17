package com.sparktube.app.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.LiveFilter
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.data.YtRepository
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.net.OkHttpDownloader
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

    val selectedQualityHeight: Int? get() = selectedHeight

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
            if (playbackState == Player.STATE_ENDED && mode == Mode.VIDEO) {
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
        runCatching {
            appContext.startService(Intent(appContext, PlaybackService::class.java))
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
     * Starts a song. The whole queue becomes a real player playlist whose
     * streams are resolved on demand; when radio is true the queue keeps
     * growing with related songs.
     */
    fun playMusic(entry: QueueEntry, radio: Boolean) {
        initFromApp()
        stopPlayerNow()
        mode = Mode.AUDIO
        radioMode = radio
        audioOnlyMode = false
        pendingMusic.clear()
        setQueue(listOf(entry), 0)
        startMusicPlaylist()
        resolveCatalog(entry)
        ensureService()
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
        } else {
            audioOnlyMode = false
            selectedHeight = height
        }
        rebuildSource(keepPosition = true)
    }

    fun setAudioTrack(trackId: String) {
        selectedAudioTrackId = trackId
        rebuildSource(keepPosition = true)
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
                LocalStore.addToHistory(appContext, entry.toVideoEntry())
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
        val known = queue.map { it.url }.toSet()
        val additions = rel.filter { it.url !in known && !LiveFilter.isLive(it) }
            .take(20)
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
        val c = catalog ?: return
        // If a high-quality merged source failed, fall back to the best muxed stream.
        if (!usedFallback && c.muxed.isNotEmpty()) {
            usedFallback = true
            rebuildSource(keepPosition = true)
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
            val audio = audioForSelection(c) ?: c.audioTracks.firstOrNull()?.best
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
            height == null -> c.videoOnly.firstOrNull() ?: c.muxed.firstOrNull()
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

    private fun audioUri(stream: AudioStream): Uri = Uri.parse(stream.content)
    private fun audioUri(stream: VideoStream): Uri = Uri.parse(stream.content)

    // ----- Music playlist -----

    /**
     * Builds the lazy-resolving playlist on the player. Each item uses a
     * virtual sparktube:// URI that the resolving data source swaps for the
     * real audio stream URL right before it is opened.
     */
    private fun startMusicPlaylist() {
        val entry = currentEntry ?: return
        val item = musicMediaItem(entry)
        player.run {
            setMediaItem(item, 0L)
            prepare()
            playWhenReady = true
            setPlaybackSpeed(playbackSpeed)
        }
    }

    private fun musicMediaItem(entry: QueueEntry): MediaItem {
        val key = "music-${musicItemId.incrementAndGet()}"
        pendingMusic[key] = entry
        return MediaItem.Builder()
            .setUri("sparktube://queue/$key")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.uploader)
                    .setArtworkUri(entry.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()
            )
            .build()
    }

    /** Blocking stream resolution for the playlist (runs on the loading thread). */
    private fun resolveMusicUri(key: String): Uri {
        val entry = pendingMusic[key]
            ?: throw IOException("Playlist item vanished: $key")
        val info = runBlocking { YtRepository.streamInfo(entry.url) }
        if (LiveFilter.isLive(info)) {
            pendingMusic.remove(key)
            throw IOException("LIVE_CONTENT")
        }
        val audio = StreamCatalog(info).audioTracks.firstOrNull()?.best
        if (audio == null) {
            pendingMusic.remove(key)
            throw IOException("No audio stream found")
        }
        pendingMusic.remove(key)
        return Uri.parse(audio.content)
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
                dataSpec.buildUpon().setUri(resolveMusicUri(key)).build()
            } else {
                dataSpec
            }
        }
        DefaultDataSource.Factory(appContext, resolving)
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
    durationSec = durationSec
)
