package com.sparktube.app.ui.music

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import coil.load
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.databinding.ActivityNowPlayingBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.player.DownloadSheet
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Spotify-style music screen: big artwork, seek bar with elapsed and total
 * time, play/pause/next/previous, favorite, start radio and download.
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
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PlaybackCenter.hasMedia) {
            finish()
            return
        }

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
        binding.downloadButton.setOnClickListener { showDownloadSheet() }

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

    override fun onStart() {
        super.onStart()
        PlaybackCenter.addListener(playbackListener)
        handler.post(progressRunnable)
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
        binding.art.load(entry.thumbnailUrl) {
            crossfade(true)
            placeholder(android.graphics.drawable.ColorDrawable(getColor(R.color.thumbnail_placeholder)))
            error(android.graphics.drawable.ColorDrawable(getColor(R.color.thumbnail_placeholder)))
        }
        binding.playingLabel.isVisible = true
        bindPlayButton()
        bindFavorite()
        updateProgress()
    }

    private fun bindPlayButton() {
        binding.playPauseButton.setImageResource(
            if (PlaybackCenter.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
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

    private fun restartRadio() {
        val entry = PlaybackCenter.currentEntry ?: return
        PlaybackCenter.playMusic(entry, radio = true)
        Toast.makeText(this, R.string.radio_started, Toast.LENGTH_SHORT).show()
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog ?: run {
            Toast.makeText(this, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        DownloadSheet(this, entry, catalog).show()
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
        fun start(context: Context) {
            context.startActivity(Intent(context, NowPlayingActivity::class.java))
        }
    }
}
