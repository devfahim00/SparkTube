package com.sparktube.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.sparktube.app.R
import com.sparktube.app.databinding.ActivityMainBinding
import com.sparktube.app.ui.home.HomeFragment
import com.sparktube.app.ui.library.LibraryFragment
import com.sparktube.app.ui.menu.MenuFragment
import com.sparktube.app.ui.music.MusicFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedId: Int = R.id.navHome
    private val fragments = mutableMapOf<Int, Fragment>()
    private var lastBackPress = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            selectedId = savedInstanceState.getInt(STATE_SELECTED, R.id.navHome)
            // Re-attach fragments that survived a configuration change.
            NAV_IDS.forEach { id ->
                supportFragmentManager.findFragmentByTag(tagOf(id))?.let {
                    fragments[id] = it
                }
            }
        }

        binding.navHome.setOnClickListener { select(R.id.navHome) }
        binding.navMusic.setOnClickListener { select(R.id.navMusic) }
        binding.navLibrary.setOnClickListener { select(R.id.navLibrary) }
        binding.navMenu.setOnClickListener { select(R.id.navMenu) }

        applySelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, selectedId)
    }

    private fun select(id: Int) {
        if (id == selectedId) return
        selectedId = id
        applySelection()
    }

    private fun applySelection() {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fragments.values.forEach { tx.hide(it) }
        val target = fragments[selectedId] ?: createFragment(selectedId).also {
            fragments[selectedId] = it
            tx.add(R.id.container, it, tagOf(selectedId))
        }
        tx.show(target)
        tx.commit()

        val isActive = { view: View -> view.id == selectedId }
        binding.navHome.isSelected = isActive(binding.navHome)
        binding.navMusic.isSelected = isActive(binding.navMusic)
        binding.navLibrary.isSelected = isActive(binding.navLibrary)
        binding.navMenu.isSelected = isActive(binding.navMenu)

        binding.toolbar.title = when (selectedId) {
            R.id.navHome -> getString(R.string.app_name)
            R.id.navMusic -> getString(R.string.tab_music)
            R.id.navLibrary -> getString(R.string.tab_library)
            else -> getString(R.string.tab_menu)
        }
    }

    private fun createFragment(id: Int): Fragment = when (id) {
        R.id.navHome -> HomeFragment()
        R.id.navMusic -> MusicFragment()
        R.id.navLibrary -> LibraryFragment()
        else -> MenuFragment()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedId != R.id.navHome) {
            select(R.id.navHome)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val STATE_SELECTED = "selected_nav"
        private val NAV_IDS = intArrayOf(R.id.navHome, R.id.navMusic, R.id.navLibrary, R.id.navMenu)
        private fun tagOf(id: Int) = "frag_$id"
    }
}
