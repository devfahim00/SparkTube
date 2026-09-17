package com.sparktube.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.databinding.FragmentLibraryBinding
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.toEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.player.PlayerActivity

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private var tab = TAB_HISTORY

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = VideoAdapter(
            onClick = { model -> PlayerActivity.start(requireContext(), model) },
            onLongClick = { model ->
                if (tab == TAB_HISTORY) {
                    LocalStore.removeFromHistory(requireContext(), model.url)
                    Toast.makeText(requireContext(), R.string.removed_from_history, Toast.LENGTH_SHORT).show()
                } else {
                    LocalStore.toggleFavorite(requireContext(), model.toEntry())
                    Toast.makeText(requireContext(), R.string.removed_from_favorites, Toast.LENGTH_SHORT).show()
                }
                refresh()
                true
            }
        )

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                this@LibraryFragment.tab = tab.position
                refresh()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.tabs.selectTab(binding.tabs.getTabAt(TAB_HISTORY))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    private fun refresh() {
        val context = context ?: return
        val entries = if (tab == TAB_HISTORY) {
            LocalStore.history(context)
        } else {
            LocalStore.favorites(context)
        }
        adapter.submitList(entries.map { it.toUiModel() })
        binding.emptyView.setText(
            if (tab == TAB_HISTORY) R.string.empty_history else R.string.empty_favorites
        )
        binding.emptyView.isVisible = entries.isEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAB_HISTORY = 0
        private const val TAB_FAVORITES = 1
    }
}
