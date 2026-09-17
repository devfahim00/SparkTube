package com.sparktube.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object Formatters {

    fun formatViewCount(count: Long): String {
        if (count < 0) return ""
        val locale = Locale.US
        return when {
            count >= 1_000_000_000 -> String.format(locale, "%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format(locale, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(locale, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = TimeUnit.SECONDS.toHours(seconds)
        val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun formatRelativeTime(textualDate: String?): String {
        // NewPipeExtractor returns strings such as "3 days ago" (localized by hl),
        // which are already human friendly. Only trim a leading "Streamed " marker
        // that sometimes appears on ended live content.
        if (textualDate.isNullOrBlank()) return ""
        return textualDate.removePrefix("Streamed ").trim()
    }

    fun formatViewsLabel(count: Long): String {
        val views = formatViewCount(count)
        return if (views.isEmpty()) "" else "$views views"
    }

    fun friendlyException(e: Throwable): String {
        return when {
            e is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException ->
                "YouTube temporarily blocked this network. Try again later."
            e is java.io.IOException -> "Network error: ${e.message ?: "check your connection"}"
            e.message.isNullOrBlank() -> e.javaClass.simpleName
            else -> e.message!!
        }
    }
}
