package com.sparktube.app

import android.app.Application
import android.app.DownloadManager
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.sparktube.app.download.DownloadCompletionReceiver
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class SparkTubeApp : Application(), ImageLoaderFactory {

    private val downloadReceiver = DownloadCompletionReceiver()

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        PlaybackCenter.init(this)
        Themes.applyDefaultNightMode()
        NewPipe.init(
            OkHttpDownloader,
            Localization("en", "US"),
            ContentCountry(AppPrefs.countryOrDefault)
        )
        // Android 14+ requires an export flag even for this system broadcast.
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()
}
