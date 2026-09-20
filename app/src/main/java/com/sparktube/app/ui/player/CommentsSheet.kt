package com.sparktube.app.ui.player

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.data.CommentUi
import com.sparktube.app.data.CommentsPage
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.SheetCommentsBinding
import com.sparktube.app.ui.common.CommentAdapter
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/**
 * YouTube-style comments sheet: opens tall over the watch page, lists top
 * comments and keeps loading the next page while the user scrolls. The first
 * page may be handed in (already fetched for the preview card) so the sheet
 * opens instantly.
 */
class CommentsSheet(
    context: Context,
    private val scope: LifecycleCoroutineScope,
    private val videoUrl: String,
    private val initial: CommentsPage?,
    private val onLoaded: (CommentsPage) -> Unit = {}
) : BottomSheetDialog(context) {

    private val binding = SheetCommentsBinding.inflate(LayoutInflater.from(context))
    private val adapter = CommentAdapter()
    private val items = mutableListOf<CommentUi>()

    private var nextPage: Page? = null
    private var total = -1
    private var loading = false
    private var job: Job? = null

    init {
        // A fixed tall sheet so the list gets real bounds inside the bottom sheet.
        val height = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
        binding.sheetRoot.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height
        )
        setContentView(binding.root)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null
        binding.closeButton.setOnClickListener { dismiss() }
        binding.status.setOnClickListener {
            if (items.isEmpty()) loadFirst()
        }
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || nextPage == null) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadMore()
            }
        })
        setOnDismissListener { job?.cancel() }
        setOnShowListener { behavior.state = BottomSheetBehavior.STATE_EXPANDED }

        val first = initial
        if (first != null) {
            showPage(first, replace = true)
        } else {
            loadFirst()
        }
    }

    private fun loadFirst() {
        job?.cancel()
        loading = true
        binding.status.isVisible = false
        binding.progress.isVisible = true
        job = scope.launch {
            try {
                val page = YtRepository.comments(videoUrl)
                onLoaded(page)
                showPage(page, replace = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                binding.progress.isVisible = false
                binding.status.setText(R.string.comments_error)
                binding.status.isVisible = true
            }
        }
    }

    private fun loadMore() {
        val page = nextPage ?: return
        loading = true
        job = scope.launch {
            try {
                val result = YtRepository.commentsMore(videoUrl, page)
                showPage(result, replace = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what is already shown; scrolling again retries.
                loading = false
            }
        }
    }

    private fun showPage(page: CommentsPage, replace: Boolean) {
        loading = false
        binding.progress.isVisible = false
        if (replace) items.clear()
        // Some pages repeat a pinned / top comment: skip exact duplicates.
        val known = items.map { it.id }.filter { it.isNotBlank() }.toHashSet()
        page.items.forEach { c ->
            if (c.id.isBlank() || known.add(c.id)) items.add(c)
        }
        nextPage = page.nextPage
        if (page.total >= 0) total = page.total
        adapter.submitList(items.toList())

        binding.countLabel.text = if (total > 0) {
            Formatters.formatViewCount(total.toLong())
        } else {
            ""
        }
        when {
            page.disabled -> showStatus(R.string.comments_disabled)
            items.isEmpty() -> showStatus(R.string.comments_empty)
            else -> binding.status.isVisible = false
        }
    }

    private fun showStatus(res: Int) {
        binding.status.setText(res)
        binding.status.isVisible = true
    }
}
