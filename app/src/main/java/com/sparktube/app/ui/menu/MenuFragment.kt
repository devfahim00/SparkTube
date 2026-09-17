package com.sparktube.app.ui.menu

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.BuildConfig
import com.sparktube.app.R
import com.sparktube.app.data.Countries
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.FragmentMenuBinding
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.BackupManager
import com.sparktube.app.util.Themes
import com.sparktube.app.util.UpdateChecker
import kotlinx.coroutines.launch

class MenuFragment : Fragment() {

    private var _binding: FragmentMenuBinding? = null
    private val binding get() = _binding!!

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rowCountry.setOnClickListener { showCountryPicker() }
        binding.rowSettings.setOnClickListener { showSettingsSheet() }
        binding.rowVideoSettings.setOnClickListener { showVideoSettingsSheet() }
        binding.rowMusicSettings.setOnClickListener { showMusicSettingsSheet() }
        binding.rowClearData.setOnClickListener { showClearDataSheet() }
        binding.rowImportData.setOnClickListener {
            runCatching {
                importLauncher.launch(arrayOf("application/json"))
            }
        }
        binding.rowExportData.setOnClickListener {
            runCatching {
                exportLauncher.launch("sparktube-backup.json")
            }
        }
        binding.rowCheckUpdate.setOnClickListener { checkForUpdate(manual = true) }
        binding.rowAbout.setOnClickListener { showAbout() }

        binding.version.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        val country = AppPrefs.countryOrDefault
        binding.countryValue.text = "${Countries.flagOf(country)} ${Countries.nameOf(country)}"
    }

    // ----- generic sheet plumbing -----

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun sheetRoot(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(28))
    }

    private fun sheetTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTextColor(Themes.onSurfaceColor(requireContext()))
        setTypeface(null, Typeface.BOLD)
    }

    private fun sheetMenuRow(mainLabel: String, valueLabel: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Themes.elevatedColor(requireContext()))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }

            addView(
                TextView(requireContext()).apply {
                    text = mainLabel
                    textSize = 15f
                    setTextColor(Themes.onSurfaceColor(requireContext()))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(
                TextView(requireContext()).apply {
                    text = valueLabel
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Themes.onSurfaceVariantColor(requireContext()))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                }
            )
        }

    private fun showSheet(sheet: BottomSheetDialog, root: LinearLayout, peekDp: Int = 420) {
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(peekDp)
        sheet.show()
    }

    // ----- Settings: theme + accent -----

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.menu_settings)))

        root.addView(
            sheetMenuRow(getString(R.string.settings_theme), themeLabel()) {
                sheet.dismiss()
                showThemePicker()
            }
        )
        root.addView(
            sheetMenuRow(getString(R.string.settings_accent), accentLabel()) {
                sheet.dismiss()
                showAccentPicker()
            }
        )
        showSheet(sheet, root, peekDp = 260)
    }

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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                Themes.setTheme(options[which].first, activity)
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_accent)
            .setSingleChoiceItems(accents.map { it.second }.toTypedArray(), current) { dialog, which ->
                Themes.setAccent(accents[which].first, activity)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Video settings -----

    private fun showVideoSettingsSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.menu_video_settings)))

        root.addView(
            sheetMenuRow(getString(R.string.settings_default_quality), qualityLabel()) {
                sheet.dismiss()
                showDefaultQualityPicker()
            }
        )
        root.addView(
            sheetMenuRow(
                getString(R.string.settings_autoplay_next_video),
                if (AppPrefs.videoAutoplayNext) getString(R.string.on) else getString(R.string.off)
            ) {
                AppPrefs.videoAutoplayNext = !AppPrefs.videoAutoplayNext
                sheet.dismiss()
                showVideoSettingsSheet()
            }
        )
        showSheet(sheet, root, peekDp = 260)
    }

    private fun qualityLabel(): String =
        if (AppPrefs.defaultVideoHeight == 0) {
            getString(R.string.quality_auto_short)
        } else {
            "${AppPrefs.defaultVideoHeight}p"
        }

    private fun showDefaultQualityPicker() {
        val choices = listOf(0, 2160, 1440, 1080, 720, 480, 360)
        val labels = choices.map {
            if (it == 0) getString(R.string.quality_auto) else "${it}p"
        }.toTypedArray()
        val current = choices.indexOfFirst { it == AppPrefs.defaultVideoHeight }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_default_quality)
            .setSingleChoiceItems(labels, if (current >= 0) current else 0) { dialog, which ->
                AppPrefs.defaultVideoHeight = choices[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Music settings -----

    private fun showMusicSettingsSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.menu_music_settings)))

        root.addView(
            sheetMenuRow(getString(R.string.settings_audio_quality), audioQualityLabel()) {
                sheet.dismiss()
                showAudioQualityPicker()
            }
        )
        root.addView(
            sheetMenuRow(
                getString(R.string.settings_autoplay_next_song),
                if (AppPrefs.musicAutoplayNext) getString(R.string.on) else getString(R.string.off)
            ) {
                AppPrefs.musicAutoplayNext = !AppPrefs.musicAutoplayNext
                PlaybackCenter.applyMusicAutoplay()
                sheet.dismiss()
                showMusicSettingsSheet()
            }
        )
        showSheet(sheet, root, peekDp = 260)
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_audio_quality)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                AppPrefs.musicAudioQuality = options[which].first
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Clear data -----

    private fun showClearDataSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.menu_clear_data)))

        root.addView(sheetMenuRow(getString(R.string.clear_history), "") {
            confirm(
                R.string.clear_history, R.string.clear_history_confirm,
                R.string.history_cleared
            ) {
                LocalStore.clearHistory(it)
                RecommendEngine.clearPlays(it)
            }
        })
        root.addView(sheetMenuRow(getString(R.string.clear_music_history), "") {
            confirm(
                R.string.clear_music_history, R.string.clear_music_history_confirm,
                R.string.music_history_cleared
            ) {
                LocalStore.clearMusicHistory(it)
            }
        })
        root.addView(sheetMenuRow(getString(R.string.clear_search_history), "") {
            confirm(
                R.string.clear_search_history, R.string.clear_search_history_confirm,
                R.string.search_history_cleared
            ) {
                RecommendEngine.clearSearches(it)
            }
        })
        root.addView(sheetMenuRow(getString(R.string.clear_favorites), "") {
            confirm(
                R.string.clear_favorites, R.string.clear_favorites_confirm,
                R.string.favorites_cleared
            ) {
                LocalStore.clearFavorites(it)
            }
        })
        root.addView(sheetMenuRow(getString(R.string.clear_subscriptions), "") {
            confirm(
                R.string.clear_subscriptions, R.string.clear_subscriptions_confirm,
                R.string.subscriptions_cleared
            ) {
                LocalStore.clearSubscriptions(it)
            }
        })
        root.addView(sheetMenuRow(getString(R.string.clear_downloads), "") {
            confirm(
                R.string.clear_downloads, R.string.clear_downloads_confirm,
                R.string.downloads_cleared
            ) {
                DownloadCenter.clearAll(it)
            }
        })
        root.addView(sheetMenuRow(getString(R.string.clear_recommendations), "") {
            confirm(
                R.string.clear_recommendations, R.string.clear_recommendations_confirm,
                R.string.recommendations_cleared
            ) {
                RecommendEngine.clearPlays(it)
                RecommendEngine.clearSearches(it)
            }
        })
        showSheet(sheet, root)
    }

    private fun confirm(
        titleRes: Int,
        messageRes: Int,
        doneRes: Int,
        action: (android.content.Context) -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ctx = context ?: return@setPositiveButton
                action(ctx)
                Toast.makeText(ctx, doneRes, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Country -----

    private fun showCountryPicker() {
        val names = Countries.all.map { "${Countries.flagOf(it.code)} ${it.name}" }.toTypedArray()
        val current = Countries.all.indexOfFirst { it.code == AppPrefs.countryOrDefault }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.change_country)
            .setSingleChoiceItems(names, if (current >= 0) current else 0) { dialog, which ->
                val country = Countries.all[which]
                AppPrefs.country = country.code
                viewLifecycleOwner.lifecycleScope.launch {
                    YtRepository.applyCountry(country.code)
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.country_changed, Countries.nameOf(country.code)),
                    Toast.LENGTH_SHORT
                ).show()
                binding.countryValue.text =
                    "${Countries.flagOf(country.code)} ${Countries.nameOf(country.code)}"
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Import / export -----

    private fun doImport(uri: Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = BackupManager.importFrom(ctx, uri)
            // The stored theme may have changed: re-apply the night mode.
            Themes.applyDefaultNightMode()
            Toast.makeText(
                ctx,
                if (ok) R.string.import_done else R.string.import_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun doExport(uri: Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = BackupManager.exportTo(ctx, uri)
            Toast.makeText(
                ctx,
                if (ok) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ----- Update check -----

    private fun checkForUpdate(manual: Boolean) {
        val ctx = context ?: return
        if (manual) {
            Toast.makeText(ctx, R.string.checking_update, Toast.LENGTH_SHORT).show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val release = UpdateChecker.fetchLatest()
            when {
                release != null && UpdateChecker.isNewer(BuildConfig.VERSION_NAME, release.tag) -> {
                    showUpdateDialog(release, manual)
                }
                manual -> {
                    Toast.makeText(ctx, R.string.up_to_date, Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
    }

    private fun showUpdateDialog(release: UpdateChecker.Release, manual: Boolean) {
        val ctx = context ?: return
        if (!manual) {
            // Silent on-open check: only nag once per app launch.
            if (UpdateChecker.silentCheckDone) return
            UpdateChecker.silentCheckDone = true
        }
        val body = release.body.ifBlank {
            "Version ${release.tag.removePrefix("v")} is available."
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.update_available_title) + " · ${release.tag.removePrefix("v")}")
            .setMessage(body)
            .setPositiveButton(R.string.update_download) { _, _ ->
                UpdateChecker.openDownload(ctx, release)
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text))
            .setPositiveButton(R.string.github) { _, _ ->
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/devfahim00/SparkTube")
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
