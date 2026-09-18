package com.sparktube.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class SparkTubeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        PlaybackCenter.init(this)
        Themes.applyDefaultNightMode()

        // Firebase Crashlytics — anonymous crash reporting. Collection is on
        // by default for both debug and release builds; custom keys give
        // every crash report useful context (visible in the Firebase console).
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
        crashlytics.setCustomKey("selected_country", AppPrefs.countryOrDefault)
        crashlytics.log("SparkTube ${BuildConfig.VERSION_NAME} started")

        NewPipe.init(
            OkHttpDownloader,
            Localization("en", "US"),
            ContentCountry(AppPrefs.countryOrDefault)
        )
        // Downloads now run through the app's own multi-thread engine
        // (DownloadCenter), so there is no system DownloadManager broadcast
        // to listen for anymore.
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()
}
