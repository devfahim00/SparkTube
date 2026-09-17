package com.sparktube.app.ui.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.data.Country
import com.sparktube.app.databinding.ItemCountryBinding

class CountryAdapter(
    private val onPick: (Country) -> Unit
) : ListAdapter<Country, CountryAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Country>() {
            override fun areItemsTheSame(a: Country, b: Country): Boolean = a.code == b.code
            override fun areContentsTheSame(a: Country, b: Country): Boolean = a == b
        }
    }

    private var selectedCode: String? = null

    fun setSelected(code: String?) {
        selectedCode = code
        notifyDataSetChanged()
    }

    class VH(val binding: ItemCountryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val country = getItem(position)
        val b = holder.binding
        b.flag.text = com.sparktube.app.data.Countries.flagOf(country.code)
        b.name.text = country.name
        b.check.isVisible = country.code == selectedCode
        b.root.setOnClickListener { onPick(country) }
    }
}
