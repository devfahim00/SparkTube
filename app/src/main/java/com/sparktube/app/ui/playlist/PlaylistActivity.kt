package com.sparktube.app.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.databinding.ActivityPlaylistBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.Themes

/**
 * One playlist's videos. Tapping a video plays it — from the local download
 * when a finished one exists (works offline), online otherwise. Long-press
 * removes a video from the playlist.
 */
class PlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistBinding
    private lateinit var adapter: VideoAdapter

    private var playlistId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getLongExtra(EXTRA_ID, -1L)

        adapter = VideoAdapter(
            onClick = { model -> play(model) },
            onLongClick = { model ->
                confirmRemove(model)
                true
            }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were in the background.
        if (Themes.recreateIfNeeded(this)) return
        reload()
    }

    private fun reload() {
        val playlist = LocalStore.playlist(this, playlistId)
        if (playlist == null) {
            // Deleted elsewhere (or bad id): nothing to show.
            finish()
            return
        }
        binding.title.text = playlist.name
        adapter.submitList(playlist.items.map { it.toUiModel() })
        binding.emptyView.setText(R.string.empty_playlist_videos)
        binding.emptyView.isVisible = playlist.items.isEmpty()
    }

    private fun play(model: VideoUiModel) {
        // Prefer a finished local download of this video (offline-friendly).
        val record = DownloadCenter.recordsFor(this, model.url)
            .filter { it.status == DownloadCenter.STATUS_DONE && it.type != DownloadCenter.TYPE_AUDIO }
            .firstOrNull()
        if (record != null) {
            PlaybackCenter.playDownload(record)
            PlayerActivity.startResume(this)
        } else {
            PlayerActivity.start(this, model)
        }
    }

    private fun confirmRemove(model: VideoUiModel) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_from_playlist_confirm)
            .setMessage(model.title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LocalStore.removeFromPlaylist(this, playlistId, model.url)
                Toast.makeText(this, R.string.removed_from_playlist, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "extra_playlist_id"

        fun start(context: Context, playlistId: Long) {
            context.startActivity(
                Intent(context, PlaylistActivity::class.java).putExtra(EXTRA_ID, playlistId)
            )
        }
    }
}
