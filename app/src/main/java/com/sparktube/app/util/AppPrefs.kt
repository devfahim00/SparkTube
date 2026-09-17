package com.sparktube.app.util

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {

    private const val PREFS_NAME = "sparktube_prefs"
    private const val KEY_COUNTRY = "country"

    const val DEFAULT_COUNTRY = "US"

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
}
