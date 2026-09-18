package com.sparktube.app.ui.menu

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.databinding.ActivitySettingsPageBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes

/**
 * One full settings PAGE per area (general / video / music) — the menu used
 * to cram these into short-lived bottom sheets, now each area gets its own
 * screen with a back arrow, like the official YouTube app's settings.
 */
class SettingsPageActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsPageBinding

    private val pageType: Int by lazy {
        intent.getIntExtra(EXTRA_TYPE, TYPE_GENERAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsPageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.title.text = when (pageType) {
            TYPE_VIDEO -> getString(R.string.menu_video_settings)
            TYPE_MUSIC -> getString(R.string.menu_music_settings)
            else -> getString(R.string.menu_settings)
        }
        binding.backButton.setOnClickListener { finish() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // Theme / accent change while a picker dialog was up (or the app was
        // backgrounded): rebuild the page with fresh colors.
        if (Themes.recreateIfNeeded(this)) return
        render()
    }

    private fun render() {
        val content = binding.content
        content.removeAllViews()
        when (pageType) {
            TYPE_VIDEO -> renderVideo(content)
            TYPE_MUSIC -> renderMusic(content)
            else -> renderGeneral(content)
        }
    }

    // ----- General -----

    private fun renderGeneral(content: LinearLayout) {
        content.addView(
            menuRow(getString(R.string.settings_theme), themeLabel()) {
                showThemePicker()
            }
        )
        content.addView(
            menuRow(getString(R.string.settings_accent), accentLabel()) {
                showAccentPicker()
            }
        )
        content.addView(
            menuRow(
                getString(R.string.settings_animations),
                if (AppPrefs.animations) getString(R.string.on) else getString(R.string.off)
            ) {
                AppPrefs.animations = !AppPrefs.animations
                render()
            }
        )
        // "Clear data" lives here now — it used to be a stray menu card.
        content.addView(
            menuRow(getString(R.string.menu_clear_data), "") {
                showClearDataSheet()
            }
        )
    }

    // ----- Clear data (moved here from the menu) -----

    private fun showClearDataSheet() {
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.menu_clear_data)
                textSize = 18f
                setTextColor(getColor(R.color.on_surface))
                setTypeface(null, Typeface.BOLD)
            }
        )

        fun clearRow(label: Int, title: Int, message: Int, done: Int, action: (Context) -> Unit) {
            root.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Themes.elevatedColor(this@SettingsPageActivity))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(10) }
                    setOnClickListener {
                        confirm(title, message, done, action)
                        sheet.dismiss()
                    }
                    addView(
                        TextView(this@SettingsPageActivity).apply {
                            text = getString(label)
                            textSize = 15f
                            setTextColor(getColor(R.color.on_surface))
                            setTypeface(null, Typeface.BOLD)
                        }
                    )
                }
            )
        }

        clearRow(
            R.string.clear_history, R.string.clear_history, R.string.clear_history_confirm,
            R.string.history_cleared
        ) { LocalStore.clearHistory(it); RecommendEngine.clearPlays(it) }
        clearRow(
            R.string.clear_music_history, R.string.clear_music_history,
            R.string.clear_music_history_confirm, R.string.music_history_cleared
        ) { LocalStore.clearMusicHistory(it) }
        clearRow(
            R.string.clear_search_history, R.string.clear_search_history,
            R.string.clear_search_history_confirm, R.string.search_history_cleared
        ) { RecommendEngine.clearSearches(it) }
        clearRow(
            R.string.clear_favorites, R.string.clear_favorites, R.string.clear_favorites_confirm,
            R.string.favorites_cleared
        ) { LocalStore.clearFavorites(it) }
        clearRow(
            R.string.clear_subscriptions, R.string.clear_subscriptions,
            R.string.clear_subscriptions_confirm, R.string.subscriptions_cleared
        ) { LocalStore.clearSubscriptions(it) }
        clearRow(
            R.string.clear_downloads, R.string.clear_downloads, R.string.clear_downloads_confirm,
            R.string.downloads_cleared
        ) { DownloadCenter.clearAll(it) }
        clearRow(
            R.string.clear_recommendations, R.string.clear_recommendations,
            R.string.clear_recommendations_confirm, R.string.recommendations_cleared
        ) {
            RecommendEngine.clearPlays(it)
            RecommendEngine.clearSearches(it)
        }

        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(420)
        sheet.show()
    }

    private fun confirm(
        titleRes: Int,
        messageRes: Int,
        doneRes: Int,
        action: (Context) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                action(this)
                Toast.makeText(this, doneRes, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Video -----

    private fun renderVideo(content: LinearLayout) {
        content.addView(
            menuRow(getString(R.string.settings_default_quality_wifi), wifiQualityLabel()) {
                showDefaultQualityPicker(forWifi = true)
            }
        )
        content.addView(
            menuRow(getString(R.string.settings_default_quality_data), dataQualityLabel()) {
                showDefaultQualityPicker(forWifi = false)
            }
        )
        content.addView(
            menuRow(
                getString(R.string.settings_autoplay_next_video),
                if (AppPrefs.videoAutoplayNext) getString(R.string.on) else getString(R.string.off)
            ) {
                AppPrefs.videoAutoplayNext = !AppPrefs.videoAutoplayNext
                render()
            }
        )
    }

    // ----- Music -----

    private fun renderMusic(content: LinearLayout) {
        content.addView(
            menuRow(getString(R.string.settings_audio_quality), audioQualityLabel()) {
                showAudioQualityPicker()
            }
        )
        content.addView(
            menuRow(
                getString(R.string.settings_autoplay_next_song),
                if (AppPrefs.musicAutoplayNext) getString(R.string.on) else getString(R.string.off)
            ) {
                AppPrefs.musicAutoplayNext = !AppPrefs.musicAutoplayNext
                PlaybackCenter.applyMusicAutoplay()
                render()
            }
        )
    }

    // ----- Pickers (theme / accent / quality) -----

    private fun themeLabel(): String = when (AppPrefs.theme) {
        AppPrefs.THEME_LIGHT -> getString(R.string.theme_light)
        AppPrefs.THEME_PITCH_BLACK -> getString(R.string.theme_pitch_black)
        AppPrefs.THEME_AUTO -> getString(R.string.theme_auto)
        else -> getString(R.string.theme_dark)
    }

    private fun showThemePicker() {
        val options = listOf(
            AppPrefs.THEME_AUTO to getString(R.string.theme_auto),
            AppPrefs.THEME_DARK to getString(R.string.theme_dark),
            AppPrefs.THEME_LIGHT to getString(R.string.theme_light),
            AppPrefs.THEME_PITCH_BLACK to getString(R.string.theme_pitch_black)
        )
        val current = options.indexOfFirst { it.first == AppPrefs.theme }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                Themes.setTheme(options[which].first, this)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun accentLabel(): String = when (AppPrefs.accent) {
        "blue" -> getString(R.string.accent_blue)
        "purple" -> getString(R.string.accent_purple)
        "green" -> getString(R.string.accent_green)
        "orange" -> getString(R.string.accent_orange)
        "pink" -> getString(R.string.accent_pink)
        "teal" -> getString(R.string.accent_teal)
        else -> getString(R.string.accent_red)
    }

    private fun showAccentPicker() {
        val accents = listOf(
            "red" to getString(R.string.accent_red),
            "blue" to getString(R.string.accent_blue),
            "purple" to getString(R.string.accent_purple),
            "green" to getString(R.string.accent_green),
            "orange" to getString(R.string.accent_orange),
            "pink" to getString(R.string.accent_pink),
            "teal" to getString(R.string.accent_teal)
        )
        val current = accents.indexOfFirst { it.first == AppPrefs.accent }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_accent)
            .setSingleChoiceItems(accents.map { it.second }.toTypedArray(), current) { dialog, which ->
                Themes.setAccent(accents[which].first, this)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun qualityValueLabel(height: Int): String =
        if (height == 0) {
            getString(R.string.quality_auto_short)
        } else {
            "${height}p"
        }

    private fun wifiQualityLabel(): String = qualityValueLabel(AppPrefs.defaultVideoHeightWifi)

    private fun dataQualityLabel(): String = qualityValueLabel(AppPrefs.defaultVideoHeightData)

    /** @param forWifi true = the Wi-Fi default, false = the mobile-data default. */
    private fun showDefaultQualityPicker(forWifi: Boolean) {
        val choices = listOf(0, 2160, 1440, 1080, 720, 480, 360)
        val labels = choices.map {
            if (it == 0) getString(R.string.quality_auto) else "${it}p"
        }.toTypedArray()
        val currentHeight = if (forWifi) AppPrefs.defaultVideoHeightWifi else AppPrefs.defaultVideoHeightData
        val current = choices.indexOfFirst { it == currentHeight }
        AlertDialog.Builder(this)
            .setTitle(
                if (forWifi) R.string.settings_default_quality_wifi
                else R.string.settings_default_quality_data
            )
            .setSingleChoiceItems(labels, if (current >= 0) current else 0) { dialog, which ->
                if (forWifi) {
                    AppPrefs.defaultVideoHeightWifi = choices[which]
                } else {
                    AppPrefs.defaultVideoHeightData = choices[which]
                }
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun audioQualityLabel(): String = when (AppPrefs.musicAudioQuality) {
        AppPrefs.AUDIO_QUALITY_MEDIUM -> getString(R.string.quality_medium)
        AppPrefs.AUDIO_QUALITY_LOW -> getString(R.string.quality_low)
        else -> getString(R.string.quality_high)
    }

    private fun showAudioQualityPicker() {
        val options = listOf(
            AppPrefs.AUDIO_QUALITY_HIGH to getString(R.string.quality_high),
            AppPrefs.AUDIO_QUALITY_MEDIUM to getString(R.string.quality_medium),
            AppPrefs.AUDIO_QUALITY_LOW to getString(R.string.quality_low)
        )
        val current = options.indexOfFirst { it.first == AppPrefs.musicAudioQuality }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_audio_quality)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                AppPrefs.musicAudioQuality = options[which].first
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Row builder -----

    /**
     * A full-width settings row: main label on the left, the current value
     * on the right — visually a continuation of the menu cards.
     */
    private fun menuRow(mainLabel: String, valueLabel: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Themes.elevatedColor(this@SettingsPageActivity))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }

            addView(
                TextView(this@SettingsPageActivity).apply {
                    text = mainLabel
                    textSize = 15f
                    setTextColor(getColor(R.color.on_surface))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(
                TextView(this@SettingsPageActivity).apply {
                    text = valueLabel
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.on_surface_variant))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                }
            )
        }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val TYPE_GENERAL = 0
        const val TYPE_VIDEO = 1
        const val TYPE_MUSIC = 2
        private const val EXTRA_TYPE = "extra_type"

        fun start(context: Context, type: Int) {
            val intent = Intent(context, SettingsPageActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
            }
            context.startActivity(intent)
        }
    }
}
