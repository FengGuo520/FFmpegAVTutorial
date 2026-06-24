package com.lovelymaple.ffmpegavtutorial.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityAacEncodeBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AacEncodeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AacEncodeActivity"
    }

    private enum class EncodingMode(
        val labelRes: Int,
        val filePrefix: String
    ) {
        MEDIA_CODEC(R.string.aac_encode_mode_hardware, "aac_hw"),
        FFMPEG_SOFTWARE(R.string.aac_encode_mode_software, "aac_sw")
    }

    private enum class SampleRateOption(
        val sampleRate: Int,
        val labelRes: Int
    ) {
        SR_44100(44_100, R.string.aac_encode_sample_rate_44100),
        SR_48000(48_000, R.string.aac_encode_sample_rate_48000)
    }

    private enum class ChannelOption(
        val channelCount: Int,
        val channelConfig: Int,
        val labelRes: Int
    ) {
        MONO(1, AudioFormat.CHANNEL_IN_MONO, R.string.aac_encode_channel_mono),
        STEREO(2, AudioFormat.CHANNEL_IN_STEREO, R.string.aac_encode_channel_stereo)
    }

    private enum class BitrateOption(
        val bitrate: Int,
        val labelRes: Int
    ) {
        BR_64K(64_000, R.string.aac_encode_bitrate_64k),
        BR_96K(96_000, R.string.aac_encode_bitrate_96k),
        BR_128K(128_000, R.string.aac_encode_bitrate_128k)
    }

    private enum class AacProfileOption(
        val labelRes: Int,
        val mediaCodecProfile: Int,
        val ffmpegProfile: Int
    ) {
        LC(R.string.aac_encode_profile_lc, MediaCodecInfo.CodecProfileLevel.AACObjectLC, 1),
        HE(R.string.aac_encode_profile_he, MediaCodecInfo.CodecProfileLevel.AACObjectHE, 4),
        HE_V2(R.string.aac_encode_profile_he_v2, MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS, 28)
    }

    private lateinit var binding: ActivityAacEncodeBinding
    private lateinit var nativeInstance: NativeInstance

    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var bufferSizeInBytes: Int = 0
    @Volatile private var isEncoding = false
    private var encodeThread: Thread? = null
    private var encodedPacketCount = 0
    private var currentMode = EncodingMode.MEDIA_CODEC
    private var currentSampleRate = SampleRateOption.SR_44100
    private var currentChannel = ChannelOption.STEREO
    private var currentBitrate = BitrateOption.BR_64K
    private var currentProfile = AacProfileOption.LC

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

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { ensurePermissionAndStart() }
        binding.startButton.setOnClickListener { ensurePermissionAndStart() }
        binding.stopButton.setOnClickListener { stopEncoding() }
        binding.encodingModeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentMode =
                if (checkedId == binding.modeSoftwareRadio.id) {
                    EncodingMode.FFMPEG_SOFTWARE
                } else {
                    EncodingMode.MEDIA_CODEC
                }
            updateModeText()
        }
        binding.sampleRateGroup.setOnCheckedChangeListener { _, checkedId ->
            currentSampleRate =
                if (checkedId == binding.sampleRate48000Radio.id) {
                    SampleRateOption.SR_48000
                } else {
                    SampleRateOption.SR_44100
                }
            updateStaticInfo()
        }
        binding.channelModeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentChannel =
                if (checkedId == binding.channelMonoRadio.id) {
                    ChannelOption.MONO
                } else {
                    ChannelOption.STEREO
                }
            updateStaticInfo()
        }
        binding.bitrateGroup.setOnCheckedChangeListener { _, checkedId ->
            currentBitrate =
                when (checkedId) {
                    binding.bitrate96kRadio.id -> BitrateOption.BR_96K
                    binding.bitrate128kRadio.id -> BitrateOption.BR_128K
                    else -> BitrateOption.BR_64K
                }
            updateStaticInfo()
        }
        binding.profileGroup.setOnCheckedChangeListener { _, checkedId ->
            currentProfile =
                when (checkedId) {
                    binding.profileHeRadio.id -> AacProfileOption.HE
                    binding.profileHeV2Radio.id -> AacProfileOption.HE_V2
                    else -> AacProfileOption.LC
                }
            updateStaticInfo()
        }

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

        val sampleRate = currentSampleRate.sampleRate
        val channelConfig = currentChannel.channelConfig
        val channelCount = currentChannel.channelCount
        val bitrate = currentBitrate.bitrate
        val profile = currentProfile

        if (currentMode == EncodingMode.FFMPEG_SOFTWARE && profile != AacProfileOption.LC) {
            val message = getString(R.string.aac_encode_profile_software_lc_only)
            updateStatus(getString(R.string.aac_encode_status_error, message))
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

        val file = createOutputFile(currentMode)
        audioRecord = recorder
        outputFile = file
        encodedPacketCount = 0
        isEncoding = true

        binding.outputPathText.text =
            getString(R.string.aac_encode_output_path_value, file.absolutePath)
        binding.probeInfoText.text = getString(R.string.aac_encode_probe_empty)
        binding.frameCountText.text =
            getString(R.string.aac_encode_packet_count_value, encodedPacketCount)
        updatePermissionUi()
        updateStatus(getString(R.string.aac_encode_status_starting))

        encodeThread = thread(start = true, name = "AacEncodeThread") {
            when (currentMode) {
                EncodingMode.MEDIA_CODEC -> runMediaCodecEncoding(recorder, file, profile)
                EncodingMode.FFMPEG_SOFTWARE -> runFfmpegSoftwareEncoding(recorder, file, profile)
            }
        }
    }

    private fun runMediaCodecEncoding(
        recorder: AudioRecord,
        file: File,
        profile: AacProfileOption
    ) {
        val sampleRate = currentSampleRate.sampleRate
        val channelCount = currentChannel.channelCount
        val bitrate = currentBitrate.bitrate
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile.mediaCodecProfile)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSizeInBytes)
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (exception: Exception) {
            recorder.release()
            codec.release()
            audioRecord = null
            postUi {
                updatePermissionUi()
                updateStatus(
                    getString(
                        R.string.aac_encode_status_error,
                        exception.message ?: "MediaCodec configuration failed"
                    )
                )
            }
            isEncoding = false
            return
        }

        var stream: FileOutputStream? = null
        mediaCodec = codec
        try {
            stream = FileOutputStream(file)
            outputStream = stream

            val pcmBuffer = ByteArray(bufferSizeInBytes)
            val bufferInfo = MediaCodec.BufferInfo()
            var presentationTimeUs = 0L
            var eosQueued = false

            recorder.startRecording()
            postUi {
                updateStatus(getString(R.string.aac_encode_status_encoding))
            }

            while (isEncoding) {
                val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                if (read > 0) {
                    val level = calculateLevel(pcmBuffer, read)
                    queueInputBuffer(codec, pcmBuffer, read, presentationTimeUs)
                    presentationTimeUs += bytesToDurationUs(read, sampleRate, channelCount)
                    val packetDelta = drainEncoder(codec, bufferInfo, stream, profile)
                    if (packetDelta < 0) {
                        throw IllegalStateException("MediaCodec drain failed: $packetDelta")
                    }
                    encodedPacketCount += packetDelta
                    postUi {
                        binding.levelProgress.progress = level
                        binding.levelText.text =
                            getString(R.string.audio_capture_level_value, level)
                        binding.frameCountText.text =
                            getString(R.string.aac_encode_packet_count_value, encodedPacketCount)
                    }
                } else if (read < 0) {
                    throw IllegalStateException("AudioRecord read returned $read")
                }
            }

            eosQueued = true
            queueEndOfStream(codec, presentationTimeUs)
            val finalPacketDelta = drainEncoder(codec, bufferInfo, stream, profile, endOfStream = true)
            if (finalPacketDelta < 0) {
                throw IllegalStateException("MediaCodec final drain failed: $finalPacketDelta")
            }
            encodedPacketCount += finalPacketDelta
            stream.flush()
        } catch (exception: Exception) {
            postUi {
                updateStatus(
                    getString(
                        R.string.aac_encode_status_error,
                        exception.message ?: "AAC hardware encoding failed"
                    )
                )
            }
        } finally {
            releaseMediaCodecResources()
            postUi {
                finishEncodingUi()
            }
        }
    }

    private fun runFfmpegSoftwareEncoding(
        recorder: AudioRecord,
        file: File,
        profile: AacProfileOption
    ) {
        val sampleRate = currentSampleRate.sampleRate
        val channelCount = currentChannel.channelCount
        val bitrate = currentBitrate.bitrate
        var closeSummary: String? = null
        var nativeOpened = false
        try {
            val openResult = nativeInstance.openSoftAacEncoder(
                file.absolutePath,
                sampleRate,
                channelCount,
                bitrate,
                profile.ffmpegProfile
            )
            if (!openResult.startsWith("OK:")) {
                throw IllegalStateException(openResult)
            }
            nativeOpened = true

            val pcmBuffer = ByteArray(bufferSizeInBytes)
            recorder.startRecording()
            postUi {
                updateStatus(getString(R.string.aac_encode_status_encoding))
            }

            while (isEncoding) {
                val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                if (read > 0) {
                    val level = calculateLevel(pcmBuffer, read)
                    val packetDelta = nativeInstance.writeSoftAacPcm(pcmBuffer, read)
                    if (packetDelta < 0) {
                        throw IllegalStateException("FFmpeg soft AAC write failed: $packetDelta")
                    }
                    encodedPacketCount += packetDelta
                    postUi {
                        binding.levelProgress.progress = level
                        binding.levelText.text =
                            getString(R.string.audio_capture_level_value, level)
                        binding.frameCountText.text =
                            getString(R.string.aac_encode_packet_count_value, encodedPacketCount)
                    }
                } else if (read < 0) {
                    throw IllegalStateException("AudioRecord read returned $read")
                }
            }
        } catch (exception: Exception) {
            postUi {
                updateStatus(
                    getString(
                        R.string.aac_encode_status_error,
                        normalizeNativeMessage(exception.message ?: "FFmpeg soft AAC encode failed")
                    )
                )
            }
        } finally {
            if (nativeOpened) {
                closeSummary = nativeInstance.closeSoftAacEncoder()
                updatePacketCountFromSummary(closeSummary)
            }
            releaseAudioRecord()
            isEncoding = false
            postUi {
                finishEncodingUi()
                if (closeSummary != null && closeSummary!!.startsWith("ERROR:")) {
                    updateStatus(
                        getString(
                            R.string.aac_encode_status_error,
                            normalizeNativeMessage(closeSummary!!)
                        )
                    )
                }
            }
        }
    }

    private fun stopEncoding() {
        if (!isEncoding) return
        isEncoding = false
        encodeThread?.join(1500)
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
        profile: AacProfileOption,
        endOfStream: Boolean = false
    ): Int {
        var emittedPackets = 0
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return emittedPackets
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "AAC output format changed: ${codec.outputFormat}")
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
                    val isCodecConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEndOfStream =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    Log.d(
                        TAG,
                        "AAC output packet index=$outputIndex ptsUs=${bufferInfo.presentationTimeUs} " +
                            "size=${bufferInfo.size} offset=${bufferInfo.offset} " +
                            "flags=${bufferInfo.flags} codecConfig=$isCodecConfig eos=$isEndOfStream"
                    )
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val packet = ByteArray(bufferInfo.size)
                        outputBuffer.get(packet)
                        writeAdtsPacket(stream, packet, packet.size, profile)
                        emittedPackets += 1
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEndOfStream) {
                        return emittedPackets
                    }
                }
                else -> return emittedPackets
            }
        }
        return emittedPackets
    }

    private fun writeAdtsPacket(
        stream: FileOutputStream,
        data: ByteArray,
        dataLength: Int,
        profile: AacProfileOption
    ) {
        val sampleRate = adtsHeaderSampleRate(profile, currentSampleRate.sampleRate)
        val channelCount = currentChannel.channelCount
        val packetLength = dataLength + 7
        val adtsHeader = ByteArray(7)
        val adtsProfile = when (profile) {
            AacProfileOption.LC -> 2
            AacProfileOption.HE, AacProfileOption.HE_V2 -> 2
        }
        val frequencyIndex = aacSampleRateIndex(sampleRate)
        val channelConfigForHeader = channelCount

        adtsHeader[0] = 0xFF.toByte()
        adtsHeader[1] = 0xF9.toByte()
        adtsHeader[2] =
            (((adtsProfile - 1) shl 6) + (frequencyIndex shl 2) + (channelConfigForHeader shr 2)).toByte()
        adtsHeader[3] =
            (((channelConfigForHeader and 3) shl 6) + (packetLength shr 11)).toByte()
        adtsHeader[4] = ((packetLength and 0x7FF) shr 3).toByte()
        adtsHeader[5] = (((packetLength and 7) shl 5) + 0x1F).toByte()
        adtsHeader[6] = 0xFC.toByte()

        stream.write(adtsHeader)
        stream.write(data, 0, dataLength)
    }

    private fun adtsHeaderSampleRate(profile: AacProfileOption, configuredSampleRate: Int): Int {
        return when (profile) {
            AacProfileOption.LC -> configuredSampleRate
            AacProfileOption.HE,
            AacProfileOption.HE_V2 -> (configuredSampleRate / 2).coerceAtLeast(8_000)
        }
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

    private fun bytesToDurationUs(byteCount: Int, sampleRate: Int, channelCount: Int): Long {
        val bytesPerSample = 2
        val sampleCount = byteCount / bytesPerSample / channelCount
        return (sampleCount * 1_000_000L) / sampleRate
    }

    private fun releaseMediaCodecResources() {
        releaseAudioRecord()

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

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    private fun finishEncodingUi() {
        binding.levelProgress.progress = 0
        binding.levelText.text =
            getString(R.string.audio_capture_level_value, 0)
        binding.frameCountText.text =
            getString(R.string.aac_encode_packet_count_value, encodedPacketCount)
        binding.probeInfoText.text = buildProbeInfo(outputFile)
        updatePermissionUi()
        if (!isFinishing && !isDestroyed) {
            updateStatus(getString(R.string.aac_encode_status_stopped))
        }
    }

    private fun updatePacketCountFromSummary(summary: String?) {
        if (summary.isNullOrBlank()) return
        val packetLine = summary.lineSequence()
            .firstOrNull { it.trim().startsWith("packets:") } ?: return
        val packetValue = packetLine.substringAfter(':').trim().toIntOrNull() ?: return
        encodedPacketCount = packetValue
    }

    private fun normalizeNativeMessage(message: String): String {
        return message.removePrefix("ERROR: ").trim()
    }

    private fun updateStaticInfo() {
        binding.sampleRateText.text =
            getString(R.string.audio_capture_sample_rate_value, currentSampleRate.sampleRate)
        binding.channelText.text =
            getString(
                R.string.audio_capture_channel_value,
                getString(currentChannel.labelRes)
            )
        binding.bitrateText.text =
            getString(R.string.aac_encode_bitrate_value, currentBitrate.bitrate)
        binding.profileText.text =
            getString(R.string.aac_encode_profile_value, getString(currentProfile.labelRes))
        binding.frameCountText.text =
            getString(R.string.aac_encode_packet_count_value, 0)
        binding.outputPathText.text =
            getString(
                R.string.aac_encode_output_path_value,
                getString(R.string.h264_encode_output_path_empty)
            )
        binding.probeInfoText.text = getString(R.string.aac_encode_probe_empty)
        binding.levelText.text =
            getString(R.string.audio_capture_level_value, 0)
        updateModeText()
    }

    private fun updateModeText() {
        binding.modeText.text =
            getString(R.string.aac_encode_mode_value, getString(currentMode.labelRes))
    }

    private fun updatePermissionUi() {
        val granted = hasAudioPermission()
        binding.permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.startButton.isEnabled = granted && !isEncoding
        binding.stopButton.isEnabled = granted && isEncoding
        binding.encodingModeGroup.isEnabled = !isEncoding
        binding.modeHardwareRadio.isEnabled = !isEncoding
        binding.modeSoftwareRadio.isEnabled = !isEncoding
        binding.sampleRateGroup.isEnabled = !isEncoding
        binding.sampleRate44100Radio.isEnabled = !isEncoding
        binding.sampleRate48000Radio.isEnabled = !isEncoding
        binding.channelModeGroup.isEnabled = !isEncoding
        binding.channelMonoRadio.isEnabled = !isEncoding
        binding.channelStereoRadio.isEnabled = !isEncoding
        binding.bitrateGroup.isEnabled = !isEncoding
        binding.bitrate64kRadio.isEnabled = !isEncoding
        binding.bitrate96kRadio.isEnabled = !isEncoding
        binding.bitrate128kRadio.isEnabled = !isEncoding
        binding.profileGroup.isEnabled = !isEncoding
        binding.profileLcRadio.isEnabled = !isEncoding
        binding.profileHeRadio.isEnabled = !isEncoding
        binding.profileHeV2Radio.isEnabled = !isEncoding
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

    private fun createOutputFile(mode: EncodingMode): File {
        val audioDir = File(filesDir, "audio").apply { mkdirs() }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return File(audioDir, "${mode.filePrefix}_${formatter.format(Date())}.aac")
    }

    private fun buildProbeInfo(file: File?): String {
        if (file == null || !file.exists() || file.length() <= 0L) {
            return getString(R.string.aac_encode_probe_empty)
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val trackFormat = findAudioTrackFormat(extractor)
                ?: return getString(
                    R.string.aac_encode_probe_failed,
                    "no audio track found"
                )

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: "unknown"
            val sampleRate = trackFormat.getIntegerSafely(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = trackFormat.getIntegerSafely(MediaFormat.KEY_CHANNEL_COUNT)
            val metadataBitrate = trackFormat.getIntegerSafely(MediaFormat.KEY_BIT_RATE)
            val durationUs = trackFormat.getLongSafely(MediaFormat.KEY_DURATION)
            val averageBitrate = estimateAverageBitrate(file.length(), durationUs)

            listOf(
                getString(R.string.aac_encode_probe_mode_value, mime),
                getString(
                    R.string.aac_encode_probe_duration_value,
                    formatDuration(durationUs)
                ),
                getString(
                    R.string.aac_encode_probe_sample_rate_value,
                    sampleRate
                ),
                getString(
                    R.string.aac_encode_probe_channel_value,
                    channelCount
                ),
                getString(
                    R.string.aac_encode_probe_target_bitrate_value,
                    formatBitrate(currentBitrate.bitrate)
                ),
                getString(
                    R.string.aac_encode_probe_metadata_bitrate_value,
                    formatBitrate(metadataBitrate)
                ),
                getString(
                    R.string.aac_encode_probe_average_bitrate_value,
                    formatBitrate(averageBitrate)
                ),
                getString(
                    R.string.aac_encode_probe_size_value,
                    Formatter.formatShortFileSize(this, file.length())
                )
            ).joinToString(separator = "\n")
        } catch (exception: Exception) {
            getString(
                R.string.aac_encode_probe_failed,
                exception.message ?: "unknown error"
            )
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun findAudioTrackFormat(extractor: MediaExtractor): MediaFormat? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return format
            }
        }
        return null
    }

    private fun MediaFormat.getIntegerSafely(key: String): Int {
        return if (containsKey(key)) getInteger(key) else 0
    }

    private fun MediaFormat.getLongSafely(key: String): Long {
        return if (containsKey(key)) getLong(key) else 0L
    }

    private fun formatDuration(durationUs: Long): String {
        if (durationUs <= 0L) return "unknown"
        val totalMs = durationUs / 1000L
        val minutes = totalMs / 60_000L
        val seconds = (totalMs % 60_000L) / 1000L
        val millis = totalMs % 1000L
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun estimateAverageBitrate(fileSizeBytes: Long, durationUs: Long): Int {
        if (fileSizeBytes <= 0L || durationUs <= 0L) return 0
        return ((fileSizeBytes * 8_000_000L) / durationUs).toInt()
    }

    private fun formatBitrate(bitrate: Int): String {
        if (bitrate <= 0) return "unknown"
        return "${bitrate / 1000} kbps"
    }
}
