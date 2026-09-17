package com.sparktube.app.util

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import com.sparktube.app.R

/**
 * Theme plumbing: night mode (auto / light / dark) plus the pitch-black
 * variant and the user-selectable accent color. [apply] must be called on
 * every activity before super.onCreate / setContentView; the night mode is
 * set once per process from the Application.
 */
object Themes {

    /** Set when a visual preference changed; activities recreate on resume. */
    @Volatile
    private var dirty = false

    fun nightModeOf(theme: String): Int = when (theme) {
        AppPrefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        AppPrefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        AppPrefs.THEME_PITCH_BLACK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun accentOverlayOf(accent: String): Int = when (accent) {
        "blue" -> R.style.ThemeOverlay_SparkTube_Accent_Blue
        "purple" -> R.style.ThemeOverlay_SparkTube_Accent_Purple
        "green" -> R.style.ThemeOverlay_SparkTube_Accent_Green
        "orange" -> R.style.ThemeOverlay_SparkTube_Accent_Orange
        "pink" -> R.style.ThemeOverlay_SparkTube_Accent_Pink
        "teal" -> R.style.ThemeOverlay_SparkTube_Accent_Teal
        else -> R.style.ThemeOverlay_SparkTube_Accent
    }

    /** Applies the stored night mode. Call once from Application.onCreate. */
    fun applyDefaultNightMode() {
        AppCompatDelegate.setDefaultNightMode(nightModeOf(AppPrefs.theme))
    }

    /** Applies pitch-black + accent to one activity. Call before setContentView. */
    fun apply(activity: Activity) {
        if (AppPrefs.theme == AppPrefs.THEME_PITCH_BLACK) {
            activity.setTheme(R.style.Theme_SparkTube_PitchBlack)
        }
        activity.theme.applyStyle(accentOverlayOf(AppPrefs.accent), true)
    }

    /** Persists a new theme and triggers a global refresh. */
    fun setTheme(theme: String, activity: Activity? = null) {
        AppPrefs.theme = theme
        AppCompatDelegate.setDefaultNightMode(nightModeOf(theme))
        dirty = true
        activity?.recreate()
    }

    /** Persists a new accent and triggers a global refresh. */
    fun setAccent(accent: String, activity: Activity? = null) {
        AppPrefs.accent = accent
        dirty = true
        activity?.recreate()
    }

    /** Activities call this in onResume; returns true when recreating. */
    fun recreateIfNeeded(activity: Activity): Boolean {
        if (!dirty) return false
        dirty = false
        activity.recreate()
        return true
    }

    /** Resolves a theme attribute to a color (for programmatic views). */
    fun color(context: Context, attrResId: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attrResId, tv, true)
        return tv.data
    }

    fun accentColor(context: Context): Int = color(context, R.attr.accent)
    fun elevatedColor(context: Context): Int = color(context, R.attr.elevatedBg)
    fun onSurfaceColor(context: Context): Int = color(context, R.attr.colorOnSurface)
    fun onSurfaceVariantColor(context: Context): Int = color(context, R.attr.colorOnSurfaceVariant)
}
