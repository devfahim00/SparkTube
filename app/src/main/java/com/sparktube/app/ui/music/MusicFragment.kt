package com.sparktube.app.ui.music

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.data.PageResult
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.FragmentMusicBinding
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.StreamInfoItem

private data class Genre(val label: String, val query: String?)

class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private val items = mutableListOf<VideoUiModel>()

    private var page: Page? = null
    private var isLoading = false
    private var currentGenre: Genre = GENRES.first()
    private var chips: List<TextView> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(requireContext(), model)
        })
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (lastVisible >= total - 4 && page != null && !isLoading) {
                    loadMore()
                }
            }
        })

        buildChips()
        loadGenre(GENRES.first())
    }

    private fun buildChips() {
        binding.chipRow.removeAllViews()
        val pad = dp(12f)
        chips = GENRES.map { genre ->
            TextView(requireContext(), null, 0).apply {
                text = genre.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(pad, dp(8f), pad, dp(8f))
                setTextColor(requireContext().getColorStateList(R.color.chip_text))
                setBackgroundResource(R.drawable.bg_chip)
                isClickable = true
                isFocusable = true
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).also {
                    (it as? android.widget.LinearLayout.LayoutParams)?.marginEnd = dp(8f)
                }
                setOnClickListener { loadGenre(genre) }
                binding.chipRow.addView(this)
            }
        }
        updateChipStates()
    }

    private fun loadGenre(genre: Genre) {
        currentGenre = genre
        updateChipStates()
        page = null
        items.clear()
        adapter.submitList(emptyList())
        binding.loading.isVisible = true
        binding.errorView.isVisible = false
        binding.emptyView.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result: PageResult = if (genre.query == null) {
                    YtRepository.musicTrending(AppPrefs.countryOrDefault)
                } else {
                    YtRepository.searchMusic(genre.query, null)
                }
                items.addAll(result.items.map { it.toUiModel() })
                page = result.nextPage
                adapter.submitList(items.toList())
                binding.emptyView.isVisible = items.isEmpty()
                if (items.isEmpty()) {
                    binding.errorText.text = getString(R.string.error_no_results)
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
        val genre = currentGenre
        val nextPage = page ?: return
        val query = genre.query ?: return
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = YtRepository.searchMusic(query, nextPage)
                val known = items.map { it.url }.toSet()
                items.addAll(result.items.map { it.toUiModel() }.filter { it.url !in known })
                page = result.nextPage
                adapter.submitList(items.toList())
            } catch (e: Exception) {
                // Pagination errors are soft: keep what we have.
                page = null
            } finally {
                isLoading = false
            }
        }
    }

    private fun updateChipStates() {
        chips.forEach { it.isSelected = it.text.toString() == currentGenre.label }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        ).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val GENRES = listOf(
            Genre("Trending", null),
            Genre("Pop", "pop hits"),
            Genre("Hip-Hop", "hip hop hits"),
            Genre("Bollywood", "bollywood songs"),
            Genre("Bangla", "bangla songs"),
            Genre("Rock", "rock hits"),
            Genre("EDM", "edm hits"),
            Genre("Lofi", "lofi songs"),
            Genre("Devotional", "devotional songs"),
            Genre("Classical", "classical music"),
            Genre("Indie", "indie songs"),
            Genre("Instrumental", "instrumental music")
        )
    }
}
