package com.lovelymaple.ffmpegavtutorial

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityFlvMuxBinding
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class FlvMuxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlvMuxBinding
    private lateinit var nativeInstance: NativeInstance

    @Volatile
    private var isMuxing = false

    private var muxThread: Thread? = null
    private var latestVideoFile: File? = null
    private var latestAudioFile: File? = null
    private var outputFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFlvMuxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener { refreshInputFiles() }
        binding.startButton.setOnClickListener { startMux() }

        refreshInputFiles()
    }

    override fun onResume() {
        super.onResume()
        if (!isMuxing) {
            refreshInputFiles()
        }
    }

    private fun refreshInputFiles() {
        latestVideoFile = findLatestFile(File(filesDir, "video"), "h264")
        latestAudioFile = findLatestFile(File(filesDir, "audio"), "aac")
        outputFile = createOutputFile()

        binding.videoInputText.text =
            getString(
                R.string.flv_mux_video_input_value,
                formatFileSummary(latestVideoFile)
            )
        binding.audioInputText.text =
            getString(
                R.string.flv_mux_audio_input_value,
                formatFileSummary(latestAudioFile)
            )
        binding.outputPathText.text =
            getString(
                R.string.flv_mux_output_value,
                outputFile?.absolutePath ?: getString(R.string.flv_mux_input_empty)
            )

        updateButtons()
        if (!isMuxing) {
            updateStatus(
                if (latestVideoFile != null && latestAudioFile != null) {
                    getString(R.string.flv_mux_status_ready)
                } else {
                    getString(R.string.flv_mux_status_missing_inputs)
                }
            )
        }
    }

    private fun startMux() {
        if (isMuxing) return

        val videoFile = latestVideoFile
        val audioFile = latestAudioFile
        if (videoFile == null || audioFile == null) {
            updateStatus(getString(R.string.flv_mux_status_missing_inputs))
            return
        }

        val muxOutput = createOutputFile()
        outputFile = muxOutput
        binding.outputPathText.text =
            getString(R.string.flv_mux_output_value, muxOutput.absolutePath)

        isMuxing = true
        updateButtons()
        updateStatus(getString(R.string.flv_mux_status_muxing))

        muxThread = thread(start = true, name = "FlvMuxThread") {
            val result = nativeInstance.muxToFlv(
                videoFile.absolutePath,
                audioFile.absolutePath,
                muxOutput.absolutePath
            )
            val success = muxOutput.exists() && muxOutput.length() > 0L && !result.startsWith("ERROR:")

            postUi {
                isMuxing = false
                updateButtons()
                binding.outputPathText.text =
                    getString(R.string.flv_mux_output_value, muxOutput.absolutePath)
                updateStatus(
                    if (success) {
                        result
                    } else {
                        getString(R.string.flv_mux_status_error, result)
                    }
                )
            }
        }
    }

    private fun updateButtons() {
        binding.refreshButton.isEnabled = !isMuxing
        binding.startButton.isEnabled = !isMuxing && latestVideoFile != null && latestAudioFile != null
    }

    private fun updateStatus(text: String) {
        binding.statusText.text = text
    }

    private fun formatFileSummary(file: File?): String {
        if (file == null) {
            return getString(R.string.flv_mux_input_empty)
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sizeKb = file.length() / 1024.0
        return buildString {
            append(file.absolutePath)
            append('\n')
            append(
                getString(
                    R.string.flv_mux_file_meta_value,
                    String.format(Locale.US, "%.1f KB", sizeKb),
                    formatter.format(Date(file.lastModified()))
                )
            )
        }
    }

    private fun findLatestFile(directory: File, extension: String): File? {
        if (!directory.exists()) {
            return null
        }
        return directory
            .listFiles { file -> file.isFile && file.extension.equals(extension, ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun createOutputFile(): File {
        val muxDir = File(filesDir, "mux").apply { mkdirs() }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return File(muxDir, "mux_${formatter.format(Date())}.flv")
    }

    private fun postUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                action()
            }
        }
    }
}
