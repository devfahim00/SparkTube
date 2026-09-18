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
        return textualDate.removePrefix("Streamed ").removePrefix("Premiered ").trim()
    }

    /**
     * Approximate age in DAYS parsed from YouTube's textual upload dates
     * ("4 hours ago", "3 days ago", "2 weeks ago", …). Returns null when the
     * text cannot be parsed (no date, "Scheduled", localized script, …) —
     * callers treat unknown ages as "not within range" for date filters.
     *
     * Bengali digits (০-৯) and unit words are handled because the extractor
     * localizes dates for some device locales.
     */
    fun parseAgeDays(textualDate: String?): Double? {
        if (textualDate.isNullOrBlank()) return null
        // Normalize non-ASCII decimal digits (Bengali ০৯, Arabic-Indic ٠٩, …).
        val text = textualDate.asSequence().joinToString("") { ch ->
            val digit = Character.digit(ch, 10)
            if (ch !in '0'..'9' && digit >= 0) digit.toString() else ch.toString()
        }
        val match = Regex("(\\d+)\\s*([A-Za-z\\u0980-\\u09FF]+)").find(text) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2].lowercase(Locale.US)
        val unitWord: String? = when {
            unit.contains("sec") || unit.contains("সেকেন্ড") -> "second"
            unit.contains("min") || unit.contains("মিনিট") -> "minute"
            unit.contains("hour") || unit.contains("ঘ") -> "hour"
            unit.contains("day") || unit.contains("দিন") -> "day"
            unit.contains("week") || unit.contains("সপ্তাহ") -> "week"
            unit.contains("month") || unit.contains("মাস") -> "month"
            unit.contains("year") || unit.contains("বছর") -> "year"
            else -> null
        }
        return when (unitWord) {
            "second", "minute" -> 0.0
            "hour" -> amount / 24.0
            "day" -> amount.toDouble()
            "week" -> amount * 7.0
            "month" -> amount * 30.44
            "year" -> amount * 365.25
            else -> null
        }
    }

    /** Search upload-date windows (client-side filtering of results). */
    enum class DateWindow(val maxDays: Double) {
        TODAY(1.0), THIS_WEEK(7.0), THIS_MONTH(31.0)
    }

    fun withinDateWindow(textualDate: String?, window: DateWindow): Boolean {
        val days = parseAgeDays(textualDate) ?: return false
        return days < window.maxDays
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
