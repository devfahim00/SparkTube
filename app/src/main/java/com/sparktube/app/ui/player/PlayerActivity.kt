package com.sparktube.app.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.sparktube.app.R
import com.sparktube.app.data.LiveFilter
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityPlayerBinding
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.common.toEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var entry: VideoEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val model = VideoUiModel(
            url = intent.getStringExtra(EXTRA_URL).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty(),
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMB).orEmpty(),
            durationSec = intent.getLongExtra(EXTRA_DURATION, 0L),
            viewCount = -1L,
            uploadDate = ""
        )
        entry = model.toEntry()

        bindBasic(model)
        binding.backButton.setOnClickListener { finish() }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }

        load()
    }

    private fun bindBasic(model: VideoUiModel) {
        binding.title.text = model.title
        binding.uploader.text = model.uploader
        binding.meta.text = model.durationLabel
    }

    private fun load() {
        val url = entry?.url ?: return
        binding.loading.isVisible = true
        binding.errorView.isVisible = false

        lifecycleScope.launch {
            try {
                val info = YtRepository.streamInfo(url)

                // SparkTube never plays live content.
                if (LiveFilter.isLive(info)) {
                    Toast.makeText(this@PlayerActivity, R.string.live_not_supported, Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                val model = info.toUiModel()
                entry = model.toEntry()
                binding.title.text = model.title
                binding.uploader.text = model.uploader
                val labels = listOf(model.viewsLabel, model.durationLabel)
                    .filter { it.isNotBlank() }
                binding.meta.text = labels.joinToString(" • ")
                LocalStore.addToHistory(this@PlayerActivity, model.toEntry())
                updateFavoriteIcon()

                startPlayer(info)
            } catch (e: Exception) {
                binding.errorText.text = Formatters.friendlyException(e)
                binding.errorView.isVisible = true
            } finally {
                binding.loading.isVisible = false
            }
        }
    }

    private fun startPlayer(info: org.schabi.newpipe.extractor.stream.StreamInfo) {
        val progressive = info.videoStreams
            .filter { it.isUrl && !it.isVideoOnly }
            .maxByOrNull { it.height }

        val chosen = progressive?.url
            ?: info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }?.url

        if (chosen == null) {
            binding.errorText.text = getString(R.string.error_no_streams)
            binding.errorView.isVisible = true
            return
        }

        player = ExoPlayer.Builder(this).build().also { p ->
            binding.playerView.player = p
            p.setMediaItem(MediaItem.fromUri(chosen))
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun toggleFavorite() {
        val current = entry ?: return
        val nowFavorite = LocalStore.toggleFavorite(this, current)
        updateFavoriteIcon()
        Toast.makeText(
            this,
            if (nowFavorite) R.string.added_to_favorites else R.string.removed_from_favorites,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateFavoriteIcon() {
        val current = entry ?: return
        val isFav = LocalStore.isFavorite(this, current.url)
        binding.favoriteButton.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        binding.favoriteButton.setColorFilter(
            if (isFav) getColor(R.color.spark_red) else getColor(R.color.on_surface_variant)
        )
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_UPLOADER = "extra_uploader"
        private const val EXTRA_THUMB = "extra_thumb"
        private const val EXTRA_DURATION = "extra_duration"

        fun start(context: Context, model: VideoUiModel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, model.url)
                putExtra(EXTRA_TITLE, model.title)
                putExtra(EXTRA_UPLOADER, model.uploader)
                putExtra(EXTRA_THUMB, model.thumbnailUrl)
                putExtra(EXTRA_DURATION, model.durationSec)
            }
            context.startActivity(intent)
        }
    }
}
