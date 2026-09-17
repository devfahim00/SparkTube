package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sparktube.app.R
import com.sparktube.app.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoUiModel) -> Unit,
    private val onLongClick: ((VideoUiModel) -> Boolean)? = null
) : ListAdapter<VideoUiModel, VideoAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoUiModel>() {
            override fun areItemsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url

            override fun areContentsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url && a.title == b.title && a.viewsLabel == b.viewsLabel
        }
    }

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.title.text = item.title
        b.uploader.text = item.uploader
        val metaParts = listOf(item.viewsLabel, item.uploadDate).filter { it.isNotBlank() }
        b.meta.text = metaParts.joinToString(" • ")

        if (item.durationLabel.isEmpty()) {
            b.duration.visibility = android.view.View.GONE
        } else {
            b.duration.visibility = android.view.View.VISIBLE
            b.duration.text = item.durationLabel
        }

        b.thumbnail.load(item.thumbnailUrl) {
            crossfade(true)
            placeholder(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
            error(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
        }

        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            onLongClick?.invoke(item) ?: false
        }
    }
}
