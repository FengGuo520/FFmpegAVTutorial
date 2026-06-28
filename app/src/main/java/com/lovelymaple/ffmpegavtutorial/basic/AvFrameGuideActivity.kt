package com.lovelymaple.ffmpegavtutorial.basic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityAvFrameGuideBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance

class AvFrameGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAvFrameGuideBinding
    private lateinit var nativeInstance: NativeInstance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAvFrameGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)
        binding.backButton.setOnClickListener { finish() }

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()
        binding.refreshButton.setOnClickListener { runNativeDemo() }
        binding.queueRefreshButton.setOnClickListener { runFrameQueueDemo() }
        binding.queueStatusText.text = getString(R.string.av_frame_queue_status_idle)
        runNativeDemo()
    }

    private fun runNativeDemo() {
        binding.statusText.text = getString(R.string.av_frame_status_loading)
        binding.refreshButton.isEnabled = false
        try {
            nativeInstance.runAvFrameDemo()
            binding.statusText.text = getString(R.string.av_frame_status_ready)
        } catch (t: Throwable) {
            binding.statusText.text = getString(
                R.string.av_frame_status_error,
                t.message ?: t.javaClass.simpleName
            )
        } finally {
            binding.refreshButton.isEnabled = true
        }
    }

    private fun runFrameQueueDemo() {
        binding.queueStatusText.text = getString(R.string.av_frame_queue_status_loading)
        binding.queueRefreshButton.isEnabled = false
        try {
            nativeInstance.runFrameQueueDemo()
            binding.queueStatusText.text = getString(R.string.av_frame_queue_status_ready)
        } catch (t: Throwable) {
            binding.queueStatusText.text = getString(
                R.string.av_frame_queue_status_error,
                t.message ?: t.javaClass.simpleName
            )
        } finally {
            binding.queueRefreshButton.isEnabled = true
        }
    }
}
