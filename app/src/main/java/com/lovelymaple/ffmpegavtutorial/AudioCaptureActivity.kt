package com.lovelymaple.ffmpegavtutorial

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityAudioCaptureBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioCaptureBinding

    private val sampleRate = 44_100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    @Volatile private var isCapturing = false
    private var captureThread: Thread? = null
    private var bufferSizeInBytes: Int = 0
    private var currentOutputFile: File? = null
    private var currentWaveFile: File? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updatePermissionUi()
                startCapture()
            } else {
                showPermissionRequiredState(R.string.audio_capture_status_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAudioCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { ensurePermissionAndStart() }
        binding.startButton.setOnClickListener { ensurePermissionAndStart() }
        binding.stopButton.setOnClickListener { stopCapture() }

        updateStaticInfo()
        updatePermissionUi()
    }

    override fun onPause() {
        stopCapture()
        super.onPause()
    }

    private fun ensurePermissionAndStart() {
        if (hasAudioPermission()) {
            startCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCapture() {
        if (isCapturing) return
        if (!hasAudioPermission()) {
            showPermissionRequiredState(R.string.audio_capture_status_permission_required)
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            updateStatus(
                getString(
                    R.string.audio_capture_status_error,
                    "invalid min buffer size: $minBufferSize"
                )
            )
            return
        }

        bufferSizeInBytes = minBufferSize * 2
        val outputFile = createOutputFile()

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSizeInBytes
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            updateStatus(
                getString(
                    R.string.audio_capture_status_error,
                    "AudioRecord initialization failed"
                )
            )
            return
        }

        audioRecord = recorder
        currentOutputFile = outputFile
        currentWaveFile = createWaveFile(outputFile)
        isCapturing = true
        updatePermissionUi()
        updateStatus(getString(R.string.audio_capture_status_starting))
        binding.filePathText.text =
            getString(R.string.audio_capture_pcm_path_value, outputFile.absolutePath)
        binding.wavPathText.text =
            getString(R.string.audio_capture_wav_path_value, getString(R.string.audio_capture_file_path_empty))

        captureThread = thread(start = true, name = "AudioCaptureThread") {
            val localRecord = audioRecord ?: return@thread
            val byteBuffer = ByteArray(bufferSizeInBytes)
            var outputStream: FileOutputStream? = null
            try {
                outputStream = FileOutputStream(outputFile)
                localRecord.startRecording()
                postUi {
                    updateStatus(getString(R.string.audio_capture_status_capturing))
                }

                while (isCapturing) {
                    val read = localRecord.read(byteBuffer, 0, byteBuffer.size)
                    if (read > 0) {
                        outputStream.write(byteBuffer, 0, read)
                        val level = calculateLevel(byteBuffer, read)
                        postUi {
                            binding.levelProgress.progress = level
                            binding.levelText.text =
                                getString(R.string.audio_capture_level_value, level)
                            binding.frameText.text =
                                getString(R.string.audio_capture_frame_value, read / 2)
                            binding.bufferText.text =
                                getString(R.string.audio_capture_buffer_value, bufferSizeInBytes)
                        }
                    } else {
                        postUi {
                            updateStatus(
                                getString(
                                    R.string.audio_capture_status_error,
                                    "AudioRecord read returned $read"
                                )
                            )
                        }
                        break
                    }
                }
                outputStream.flush()
            } catch (exception: Exception) {
                postUi {
                    updateStatus(
                        getString(
                            R.string.audio_capture_status_error,
                            exception.message ?: "audio capture failed"
                        )
                    )
                }
            } finally {
                try {
                    outputStream?.flush()
                } catch (_: Exception) {
                }
                try {
                    outputStream?.close()
                } catch (_: Exception) {
                }
                try {
                    localRecord.stop()
                } catch (_: IllegalStateException) {
                }
                localRecord.release()
                if (audioRecord === localRecord) {
                    audioRecord = null
                }
                isCapturing = false
                val pcmFile = currentOutputFile
                val wavFile = currentWaveFile
                var wavError: String? = null
                if (pcmFile != null && wavFile != null && pcmFile.exists()) {
                    try {
                        convertPcmToWave(pcmFile, wavFile)
                    } catch (exception: Exception) {
                        wavError = exception.message ?: "wave export failed"
                    }
                }
                postUi {
                    binding.levelProgress.progress = 0
                    binding.levelText.text =
                        getString(R.string.audio_capture_level_value, 0)
                    if (pcmFile != null) {
                        binding.filePathText.text =
                            getString(R.string.audio_capture_pcm_path_value, pcmFile.absolutePath)
                    }
                    if (wavFile != null && wavFile.exists() && wavError == null) {
                        binding.wavPathText.text =
                            getString(R.string.audio_capture_wav_path_value, wavFile.absolutePath)
                    } else if (wavError != null) {
                        binding.wavPathText.text =
                            getString(R.string.audio_capture_wav_path_value, getString(R.string.audio_capture_file_path_empty))
                    }
                    updatePermissionUi()
                    if (!isFinishing && !isDestroyed) {
                        updateStatus(
                            if (wavError == null) {
                                getString(R.string.audio_capture_status_stopped)
                            } else {
                                getString(R.string.audio_capture_status_error, wavError)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        captureThread?.join(500)
        captureThread = null
    }

    private fun updateStaticInfo() {
        binding.sampleRateText.text =
            getString(R.string.audio_capture_sample_rate_value, sampleRate)
        binding.channelText.text =
            getString(R.string.audio_capture_channel_value, getString(R.string.audio_capture_channel_mono))
        binding.bufferText.text =
            getString(R.string.audio_capture_buffer_value, 0)
        binding.frameText.text =
            getString(R.string.audio_capture_frame_value, 0)
        binding.filePathText.text =
            getString(R.string.audio_capture_pcm_path_value, getString(R.string.audio_capture_file_path_empty))
        binding.wavPathText.text =
            getString(R.string.audio_capture_wav_path_value, getString(R.string.audio_capture_file_path_empty))
        binding.levelText.text =
            getString(R.string.audio_capture_level_value, 0)
    }

    private fun updatePermissionUi() {
        val granted = hasAudioPermission()
        binding.permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.startButton.isEnabled = granted && !isCapturing
        binding.stopButton.isEnabled = granted && isCapturing
        if (!granted) {
            updateStatus(getString(R.string.audio_capture_status_permission_required))
        } else if (!isCapturing) {
            updateStatus(getString(R.string.audio_capture_status_idle))
        }
    }

    private fun showPermissionRequiredState(messageRes: Int) {
        binding.permissionButton.visibility = View.VISIBLE
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = false
        updateStatus(getString(messageRes))
    }

    private fun updateStatus(text: String) {
        binding.statusText.text = text
    }

    private fun postUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun calculateLevel(buffer: ByteArray, count: Int): Int {
        var sum = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < count) {
            val low = buffer[index].toInt() and 0xFF
            val high = buffer[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toDouble()
            sum += sample * sample
            sampleCount++
            index += 2
        }
        val rms = sqrt(sum / sampleCount.coerceAtLeast(1))
        return ((rms / Short.MAX_VALUE) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun createOutputFile(): File {
        val audioDir = File(filesDir, "audio").apply { mkdirs() }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return File(audioDir, "capture_${formatter.format(Date())}.pcm")
    }

    private fun createWaveFile(pcmFile: File): File {
        val baseName = pcmFile.nameWithoutExtension
        return File(pcmFile.parentFile, "$baseName.wav")
    }

    @Throws(IOException::class)
    private fun convertPcmToWave(pcmFile: File, wavFile: File) {
        val pcmSize = pcmFile.length().toInt()
        FileInputStream(pcmFile).use { input ->
            FileOutputStream(wavFile).use { output ->
                writeWaveHeader(
                    output = output,
                    pcmDataSize = pcmSize,
                    sampleRate = sampleRate,
                    channelCount = 1,
                    bitsPerSample = 16
                )
                val buffer = ByteArray(4096)
                var read = input.read(buffer)
                while (read != -1) {
                    output.write(buffer, 0, read)
                    read = input.read(buffer)
                }
                output.flush()
            }
        }
    }

    @Throws(IOException::class)
    private fun writeWaveHeader(
        output: FileOutputStream,
        pcmDataSize: Int,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8
        val chunkSize = 36 + pcmDataSize

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, chunkSize)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, channelCount)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, blockAlign)
        writeShortLE(header, 34, bitsPerSample)
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, pcmDataSize)
        output.write(header)
    }

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = (value shr 8 and 0xFF).toByte()
        buffer[offset + 2] = (value shr 16 and 0xFF).toByte()
        buffer[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShortLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = (value shr 8 and 0xFF).toByte()
    }
}
