package com.lovelymaple.ffmpegavtutorial

import android.app.Application
import com.lovelymaple.ffmpegavtutorial.config.AppConfig

class FfmpegAvTutorialApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
    }
}
