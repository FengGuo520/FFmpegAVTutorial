package com.lovelymaple.ffmpegavtutorial.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityAacEncodeBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AacEncodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAacEncodeBinding

    private val sampleRate = 44_100
    private val channelCount = 1
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bitrate = 128_000

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var bufferSizeInBytes: Int = 0
    @Volatile private var isEncoding = false
    private var encodeThread: Thread? = null
    private var encodedPacketCount = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updatePermissionUi()
                startEncoding()
            } else {
                showPermissionRequiredState(R.string.aac_encode_status_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAacEncodeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { ensurePermissionAndStart() }
        binding.startButton.setOnClickListener { ensurePermissionAndStart() }
        binding.stopButton.setOnClickListener { stopEncoding() }

        updateStaticInfo()
        updatePermissionUi()
    }

    override fun onPause() {
        stopEncoding()
        super.onPause()
    }

    private fun ensurePermissionAndStart() {
        if (hasAudioPermission()) {
            startEncoding()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startEncoding() {
        if (isEncoding) return
        if (!hasAudioPermission()) {
            showPermissionRequiredState(R.string.aac_encode_status_permission_required)
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            updateStatus(
                getString(R.string.aac_encode_status_error, "invalid min buffer size: $minBufferSize")
            )
            return
        }
        bufferSizeInBytes = minBufferSize * 2

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
                getString(R.string.aac_encode_status_error, "AudioRecord initialization failed")
            )
            return
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSizeInBytes)
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (exception: Exception) {
            recorder.release()
            codec.release()
            updateStatus(
                getString(
                    R.string.aac_encode_status_error,
                    exception.message ?: "MediaCodec configuration failed"
                )
            )
            return
        }

        val file = createOutputFile()
        val stream = FileOutputStream(file)

        audioRecord = recorder
        mediaCodec = codec
        outputFile = file
        outputStream = stream
        encodedPacketCount = 0
        isEncoding = true

        binding.outputPathText.text =
            getString(R.string.aac_encode_output_path_value, file.absolutePath)
        binding.frameCountText.text =
            getString(R.string.aac_encode_packet_count_value, encodedPacketCount)
        updatePermissionUi()
        updateStatus(getString(R.string.aac_encode_status_starting))

        encodeThread = thread(start = true, name = "AacEncodeThread") {
            val pcmBuffer = ByteArray(bufferSizeInBytes)
            val bufferInfo = MediaCodec.BufferInfo()
            var presentationTimeUs = 0L
            var eosQueued = false
            try {
                recorder.startRecording()
                postUi {
                    updateStatus(getString(R.string.aac_encode_status_encoding))
                }

                while (isEncoding) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        val level = calculateLevel(pcmBuffer, read)
                        queueInputBuffer(codec, pcmBuffer, read, presentationTimeUs)
                        presentationTimeUs += bytesToDurationUs(read)
                        drainEncoder(codec, bufferInfo, stream)
                        postUi {
                            binding.levelProgress.progress = level
                            binding.levelText.text =
                                getString(R.string.audio_capture_level_value, level)
                        }
                    } else if (read < 0) {
                        postUi {
                            updateStatus(
                                getString(
                                    R.string.aac_encode_status_error,
                                    "AudioRecord read returned $read"
                                )
                            )
                        }
                        break
                    }
                }

                eosQueued = true
                queueEndOfStream(codec, presentationTimeUs)
                drainEncoder(codec, bufferInfo, stream, endOfStream = true)
                stream.flush()
            } catch (exception: Exception) {
                postUi {
                    updateStatus(
                        getString(
                            R.string.aac_encode_status_error,
                            exception.message ?: "AAC encoding failed"
                        )
                    )
                }
            } finally {
                if (!eosQueued) {
                    try {
                        queueEndOfStream(codec, presentationTimeUs)
                        drainEncoder(codec, bufferInfo, stream, endOfStream = true)
                    } catch (_: Exception) {
                    }
                }
                releaseResources()
                postUi {
                    binding.levelProgress.progress = 0
                    binding.levelText.text =
                        getString(R.string.audio_capture_level_value, 0)
                    updatePermissionUi()
                    if (!isFinishing && !isDestroyed) {
                        updateStatus(getString(R.string.aac_encode_status_stopped))
                    }
                }
            }
        }
    }

    private fun stopEncoding() {
        if (!isEncoding) return
        isEncoding = false
        encodeThread?.join(1200)
        encodeThread = null
    }

    private fun queueInputBuffer(codec: MediaCodec, pcmBuffer: ByteArray, size: Int, ptsUs: Long) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(pcmBuffer, 0, size)
            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
        }
    }

    private fun queueEndOfStream(codec: MediaCodec, ptsUs: Long) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                ptsUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        stream: FileOutputStream,
        endOfStream: Boolean = false
    ) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val packet = ByteArray(bufferInfo.size)
                        outputBuffer.get(packet)
                        writeAdtsPacket(stream, packet, packet.size)
                        encodedPacketCount++
                        postUi {
                            binding.frameCountText.text =
                                getString(
                                    R.string.aac_encode_packet_count_value,
                                    encodedPacketCount
                                )
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
                else -> return
            }
        }
    }

    private fun writeAdtsPacket(stream: FileOutputStream, data: ByteArray, dataLength: Int) {
        val packetLength = dataLength + 7
        val adtsHeader = ByteArray(7)
        val profile = 2
        val frequencyIndex = aacSampleRateIndex(sampleRate)
        val channelConfigForHeader = channelCount

        adtsHeader[0] = 0xFF.toByte()
        adtsHeader[1] = 0xF9.toByte()
        adtsHeader[2] =
            (((profile - 1) shl 6) + (frequencyIndex shl 2) + (channelConfigForHeader shr 2)).toByte()
        adtsHeader[3] =
            (((channelConfigForHeader and 3) shl 6) + (packetLength shr 11)).toByte()
        adtsHeader[4] = ((packetLength and 0x7FF) shr 3).toByte()
        adtsHeader[5] = (((packetLength and 7) shl 5) + 0x1F).toByte()
        adtsHeader[6] = 0xFC.toByte()

        stream.write(adtsHeader)
        stream.write(data, 0, dataLength)
    }

    private fun aacSampleRateIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96_000 -> 0
            88_200 -> 1
            64_000 -> 2
            48_000 -> 3
            44_100 -> 4
            32_000 -> 5
            24_000 -> 6
            22_050 -> 7
            16_000 -> 8
            12_000 -> 9
            11_025 -> 10
            8_000 -> 11
            7_350 -> 12
            else -> 4
        }
    }

    private fun bytesToDurationUs(byteCount: Int): Long {
        val bytesPerSample = 2
        val sampleCount = byteCount / bytesPerSample / channelCount
        return (sampleCount * 1_000_000L) / sampleRate
    }

    private fun releaseResources() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null

        try {
            outputStream?.flush()
        } catch (_: Exception) {
        }
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        outputStream = null
        isEncoding = false
    }

    private fun updateStaticInfo() {
        binding.sampleRateText.text =
            getString(R.string.audio_capture_sample_rate_value, sampleRate)
        binding.channelText.text =
            getString(
                R.string.audio_capture_channel_value,
                getString(R.string.audio_capture_channel_mono)
            )
        binding.bitrateText.text =
            getString(R.string.aac_encode_bitrate_value, bitrate)
        binding.frameCountText.text =
            getString(R.string.aac_encode_packet_count_value, 0)
        binding.outputPathText.text =
            getString(
                R.string.aac_encode_output_path_value,
                getString(R.string.h264_encode_output_path_empty)
            )
        binding.levelText.text =
            getString(R.string.audio_capture_level_value, 0)
    }

    private fun updatePermissionUi() {
        val granted = hasAudioPermission()
        binding.permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.startButton.isEnabled = granted && !isEncoding
        binding.stopButton.isEnabled = granted && isEncoding
        if (!granted) {
            updateStatus(getString(R.string.aac_encode_status_permission_required))
        } else if (!isEncoding) {
            updateStatus(getString(R.string.aac_encode_status_idle))
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
        return File(audioDir, "audio_${formatter.format(Date())}.aac")
    }
}
