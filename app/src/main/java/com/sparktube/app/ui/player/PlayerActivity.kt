package com.sparktube.app.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media3.ui.R as Media3R
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityPlayerBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.playback.StreamCatalog
import com.sparktube.app.playback.effectiveHeight
import com.sparktube.app.ui.channel.ChannelActivity
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import coil.load
import kotlinx.coroutines.launch

/**
 * YouTube-style watch page: standard 16:9 player on top, title, views,
 * action pills (favorite / share / download / background audio / PiP),
 * channel row with subscribe and related videos below. Quality, playback
 * speed and the dubbing audio track live inside the player's gear menu.
 * A swipe down on the video shrinks playback into the mini player.
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

        override fun onNotice(message: String) {
            Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private var loadedUrl: String? = null

    private val downloadHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
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
        binding.actionDownload.setOnClickListener { onDownloadClicked() }
        binding.actionBackground.setOnClickListener {
            PlaybackCenter.switchToAudioOnly()
            Toast.makeText(this, R.string.background_started, Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.actionPip.setOnClickListener { enterPipOrToast() }

        binding.channelInfo.setOnClickListener { openChannel() }
        binding.subscribeButton.setOnClickListener { toggleSubscribe() }

        // Fullscreen toggle: portrait <-> landscape without restarting the activity.
        binding.fullscreenButton.setOnClickListener { toggleFullscreen() }

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

        hookGearMenu()

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

    /**
     * Puts quality / speed / audio-track selection behind the gear button of
     * the player's built-in controller.
     */
    private fun hookGearMenu() {
        val gear = binding.playerView.findViewById<View>(Media3R.id.exo_settings)
        gear?.setOnClickListener { showVideoSettingsSheet() }
    }

    private fun toggleFullscreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onStart() {
        super.onStart()
        PlaybackCenter.addListener(playbackListener)
        downloadHandler.post(downloadPoll)
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
        downloadHandler.removeCallbacks(downloadPoll)
        PlaybackCenter.removeListener(playbackListener)
        // Return to sensor portrait so the next video starts the standard way.
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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
        if (isInPictureInPictureMode) {
            // Show the video only: no back button, no fullscreen button, no page.
            binding.backButton.isVisible = false
            binding.fullscreenButton.isVisible = false
            binding.scrollArea.isVisible = false
            binding.loading.isVisible = false
            binding.errorView.isVisible = false
            // Expand player to the whole window
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimensionRatio = null
            }
        } else {
            binding.fullscreenButton.isVisible = true
            binding.scrollArea.isVisible = true
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
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
        binding.fullscreenButton.setImageResource(
            if (landscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        if (landscape) {
            binding.scrollArea.isVisible = false
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimensionRatio = null
            }
        } else {
            binding.scrollArea.isVisible = true
            binding.playerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
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
        updateDownloadUi()

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
                Thumbs.load(binding.channelAvatar, catalog.uploaderAvatarUrl)
            }
            relatedAdapter.submitList(catalog.related.map { it.toUiModel() })
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
            if (isFav) Themes.accentColor(this) else getColor(R.color.on_surface)
        )
        binding.actionFavoriteLabel.text =
            getString(if (isFav) R.string.saved_short else R.string.action_favorite)
    }

    // ----- Download pill -----

    private fun onDownloadClicked() {
        val entry = PlaybackCenter.currentEntry ?: return
        if (DownloadCenter.isDownloaded(this, entry.url)) {
            Toast.makeText(this, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        showDownloadSheet()
    }

    private fun updateDownloadUi() {
        val entry = PlaybackCenter.currentEntry ?: return
        when {
            DownloadCenter.isDownloaded(this, entry.url) -> {
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_check)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.music_green))
                binding.actionDownloadLabel.text = getString(R.string.downloaded_label)
            }
            DownloadCenter.hasActiveDownload(this, entry.url) -> {
                val percent = DownloadCenter.downloadProgress(this, entry.url)
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_download)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.on_surface))
                binding.actionDownloadLabel.text = getString(R.string.downloading_fmt, percent)
            }
            else -> {
                binding.actionDownloadIcon.setImageResource(R.drawable.ic_download)
                binding.actionDownloadIcon.setColorFilter(getColor(R.color.on_surface))
                binding.actionDownloadLabel.text = getString(R.string.action_download)
            }
        }
    }

    private val downloadPoll = object : Runnable {
        override fun run() {
            val entry = PlaybackCenter.currentEntry
            if (entry != null && !isFinishing) {
                updateDownloadUi()
            }
            downloadHandler.postDelayed(this, 1000)
        }
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

    private fun sheetRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(24))
    }

    private fun sheetTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(getColor(R.color.on_surface))
        setTypeface(null, Typeface.BOLD)
    }

    private fun sheetRow(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(if (selected) getColor(R.color.white) else Themes.onSurfaceColor(this@PlayerActivity))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(
                    if (selected) Themes.accentColor(this@PlayerActivity)
                    else Themes.elevatedColor(this@PlayerActivity)
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    /**
     * A category row for the gear menu: title on the left, the currently
     * selected value on the right and a chevron — like the official
     * YouTube player's settings menu.
     */
    private fun sheetMenuRow(mainLabel: String, valueLabel: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Themes.elevatedColor(this@PlayerActivity))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }

            addView(
                TextView(this@PlayerActivity).apply {
                    text = mainLabel
                    textSize = 15f
                    setTextColor(getColor(R.color.on_surface))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(
                TextView(this@PlayerActivity).apply {
                    text = valueLabel
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.on_surface_variant))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                }
            )
            addView(
                android.widget.ImageView(this@PlayerActivity).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(getColor(R.color.on_surface_variant))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                }
            )
        }

    private fun showSheet(sheet: BottomSheetDialog, root: LinearLayout, peekDp: Int = 420) {
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(peekDp)
        sheet.show()
    }

    /**
     * The gear menu: categories first (quality, playback speed, audio
     * track) — picking one opens its own list, like the official app.
     */
    private fun showVideoSettingsSheet() {
        val catalog = PlaybackCenter.catalog
        if (catalog == null) {
            Toast.makeText(this, R.string.error_no_streams, Toast.LENGTH_SHORT).show()
            return
        }

        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.video_settings)))

        root.addView(
            sheetMenuRow(getString(R.string.settings_quality_section), qualitySummary(catalog)) {
                sheet.dismiss()
                showQualitySheet()
            }
        )
        root.addView(
            sheetMenuRow(getString(R.string.settings_speed_section), speedSummary()) {
                sheet.dismiss()
                showSpeedSheet()
            }
        )
        if (catalog.audioTracks.size > 1) {
            root.addView(
                sheetMenuRow(getString(R.string.settings_audio_track_section), audioTrackSummary(catalog)) {
                    sheet.dismiss()
                    showAudioTrackSheet()
                }
            )
        }

        showSheet(sheet, root, peekDp = 320)
    }

    private fun qualitySummary(catalog: StreamCatalog): String {
        val current = PlaybackCenter.selectedQualityHeight ?: return getString(R.string.quality_auto_short)
        if (current == -1) return getString(R.string.quality_audio_only)
        val fps = catalog.videoOnly.firstOrNull { it.effectiveHeight() == current }?.fps
            ?: catalog.muxed.firstOrNull { it.effectiveHeight() == current }?.fps ?: 0
        return if (fps > 30) "${current}p $fps" else "${current}p"
    }

    private fun speedSummary(): String =
        if (PlaybackCenter.playbackSpeed == 1.0f) {
            getString(R.string.speed_normal)
        } else {
            "${PlaybackCenter.playbackSpeed}x"
        }

    private fun audioTrackSummary(catalog: StreamCatalog): String {
        val selected = PlaybackCenter.selectedAudioTrackId
        val track = catalog.audioTracks.firstOrNull { it.id == selected }
            ?: catalog.audioTracks.firstOrNull()
            ?: return ""
        return track.label + if (track.isOriginal) {
            " (${getString(R.string.audio_track_original)})"
        } else {
            ""
        }
    }

    private fun showQualitySheet() {
        val catalog = PlaybackCenter.catalog ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_quality_section)))

        val currentHeight = PlaybackCenter.selectedQualityHeight
        root.addView(
            sheetRow(getString(R.string.quality_auto), currentHeight == null) {
                PlaybackCenter.setVideoQuality(null)
                sheet.dismiss()
            }
        )
        val useMuxed = catalog.videoOnly.isEmpty()
        val candidates = if (useMuxed) catalog.muxed else catalog.videoOnly
        candidates.forEach { v ->
            val h = v.effectiveHeight()
            val label = "${h}p" +
                (if (v.fps > 30) " ${v.fps}fps" else "") +
                (if (useMuxed) " (muxed)" else "")
            root.addView(sheetRow(label, currentHeight == h) {
                PlaybackCenter.setVideoQuality(h)
                sheet.dismiss()
            })
        }
        root.addView(
            sheetRow(getString(R.string.quality_audio_only), currentHeight == -1) {
                PlaybackCenter.setVideoQuality(-1)
                sheet.dismiss()
            }
        )
        showSheet(sheet, root)
    }

    private fun showSpeedSheet() {
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_speed_section)))

        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val currentSpeed = PlaybackCenter.playbackSpeed
        speeds.forEach { s ->
            root.addView(
                sheetRow(
                    if (s == 1.0f) getString(R.string.speed_normal) else "${s}x",
                    s == currentSpeed
                ) {
                    PlaybackCenter.setSpeed(s)
                    sheet.dismiss()
                }
            )
        }
        showSheet(sheet, root)
    }

    private fun showAudioTrackSheet() {
        val catalog = PlaybackCenter.catalog ?: return
        val sheet = BottomSheetDialog(this)
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.settings_audio_track_section)))

        val currentTrack = PlaybackCenter.selectedAudioTrackId
        catalog.audioTracks.forEach { t ->
            val label = t.label + if (t.isOriginal) {
                " (${getString(R.string.audio_track_original)})"
            } else {
                ""
            }
            root.addView(
                sheetRow(label, currentTrack == t.id) {
                    PlaybackCenter.setAudioTrack(t.id)
                    Toast.makeText(
                        this,
                        getString(R.string.audio_track_switched_to, label),
                        Toast.LENGTH_SHORT
                    ).show()
                    sheet.dismiss()
                }
            )
        }
        showSheet(sheet, root)
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
