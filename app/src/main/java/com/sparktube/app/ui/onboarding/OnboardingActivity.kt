package com.sparktube.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.sparktube.app.R
import com.sparktube.app.data.Countries
import com.sparktube.app.data.Country
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityOnboardingBinding
import com.sparktube.app.ui.MainActivity
import com.sparktube.app.util.Themes
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * First launch flow: pick a country/region, then enter the main interface.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: CountryAdapter
    private var selected: Country? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (com.sparktube.app.util.AppPrefs.country != null) {
            goToMain()
            return
        }

        adapter = CountryAdapter { country ->
            selected = country
            adapter.setSelected(country.code)
            binding.continueButton.isEnabled = true
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        adapter.submitList(Countries.all)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                val filtered = if (q.isEmpty()) {
                    Countries.all
                } else {
                    Countries.all.filter {
                        it.name.contains(q, ignoreCase = true) || it.code.equals(q, true)
                    }
                }
                binding.emptyView.isVisible = filtered.isEmpty()
                adapter.submitList(filtered)
            }
        })

        binding.continueButton.setOnClickListener {
            val country = selected ?: return@setOnClickListener
            com.sparktube.app.util.AppPrefs.country = country.code
            lifecycleScope.launch {
                YtRepository.applyCountry(country.code)
                Toast.makeText(
                    this@OnboardingActivity,
                    getString(R.string.welcome_toast, Countries.flagOf(country.code), country.name),
                    Toast.LENGTH_SHORT
                ).show()
                goToMain()
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
