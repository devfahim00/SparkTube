package com.sparktube.app.ui.music

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
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.databinding.ActivityMusicListBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.ui.common.MusicRowAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.util.Themes
import java.io.File

/**
 * The music page's own lists: Favorites (songs favorited from the player)
 * and Downloads (audio files). Playing anything here queues exactly this
 * list — favorites play favorites one by one, downloads play downloads.
 */
class MusicListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicListBinding
    private lateinit var adapter: MusicRowAdapter

    private var favourites = true
    private var entries: List<QueueEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMusicListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        favourites = intent.getBooleanExtra(EXTRA_FAVOURITES, true)
        binding.title.text =
            getString(if (favourites) R.string.music_favorites else R.string.music_downloads)

        adapter = MusicRowAdapter(
            onClick = { model ->
                val index = entries.indexOfFirst { it.url == model.url }
                if (index >= 0) {
                    PlaybackCenter.playMusicQueue(entries, index)
                    NowPlayingActivity.start(this)
                }
            },
            onLongClick = if (favourites) null else ({ model ->
                val record = LocalStore.musicDownloads(this)
                    .firstOrNull { "file://" + it.filePaths.firstOrNull() == model.url }
                if (record != null) {
                    confirmDelete(record)
                }
            })
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were in the background.
        Themes.recreateIfNeeded(this)
        reload()
    }

    private fun reload() {
        if (favourites) {
            entries = LocalStore.musicFavorites(this).map { it.toQueueEntry() }
            binding.emptyView.setText(R.string.empty_music_favorites)
        } else {
            DownloadCenter.refreshStatuses(this)
            entries = LocalStore.musicDownloads(this)
                .mapNotNull { record ->
                    val path = record.filePaths.firstOrNull { File(it).exists() }
                        ?: return@mapNotNull null
                    record.toQueueEntry(path)
                }
            binding.emptyView.setText(R.string.empty_music_downloads)
        }
        adapter.submitList(entries.map { it.toRowModel() })
        binding.emptyView.isVisible = entries.isEmpty()
    }

    private fun confirmDelete(record: com.sparktube.app.data.DownloadRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_delete_confirm)
            .setMessage(record.title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadCenter.delete(this, record)
                Toast.makeText(this, R.string.download_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_FAVOURITES = "extra_favourites"

        fun start(context: Context, favourites: Boolean) {
            context.startActivity(
                Intent(context, MusicListActivity::class.java)
                    .putExtra(EXTRA_FAVOURITES, favourites)
            )
        }
    }
}

private fun VideoEntry.toQueueEntry(): QueueEntry = QueueEntry(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    isMusic = true
)

private fun QueueEntry.toRowModel(): VideoUiModel = VideoUiModel(
    url = url,
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = durationSec,
    viewCount = -1L,
    uploadDate = ""
)

private fun com.sparktube.app.data.DownloadRecord.toQueueEntry(path: String): QueueEntry = QueueEntry(
    url = "file://$path",
    title = title,
    uploader = uploader,
    thumbnailUrl = thumbnailUrl,
    durationSec = 0L,
    isMusic = true
)
