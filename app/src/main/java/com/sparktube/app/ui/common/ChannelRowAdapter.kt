package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sparktube.app.R
import com.sparktube.app.data.ChannelEntry
import com.sparktube.app.databinding.ItemChannelRowBinding
import com.sparktube.app.util.Formatters

/** Channel row: subscriptions list (with unsubscribe) or search results (plain). */
class ChannelRowAdapter(
    private val onClick: (ChannelEntry) -> Unit,
    private val onUnsubscribe: ((ChannelEntry) -> Unit)? = null
) : ListAdapter<ChannelEntry, ChannelRowAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChannelEntry>() {
            override fun areItemsTheSame(a: ChannelEntry, b: ChannelEntry): Boolean =
                a.url == b.url

            override fun areContentsTheSame(a: ChannelEntry, b: ChannelEntry): Boolean =
                a.url == b.url && a.name == b.name
        }
    }

    class VH(val binding: ItemChannelRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChannelRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.name.text = item.name
        b.subs.text = if (item.subscriberCount >= 0) {
            b.root.context.getString(R.string.subscribers_fmt, Formatters.formatViewCount(item.subscriberCount))
        } else {
            ""
        }
        b.avatar.load(item.avatarUrl) {
            placeholder(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
            error(ContextCompat.getDrawable(b.root.context, R.color.thumbnail_placeholder))
        }

        b.root.setOnClickListener { onClick(item) }
        if (onUnsubscribe != null) {
            b.unsubscribe.isVisible = true
            b.unsubscribe.setOnClickListener { onUnsubscribe(item) }
        } else {
            // Search results only browse a channel — nothing to unsubscribe.
            b.unsubscribe.isVisible = false
        }
    }
}
