package com.lovelymaple.ffmpegavtutorial.basic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityStreamingTopicBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance
import kotlin.concurrent.thread

class StreamingTopicActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamingTopicBinding
    private lateinit var nativeInstance: NativeInstance

    @Volatile
    private var isWorking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStreamingTopicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.probeButton.setOnClickListener { probeUrl() }

        binding.urlInput.setText("https://v-mps.crazymaplestudios.com/d0a0eaf36e5d71f1813de6f6c55a0102/747103a20dd447f99676724fc5dc4478.m3u8")
        binding.resultText.text = getString(R.string.streaming_topic_result_empty)
        updateStatus(getString(R.string.streaming_topic_status_idle))
        renderState()
    }

    private fun probeUrl() {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            updateStatus(getString(R.string.streaming_topic_status_input_required))
            return
        }
        if (isWorking) return

        isWorking = true
        renderState()
        binding.resultText.text = getString(R.string.streaming_topic_status_probing, url)
        updateStatus(getString(R.string.streaming_topic_status_probing, url))

        thread(start = true, name = "StreamingTopicProbe") {
            val result = runCatching {
                nativeInstance.probeStreamingUrl(url)
            }

            postUi {
                isWorking = false
                renderState()
                result.fold(
                    onSuccess = { raw ->
                        binding.resultText.text = raw
                        if (raw.startsWith("ERROR:")) {
                            updateStatus(
                                getString(
                                    R.string.streaming_topic_status_error,
                                    raw.removePrefix("ERROR: ").trim()
                                )
                            )
                        } else {
                            updateStatus(getString(R.string.streaming_topic_status_done))
                        }
                    },
                    onFailure = { throwable ->
                        val message = throwable.message ?: throwable.javaClass.simpleName
                        binding.resultText.text = message
                        updateStatus(getString(R.string.streaming_topic_status_error, message))
                    }
                )
            }
        }
    }

    private fun renderState() {
        binding.probeButton.isEnabled = !isWorking
        binding.urlInput.isEnabled = !isWorking
    }

    private fun updateStatus(text: String) {
        binding.statusText.text = text
    }

    private fun postUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { action() }
    }
}
