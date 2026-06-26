package com.lovelymaple.ffmpegavtutorial.basic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityAvRationalGuideBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance

class AvRationalGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAvRationalGuideBinding
    private lateinit var nativeInstance: NativeInstance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAvRationalGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)
        binding.backButton.setOnClickListener { finish() }

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()
        binding.refreshButton.setOnClickListener { loadNativeDemo() }
        loadNativeDemo()
    }

    private fun loadNativeDemo() {
        binding.statusText.text = getString(R.string.av_rational_status_loading)
        binding.refreshButton.isEnabled = false
        try {
            nativeInstance.runAvRationalDemo()
            binding.statusText.text = getString(R.string.av_rational_status_ready)
        } catch (t: Throwable) {
            binding.statusText.text = getString(
                R.string.av_rational_status_error,
                t.message ?: t.javaClass.simpleName
            )
        } finally {
            binding.refreshButton.isEnabled = true
        }
    }
}
