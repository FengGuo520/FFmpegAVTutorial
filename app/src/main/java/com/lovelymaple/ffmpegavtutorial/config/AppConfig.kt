package com.lovelymaple.ffmpegavtutorial.config

import android.app.Application
import androidx.core.content.res.ResourcesCompat
import com.lovelymaple.ffmpegavtutorial.R

object AppConfig {

    private var application: Application? = null

    val mBoldTypeface by lazy {
        ResourcesCompat.getFont(
            getApplication(),
            R.font.noto_sans_0
        )
    }

    fun init(application: Application) {
        AppConfig.application = application
    }

    fun getApplication(): Application {
        return application ?: error("Please init AppConfig in Application#onCreate first.")
    }
}
