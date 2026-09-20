package com.sparktube.app.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.dispose
import coil.load

/**
 * Round profile pictures (comment authors, ...).
 *
 * The picture is loaded with Coil; while it loads, when the URL is missing
 * or when it fails, a colored circle with the author's initial is shown
 * instead — so a comment row never ends up with an empty / invisible avatar.
 */
object Avatars {

    private val PALETTE = intArrayOf(
        0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFF1E88E5.toInt(),
        0xFF00897B.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(),
        0xFF546E7A.toInt(), 0xFFD81B60.toInt()
    )

    fun load(view: ImageView, url: String?, name: String) {
        val fallback = InitialsDrawable(name)
        val fixed = normalize(url)
        if (fixed.isBlank()) {
            view.dispose()
            view.setImageDrawable(fallback)
            return
        }
        view.load(fixed) {
            placeholder(fallback)
            error(fallback)
            crossfade(true)
        }
    }

    /** YouTube sometimes hands out protocol-relative or plain-http image URLs. */
    private fun normalize(url: String?): String {
        val u = url?.trim().orEmpty()
        return when {
            u.isEmpty() -> ""
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") -> "https://" + u.removePrefix("http://")
            else -> u
        }
    }

    private class InitialsDrawable(name: String) : Drawable() {
        private val letter: String = name.trim().removePrefix("@")
            .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PALETTE[(name.hashCode() and 0x7fffffff) % PALETTE.size]
        }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun draw(canvas: Canvas) {
            val b: Rect = bounds
            if (b.isEmpty) return
            canvas.drawRect(b, bg)
            fg.textSize = b.height() * 0.46f
            val y = b.exactCenterY() - (fg.descent() + fg.ascent()) / 2f
            canvas.drawText(letter, b.exactCenterX(), y, fg)
        }

        override fun setAlpha(alpha: Int) {
            bg.alpha = alpha
            fg.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bg.colorFilter = colorFilter
            fg.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
