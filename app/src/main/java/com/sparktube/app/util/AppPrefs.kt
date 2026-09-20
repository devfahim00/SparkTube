package com.sparktube.app.util

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {

    private const val PREFS_NAME = "sparktube_prefs"
    private const val KEY_COUNTRY = "country"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_VIDEO_AUTOPLAY_NEXT = "video_autoplay_next"
    private const val KEY_SMART_STREAMING = "smart_streaming"
    private const val KEY_MUSIC_AUDIO_QUALITY = "music_audio_quality"
    private const val KEY_MUSIC_AUTOPLAY_NEXT = "music_autoplay_next"
    private const val KEY_ANIMATIONS = "animations"

    // Default video quality: separate values for Wi-Fi and mobile data.
    private const val KEY_DEFAULT_VIDEO_HEIGHT_WIFI = "default_video_height_wifi"
    private const val KEY_DEFAULT_VIDEO_HEIGHT_DATA = "default_video_height_data"
    // Legacy single-quality key (pre 1.1.0) used for the one-time migration.
    private const val KEY_DEFAULT_VIDEO_HEIGHT_LEGACY = "default_video_height"

    // Fullscreen video scale mode: fit / crop / stretch.
    private const val KEY_FULLSCREEN_SCALE = "fullscreen_scale"

    const val DEFAULT_COUNTRY = "US"

    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_PITCH_BLACK = "pitch_black"

    const val AUDIO_QUALITY_HIGH = "high"
    const val AUDIO_QUALITY_MEDIUM = "medium"
    const val AUDIO_QUALITY_LOW = "low"

    const val SCALE_FIT = "fit"
    const val SCALE_CROP = "crop"
    const val SCALE_STRETCH = "stretch"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyQuality()
    }

    /**
     * One-time migration: carries the pre-1.1.0 single "default video quality"
     * into the Wi-Fi slot (and a data-friendly value into the data slot) so
     * existing users keep a sensible starting point.
     */
    private fun migrateLegacyQuality() {
        if (!sp.contains(KEY_DEFAULT_VIDEO_HEIGHT_LEGACY)) return
        if (!sp.contains(KEY_DEFAULT_VIDEO_HEIGHT_WIFI) && !sp.contains(KEY_DEFAULT_VIDEO_HEIGHT_DATA)) {
            val legacy = sp.getInt(KEY_DEFAULT_VIDEO_HEIGHT_LEGACY, 720)
            sp.edit()
                .putInt(KEY_DEFAULT_VIDEO_HEIGHT_WIFI, legacy.coerceAtLeast(720))
                .putInt(KEY_DEFAULT_VIDEO_HEIGHT_DATA, legacy.coerceAtMost(480))
                .apply()
        }
        sp.edit().remove(KEY_DEFAULT_VIDEO_HEIGHT_LEGACY).apply()
    }

    var country: String?
        get() = sp.getString(KEY_COUNTRY, null)
        set(value) {
            sp.edit().putString(KEY_COUNTRY, value).apply()
        }

    val countryOrDefault: String
        get() = country ?: DEFAULT_COUNTRY

    /** UI theme: auto / light / dark / pitch_black (default keeps the old always-dark look). */
    var theme: String
        get() = sp.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
        set(value) {
            sp.edit().putString(KEY_THEME, value).apply()
        }

    /** Accent color key: red / blue / purple / green / orange / pink / teal. */
    var accent: String
        get() = sp.getString(KEY_ACCENT, "red") ?: "red"
        set(value) {
            sp.edit().putString(KEY_ACCENT, value).apply()
        }

    // ----- Default video quality (per network type) -----

    /** 0 = auto (best available), otherwise a pixel height like 720. Default: 720p (fast start). */
    var defaultVideoHeightWifi: Int
        get() = sp.getInt(KEY_DEFAULT_VIDEO_HEIGHT_WIFI, 720)
        set(value) {
            sp.edit().putInt(KEY_DEFAULT_VIDEO_HEIGHT_WIFI, value).apply()
        }

    /** Mobile-data default. Lower default: 480p keeps buffered starts smooth on slow radios. */
    var defaultVideoHeightData: Int
        get() = sp.getInt(KEY_DEFAULT_VIDEO_HEIGHT_DATA, 480)
        set(value) {
            sp.edit().putInt(KEY_DEFAULT_VIDEO_HEIGHT_DATA, value).apply()
        }

    /**
     * The default that applies right now: the Wi-Fi pick when the current
     * network is unmetered Wi-Fi, the data pick otherwise.
     */
    fun defaultVideoHeightNow(context: Context): Int =
        if (Net.isOnWifi(context)) defaultVideoHeightWifi else defaultVideoHeightData

    /**
     * Adaptive (DASH) start for "Auto" / default-quality playback: begins with small
     * segments and adapts the quality like the official player. Turn off to always
     * use the plain single-file stream.
     */
    var smartStreaming: Boolean
        get() = sp.getBoolean(KEY_SMART_STREAMING, true)
        set(value) {
            sp.edit().putBoolean(KEY_SMART_STREAMING, value).apply()
        }

    var videoAutoplayNext: Boolean
        get() = sp.getBoolean(KEY_VIDEO_AUTOPLAY_NEXT, true)
        set(value) {
            sp.edit().putBoolean(KEY_VIDEO_AUTOPLAY_NEXT, value).apply()
        }

    /** Music streaming quality: high / medium / low. */
    var musicAudioQuality: String
        get() = sp.getString(KEY_MUSIC_AUDIO_QUALITY, AUDIO_QUALITY_HIGH) ?: AUDIO_QUALITY_HIGH
        set(value) {
            sp.edit().putString(KEY_MUSIC_AUDIO_QUALITY, value).apply()
        }

    var musicAutoplayNext: Boolean
        get() = sp.getBoolean(KEY_MUSIC_AUTOPLAY_NEXT, true)
        set(value) {
            sp.edit().putBoolean(KEY_MUSIC_AUTOPLAY_NEXT, value).apply()
        }

    /** UI animations (tab switches, transitions): on by default. */
    var animations: Boolean
        get() = sp.getBoolean(KEY_ANIMATIONS, true)
        set(value) {
            sp.edit().putBoolean(KEY_ANIMATIONS, value).apply()
        }

    /** Fullscreen video scale preference: fit / crop / stretch (default: fit). */
    var fullscreenScale: String
        get() = sp.getString(KEY_FULLSCREEN_SCALE, SCALE_FIT) ?: SCALE_FIT
        set(value) {
            sp.edit().putString(KEY_FULLSCREEN_SCALE, value).apply()
        }
}
