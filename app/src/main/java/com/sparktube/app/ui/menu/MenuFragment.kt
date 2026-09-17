package com.sparktube.app.ui.menu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.sparktube.app.BuildConfig
import com.sparktube.app.R
import com.sparktube.app.data.Countries
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.FragmentMenuBinding
import com.sparktube.app.util.AppPrefs
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MenuFragment : Fragment() {

    private var _binding: FragmentMenuBinding? = null
    private val binding get() = _binding!!

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
        binding.rowClearHistory.setOnClickListener { confirmClearHistory() }
        binding.rowClearSearchHistory.setOnClickListener { confirmClearSearchHistory() }
        binding.rowClearFavorites.setOnClickListener { confirmClearFavorites() }
        binding.rowAbout.setOnClickListener { showAbout() }

        binding.version.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        val country = AppPrefs.countryOrDefault
        binding.countryValue.text = "${Countries.flagOf(country)} ${Countries.nameOf(country)}"
    }

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

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LocalStore.clearHistory(requireContext())
                // The recommendation profile must forget the same signals.
                RecommendEngine.clearPlays(requireContext())
                Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearSearchHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_search_history)
            .setMessage(R.string.clear_search_history_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                RecommendEngine.clearSearches(requireContext())
                Toast.makeText(requireContext(), R.string.search_history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearFavorites() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_favorites)
            .setMessage(R.string.clear_favorites_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LocalStore.clearFavorites(requireContext())
                Toast.makeText(requireContext(), R.string.favorites_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text))
            .setPositiveButton(R.string.github) { _, _ ->
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/devfahim00/SparkTube"))
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
