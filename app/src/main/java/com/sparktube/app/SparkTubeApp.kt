package com.sparktube.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.sparktube.app.net.OkHttpDownloader
import com.sparktube.app.util.AppPrefs
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class SparkTubeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        NewPipe.init(
            OkHttpDownloader,
            Localization("en", "US"),
            ContentCountry(AppPrefs.countryOrDefault)
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()
}
