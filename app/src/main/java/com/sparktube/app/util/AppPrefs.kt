package com.sparktube.app.util

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {

    private const val PREFS_NAME = "sparktube_prefs"
    private const val KEY_COUNTRY = "country"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_DEFAULT_VIDEO_HEIGHT = "default_video_height"
    private const val KEY_VIDEO_AUTOPLAY_NEXT = "video_autoplay_next"
    private const val KEY_MUSIC_AUDIO_QUALITY = "music_audio_quality"
    private const val KEY_MUSIC_AUTOPLAY_NEXT = "music_autoplay_next"
    private const val KEY_ANIMATIONS = "animations"

    const val DEFAULT_COUNTRY = "US"

    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_PITCH_BLACK = "pitch_black"

    const val AUDIO_QUALITY_HIGH = "high"
    const val AUDIO_QUALITY_MEDIUM = "medium"
    const val AUDIO_QUALITY_LOW = "low"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    /** 0 = auto (best available), otherwise a pixel height like 720. Default: 720p (fast start). */
    var defaultVideoHeight: Int
        get() = sp.getInt(KEY_DEFAULT_VIDEO_HEIGHT, 720)
        set(value) {
            sp.edit().putInt(KEY_DEFAULT_VIDEO_HEIGHT, value).apply()
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
}
