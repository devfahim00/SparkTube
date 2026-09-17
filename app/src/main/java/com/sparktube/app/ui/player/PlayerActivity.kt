package com.sparktube.app.ui.player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityPlayerBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.playback.toQueueEntry
import com.sparktube.app.ui.channel.ChannelActivity
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.util.Formatters
import coil.load
import kotlinx.coroutines.launch

/**
 * YouTube-style watch page: standard 16:9 player on top, title, views,
 * action pills (favorite / share / download / background audio / PiP),
 * quality + speed + audio-track controls, channel row with subscribe and
 * related videos below. A swipe down on the video shrinks playback into
 * the mini player.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var relatedAdapter: VideoAdapter
    private var channelSubCount: Long = -1L

    private var swipeTriggered = false
    private lateinit var gestureDetector: GestureDetector

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: QueueEntry?) {
            if (entry != null && entry.url != loadedUrl) {
                loadedUrl = entry.url
                bind(entry)
            }
        }

        override fun onResolvingChanged(isResolving: Boolean) {
            binding.loading.isVisible = isResolving && !binding.errorView.isVisible
        }

        override fun onCatalogReady() {
            bindCatalog()
        }

        override fun onError(message: String) {
            if (message.contains("LIVE_CONTENT")) {
                Toast.makeText(this@PlayerActivity, R.string.live_not_supported, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            binding.errorText.text = message
            binding.errorView.isVisible = true
        }

        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = updateFavoriteUi()

        override fun onAudioOnlyChanged(audioOnly: Boolean) = bindCatalog()
    }

    private var loadedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        relatedAdapter = VideoAdapter(onClick = { model ->
            // Play inside this page so swipe/mini flow keeps working.
            val entry = model.toQueueEntry()
            PlaybackCenter.playVideo(entry)
        })
        binding.relatedList.layoutManager = LinearLayoutManager(this)
        binding.relatedList.adapter = relatedAdapter

        binding.retryButton.setOnClickListener { retry() }

        binding.actionFavorite.setOnClickListener { toggleFavorite() }
        binding.actionShare.setOnClickListener { shareVideo() }
        binding.actionDownload.setOnClickListener { showDownloadSheet() }
        binding.actionBackground.setOnClickListener {
            PlaybackCenter.switchToAudioOnly()
            Toast.makeText(this, R.string.background_started, Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.actionPip.setOnClickListener { enterPipOrToast() }
        binding.actionQuality.setOnClickListener { showQualitySheet() }
        binding.actionSpeed.setOnClickListener { showSpeedSheet() }
        binding.actionAudioTrack.setOnClickListener { showAudioTrackSheet() }

        binding.channelInfo.setOnClickListener { openChannel() }
        binding.subscribeButton.setOnClickListener { toggleSubscribe() }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                swipeTriggered = false
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                if (!swipeTriggered && dy > SWIPE_MIN_DISTANCE && dy > kotlin.math.abs(dx) * 1.4f) {
                    swipeTriggered = true
                    // Swipe down on the video collapses playback into the mini player.
                    finish()
                }
                return false
            }
        })
        binding.playerView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            v.onTouchEvent(event)
        }

        applyOrientation(resources.configuration)

        if (intent.getBooleanExtra(EXTRA_ATTACH, false) && PlaybackCenter.hasMedia) {
            loadedUrl = PlaybackCenter.currentEntry?.url
            PlaybackCenter.currentEntry?.let(::bind)
            bindCatalog()
        } else {
            val entry = entryFromIntent()
            if (entry == null) {
                finish()
                return
            }
            if (PlaybackCenter.currentEntry?.url == entry.url && PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO) {
                PlaybackCenter.switchBackToVideo()
                loadedUrl = entry.url
                bind(entry)
                bindCatalog()
            } else {
                PlaybackCenter.playVideo(entry)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PlaybackCenter.addListener(playbackListener)
        if (PlaybackCenter.hasMedia) {
            PlaybackCenter.player.playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) {
            PlaybackCenter.attachView(binding.playerView)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            PlaybackCenter.detachView(binding.playerView)
        }
    }

    override fun onStop() {
        super.onStop()
        PlaybackCenter.removeListener(playbackListener)
    }

    // ----- PiP -----

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && PlaybackCenter.hasMedia &&
            PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO && !PlaybackCenter.audioOnlyMode
        ) {
            enterPip()
        }
    }

    private fun enterPipOrToast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPip()
        } else {
            Toast.makeText(this, R.string.pip_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } catch (e: Exception) {
            // Some ROMs throw when PiP is unavailable.
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlaybackCenter.inPip = isInPictureInPictureMode
        binding.backButton.isVisible = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.scrollArea.isVisible = false
            binding.loading.isVisible = false
            binding.errorView.isVisible = false
            // Expand player to the whole window
            binding.playerView.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimensionRatio = null
            }
        } else {
            binding.scrollArea.isVisible = true
            binding.playerView.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                height = 0
                dimensionRatio = "16:9"
            }
        }
    }

    // ----- Orientation -----

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            binding.scrollArea.isVisible = false
            binding.playerView.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimensionRatio = null
            }
        } else {
            binding.scrollArea.isVisible = true
            binding.playerView.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                height = 0
                dimensionRatio = "16:9"
            }
        }
    }

    // ----- Binding -----

    private fun entryFromIntent(): QueueEntry? {
        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        return QueueEntry(
            url = url,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty(),
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMB).orEmpty(),
            durationSec = intent.getLongExtra(EXTRA_DURATION, 0L)
        )
    }

    private fun bind(entry: QueueEntry) {
        binding.errorView.isVisible = false
        binding.title.text = entry.title
        binding.views.text = ""
        binding.channelName.text = entry.uploader
        binding.channelSubs.text = ""
        binding.channelAvatar.setImageResource(0)
        loadChannelInfo()

        updateFavoriteUi()

        // History + favourites may have changed, list them below.
        bindCatalog()
    }

    private fun bindCatalog() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog

        if (catalog != null) {
            binding.title.text = catalog.name.ifBlank { entry.title }
            binding.views.text = when {
                catalog.viewCount >= 0 ->
                    getString(R.string.views_fmt, Formatters.formatViewCount(catalog.viewCount))
                else -> ""
            }
            binding.channelName.text = catalog.uploaderName.ifBlank { entry.uploader }
            if (catalog.uploaderAvatarUrl.isNotBlank()) {
                binding.channelAvatar.load(catalog.uploaderAvatarUrl) {
                    placeholder(android.graphics.drawable.ColorDrawable(getColor(R.color.thumbnail_placeholder)))
                }
            }
            binding.actionAudioTrack.isVisible = catalog.audioTracks.size > 1

            relatedAdapter.submitList(catalog.related.map { it.toUiModel() })

            val height = PlaybackCenter.selectedQualityHeight
            binding.qualityValue.text = when {
                height != null -> height.toString() + "p"
                catalog.videoOnly.isNotEmpty() -> catalog.videoOnly.first().height.toString() + "p"
                catalog.muxed.isNotEmpty() -> catalog.muxed.first().height.toString() + "p"
                else -> getString(R.string.quality_audio_only)
            }
            val track = PlaybackCenter.selectedAudioTrackLabel
            binding.audioTrackValue.text = track ?: getString(R.string.action_audio_track)
        }
        binding.speedValue.text = if (PlaybackCenter.playbackSpeed == 1.0f) {
            getString(R.string.action_speed)
        } else {
            "${PlaybackCenter.playbackSpeed}x"
        }
        updateSubscribeUi()
    }

    private fun loadChannelInfo() {
        val uploaderUrl = PlaybackCenter.catalog?.uploaderUrl ?: return
        lifecycleScope.launch {
            try {
                val channel = YtRepository.channelInfo(uploaderUrl)
                channelSubCount = channel.subscriberCount
                binding.channelSubs.text =
                    getString(R.string.subscribers_fmt, Formatters.formatViewCount(channel.subscriberCount))
                updateSubscribeUi()
            } catch (e: Exception) {
                // Sub count is optional decoration; ignore failures.
            }
        }
    }

    private fun updateFavoriteUi() {
        val entry = PlaybackCenter.currentEntry ?: return
        val isFav = LocalStore.isFavorite(this, entry.url)
        binding.actionFavoriteIcon.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        binding.actionFavoriteIcon.setColorFilter(
            if (isFav) getColor(R.color.spark_red) else getColor(R.color.on_surface)
        )
        binding.actionFavoriteLabel.text =
            getString(if (isFav) R.string.saved_short else R.string.action_favorite)
    }

    private fun toggleFavorite() {
        val nowFav = PlaybackCenter.toggleFavoriteCurrent()
        Toast.makeText(
            this,
            if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareVideo() {
        val entry = PlaybackCenter.currentEntry ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, entry.url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
    }

    private fun openChannel() {
        val entry = PlaybackCenter.currentEntry ?: return
        val channelUrl = PlaybackCenter.catalog?.uploaderUrl ?: return
        if (channelUrl.isBlank()) return
        ChannelActivity.start(this, channelUrl, entry.uploader)
    }

    private fun toggleSubscribe() {
        val entry = PlaybackCenter.currentEntry ?: return
        val channelUrl = PlaybackCenter.catalog?.uploaderUrl ?: return
        if (channelUrl.isBlank()) return
        val channelEntry = com.sparktube.app.data.ChannelEntry(
            url = channelUrl,
            name = PlaybackCenter.catalog?.uploaderName ?: entry.uploader,
            avatarUrl = PlaybackCenter.catalog?.uploaderAvatarUrl.orEmpty(),
            subscriberCount = channelSubCount
        )
        val nowSubscribed = LocalStore.toggleSubscription(this, channelEntry)
        Toast.makeText(
            this,
            if (nowSubscribed) R.string.subscribed_toast else R.string.unsubscribed_toast,
            Toast.LENGTH_SHORT
        ).show()
        updateSubscribeUi()
    }

    private fun updateSubscribeUi() {
        val channelUrl = PlaybackCenter.catalog?.uploaderUrl
        if (channelUrl.isNullOrBlank()) {
            binding.subscribeButton.isVisible = false
            return
        }
        val subscribed = LocalStore.isSubscribed(this, channelUrl)
        binding.subscribeButton.text = getString(if (subscribed) R.string.subscribed else R.string.subscribe)
        binding.subscribeButton.setBackgroundResource(
            if (subscribed) R.drawable.bg_subscribe_on else R.drawable.bg_subscribe_off
        )
    }

    private fun retry() {
        binding.errorView.isVisible = false
        val entry = PlaybackCenter.currentEntry
        if (entry != null) {
            PlaybackCenter.playVideo(entry)
        }
    }

    // ----- Bottom sheets -----

    private fun showOptionSheet(
        title: String,
        options: List<Pair<String, Boolean>>,
        onSelect: (Int) -> Unit
    ) {
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(titleView)
        options.forEachIndexed { index, (label, selected) ->
            val row = TextView(this).apply {
                text = label
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(if (selected) getColor(R.color.white) else getColor(R.color.on_surface))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(
                        if (selected) getColor(R.color.spark_red)
                        else getColor(R.color.surface_elevated)
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
                setOnClickListener {
                    onSelect(index)
                    sheet.dismiss()
                }
            }
            root.addView(row)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(420)
        sheet.show()
    }

    private fun showQualitySheet() {
        val catalog = PlaybackCenter.catalog ?: return
        val current = PlaybackCenter.selectedQualityHeight
        val options = mutableListOf<Pair<String, Boolean>>()

        catalog.videoOnly.forEach { v ->
            val h = v.height
            options.add("${h}p (adaptive)" to (current == h))
        }
        // Muxed streams are only offered when no adaptive video exists
        // (muxed tops out around 360p/720p).
        if (catalog.videoOnly.isEmpty()) {
            catalog.muxed.forEach { v ->
                val h = v.height
                options.add("${h}p (muxed)" to (current == h))
            }
        }
        options.add(getString(R.string.quality_audio_only) to (current == -1))
        showOptionSheet(getString(R.string.quality_label), options) { index ->
            val chosen = catalog.videoOnly.getOrNull(index)
            when {
                index == options.size - 1 -> PlaybackCenter.setVideoQuality(-1)
                chosen != null -> PlaybackCenter.setVideoQuality(chosen.height)
                else -> PlaybackCenter.setVideoQuality(catalog.muxed.getOrNull(index)?.height)
            }
        }
    }

    private fun showSpeedSheet() {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val current = PlaybackCenter.playbackSpeed
        showOptionSheet(
            getString(R.string.speed_label),
            speeds.map { s -> (if (s == 1.0f) "Normal" else "${s}x") to (s == current) }
        ) { index ->
            PlaybackCenter.setSpeed(speeds[index])
            binding.speedValue.text =
                if (speeds[index] == 1.0f) getString(R.string.action_speed) else "${speeds[index]}x"
        }
    }

    private fun showAudioTrackSheet() {
        val catalog = PlaybackCenter.catalog ?: return
        if (catalog.audioTracks.isEmpty()) return
        val current = PlaybackCenter.selectedAudioTrackId
        val tracks = catalog.audioTracks
        showOptionSheet(
            getString(R.string.audio_track_label),
            tracks.map { t -> t.label to (current == t.id) }
        ) { index ->
            PlaybackCenter.setAudioTrack(tracks[index].id)
            binding.audioTrackValue.text = tracks[index].label
        }
    }

    private fun showDownloadSheet() {
        val entry = PlaybackCenter.currentEntry ?: return
        val catalog = PlaybackCenter.catalog ?: run {
            Toast.makeText(this, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        DownloadSheet(this, entry, catalog).show()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_UPLOADER = "extra_uploader"
        private const val EXTRA_THUMB = "extra_thumb"
        private const val EXTRA_DURATION = "extra_duration"
        private const val EXTRA_ATTACH = "extra_attach"
        private const val SWIPE_MIN_DISTANCE = 130f

        fun start(context: Context, model: com.sparktube.app.ui.common.VideoUiModel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, model.url)
                putExtra(EXTRA_TITLE, model.title)
                putExtra(EXTRA_UPLOADER, model.uploader)
                putExtra(EXTRA_THUMB, model.thumbnailUrl)
                putExtra(EXTRA_DURATION, model.durationSec)
            }
            context.startActivity(intent)
        }

        /** Re-opens the page for whatever is already playing in the mini player. */
        fun startResume(context: Context) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_ATTACH, true)
            }
            context.startActivity(intent)
        }
    }
}
