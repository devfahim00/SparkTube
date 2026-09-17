package com.sparktube.app.util

import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import coil.load
import coil.request.ImageRequest
import com.sparktube.app.R

/**
 * Thumbnail loading for YouTube video images.
 *
 * Feed items often come with `hqdefault.jpg` thumbnails, which are 4:3 with
 * black bars baked into the image. SparkTube upgrades those to the true 16:9
 * `hq720` variant and falls back to `mqdefault` (also 16:9, always present)
 * if the larger one is missing for an old video.
 */
object Thumbs {

    fun load(view: ImageView, url: String?) {
        val original = url.orEmpty()
        if (original.isBlank()) return
        val upgraded = original.replace("/hqdefault.", "/hq720.")
        if (upgraded == original) {
            // Channel avatars and already-16:9 thumbs pass straight through.
            view.load(original) {
                placeholder(placeholder(view))
                error(placeholder(view))
                crossfade(true)
            }
            return
        }
        view.load(upgraded) {
            placeholder(placeholder(view))
            error(placeholder(view))
            crossfade(true)
            listener(
                onError = { _, _ ->
                    val fallback = original.replace("/hqdefault.", "/mqdefault.")
                    view.load(fallback) {
                        placeholder(placeholder(view))
                        error(placeholder(view))
                    }
                }
            )
        }
    }

    private fun ImageRequest.Builder.placeholder(view: ImageView): ColorDrawable =
        ColorDrawable(ContextCompat.getColor(view.context, R.color.thumbnail_placeholder))
}
