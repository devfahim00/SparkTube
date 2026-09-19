package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.data.Playlist
import com.sparktube.app.databinding.ItemPlaylistBinding
import com.sparktube.app.util.Thumbs

/**
 * Playlist rows on the Library → Playlists tab: name, video count and the
 * first video's thumbnail. Tap opens the playlist, long-press deletes it.
 */
class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Playlist>() {
            override fun areItemsTheSame(a: Playlist, b: Playlist): Boolean = a.id == b.id

            override fun areContentsTheSame(a: Playlist, b: Playlist): Boolean = a == b
        }
    }

    class VH(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.title.text = item.name
        b.meta.text = b.root.context.resources.getQuantityString(
            R.plurals.playlist_video_count, item.items.size, item.items.size
        )
        // The playlist "cover" is simply its first video's thumbnail.
        Thumbs.load(b.thumb, item.items.firstOrNull()?.thumbnailUrl)

        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
