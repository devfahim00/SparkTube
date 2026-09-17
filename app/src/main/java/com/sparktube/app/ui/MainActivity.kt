package com.sparktube.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.sparktube.app.R
import com.sparktube.app.databinding.ActivityMainBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.home.HomeFragment
import com.sparktube.app.ui.library.LibraryFragment
import com.sparktube.app.ui.menu.MenuFragment
import com.sparktube.app.ui.music.MusicFragment
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import coil.load

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedId: Int = R.id.navHome
    private val fragments = mutableMapOf<Int, Fragment>()
    private var lastBackPress = 0L

    private val progressHandler = Handler(Looper.getMainLooper())

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: com.sparktube.app.playback.QueueEntry?) = updateMiniPlayer()

        override fun onPlaybackStateChanged(isPlaying: Boolean) = updateMiniPlayer()

        override fun onQueueChanged() = updateMiniPlayer()

        override fun onAudioOnlyChanged(audioOnly: Boolean) = updateMiniPlayer()

        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = updateMiniPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            selectedId = savedInstanceState.getInt(STATE_SELECTED, R.id.navHome)
            // Re-attach fragments that survived a configuration change.
            NAV_IDS.forEach { id ->
                supportFragmentManager.findFragmentByTag(tagOf(id))?.let {
                    fragments[id] = it
                }
            }
        }

        binding.navHome.setOnClickListener { select(R.id.navHome) }
        binding.navMusic.setOnClickListener { select(R.id.navMusic) }
        binding.navLibrary.setOnClickListener { select(R.id.navLibrary) }
        binding.navMenu.setOnClickListener { select(R.id.navMenu) }

        binding.miniPlayPause.setOnClickListener { PlaybackCenter.togglePlayPause() }
        binding.miniClose.setOnClickListener {
            PlaybackCenter.stopPlayback()
        }
        binding.miniPlayer.setOnClickListener {
            val entry = PlaybackCenter.currentEntry ?: return@setOnClickListener
            if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO) {
                NowPlayingActivity.start(this)
            } else {
                PlayerActivity.startResume(this)
            }
        }

        applySelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, selectedId)
    }

    override fun onResume() {
        super.onResume()
        PlaybackCenter.addListener(playbackListener)
        bindMiniPlayer()
    }

    override fun onPause() {
        super.onPause()
        PlaybackCenter.removeListener(playbackListener)
        progressHandler.removeCallbacksAndMessages(null)
        // Hand the video surface over to whoever is coming on top.
        PlaybackCenter.detachView(binding.miniVideo)
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackCenter.removeListener(playbackListener)
    }

    private fun bindMiniPlayer() {
        val hasMedia = PlaybackCenter.hasMedia && !PlaybackCenter.inPip
        binding.miniPlayer.isVisible = hasMedia
        if (!hasMedia) {
            PlaybackCenter.detachView(binding.miniVideo)
            return
        }
        if (PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO && !PlaybackCenter.audioOnlyMode) {
            // Live video surface in the mini player, like YouTube.
            binding.miniThumb.isVisible = false
            binding.miniVideo.isVisible = true
            PlaybackCenter.attachView(binding.miniVideo)
        } else {
            PlaybackCenter.detachView(binding.miniVideo)
            binding.miniVideo.isVisible = false
            binding.miniThumb.isVisible = true
        }
        updateMiniPlayer()
    }

    private fun updateMiniPlayer() {
        if (isFinishing || isDestroyed) return
        val entry = PlaybackCenter.currentEntry
        val show = entry != null && !PlaybackCenter.inPip
        binding.miniPlayer.isVisible = show
        if (entry == null) return

        binding.miniTitle.text = entry.title
        binding.miniTitle.isSelected = true
        binding.miniSubtitle.text = entry.uploader
        binding.miniThumb.load(entry.thumbnailUrl) {
            placeholder(android.graphics.drawable.ColorDrawable(getColor(R.color.thumbnail_placeholder)))
            error(android.graphics.drawable.ColorDrawable(getColor(R.color.thumbnail_placeholder)))
        }
        binding.miniPlayPause.setImageResource(
            if (PlaybackCenter.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun select(id: Int) {
        if (id == selectedId) return
        selectedId = id
        applySelection()
    }

    private fun applySelection() {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fragments.values.forEach { tx.hide(it) }
        val target = fragments[selectedId] ?: createFragment(selectedId).also {
            fragments[selectedId] = it
            tx.add(R.id.container, it, tagOf(selectedId))
        }
        tx.show(target)
        tx.commit()

        val isActive = { view: View -> view.id == selectedId }
        binding.navHome.isSelected = isActive(binding.navHome)
        binding.navMusic.isSelected = isActive(binding.navMusic)
        binding.navLibrary.isSelected = isActive(binding.navLibrary)
        binding.navMenu.isSelected = isActive(binding.navMenu)
    }

    private fun createFragment(id: Int): Fragment = when (id) {
        R.id.navHome -> HomeFragment()
        R.id.navMusic -> MusicFragment()
        R.id.navLibrary -> LibraryFragment()
        else -> MenuFragment()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedId != R.id.navHome) {
            select(R.id.navHome)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val STATE_SELECTED = "selected_nav"
        private val NAV_IDS = intArrayOf(R.id.navHome, R.id.navMusic, R.id.navLibrary, R.id.navMenu)
        private fun tagOf(id: Int) = "frag_$id"
    }
}
