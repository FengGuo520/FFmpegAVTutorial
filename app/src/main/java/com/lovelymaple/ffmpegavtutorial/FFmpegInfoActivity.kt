package com.lovelymaple.ffmpegavtutorial

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityFfmpegInfoBinding
import io.ffmpegtutotial.player.internal.NativeInstance

class FFmpegInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFfmpegInfoBinding
    private lateinit var nativeInstance: NativeInstance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFfmpegInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        binding.backButton.setOnClickListener {
            finish()
        }

        nativeInstance = NativeInstance()
        binding.refreshButton.setOnClickListener {
            renderFFmpegInfo()
        }
        renderFFmpegInfo()
    }

    private fun renderFFmpegInfo() {
        binding.infoContent.text = try {
            nativeInstance.getInfo(0L)
        } catch (t: Throwable) {
            getString(R.string.ffmpeg_info_load_failed, t.message ?: t.javaClass.simpleName)
        }
    }
}
