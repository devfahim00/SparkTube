package com.sparktube.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sparktube.app.R
import com.sparktube.app.data.Countries
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.FragmentHomeBinding
import com.sparktube.app.ui.common.SkeletonAdapter
import com.sparktube.app.ui.common.showSkeleton
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.ui.search.SearchActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private var loadedCountry: String? = null
    private var loadedGeneration: Int = -1
    private var loadJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(requireContext(), model)
        })
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { load() }

        binding.searchButton.setOnClickListener {
            startActivity(
                SearchActivity.intent(requireContext(), music = false)
            )
        }

        binding.retryButton.setOnClickListener { load() }
    }

    override fun onResume() {
        super.onResume()
        // Flag follows the selected country at all times.
        binding.countryFlag.text = Countries.flagOf(AppPrefs.countryOrDefault)
        maybeReload()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            binding.countryFlag.text = Countries.flagOf(AppPrefs.countryOrDefault)
            maybeReload()
        }
    }

    private fun maybeReload() {
        val current = AppPrefs.countryOrDefault
        val generation = RecommendEngine.generation(requireContext())
        if (current != loadedCountry || generation != loadedGeneration) {
            load()
        }
    }

    private fun load() {
        val country = AppPrefs.countryOrDefault
        loadedCountry = country
        loadedGeneration = RecommendEngine.generation(requireContext())

        loadJob?.cancel()
        // Capture the binding: a theme change relaunches the activity, which
        // destroys this view (nulling _binding) while the coroutine may still
        // be suspended on IO. Touching `binding` after that crashed the app
        // (NullPointerException in the finally block), so the coroutine works
        // on its own stable reference instead.
        val b = _binding ?: return
        val ctx = requireContext()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            b.errorView.isVisible = false
            b.emptyView.isVisible = false
            // Skeleton cards instead of a spinner: the feed keeps its shape
            // while the personalized list is being fetched.
            showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 10)
            try {
                val snap = withContext(Dispatchers.IO) {
                    RecommendEngine.snapshot(ctx)
                }
                val items = YtRepository.personalizedFeed(country, snap)
                b.list.adapter = adapter
                adapter.submitList(items.map { it.toUiModel() })
                b.emptyView.isVisible = items.isEmpty()
                if (items.isEmpty()) {
                    b.errorText.text = getString(R.string.error_no_results)
                    b.errorView.isVisible = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A newer load() cancelled this one: not an error.
                throw e
            } catch (e: Exception) {
                b.list.adapter = adapter
                adapter.submitList(emptyList())
                b.errorText.text = Formatters.friendlyException(e)
                b.errorView.isVisible = true
            } finally {
                b.swipe.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
