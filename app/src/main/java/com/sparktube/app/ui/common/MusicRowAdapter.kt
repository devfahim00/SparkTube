package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sparktube.app.R
import com.sparktube.app.databinding.ItemMusicRowBinding

/** Spotify-style song row: square art, bold title, gray artist line. */
class MusicRowAdapter(
    private val onClick: (VideoUiModel) -> Unit
) : ListAdapter<VideoUiModel, MusicRowAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoUiModel>() {
            override fun areItemsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url

            override fun areContentsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url && a.title == b.title
        }
    }

    class VH(val binding: ItemMusicRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMusicRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.title.text = item.title
        b.subtitle.text = item.uploader
        b.duration.text = item.durationLabel

        b.art.load(item.thumbnailUrl) {
            crossfade(true)
            placeholder(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
            error(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
        }

        b.root.setOnClickListener { onClick(item) }
    }
}
