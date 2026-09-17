package com.sparktube.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.databinding.ItemSuggestionBinding

/** Keyword suggestions shown while the user types in search. */
class SuggestionAdapter(
    private val onClick: (String) -> Unit
) : ListAdapter<String, SuggestionAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String): Boolean = a == b
            override fun areContentsTheSame(a: String, b: String): Boolean = a == b
        }
    }

    class VH(val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val text = getItem(position)
        holder.binding.suggestionText.text = text
        holder.binding.root.setOnClickListener { onClick(text) }
    }
}
