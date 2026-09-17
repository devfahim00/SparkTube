package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.databinding.ItemSuggestionBinding

/** One row of the search drop-down: a remote suggestion or a saved search. */
data class SuggestionRow(
    val text: String,
    val isHistory: Boolean
)

/**
 * Keyword suggestions shown while the user types in search, mixed with saved
 * searches from the local search history. History rows use a clock icon and
 * expose a small delete button to drop a single entry.
 */
class SuggestionAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: ((String) -> Unit)? = null
) : ListAdapter<SuggestionRow, SuggestionAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SuggestionRow>() {
            override fun areItemsTheSame(a: SuggestionRow, b: SuggestionRow): Boolean = a.text == b.text
            override fun areContentsTheSame(a: SuggestionRow, b: SuggestionRow): Boolean = a == b
        }
    }

    class VH(val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.binding.suggestionText.text = row.text
        holder.binding.rowIcon.setImageResource(
            if (row.isHistory) R.drawable.ic_history else R.drawable.ic_search
        )
        if (row.isHistory && onDelete != null) {
            holder.binding.deleteButton.isVisible = true
            holder.binding.deleteButton.setOnClickListener { onDelete.invoke(row.text) }
        } else {
            holder.binding.deleteButton.isVisible = false
            holder.binding.deleteButton.setOnClickListener(null)
        }
        holder.binding.root.setOnClickListener { onClick(row.text) }
    }
}
