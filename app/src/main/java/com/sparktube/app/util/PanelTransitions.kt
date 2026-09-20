package com.sparktube.app.util

import android.app.Activity
import android.os.Build
import com.sparktube.app.R

/**
 * Vertical "panel" transitions for the full-screen player pages (video watch
 * page and music Now Playing). They expand UP out of the mini player and
 * collapse DOWN into it, instead of the default sideways activity slide.
 */
object PanelTransitions {

    /** Call from onCreate(): registers the open (slide up) + close (slide down) animation. */
    @Suppress("DEPRECATION")
    fun install(activity: Activity) {
        if (!AppPrefs.animations) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN, R.anim.slide_up_in, R.anim.hold
            )
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.hold, R.anim.slide_down_out
            )
        } else {
            activity.overridePendingTransition(R.anim.slide_up_in, R.anim.hold)
        }
    }

    /** Call right after finish() on Android < 14 (no per-activity close registration there). */
    @Suppress("DEPRECATION")
    fun applyClose(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && AppPrefs.animations) {
            activity.overridePendingTransition(R.anim.hold, R.anim.slide_down_out)
        }
    }
}
