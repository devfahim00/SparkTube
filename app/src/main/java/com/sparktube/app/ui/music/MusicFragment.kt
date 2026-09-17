package com.sparktube.app.ui.music

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.sparktube.app.R
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.FragmentMusicBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.search.SearchActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Thumbs
import kotlinx.coroutines.launch

private data class Genre(val label: String, val query: String?, val top: Int, val bottom: Int)

/**
 * Spotify-inspired music home: big trending shelf of square art cards,
 * colorful genre tiles and a popular-songs list. Tapping a song opens the
 * Now Playing screen and starts a radio of related tracks.
 */
class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private var loadJob: kotlinx.coroutines.Job? = null

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

        binding.searchButton.setOnClickListener {
            startActivity(SearchActivity.intent(requireContext(), music = true))
        }
        binding.favoritesButton.setOnClickListener {
            MusicListActivity.start(requireContext(), favourites = true)
        }
        binding.downloadsButton.setOnClickListener {
            MusicListActivity.start(requireContext(), favourites = false)
        }

        buildGenreTiles()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (binding.shelfRow.childCount == 0) {
            load()
        }
    }

    private fun playSong(model: VideoUiModel, radio: Boolean) {
        PlaybackCenter.playMusic(model.toQueueEntry(isMusic = true), radio = radio)
        NowPlayingActivity.start(requireContext())
    }

    private fun load() {
        loadJob?.cancel()
        // Same as HomeFragment: work on a captured binding reference so a
        // theme-change relaunch (view destroyed mid-coroutine) cannot NPE.
        val b = _binding ?: return
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            b.loading.isVisible = true
            b.errorView.isVisible = false
            try {
                val result = YtRepository.musicTrending(AppPrefs.countryOrDefault)
                val models = result.items.map { it.toUiModel() }
                bindTrending(b, models)
            } catch (e: Exception) {
                b.errorText.text = Formatters.friendlyException(e)
                b.errorView.isVisible = true
            } finally {
                b.loading.isVisible = false
            }
        }
    }

    private fun bindTrending(b: FragmentMusicBinding, models: List<VideoUiModel>) {
        // --- Trending shelf (square art cards) ---
        b.shelfTitle.isVisible = models.isNotEmpty()
        b.shelfScroller.isVisible = models.isNotEmpty()
        b.shelfRow.removeAllViews()
        models.take(12).forEach { model ->
            val card = layoutInflater.inflate(R.layout.item_music_card, b.shelfRow, false)
            Thumbs.load(card.findViewById(R.id.art), model.thumbnailUrl)
            card.findViewById<TextView>(R.id.title).text = model.title
            card.findViewById<TextView>(R.id.subtitle).text = model.uploader
            card.setOnClickListener { playSong(model, radio = true) }
            b.shelfRow.addView(card)
        }

        // --- Popular list ---
        b.popularTitle.isVisible = models.isNotEmpty()
        b.popularList.removeAllViews()
        models.drop(12).take(15).forEach { model ->
            val row = layoutInflater.inflate(R.layout.item_music_row, b.popularList, false)
            Thumbs.load(row.findViewById(R.id.art), model.thumbnailUrl)
            row.findViewById<TextView>(R.id.title).text = model.title
            row.findViewById<TextView>(R.id.subtitle).text = model.uploader
            row.findViewById<TextView>(R.id.duration).text = model.durationLabel
            row.setOnClickListener { playSong(model, radio = true) }
            b.popularList.addView(row)
        }
    }

    private fun buildGenreTiles() {
        val grid = binding.genreGrid
        grid.removeAllViews()
        val margin = dp(6)
        GENRES.forEach { genre ->
            val tile = TextView(requireContext()).apply {
                text = genre.label
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(16), dp(28), dp(16), dp(28))
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = dp(14).toFloat()
                    colors = intArrayOf(genre.top, genre.bottom)
                }
                setOnClickListener {
                    if (genre.query == null) {
                        // Trending chip: scroll back to the top shelf.
                        binding.root.smoothScrollTo(0, 0)
                        load()
                    } else {
                        startActivity(
                            SearchActivity.intent(requireContext(), music = true, prefill = genre.query)
                        )
                    }
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                topMargin = margin
                bottomMargin = margin
                marginStart = margin / 2
                marginEnd = margin / 2
            }
            grid.addView(tile, params)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val GENRES = listOf(
            Genre("Trending", null, 0xFF1DB954.toInt(), 0xFF126B3A.toInt()),
            Genre("Pop", "pop hits", 0xFFF06292.toInt(), 0xFFAD1457.toInt()),
            Genre("Hip-Hop", "hip hop hits", 0xFF7E57C2.toInt(), 0xFF4527A0.toInt()),
            Genre("Bollywood", "bollywood songs", 0xFFFFA726.toInt(), 0xFFE65100.toInt()),
            Genre("Bangla", "bangla songs", 0xFF66BB6A.toInt(), 0xFF2E7D32.toInt()),
            Genre("Rock", "rock hits", 0xFFEF5350.toInt(), 0xFFB71C1C.toInt()),
            Genre("EDM", "edm hits", 0xFF26C6DA.toInt(), 0xFF00838F.toInt()),
            Genre("Lofi", "lofi songs", 0xFF8D6E63.toInt(), 0xFF4E342E.toInt()),
            Genre("Classical", "classical music", 0xFFAB47BC.toInt(), 0xFF6A1B9A.toInt()),
            Genre("Indie", "indie songs", 0xFF42A5F5.toInt(), 0xFF1565C0.toInt()),
            Genre("Devotional", "devotional songs", 0xFFFFB300.toInt(), 0xFFFF8F00.toInt()),
            Genre("Instrumental", "instrumental music", 0xFF26A69A.toInt(), 0xFF00695C.toInt())
        )
    }
}
