package com.sparktube.app.util

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import com.sparktube.app.R
import java.util.Collections
import java.util.WeakHashMap

/**
 * Theme plumbing: night mode (auto / light / dark) plus the pitch-black
 * variant and the user-selectable accent color. [apply] must be called on
 * every activity before super.onCreate / setContentView; the night mode is
 * set once per process from the Application.
 */
object Themes {

    /**
     * Theme + accent signature each activity applied in onCreate. Kept per
     * activity (weak) so back-stack screens can detect a change on resume
     * WITHOUT the global dirty flag re-recreating the already-refreshed
     * foreground activity a second (or third) time.
     */
    private val appliedStamps: MutableMap<Activity, String> =
        Collections.synchronizedMap(WeakHashMap<Activity, String>())

    private fun stampOf(): String = "${AppPrefs.theme}|${AppPrefs.accent}"

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
        appliedStamps[activity] = stampOf()
    }

    /**
     * Persists a new theme and refreshes everything exactly once:
     * a real night-mode change lets AppCompatDelegate recreate all activities
     * by itself; same-mode switches (dark <-> pitch_black) recreate just the
     * calling activity. Others catch up via [recreateIfNeeded] on resume.
     */
    fun setTheme(theme: String, activity: Activity? = null) {
        AppPrefs.theme = theme
        val newMode = nightModeOf(theme)
        val modeChanged = AppCompatDelegate.getDefaultNightMode() != newMode
        AppCompatDelegate.setDefaultNightMode(newMode)
        if (!modeChanged) {
            activity?.recreate()
        }
    }

    /** Persists a new accent and refreshes the visible activity immediately. */
    fun setAccent(accent: String, activity: Activity? = null) {
        AppPrefs.accent = accent
        activity?.recreate()
    }

    /** Activities call this in onResume; returns true when recreating. */
    fun recreateIfNeeded(activity: Activity): Boolean {
        if (appliedStamps[activity] == stampOf()) return false
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

    /** Text colors follow the day/night palette directly. */
    fun onSurfaceColor(context: Context): Int = context.getColor(R.color.on_surface)
    fun onSurfaceVariantColor(context: Context): Int = context.getColor(R.color.on_surface_variant)
}
