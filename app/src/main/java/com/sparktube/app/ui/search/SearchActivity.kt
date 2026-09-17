package com.sparktube.app.ui.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivitySearchBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.common.MusicRowAdapter
import com.sparktube.app.ui.common.SuggestionAdapter
import com.sparktube.app.ui.common.SuggestionRow
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Themes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/**
 * Search screen for both videos and music. While the user types, keyword
 * suggestions from YouTube are shown; picking one runs the search.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private var musicMode: Boolean = false
    private var prefillQuery: String? = null

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var musicAdapter: MusicRowAdapter
    private lateinit var suggestionAdapter: SuggestionAdapter

    private val items = mutableListOf<VideoUiModel>()
    private var page: Page? = null
    private var query: String = ""
    private var isLoading = false
    private var suggestionJob: Job? = null
    private var lastSuggestions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicMode = intent.getBooleanExtra(EXTRA_MUSIC, false)
        prefillQuery = intent.getStringExtra(EXTRA_PREFILL)

        if (musicMode) {
            binding.searchInput.hint = getString(R.string.search_music_hint)
        }

        videoAdapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(this, model)
        })
        musicAdapter = MusicRowAdapter(onClick = { model ->
            playMusic(model)
        })
        suggestionAdapter = SuggestionAdapter(
            onClick = { suggestion ->
                binding.searchInput.setText(suggestion)
                binding.searchInput.setSelection(suggestion.length)
                doSearch()
            },
            onDelete = { entry ->
                LocalStore.removeSearch(this, entry)
                val text = binding.searchInput.text?.toString().orEmpty()
                if (text.length < 2) {
                    showHistoryRows()
                } else {
                    showMergedRows(text, lastSuggestions)
                }
            }
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = if (musicMode) musicAdapter else videoAdapter
        binding.suggestionList.layoutManager = LinearLayoutManager(this)
        binding.suggestionList.adapter = suggestionAdapter

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= itemCount() - 4 && page != null && !isLoading) {
                    loadMore()
                }
            }
        })

        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener {
            binding.searchInput.setText("")
            showHistoryRows()
            binding.searchInput.requestFocus()
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                binding.clearButton.isVisible = text.isNotEmpty()
                if (text == query) {
                    // The input matches what is already searched: show results.
                    return
                }
                if (text.length < 2) {
                    suggestionJob?.cancel()
                    lastSuggestions = emptyList()
                    showHistoryRows()
                    return
                }
                queueSuggestions(text)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.searchInput.requestFocus()
        showKeyboard()
        // Fresh open with an empty input: surface the recent searches.
        if (prefillQuery.isNullOrBlank()) {
            showHistoryRows()
        }

        prefillQuery?.takeIf { it.isNotBlank() }?.let { prefill ->
            binding.searchInput.setText(prefill)
            binding.searchInput.setSelection(prefill.length)
            doSearch()
        }
    }

    private fun playMusic(model: VideoUiModel) {
        PlaybackCenter.playMusic(model.toQueueEntry(isMusic = true), radio = true)
        NowPlayingActivity.start(this)
    }

    private fun itemCount(): Int =
        if (musicMode) musicAdapter.itemCount else videoAdapter.itemCount

    private fun queueSuggestions(text: String) {
        suggestionJob?.cancel()
        suggestionJob = lifecycleScope.launch {
            delay(250)
            try {
                val suggestions = YtRepository.suggestions(text)
                if (binding.searchInput.text?.toString() == text) {
                    lastSuggestions = suggestions
                    showMergedRows(text, suggestions)
                }
            } catch (e: Exception) {
                // Suggestions are best-effort only.
            }
        }
    }

    /** Empty input: show the saved search history (newest first). */
    private fun showHistoryRows() {
        val searches = LocalStore.searches(this).take(15)
        if (searches.isEmpty()) {
            suggestionAdapter.submitList(emptyList())
            binding.suggestionList.isVisible = false
            return
        }
        suggestionAdapter.submitList(searches.map { SuggestionRow(it, isHistory = true) })
        binding.suggestionList.isVisible = true
    }

    /** Typing: matching saved searches on top, remote suggestions below. */
    private fun showMergedRows(text: String, suggestions: List<String>) {
        val lower = text.lowercase()
        val historyMatches = LocalStore.searches(this)
            .filter { it.lowercase().contains(lower) }
            .take(5)
        val historyKeys = historyMatches.map { it.lowercase() }.toSet()
        val rows = historyMatches.map { SuggestionRow(it, isHistory = true) } +
            suggestions
                .filter { it.lowercase() !in historyKeys }
                .take(10)
                .map { SuggestionRow(it, isHistory = false) }

        val showResults = items.isNotEmpty() || binding.loading.isVisible || binding.errorView.isVisible
        if (rows.isEmpty() || (showResults && rows.firstOrNull()?.text == query)) {
            binding.suggestionList.isVisible = false
        } else {
            binding.suggestionList.isVisible = true
        }
        suggestionAdapter.submitList(rows)
    }

    private fun doSearch() {
        val text = binding.searchInput.text?.toString()?.trim().orEmpty()
        hideKeyboard()
        if (text.isEmpty()) return
        query = text
        page = null
        items.clear()
        RecommendEngine.logSearch(this, text)
        binding.suggestionList.isVisible = false
        if (musicMode) musicAdapter.submitList(emptyList()) else videoAdapter.submitList(emptyList())
        binding.loading.isVisible = true
        binding.errorView.isVisible = false
        binding.emptyView.isVisible = false

        lifecycleScope.launch {
            try {
                val result = if (musicMode) {
                    YtRepository.searchMusic(query, null)
                } else {
                    YtRepository.searchVideos(query, null)
                }
                items.addAll(result.items.map { it.toUiModel() })
                page = result.nextPage
                if (musicMode) musicAdapter.submitList(items.toList()) else videoAdapter.submitList(items.toList())
                binding.emptyView.isVisible = items.isEmpty()
                if (items.isEmpty()) {
                    binding.errorText.text =
                        getString(if (musicMode) R.string.error_no_songs else R.string.error_no_results)
                    binding.errorView.isVisible = true
                }
            } catch (e: Exception) {
                binding.errorText.text = Formatters.friendlyException(e)
                binding.errorView.isVisible = true
            } finally {
                binding.loading.isVisible = false
            }
        }
    }

    private fun loadMore() {
        val nextPage = page ?: return
        isLoading = true
        lifecycleScope.launch {
            try {
                val result = if (musicMode) {
                    YtRepository.searchMusic(query, nextPage)
                } else {
                    YtRepository.searchVideos(query, nextPage)
                }
                val known = items.map { it.url }.toSet()
                items.addAll(result.items.map { it.toUiModel() }.filter { it.url !in known })
                page = result.nextPage
                if (musicMode) musicAdapter.submitList(items.toList()) else videoAdapter.submitList(items.toList())
            } catch (e: Exception) {
                page = null
            } finally {
                isLoading = false
            }
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    companion object {
        private const val EXTRA_MUSIC = "extra_music"
        private const val EXTRA_PREFILL = "extra_prefill"

        fun intent(context: Context, music: Boolean, prefill: String? = null): Intent =
            Intent(context, SearchActivity::class.java).apply {
                putExtra(EXTRA_MUSIC, music)
                putExtra(EXTRA_PREFILL, prefill)
            }
    }
}
