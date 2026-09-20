package com.sparktube.app.ui.music

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.databinding.ItemQueueRowBinding
import com.sparktube.app.databinding.SheetQueueBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Thumbs

/** One row of the queue sheet. */
data class QueueRow(
    val index: Int,
    val entry: QueueEntry,
    val isCurrent: Boolean,
    val isPast: Boolean
)

/**
 * The music queue ("Up next"): the current song, what already played and what
 * comes next. While a radio is running this is the radio's song list, so the
 * user can jump to whichever track they feel like instead of waiting for the
 * auto-generated order. Updates live while the radio keeps adding songs.
 */
class QueueSheet(context: Context) : BottomSheetDialog(context) {

    private val binding = SheetQueueBinding.inflate(LayoutInflater.from(context))
    private val adapter = QueueAdapter { row ->
        PlaybackCenter.playQueueIndex(row.index)
        dismiss()
    }
    private var scrolledToCurrent = false

    private val listener = object : PlaybackCenter.Listener {
        override fun onQueueChanged() = refresh()
        override fun onItemChanged(entry: QueueEntry?) = refresh()
    }

    init {
        val height = (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
        binding.sheetRoot.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height
        )
        setContentView(binding.root)
        behavior.skipCollapsed = true
        setOnShowListener { behavior.state = BottomSheetBehavior.STATE_EXPANDED }

        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null
        binding.closeButton.setOnClickListener { dismiss() }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        PlaybackCenter.addListener(listener)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        PlaybackCenter.removeListener(listener)
    }

    private fun refresh() {
        val queue = PlaybackCenter.queue.toList()
        val current = PlaybackCenter.queueIndex
        val radio = PlaybackCenter.radioMode

        binding.radioBadge.isVisible = radio
        binding.countLabel.text =
            binding.root.context.getString(R.string.queue_songs_fmt, queue.size)

        val rows = queue.mapIndexed { i, entry ->
            QueueRow(
                index = i,
                entry = entry,
                isCurrent = i == current,
                isPast = i < current
            )
        }

        when {
            rows.isEmpty() -> {
                binding.status.setText(R.string.queue_empty)
                binding.status.isVisible = true
            }
            rows.size == 1 && radio -> {
                // Radio just started: its songs are still being fetched.
                binding.status.setText(R.string.queue_loading)
                binding.status.isVisible = true
            }
            else -> binding.status.isVisible = false
        }

        val firstFill = !scrolledToCurrent && rows.isNotEmpty()
        adapter.submitList(rows) {
            if (firstFill) {
                scrolledToCurrent = true
                val lm = binding.list.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(current.coerceAtLeast(0), 0)
            }
        }
    }

    private class QueueAdapter(
        private val onPick: (QueueRow) -> Unit
    ) : ListAdapter<QueueRow, QueueAdapter.VH>(DIFF) {

        class VH(val binding: ItemQueueRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemQueueRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = getItem(position)
            val b = holder.binding
            val ctx = b.root.context

            b.title.text = row.entry.title
            b.subtitle.text = row.entry.uploader
            b.duration.text = Formatters.formatDuration(row.entry.durationSec)
            Thumbs.load(b.art, row.entry.thumbnailUrl)

            b.title.setTextColor(
                ctx.getColor(if (row.isCurrent) R.color.music_green else R.color.on_surface)
            )
            b.playingIcon.isVisible = row.isCurrent
            b.duration.isVisible = !row.isCurrent
            // Songs that already played fade back so "what's next" stands out.
            b.root.alpha = if (row.isPast) 0.55f else 1f
            b.root.setOnClickListener { onPick(row) }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<QueueRow>() {
                override fun areItemsTheSame(a: QueueRow, b: QueueRow): Boolean =
                    a.index == b.index && a.entry.url == b.entry.url

                override fun areContentsTheSame(a: QueueRow, b: QueueRow): Boolean = a == b
            }
        }
    }
}
