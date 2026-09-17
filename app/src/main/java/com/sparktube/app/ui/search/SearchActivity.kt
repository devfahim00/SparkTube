package com.sparktube.app.ui.search

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivitySearchBinding
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: VideoAdapter

    private val items = mutableListOf<VideoUiModel>()
    private var page: Page? = null
    private var query: String = ""
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(this, model)
        })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener {
            binding.searchInput.setText("")
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

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - 4 && page != null && !isLoading) {
                    loadMore()
                }
            }
        })

        binding.searchInput.requestFocus()
        showKeyboard()
    }

    private fun doSearch() {
        val text = binding.searchInput.text?.toString()?.trim().orEmpty()
        hideKeyboard()
        if (text.isEmpty()) return
        query = text
        page = null
        items.clear()
        adapter.submitList(emptyList())
        binding.loading.isVisible = true
        binding.errorView.isVisible = false
        binding.emptyView.isVisible = false

        lifecycleScope.launch {
            try {
                val result = YtRepository.searchVideos(query, null)
                items.addAll(result.items.map { it.toUiModel() })
                page = result.nextPage
                adapter.submitList(items.toList())
                binding.emptyView.isVisible = items.isEmpty()
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
                val result = YtRepository.searchVideos(query, nextPage)
                val known = items.map { it.url }.toSet()
                items.addAll(result.items.map { it.toUiModel() }.filter { it.url !in known })
                page = result.nextPage
                adapter.submitList(items.toList())
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
}
