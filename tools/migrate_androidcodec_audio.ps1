$ErrorActionPreference = "Stop"

$utf8 = New-Object System.Text.UTF8Encoding($false)

$targetRoot = "D:\study\GitProject\AndroidCodec"
$sourceRoot = "D:\study\GitProject\FFmpegAVTutorial"

New-Item -ItemType Directory -Force -Path "$targetRoot\app\src\main\java\com\lovelymaple\codec\audio" | Out-Null

$strings = @'
<resources>
    <string name="app_name">AndroidCodec</string>
    <string name="home_title">Android-Codec</string>
    <string name="feature_chevron">›</string>
    <string name="back_arrow">←</string>

    <string name="section_codec_basics">一、编解码基础</string>
    <string name="section_live_pipeline">二、实时链路</string>

    <string name="feature_camera_preview_title">Camera Preview</string>
    <string name="feature_camera_preview_summary">本地摄像头预览、切换前后摄像头与生命周期管理</string>
    <string name="feature_camera_info_title">Camera Info</string>
    <string name="feature_camera_info_summary">查看当前手机 Camera2 摄像头数量、朝向和基础能力</string>
    <string name="feature_device_codec_info_title">Device Codec Info</string>
    <string name="feature_device_codec_info_summary">查看当前设备的 MediaCodec 编解码器列表与支持能力</string>
    <string name="feature_audio_capture_title">Audio Capture</string>
    <string name="feature_audio_capture_summary">打开麦克风采集 PCM，观察采样参数和输入电平</string>
    <string name="feature_video_encode_title">Video Encode Demo</string>
    <string name="feature_video_encode_summary">使用 MediaCodec 对摄像头画面进行 H.264 硬编码</string>
    <string name="feature_aac_encode_title">AAC Encode Demo</string>
    <string name="feature_aac_encode_summary">使用 MediaCodec 对麦克风 PCM 进行 AAC 编码</string>
    <string name="feature_live_camera_audio_title">Live Camera + Audio</string>
    <string name="feature_live_camera_audio_summary">实时采集摄像头与麦克风，为后续推流链路打底</string>
    <string name="feature_mux_pipeline_title">Live Mux Pipeline</string>
    <string name="feature_mux_pipeline_summary">把实时 H.264/AAC 数据送入封装链路，验证同步与输出</string>
    <string name="feature_rtmp_push_title">RTMP Push</string>
    <string name="feature_rtmp_push_summary">复用实时采集和编码结果，进一步接入 RTMP 推流输出</string>
    <string name="home_feature_toast">%1$s 入口会在后续模块迁移时接入。</string>

    <string name="audio_capture_page_title">Audio Capture</string>
    <string name="audio_capture_badge">Stage 2</string>
    <string name="audio_capture_headline">Microphone capture with AudioRecord</string>
    <string name="audio_capture_description">This page verifies the microphone input path: request audio permission, run a local PCM capture loop, observe live input level changes, save PCM data, and auto-export a playable WAV file after stop.</string>
    <string name="audio_capture_grant_permission">Grant Mic Permission</string>
    <string name="audio_capture_start">Start Capture</string>
    <string name="audio_capture_stop">Stop Capture</string>
    <string name="audio_capture_runtime_title">Runtime Snapshot</string>
    <string name="audio_capture_level_title">Live Input Level</string>
    <string name="audio_capture_status_idle">Ready to start microphone capture.</string>
    <string name="audio_capture_status_permission_required">Microphone permission is required before capture can start.</string>
    <string name="audio_capture_status_permission_denied">Microphone permission was denied. Please grant it to continue.</string>
    <string name="audio_capture_status_starting">Starting microphone capture...</string>
    <string name="audio_capture_status_capturing">Capturing PCM audio from the microphone.</string>
    <string name="audio_capture_status_stopped">Microphone capture stopped.</string>
    <string name="audio_capture_status_error">Audio capture failed: %1$s</string>
    <string name="audio_capture_sample_rate_value">Sample rate: %1$d Hz</string>
    <string name="audio_capture_channel_value">Channels: %1$s</string>
    <string name="audio_capture_channel_mono">Mono</string>
    <string name="audio_capture_channel_stereo">Stereo</string>
    <string name="audio_capture_buffer_value">Buffer size: %1$d bytes</string>
    <string name="audio_capture_frame_value">Last frame: %1$d samples</string>
    <string name="audio_capture_pcm_path_value">PCM file: %1$s</string>
    <string name="audio_capture_wav_path_value">WAV file: %1$s</string>
    <string name="audio_capture_file_path_empty">Not saved yet</string>
    <string name="audio_capture_level_value">Input level: %1$d%%</string>

    <string name="aac_encode_page_title">AAC Encode Demo</string>
    <string name="aac_encode_badge">Stage 4</string>
    <string name="aac_encode_headline">Microphone PCM to AAC elementary stream</string>
    <string name="aac_encode_description">This page captures microphone PCM with AudioRecord, encodes it through MediaCodec AAC, writes an ADTS elementary stream, and probes the saved output file after stop.</string>
    <string name="aac_encode_capture_group_title">Capture Parameters</string>
    <string name="aac_encode_capture_group_summary">These options configure the AudioRecord input used to capture microphone PCM.</string>
    <string name="aac_encode_encoder_group_title">Encoding Parameters</string>
    <string name="aac_encode_encoder_group_summary">These options configure the MediaCodec AAC encoder output profile and target bitrate.</string>
    <string name="aac_encode_mode_value">Mode: %1$s</string>
    <string name="aac_encode_mode_hardware">MediaCodec Hardware</string>
    <string name="aac_encode_profile_title">AAC Profile</string>
    <string name="aac_encode_profile_lc">AAC LC</string>
    <string name="aac_encode_profile_he">HE-AAC</string>
    <string name="aac_encode_profile_he_v2">HE-AAC v2</string>
    <string name="aac_encode_sample_rate_title">Sample Rate</string>
    <string name="aac_encode_sample_rate_44100">44.1 kHz</string>
    <string name="aac_encode_sample_rate_48000">48 kHz</string>
    <string name="aac_encode_channel_title">Channel Mode</string>
    <string name="aac_encode_channel_mono">Mono</string>
    <string name="aac_encode_channel_stereo">Stereo</string>
    <string name="aac_encode_bitrate_title">Bitrate</string>
    <string name="aac_encode_bitrate_64k">64 kbps</string>
    <string name="aac_encode_bitrate_96k">96 kbps</string>
    <string name="aac_encode_bitrate_128k">128 kbps</string>
    <string name="aac_encode_start">Start Encode</string>
    <string name="aac_encode_stop">Stop Encode</string>
    <string name="aac_encode_runtime_title">Encoder Snapshot</string>
    <string name="aac_encode_probe_title">Output Probe</string>
    <string name="aac_encode_probe_empty">File probe info will appear here after encoding finishes.</string>
    <string name="aac_encode_probe_failed">Probe failed: %1$s</string>
    <string name="aac_encode_probe_mode_value">Container/MIME: %1$s</string>
    <string name="aac_encode_probe_duration_value">Duration: %1$s</string>
    <string name="aac_encode_probe_sample_rate_value">Sample rate: %1$d Hz</string>
    <string name="aac_encode_probe_channel_value">Channels: %1$d</string>
    <string name="aac_encode_probe_target_bitrate_value">Target bitrate: %1$s</string>
    <string name="aac_encode_probe_metadata_bitrate_value">Metadata bitrate: %1$s</string>
    <string name="aac_encode_probe_average_bitrate_value">Average bitrate: %1$s</string>
    <string name="aac_encode_probe_size_value">File size: %1$s</string>
    <string name="aac_encode_status_idle">Ready to start AAC encoding.</string>
    <string name="aac_encode_status_permission_required">Microphone permission is required before AAC encoding can start.</string>
    <string name="aac_encode_status_permission_denied">Microphone permission was denied. Please grant it to continue.</string>
    <string name="aac_encode_status_starting">Starting microphone capture and AAC encoder...</string>
    <string name="aac_encode_status_encoding">Encoding AAC packets and writing the output stream.</string>
    <string name="aac_encode_status_stopped">AAC encoding stopped.</string>
    <string name="aac_encode_status_error">AAC encode failed: %1$s</string>
    <string name="aac_encode_bitrate_value">Bitrate: %1$d bps</string>
    <string name="aac_encode_profile_value">AAC profile: %1$s</string>
    <string name="aac_encode_packet_count_value">AAC packets: %1$d</string>
    <string name="aac_encode_output_path_value">AAC file: %1$s</string>
</resources>
'@
[System.IO.File]::WriteAllText("$targetRoot\app\src\main\res\values\strings.xml", $strings, $utf8)

$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Codex">
        <activity
            android:name=".audio.AudioCaptureActivity"
            android:exported="false" />
        <activity
            android:name=".audio.AacEncodeActivity"
            android:exported="false" />
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
'@
[System.IO.File]::WriteAllText("$targetRoot\app\src\main\AndroidManifest.xml", $manifest, $utf8)

$mainActivity = @'
package com.lovelymaple.codec

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.codec.audio.AacEncodeActivity
import com.lovelymaple.codec.audio.AudioCaptureActivity
import com.lovelymaple.codec.databinding.ActivityMainBinding
import com.lovelymaple.codec.databinding.ItemFeatureRowBinding
import com.lovelymaple.codec.databinding.ItemFeatureSectionBinding
import com.lovelymaple.codec.ui.setupNavigationBarSpace
import com.lovelymaple.codec.ui.setupStatusBarSpace

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = true)
        setupNavigationBarSpace(binding.navigationBarSpace)
        renderFeatureCatalog()
    }

    private fun renderFeatureCatalog() {
        val inflater = LayoutInflater.from(this)
        FeatureCatalog.sections.forEach { section ->
            val sectionBinding = ItemFeatureSectionBinding.inflate(inflater, binding.featureListContainer, false)
            sectionBinding.sectionTitle.text = getString(section.titleRes)
            binding.featureListContainer.addView(sectionBinding.root)

            section.items.forEachIndexed { index, item ->
                val rowBinding = ItemFeatureRowBinding.inflate(inflater, binding.featureListContainer, false)
                rowBinding.featureTitle.text = getString(item.titleRes)
                rowBinding.featureSummary.text = getString(item.summaryRes)
                rowBinding.featureRowRoot.setOnClickListener {
                    when (item.destination) {
                        HomeDestination.AUDIO_CAPTURE -> startActivity(Intent(this, AudioCaptureActivity::class.java))
                        HomeDestination.AAC_ENCODE -> startActivity(Intent(this, AacEncodeActivity::class.java))
                        HomeDestination.PLACEHOLDER -> {
                            Toast.makeText(this, getString(R.string.home_feature_toast, getString(item.titleRes)), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                binding.featureListContainer.addView(rowBinding.root)
                if (index != section.items.lastIndex) {
                    binding.featureListContainer.addView(createDivider())
                }
            }
        }
    }

    private fun createDivider() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.density.toInt().coerceAtLeast(1)
        )
        setBackgroundColor(0xFFE8E8E8.toInt())
    }
}

private data class FeatureSection(
    @StringRes val titleRes: Int,
    val items: List<FeatureItem>
)

private data class FeatureItem(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val destination: HomeDestination
)

private enum class HomeDestination {
    AUDIO_CAPTURE,
    AAC_ENCODE,
    PLACEHOLDER
}

private object FeatureCatalog {
    val sections = listOf(
        FeatureSection(
            titleRes = R.string.section_codec_basics,
            items = listOf(
                FeatureItem(R.string.feature_camera_preview_title, R.string.feature_camera_preview_summary, HomeDestination.PLACEHOLDER),
                FeatureItem(R.string.feature_camera_info_title, R.string.feature_camera_info_summary, HomeDestination.PLACEHOLDER),
                FeatureItem(R.string.feature_device_codec_info_title, R.string.feature_device_codec_info_summary, HomeDestination.PLACEHOLDER),
                FeatureItem(R.string.feature_audio_capture_title, R.string.feature_audio_capture_summary, HomeDestination.AUDIO_CAPTURE),
                FeatureItem(R.string.feature_video_encode_title, R.string.feature_video_encode_summary, HomeDestination.PLACEHOLDER),
                FeatureItem(R.string.feature_aac_encode_title, R.string.feature_aac_encode_summary, HomeDestination.AAC_ENCODE)
            )
        ),
        FeatureSection(
            titleRes = R.string.section_live_pipeline,
            items = listOf(
                FeatureItem(R.string.feature_live_camera_audio_title, R.string.feature_live_camera_audio_summary, HomeDestination.PLACEHOLDER),
                FeatureItem(R.string.feature_mux_pipeline_title, R.string.feature_mux_pipeline_summary, HomeDestination.PLACEHOLDER),
                FeatureItem(R.string.feature_rtmp_push_title, R.string.feature_rtmp_push_summary, HomeDestination.PLACEHOLDER)
            )
        )
    )
}
'@
[System.IO.File]::WriteAllText("$targetRoot\app\src\main\java\com\lovelymaple\codec\MainActivity.kt", $mainActivity, $utf8)

Copy-Item "$sourceRoot\app\src\main\res\layout\activity_audio_capture.xml" "$targetRoot\app\src\main\res\layout\activity_audio_capture.xml" -Force
Copy-Item "$sourceRoot\app\src\main\res\layout\activity_aac_encode.xml" "$targetRoot\app\src\main\res\layout\activity_aac_encode.xml" -Force
Copy-Item "$sourceRoot\app\src\main\java\com\lovelymaple\ffmpegavtutorial\audio\AudioCaptureActivity.kt" "$targetRoot\app\src\main\java\com\lovelymaple\codec\audio\AudioCaptureActivity.kt" -Force
Copy-Item "$sourceRoot\app\src\main\java\com\lovelymaple\ffmpegavtutorial\audio\AacEncodeActivity.kt" "$targetRoot\app\src\main\java\com\lovelymaple\codec\audio\AacEncodeActivity.kt" -Force

$audioCapturePath = "$targetRoot\app\src\main\java\com\lovelymaple\codec\audio\AudioCaptureActivity.kt"
$audioCaptureText = [System.IO.File]::ReadAllText($audioCapturePath)
$audioCaptureText = $audioCaptureText.Replace('package com.lovelymaple.ffmpegavtutorial.audio', 'package com.lovelymaple.codec.audio')
$audioCaptureText = $audioCaptureText.Replace('import com.lovelymaple.ffmpegavtutorial.R', 'import com.lovelymaple.codec.R')
$audioCaptureText = $audioCaptureText.Replace('import com.lovelymaple.ffmpegavtutorial.databinding.ActivityAudioCaptureBinding', 'import com.lovelymaple.codec.databinding.ActivityAudioCaptureBinding')
$audioCaptureText = $audioCaptureText.Replace('import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace', 'import com.lovelymaple.codec.ui.setupNavigationBarSpace')
$audioCaptureText = $audioCaptureText.Replace('import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace', 'import com.lovelymaple.codec.ui.setupStatusBarSpace')
$audioCaptureText = $audioCaptureText.Replace('firstSample=${read / 2}', 'firstSample=${readFirstSample(byteBuffer, read)}')
[System.IO.File]::WriteAllText($audioCapturePath, $audioCaptureText, $utf8)

$aacPath = "$targetRoot\app\src\main\java\com\lovelymaple\codec\audio\AacEncodeActivity.kt"
$aacText = [System.IO.File]::ReadAllText($aacPath)
$aacText = $aacText.Replace('package com.lovelymaple.ffmpegavtutorial.audio', 'package com.lovelymaple.codec.audio')
$aacText = $aacText.Replace('import com.lovelymaple.ffmpegavtutorial.R', 'import com.lovelymaple.codec.R')
$aacText = $aacText.Replace('import com.lovelymaple.ffmpegavtutorial.databinding.ActivityAacEncodeBinding', 'import com.lovelymaple.codec.databinding.ActivityAacEncodeBinding')
$aacText = $aacText.Replace('import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace', 'import com.lovelymaple.codec.ui.setupNavigationBarSpace')
$aacText = $aacText.Replace('import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace', 'import com.lovelymaple.codec.ui.setupStatusBarSpace')
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, 'import io\\.ffmpegtutotial\\.player\\.internal\\.NativeInstance\\r?\\n', '')
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, '\\s*private lateinit var nativeInstance: NativeInstance\\r?\\n', '')
$aacText = $aacText.Replace('        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()' + [Environment]::NewLine, '')
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, '(?s)    private enum class EncodingMode\\(.*?^    }\\r?\\n\\r?\\n', '', [System.Text.RegularExpressions.RegexOptions]::Multiline)
$aacText = $aacText.Replace('    private var currentMode = EncodingMode.MEDIA_CODEC' + [Environment]::NewLine, '')
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, '(?s)        binding\\.encodingModeGroup\\.setOnCheckedChangeListener \\{.*?        }\\r?\\n', '')
$aacText = $aacText.Replace('        val bitrate = currentBitrate.bitrate' + [Environment]::NewLine + '        val profile = currentProfile' + [Environment]::NewLine + [Environment]::NewLine + '        if (currentMode == EncodingMode.FFMPEG_SOFTWARE && profile != AacProfileOption.LC) {' + [Environment]::NewLine + '            val message = getString(R.string.aac_encode_profile_software_lc_only)' + [Environment]::NewLine + '            updateStatus(getString(R.string.aac_encode_status_error, message))' + [Environment]::NewLine + '            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()' + [Environment]::NewLine + '            return' + [Environment]::NewLine + '        }' + [Environment]::NewLine + [Environment]::NewLine, '        val bitrate = currentBitrate.bitrate' + [Environment]::NewLine + '        val profile = currentProfile' + [Environment]::NewLine + [Environment]::NewLine)
$aacText = $aacText.Replace('        val file = createOutputFile(currentMode)', '        val file = createOutputFile()')
$aacText = $aacText.Replace('        encodeThread = thread(start = true, name = \"AacEncodeThread\") {' + [Environment]::NewLine + '            when (currentMode) {' + [Environment]::NewLine + '                EncodingMode.MEDIA_CODEC -> runMediaCodecEncoding(recorder, file, profile)' + [Environment]::NewLine + '                EncodingMode.FFMPEG_SOFTWARE -> runFfmpegSoftwareEncoding(recorder, file, profile)' + [Environment]::NewLine + '            }' + [Environment]::NewLine + '        }', '        encodeThread = thread(start = true, name = \"AacEncodeThread\") {' + [Environment]::NewLine + '            runMediaCodecEncoding(recorder, file, profile)' + [Environment]::NewLine + '        }')
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, '(?s)    private fun runFfmpegSoftwareEncoding\\(.*?^    }\\r?\\n\\r?\\n', '', [System.Text.RegularExpressions.RegexOptions]::Multiline)
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, '    private fun updatePacketCountFromSummary\\(.*?^    }\\r?\\n\\r?\\n', '', [System.Text.RegularExpressions.RegexOptions]::Singleline -bor [System.Text.RegularExpressions.RegexOptions]::Multiline)
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, '    private fun normalizeNativeMessage\\(.*?^    }\\r?\\n\\r?\\n', '', [System.Text.RegularExpressions.RegexOptions]::Singleline -bor [System.Text.RegularExpressions.RegexOptions]::Multiline)
$aacText = $aacText.Replace('        updateModeText()' + [Environment]::NewLine, '')
$aacText = [System.Text.RegularExpressions.Regex]::Replace($aacText, '    private fun updateModeText\\(\\) \\{.*?^    }\\r?\\n\\r?\\n', '', [System.Text.RegularExpressions.RegexOptions]::Singleline -bor [System.Text.RegularExpressions.RegexOptions]::Multiline)
$aacText = $aacText.Replace('        binding.encodingModeGroup.isEnabled = !isEncoding' + [Environment]::NewLine, '')
$aacText = $aacText.Replace('        binding.modeHardwareRadio.isEnabled = !isEncoding' + [Environment]::NewLine, '')
$aacText = $aacText.Replace('        binding.modeSoftwareRadio.isEnabled = !isEncoding' + [Environment]::NewLine, '')
$aacText = $aacText.Replace('    private fun createOutputFile(mode: EncodingMode): File {' + [Environment]::NewLine + '        val audioDir = File(filesDir, \"audio\").apply { mkdirs() }' + [Environment]::NewLine + '        val formatter = SimpleDateFormat(\"yyyyMMdd_HHmmss\", Locale.US)' + [Environment]::NewLine + '        return File(audioDir, \"${mode.filePrefix}_${formatter.format(Date())}.aac\")' + [Environment]::NewLine + '    }', '    private fun createOutputFile(): File {' + [Environment]::NewLine + '        val audioDir = File(filesDir, \"audio\").apply { mkdirs() }' + [Environment]::NewLine + '        val formatter = SimpleDateFormat(\"yyyyMMdd_HHmmss\", Locale.US)' + [Environment]::NewLine + '        return File(audioDir, \"aac_hw_${formatter.format(Date())}.aac\")' + [Environment]::NewLine + '    }')
$aacText = $aacText.Replace('                getString(' + [Environment]::NewLine + '                    R.string.aac_encode_probe_bitrate_value,' + [Environment]::NewLine + '                    formatBitrate(averageBitrate)' + [Environment]::NewLine + '                ),' + [Environment]::NewLine, '')
$aacText = $aacText.Replace('                getString(R.string.aac_encode_output_path_value, getString(R.string.h264_encode_output_path_empty))', '                getString(R.string.aac_encode_output_path_value, getString(R.string.audio_capture_file_path_empty))')
$aacText = $aacText.Replace('        updateModeText()' + [Environment]::NewLine, '        binding.modeText.text =' + [Environment]::NewLine + '            getString(R.string.aac_encode_mode_value, getString(R.string.aac_encode_mode_hardware))' + [Environment]::NewLine)
[System.IO.File]::WriteAllText($aacPath, $aacText, $utf8)
