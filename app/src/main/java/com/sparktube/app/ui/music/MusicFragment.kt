package com.sparktube.app.ui.music

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
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
import com.sparktube.app.ui.common.SkeletonPulse
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
 * Modern music home: time-of-day greeting, search field, quick access tiles
 * (favorites / downloads), a trending shelf of rounded art cards, colorful
 * genre tiles and a popular-songs list. Tapping a song opens the Now Playing
 * screen and starts a radio of related tracks (its queue is one tap away).
 */
class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private var loadJob: kotlinx.coroutines.Job? = null

    /** Pulse animators of the currently shown skeleton placeholders. */
    private var skeletonPulse: SkeletonPulse? = null

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

        binding.greeting.setText(greetingRes())

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

    private fun greetingRes(): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            in 17..21 -> R.string.greeting_evening
            else -> R.string.greeting_night
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
            b.errorView.isVisible = false
            // Skeleton placeholders instead of a spinner: the trending shelf
            // and the popular list keep their shape while data loads.
            showMusicSkeleton(b)
            try {
                val result = YtRepository.musicTrending(AppPrefs.countryOrDefault)
                val models = result.items.map { it.toUiModel() }
                clearMusicSkeleton(b)
                bindTrending(b, models)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A newer load() cancelled this one: not an error.
                throw e
            } catch (e: Exception) {
                clearMusicSkeleton(b)
                b.errorText.text = Formatters.friendlyException(e)
                b.errorView.isVisible = true
            }
        }
    }

    /** Fills the shelf + popular list with pulsing placeholder cards. */
    private fun showMusicSkeleton(b: FragmentMusicBinding) {
        clearMusicSkeleton(b)
        b.shelfTitle.isVisible = true
        b.shelfScroller.isVisible = true
        b.popularTitle.isVisible = true
        val pulse = SkeletonPulse()
        skeletonPulse = pulse
        repeat(8) { i ->
            val card = layoutInflater.inflate(R.layout.item_skeleton_music_card, b.shelfRow, false)
            b.shelfRow.addView(card)
            pulse.attach(card, i)
        }
        repeat(6) { i ->
            val row = layoutInflater.inflate(R.layout.item_skeleton_row, b.popularList, false)
            b.popularList.addView(row)
            pulse.attach(row, i)
        }
    }

    /** Stops the placeholder pulse; real binders clear the views themselves. */
    private fun clearMusicSkeleton(b: FragmentMusicBinding) {
        skeletonPulse?.cancel()
        skeletonPulse = null
        b.shelfRow.removeAllViews()
        b.popularList.removeAllViews()
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
            val ctx = requireContext()
            val tile = FrameLayout(ctx).apply {
                minimumHeight = dp(84)
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = dp(16).toFloat()
                    colors = intArrayOf(genre.top, genre.bottom)
                }
                // Clip the decorative note to the rounded corners.
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                isClickable = true
                isFocusable = true

                // Big faded note peeking out of the corner.
                addView(
                    ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_music_note)
                        setColorFilter(Color.WHITE)
                        alpha = 0.22f
                        rotation = 22f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(dp(64), dp(64), Gravity.END or Gravity.BOTTOM).apply {
                        marginEnd = -dp(8)
                        bottomMargin = -dp(10)
                    }
                )
                addView(
                    TextView(ctx).apply {
                        text = genre.label
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.START or Gravity.TOP
                    ).apply { setMargins(dp(14), dp(14), dp(14), dp(14)) }
                )
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
