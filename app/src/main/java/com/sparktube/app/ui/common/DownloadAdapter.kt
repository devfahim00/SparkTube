package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sparktube.app.R
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.databinding.ItemDownloadBinding
import com.sparktube.app.download.DownloadCenter

/** Download entries on the Library > Downloads tab. */
class DownloadAdapter(
    private val onClick: (DownloadRecord) -> Unit,
    private val onLongClick: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadRecord>() {
            override fun areItemsTheSame(a: DownloadRecord, b: DownloadRecord): Boolean =
                a.videoId == b.videoId && a.type == b.type && a.quality == b.quality

            override fun areContentsTheSame(a: DownloadRecord, b: DownloadRecord): Boolean =
                a.status == b.status && a.title == b.title
        }
    }

    class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.title.text = item.title
        val typeLabel = when (item.type) {
            DownloadCenter.TYPE_AUDIO -> b.root.context.getString(R.string.download_type_audio)
            DownloadCenter.TYPE_AV -> b.root.context.getString(R.string.download_type_av)
            else -> b.root.context.getString(R.string.download_type_video)
        }
        val statusLabel = when (item.status) {
            DownloadCenter.STATUS_DONE -> b.root.context.getString(R.string.download_status_done)
            DownloadCenter.STATUS_RUNNING -> b.root.context.getString(R.string.download_status_running)
            DownloadCenter.STATUS_FAILED -> b.root.context.getString(R.string.download_status_failed)
            else -> b.root.context.getString(R.string.download_status_pending)
        }
        b.meta.text = "$typeLabel • ${item.quality} • $statusLabel"
        b.thumb.load(item.thumbnailUrl) {
            placeholder(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
            error(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
        }

        b.root.setOnClickListener { if (item.status == DownloadCenter.STATUS_DONE) onClick(item) }
        b.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
