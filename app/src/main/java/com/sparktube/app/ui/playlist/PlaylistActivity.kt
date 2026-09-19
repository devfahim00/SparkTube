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
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.Themes

/**
 * One playlist's videos. "Play all" starts the list from the top; tapping a
 * video starts it from that video. Either way the playlist becomes the play
 * queue, so each video that finishes hands over to the next one in the list.
 * A video with a finished local download plays from disk (works offline).
 * Long-press removes a video from the playlist.
 */
class PlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistBinding
    private lateinit var adapter: VideoAdapter

    private var playlistId = -1L

    /** The playlist's videos as currently shown (the play queue is built from this). */
    private var videos: List<VideoUiModel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getLongExtra(EXTRA_ID, -1L)

        adapter = VideoAdapter(
            onClick = { model -> playFrom(videos.indexOfFirst { it.url == model.url }) },
            onLongClick = { model ->
                confirmRemove(model)
                true
            }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.playAllButton.setOnClickListener { playFrom(0) }
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
        videos = playlist.items.map { it.toUiModel() }
        adapter.submitList(videos)
        binding.playAllRow.isVisible = videos.isNotEmpty()
        binding.videoCount.text = resources.getQuantityString(
            R.plurals.playlist_video_count, videos.size, videos.size
        )
        binding.emptyView.setText(R.string.empty_playlist_videos)
        binding.emptyView.isVisible = videos.isEmpty()
    }

    /**
     * Makes the whole playlist the play queue and opens the watch page on
     * [index]. Playback starts first and the page then attaches to it — the
     * same hand-off the mini player uses — so the queue (next / previous and
     * the auto-advance when a video ends) lives in PlaybackCenter and keeps
     * working when the page is closed into the mini player.
     */
    private fun playFrom(index: Int) {
        if (videos.isEmpty()) return
        PlaybackCenter.playPlaylist(videos.map { it.toQueueEntry() }, index.coerceAtLeast(0))
        PlayerActivity.startResume(this)
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
