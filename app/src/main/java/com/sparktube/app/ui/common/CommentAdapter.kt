package com.sparktube.app.ui.common

import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.data.CommentUi
import com.sparktube.app.databinding.ItemCommentBinding
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Thumbs

/** Comment row: avatar, author · date, body, likes, hearted mark, reply count. */
class CommentAdapter : ListAdapter<CommentUi, CommentAdapter.VH>(DIFF) {

    class VH(val binding: ItemCommentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        Thumbs.load(b.avatar, item.avatarUrl)

        val author = item.author.trim().removePrefix("@")
        b.meta.text = if (item.date.isBlank()) author else "$author  ·  ${item.date}"
        b.pinIcon.isVisible = item.pinned

        b.text.text = commentText(item)
        b.text.movementMethod = LinkMovementMethod.getInstance()

        b.likes.text = if (item.likes > 0) Formatters.formatViewCount(item.likes) else ""
        b.heartIcon.isVisible = item.hearted

        b.replies.isVisible = item.replyCount > 0
        if (item.replyCount > 0) {
            b.replies.text = if (item.replyCount == 1) {
                ctx.getString(R.string.comments_reply_one)
            } else {
                ctx.getString(R.string.comments_replies_fmt, item.replyCount)
            }
        }
    }

    companion object {
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

        private val DIFF = object : DiffUtil.ItemCallback<CommentUi>() {
            override fun areItemsTheSame(a: CommentUi, b: CommentUi): Boolean =
                if (a.id.isNotBlank() && b.id.isNotBlank()) a.id == b.id
                else a.author == b.author && a.text == b.text

            override fun areContentsTheSame(a: CommentUi, b: CommentUi): Boolean = a == b
        }
    }
}
