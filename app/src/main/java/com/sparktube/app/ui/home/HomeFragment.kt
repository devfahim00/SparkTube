package com.sparktube.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sparktube.app.BuildConfig
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
import com.sparktube.app.util.CrashReporter
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private var loadedCountry: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * Elapsed-realtime stamp of the last successful feed load. The feed now
     * refreshes on a schedule (see maybeReload + the ticker in onViewCreated),
     * usage: every search / video play used to bump the recommendation
     * generation and silently reload the whole list the moment the user came
     * back — scrolling position lost, skeletons flashing — for no visible
     * benefit.
     */
    private var lastLoadedAt = 0L

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

        // Test-crash button: verifies the local crash reporter pipeline
        // (CrashReporter writes reports into the "SparkTube" folder on the
        // device — Download/SparkTube on Android 10+).
        // DEBUG BUILDS ONLY — release users never see it.
        // Tap → uncaught exception → report file written to the device →
        // reopen the app and check Settings → Crash logs to view/share it.
        binding.crashTestButton.isVisible = BuildConfig.DEBUG
        binding.crashTestButton.setOnClickListener {
            CrashReporter.logBreadcrumb("Test crash button tapped on Home")
            CrashReporter.logBreadcrumb(
                "loaded_country=${loadedCountry ?: "<not loaded>"}"
            )
            throw RuntimeException(
                "SparkTube test crash — local crash report check"
            )
        }

        binding.retryButton.setOnClickListener { load() }

        // One automatic refresh every 5 minutes while the feed is on screen.
        // (The user can always pull-to-refresh manually.)
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_MS)
                if (_binding != null && isAdded && !isHidden && stale()) {
                    load()
                }
            }
        }
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

    /** True when the feed content is older than the auto-refresh interval. */
    private fun stale(): Boolean =
        lastLoadedAt == 0L || SystemClock.elapsedRealtime() - lastLoadedAt >= AUTO_REFRESH_MS

    /**
     * Reload only when the country changed (different trending pool) or the
     * feed is stale (> 5 min). Coming back from a search or the player does
     * NOT reload anything anymore.
     */
    private fun maybeReload() {
        val current = AppPrefs.countryOrDefault
        if (current != loadedCountry || stale()) {
            load()
        }
    }

    private fun load() {
        val country = AppPrefs.countryOrDefault
        loadedCountry = country
        lastLoadedAt = SystemClock.elapsedRealtime()

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
                // Mark as "never loaded" so the next return to this screen
                // retries automatically instead of waiting out the 5 min.
                lastLoadedAt = 0L
            } finally {
                b.swipe.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** The feed re-fetches itself at most once every 5 minutes. */
        const val AUTO_REFRESH_MS = 5 * 60 * 1000L
    }
}
