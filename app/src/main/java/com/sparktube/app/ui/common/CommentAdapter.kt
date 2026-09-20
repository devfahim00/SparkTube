package com.sparktube.app.ui.common

import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.data.CommentUi
import com.sparktube.app.databinding.ItemCommentBinding
import com.sparktube.app.databinding.ItemCommentMoreBinding
import com.sparktube.app.util.Avatars
import com.sparktube.app.util.Formatters

/** Stable identity of a comment (falls back to author + text when it has no id). */
val CommentUi.key: String
    get() = if (id.isNotBlank()) id else "$author|${text.hashCode()}"

/** Whether the replies of a top-level comment are hidden, loading or shown. */
enum class RepliesState { COLLAPSED, LOADING, EXPANDED }

/** One visible row of the comments list: a comment / reply, or a "more replies" footer. */
sealed class CommentRow {
    data class Comment(
        val item: CommentUi,
        val repliesState: RepliesState = RepliesState.COLLAPSED
    ) : CommentRow()

    data class MoreReplies(val parentKey: String, val loading: Boolean) : CommentRow()
}

/**
 * Comment rows: avatar, author · date, body, likes, hearted mark and a
 * "View N replies" toggle that expands the replies right under the comment
 * (indented, with a smaller avatar), plus a "Show more replies" footer.
 */
class CommentAdapter(
    private val onToggleReplies: (CommentUi) -> Unit = {},
    private val onMoreReplies: (String) -> Unit = {}
) : ListAdapter<CommentRow, RecyclerView.ViewHolder>(DIFF) {

    class CommentVH(val binding: ItemCommentBinding) : RecyclerView.ViewHolder(binding.root)
    class MoreVH(val binding: ItemCommentMoreBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is CommentRow.Comment -> TYPE_COMMENT
        is CommentRow.MoreReplies -> TYPE_MORE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MORE) {
            MoreVH(ItemCommentMoreBinding.inflate(inflater, parent, false))
        } else {
            CommentVH(ItemCommentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is CommentRow.Comment -> bindComment(holder as CommentVH, row)
            is CommentRow.MoreReplies -> bindMore(holder as MoreVH, row)
        }
    }

    private fun bindMore(holder: MoreVH, row: CommentRow.MoreReplies) {
        val b = holder.binding
        b.moreProgress.isVisible = row.loading
        b.root.isClickable = !row.loading
        b.root.setOnClickListener {
            if (!row.loading) onMoreReplies(row.parentKey)
        }
    }

    private fun bindComment(holder: CommentVH, row: CommentRow.Comment) {
        val item = row.item
        val b = holder.binding
        val ctx = b.root.context
        val density = ctx.resources.displayMetrics.density

        // Replies are indented and use a smaller avatar.
        val avatarSize = ((if (item.isReply) 26 else 36) * density).toInt()
        val lp = b.avatar.layoutParams
        if (lp.width != avatarSize || lp.height != avatarSize) {
            lp.width = avatarSize
            lp.height = avatarSize
            b.avatar.layoutParams = lp
        }
        b.root.updatePaddingRelative(start = ((if (item.isReply) 68 else 16) * density).toInt())

        val author = item.author.trim().removePrefix("@")
        Avatars.load(b.avatar, item.avatarUrl, author)

        b.meta.text = if (item.date.isBlank()) author else "$author  ·  ${item.date}"
        b.pinIcon.isVisible = item.pinned

        b.text.text = commentText(item)
        b.text.movementMethod = LinkMovementMethod.getInstance()

        b.likes.text = if (item.likes > 0) Formatters.formatViewCount(item.likes) else ""
        b.heartIcon.isVisible = item.hearted

        val hasReplies = !item.isReply && item.replyCount > 0
        b.replies.isVisible = hasReplies
        if (hasReplies) {
            b.replies.text = when (row.repliesState) {
                RepliesState.LOADING -> ctx.getString(R.string.comments_replies_loading)
                RepliesState.EXPANDED -> ctx.getString(R.string.comments_hide_replies)
                RepliesState.COLLAPSED ->
                    if (item.replyCount == 1) ctx.getString(R.string.comments_view_reply_one)
                    else ctx.getString(R.string.comments_view_replies_fmt, item.replyCount)
            }
            // Without a replies cursor the count is informational only.
            val canOpen = item.repliesPage != null
            b.replies.isClickable = canOpen
            b.replies.setOnClickListener(if (canOpen) View.OnClickListener { onToggleReplies(item) } else null)
        } else {
            b.replies.setOnClickListener(null)
        }
    }

    companion object {
        private const val TYPE_COMMENT = 0
        private const val TYPE_MORE = 1

        /** Comment / description body → styled text with clickable links. */
        fun commentText(item: CommentUi): CharSequence = bodyToText(item.text, item.textIsHtml)

        fun bodyToText(raw: String, isHtml: Boolean): CharSequence {
            if (raw.isBlank()) return ""
            return if (isHtml) {
                HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).trimEnd()
            } else {
                val spannable = SpannableString(raw.trimEnd())
                Linkify.addLinks(spannable, Linkify.WEB_URLS)
                spannable
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<CommentRow>() {
            override fun areItemsTheSame(a: CommentRow, b: CommentRow): Boolean = when {
                a is CommentRow.Comment && b is CommentRow.Comment ->
                    a.item.isReply == b.item.isReply && a.item.key == b.item.key
                a is CommentRow.MoreReplies && b is CommentRow.MoreReplies ->
                    a.parentKey == b.parentKey
                else -> false
            }

            override fun areContentsTheSame(a: CommentRow, b: CommentRow): Boolean = a == b
        }
    }
}
