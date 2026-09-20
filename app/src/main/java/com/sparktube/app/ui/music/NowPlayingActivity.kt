package com.sparktube.app.ui.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import coil.load
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.databinding.ActivityNowPlayingBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.player.DownloadSheet
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.PanelTransitions
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Modern music player: large rounded artwork that breathes with play/pause,
 * title + artist with a heart, seek bar, big transport controls, radio and
 * download, plus an "Up next" card / queue button that opens the full queue
 * — so when a radio is running the user can pick any song from it.
 * Playback itself runs in the shared background player so music keeps
 * playing (with notification controls) even when the app is closed.
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: QueueEntry?) = bind()
        override fun onPlaybackStateChanged(isPlaying: Boolean) = bindPlayButton()
        override fun onResolvingChanged(isResolving: Boolean) {
            binding.loading.isVisible = isResolving
        }

        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = bindFavorite()

        // Radio keeps adding songs while it plays: keep "Up next" current.
        override fun onQueueChanged() = bindUpNext()
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            updateDownloadState()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        // Expands UP out of the mini player (and collapses back down).
        PanelTransitions.install(this)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PlaybackCenter.hasMedia) {
            finish()
            return
        }

        // Background music needs the notification: ask again here if the
        // user missed / denied the prompt shown on first launch.
        requestNotificationPermissionIfNeeded()

        binding.backButton.setOnClickListener { finish() }
        binding.playPauseButton.setOnClickListener { PlaybackCenter.togglePlayPause() }
        binding.prevButton.setOnClickListener { PlaybackCenter.previous() }
        binding.nextButton.setOnClickListener { PlaybackCenter.next() }
        binding.favoriteButton.setOnClickListener {
            val nowFav = PlaybackCenter.toggleFavoriteCurrent()
            Toast.makeText(
                this,
                if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites,
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.radioButton.setOnClickListener { restartRadio() }
        binding.downloadButton.setOnClickListener { onDownloadClicked() }
        binding.queueButton.setOnClickListener { showQueue() }
        binding.upNextCard.setOnClickListener { showQueue() }

        // The artwork is always a square that fits the space the controls
        // leave over (small phones / landscape included).
        binding.artContainer.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val size = minOf(r - l, b - t).coerceAtLeast(0)
            val lp = binding.art.layoutParams
            if (size > 0 && (lp.width != size || lp.height != size)) {
                lp.width = size
                lp.height = size
                binding.art.layoutParams = lp
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.position.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                seekBar?.let { PlaybackCenter.seekTo(it.progress.toLong()) }
            }
        })

        bind()
    }

    // ----- Swipe down to collapse into the mini player -----

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false

    /**
     * A downward swipe anywhere on the page (except on the seek bar, which
     * owns its own drags) collapses the player into the mini player, sliding
     * DOWN — the mirror of how it opened.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                swipeTracking = !isOnSeekBar(ev)
            }
            MotionEvent.ACTION_MOVE -> if (swipeTracking) {
                val dy = ev.rawY - swipeDownY
                val dx = kotlin.math.abs(ev.rawX - swipeDownX)
                if (dy > SWIPE_MIN_DISTANCE_PX && dy > dx * 1.5f) {
                    swipeTracking = false
                    // Let the touched child (button / card) drop its pressed state.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    finish()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeTracking = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isOnSeekBar(ev: MotionEvent): Boolean {
        val rect = android.graphics.Rect()
        if (!binding.seekBar.getGlobalVisibleRect(rect)) return false
        // A little slack: the thumb is easy to miss by a finger's width.
        val slack = (16 * resources.displayMetrics.density).toInt()
        rect.inset(-slack, -slack)
        return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    override fun finish() {
        super.finish()
        PanelTransitions.applyClose(this)
    }

    override fun onStart() {
        super.onStart()
        PlaybackCenter.addListener(playbackListener)
        handler.post(progressRunnable)
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were in the background.
        Themes.recreateIfNeeded(this)
    }

    override fun onStop() {
        super.onStop()
        PlaybackCenter.removeListener(playbackListener)
        handler.removeCallbacks(progressRunnable)
    }

    private fun bind() {
        val entry = PlaybackCenter.currentEntry ?: run { finish(); return }
        binding.title.text = entry.title
        binding.subtitle.text = entry.uploader
        Thumbs.load(binding.art, entry.thumbnailUrl)
        binding.playingLabel.isVisible = true
        bindPlayButton()
        bindFavorite()
        bindUpNext()
        updateProgress()
        updateDownloadState()
    }

    private fun bindPlayButton() {
        val isPlaying = PlaybackCenter.isPlaying
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        binding.playPauseButton.contentDescription = getString(
            if (isPlaying) R.string.cd_pause else R.string.cd_play
        )
        // Artwork "breathes": full size while playing, slightly smaller paused.
        val scale = if (isPlaying) 1f else 0.88f
        if (AppPrefs.animations) {
            binding.art.animate().scaleX(scale).scaleY(scale).setDuration(260).start()
        } else {
            binding.art.animate().cancel()
            binding.art.scaleX = scale
            binding.art.scaleY = scale
        }
    }

    /** "Up next" card + queue button; also flags a radio session in the header. */
    private fun bindUpNext() {
        if (isFinishing) return
        val queue = PlaybackCenter.queue
        val radio = PlaybackCenter.radioMode
        val next = queue.getOrNull(PlaybackCenter.queueIndex + 1)

        binding.playingLabel.setText(
            if (radio) R.string.playing_from_radio else R.string.now_playing
        )
        binding.queueButton.visibility =
            if (queue.size > 1 || radio) android.view.View.VISIBLE else android.view.View.INVISIBLE

        if (next != null) {
            binding.upNextCard.isVisible = true
            binding.upNextLabel.setText(
                if (radio) R.string.up_next_radio_label else R.string.up_next_label
            )
            binding.upNextTitle.text = next.title
        } else {
            // A radio that has not fetched its songs yet keeps the card as an
            // entry point instead of the queue button vanishing.
            binding.upNextCard.isVisible = radio
            binding.upNextLabel.setText(R.string.up_next_radio_label)
            binding.upNextTitle.setText(R.string.queue_loading)
        }
    }

    private fun showQueue() {
        QueueSheet(this).show()
    }

    private fun bindFavorite() {
        val entry = PlaybackCenter.currentEntry ?: return
        val isFav = LocalStore.isFavorite(this, entry.url)
        binding.favoriteButton.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        binding.favoriteButton.setColorFilter(
            if (isFav) getColor(R.color.music_green) else getColor(R.color.on_surface)
        )
    }

    private fun updateProgress() {
        if (userSeeking || isFinishing) return
        val duration = PlaybackCenter.durationMs
        val position = PlaybackCenter.positionMs
        binding.seekBar.max = if (duration > 0) duration.toInt() else 0
        binding.seekBar.progress = position.toInt()
        binding.position.text = formatTime(position)
        binding.duration.text = formatTime(duration)
    }

    /** Android 13+: without this the media notification (and its controls) stay hidden. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Optional: playback works either way.
    }

    private fun restartRadio() {
        val entry = PlaybackCenter.currentEntry ?: return
        PlaybackCenter.playMusic(entry, radio = true)
        Toast.makeText(this, R.string.radio_started, Toast.LENGTH_SHORT).show()
        // Straight into the radio's queue so any song can be picked.
        showQueue()
    }

    // ----- Download state -----

    private fun onDownloadClicked() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (DownloadCenter.isDownloaded(this, entry.url)) {
            Toast.makeText(this, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        showDownloadSheet()
    }

    /** Icon + status line for downloaded / downloading states. */
    private fun updateDownloadState() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (isFinishing) return
        // Re-sync with the system download manager so a finished download
        // shows "Downloaded" instead of sticking at "Downloading • 100%".
        if (DownloadCenter.hasActiveDownload(this, entry.url)) {
            DownloadCenter.refreshStatuses(this)
        }
        when {
            DownloadCenter.isDownloaded(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_check)
                binding.downloadButton.setColorFilter(getColor(R.color.music_green))
                binding.downloadStatus.isVisible = false
            }
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                binding.downloadButton.setImageResource(R.drawable.ic_download)
                binding.downloadButton.setColorFilter(getColor(R.color.on_surface))
                val percent = DownloadCenter.downloadProgress(this, entry.url)
                binding.downloadStatus.text = getString(R.string.downloading_fmt, percent)
                binding.downloadStatus.isVisible = true
            }
            else -> {
                binding.downloadButton.setImageResource(R.drawable.ic_download)
                binding.downloadButton.setColorFilter(getColor(R.color.on_surface))
                binding.downloadStatus.isVisible = false
            }
        }
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog ?: run {
            Toast.makeText(this, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        // This is the music player: only the audio section applies.
        DownloadSheet(this, entry, catalog, audioOnly = true).show()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 4712
        private const val SWIPE_MIN_DISTANCE_PX = 260f

        fun start(context: Context) {
            context.startActivity(Intent(context, NowPlayingActivity::class.java))
        }
    }
}
