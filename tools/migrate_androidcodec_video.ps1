$ErrorActionPreference = "Stop"

$sourceRoot = "D:\study\GitProject\FFmpegAVTutorial"
$targetRoot = "D:\study\GitProject\AndroidCodec"

$sourceJavaRoot = Join-Path $sourceRoot "app\src\main\java\com\lovelymaple\ffmpegavtutorial\basic"
$targetJavaRoot = Join-Path $targetRoot "app\src\main\java\com\lovelymaple\codec\basic"
$sourceLayoutRoot = Join-Path $sourceRoot "app\src\main\res\layout"
$targetLayoutRoot = Join-Path $targetRoot "app\src\main\res\layout"
$sourceStrings = Join-Path $sourceRoot "app\src\main\res\values\strings.xml"
$targetStrings = Join-Path $targetRoot "app\src\main\res\values\strings.xml"
$targetManifest = Join-Path $targetRoot "app\src\main\AndroidManifest.xml"
$targetMainActivity = Join-Path $targetRoot "app\src\main\java\com\lovelymaple\codec\MainActivity.kt"

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

New-Item -ItemType Directory -Force -Path $targetJavaRoot | Out-Null

$sourceFiles = @(
    "CameraPreviewActivity.kt",
    "CameraInfoActivity.kt",
    "DeviceCodecInfoActivity.kt",
    "VideoEncodeActivity.kt",
    "VideoSoftEncodeActivity.kt",
    "H264StreamAnalyzerActivity.kt",
    "H265StreamAnalyzerActivity.kt"
)

foreach ($fileName in $sourceFiles) {
    $sourcePath = Join-Path $sourceJavaRoot $fileName
    $targetPath = Join-Path $targetJavaRoot $fileName
    $content = [System.IO.File]::ReadAllText($sourcePath)
    $content = $content.Replace("com.lovelymaple.ffmpegavtutorial", "com.lovelymaple.codec")
    [System.IO.File]::WriteAllText($targetPath, $content, $utf8NoBom)
}

$layoutFiles = @(
    "activity_camera_preview.xml",
    "activity_camera_info.xml",
    "activity_device_codec_info.xml",
    "activity_video_encode.xml",
    "activity_video_soft_encode.xml",
    "activity_h264_stream_analyzer.xml"
)

foreach ($fileName in $layoutFiles) {
    Copy-Item -LiteralPath (Join-Path $sourceLayoutRoot $fileName) -Destination (Join-Path $targetLayoutRoot $fileName) -Force
}

$stringsContent = [System.IO.File]::ReadAllText($sourceStrings)
$stringsContent = $stringsContent.Replace("<string name=`"app_name`">FFmpegAVTutorial</string>", "<string name=`"app_name`">AndroidCodec</string>")
$stringsContent = $stringsContent.Replace("<string name=`"home_title`">FFmpeg-Tutorial</string>", "<string name=`"home_title`">Android-Codec</string>")

if ($stringsContent -notmatch 'name="section_video_codec"') {
    $stringsContent = $stringsContent.Replace(
        "</resources>",
@"
    <string name="section_video_codec">一、视频编解码</string>
    <string name="section_audio_codec">二、音频编解码</string>
</resources>
"@
    )
}

[System.IO.File]::WriteAllText($targetStrings, $stringsContent, $utf8NoBom)

$manifestContent = [System.IO.File]::ReadAllText($targetManifest)
if ($manifestContent -notmatch 'android\.permission\.CAMERA') {
    $manifestContent = $manifestContent.Replace(
        '<uses-permission android:name="android.permission.RECORD_AUDIO" />',
        "<uses-permission android:name=`"android.permission.CAMERA`" />`r`n    <uses-permission android:name=`"android.permission.RECORD_AUDIO`" />"
    )
}

$activityBlock = @"
        <activity
            android:name=".basic.H265StreamAnalyzerActivity"
            android:exported="false" />
        <activity
            android:name=".basic.H264StreamAnalyzerActivity"
            android:exported="false" />
        <activity
            android:name=".basic.VideoSoftEncodeActivity"
            android:exported="false" />
        <activity
            android:name=".basic.VideoEncodeActivity"
            android:exported="false" />
        <activity
            android:name=".basic.DeviceCodecInfoActivity"
            android:exported="false" />
        <activity
            android:name=".basic.CameraInfoActivity"
            android:exported="false" />
        <activity
            android:name=".basic.CameraPreviewActivity"
            android:exported="false" />
"@

if ($manifestContent -notmatch '\.basic\.CameraPreviewActivity') {
    $manifestContent = $manifestContent.Replace(
        '        <activity
            android:name=".audio.AacEncodeActivity"
            android:exported="false" />',
@"
        <activity
            android:name=".audio.AacEncodeActivity"
            android:exported="false" />
$activityBlock
"@
    )
}

[System.IO.File]::WriteAllText($targetManifest, $manifestContent, $utf8NoBom)

$mainActivityContent = @"
package com.lovelymaple.codec

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.codec.audio.AacEncodeActivity
import com.lovelymaple.codec.audio.AudioCaptureActivity
import com.lovelymaple.codec.basic.CameraInfoActivity
import com.lovelymaple.codec.basic.CameraPreviewActivity
import com.lovelymaple.codec.basic.DeviceCodecInfoActivity
import com.lovelymaple.codec.basic.VideoEncodeActivity
import com.lovelymaple.codec.basic.VideoSoftEncodeActivity
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
            val sectionBinding =
                ItemFeatureSectionBinding.inflate(inflater, binding.featureListContainer, false)
            sectionBinding.sectionTitle.text = getString(section.titleRes)
            binding.featureListContainer.addView(sectionBinding.root)

            section.items.forEachIndexed { index, item ->
                val rowBinding =
                    ItemFeatureRowBinding.inflate(inflater, binding.featureListContainer, false)
                rowBinding.featureTitle.text = getString(item.titleRes)
                rowBinding.featureSummary.text = getString(item.summaryRes)
                rowBinding.featureRowRoot.setOnClickListener {
                    when (item.destination) {
                        HomeDestination.CAMERA_PREVIEW ->
                            startActivity(Intent(this, CameraPreviewActivity::class.java))

                        HomeDestination.CAMERA_INFO ->
                            startActivity(Intent(this, CameraInfoActivity::class.java))

                        HomeDestination.DEVICE_CODEC_INFO ->
                            startActivity(Intent(this, DeviceCodecInfoActivity::class.java))

                        HomeDestination.VIDEO_ENCODE ->
                            startActivity(Intent(this, VideoEncodeActivity::class.java))

                        HomeDestination.VIDEO_SOFT_ENCODE ->
                            startActivity(Intent(this, VideoSoftEncodeActivity::class.java))

                        HomeDestination.AUDIO_CAPTURE ->
                            startActivity(Intent(this, AudioCaptureActivity::class.java))

                        HomeDestination.AAC_ENCODE ->
                            startActivity(Intent(this, AacEncodeActivity::class.java))
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
    CAMERA_PREVIEW,
    CAMERA_INFO,
    DEVICE_CODEC_INFO,
    VIDEO_ENCODE,
    VIDEO_SOFT_ENCODE,
    AUDIO_CAPTURE,
    AAC_ENCODE
}

private object FeatureCatalog {
    val sections = listOf(
        FeatureSection(
            titleRes = R.string.section_video_codec,
            items = listOf(
                FeatureItem(
                    R.string.feature_camera_preview_title,
                    R.string.feature_camera_preview_summary,
                    HomeDestination.CAMERA_PREVIEW
                ),
                FeatureItem(
                    R.string.feature_camera_info_title,
                    R.string.feature_camera_info_summary,
                    HomeDestination.CAMERA_INFO
                ),
                FeatureItem(
                    R.string.feature_device_codec_info_title,
                    R.string.feature_device_codec_info_summary,
                    HomeDestination.DEVICE_CODEC_INFO
                ),
                FeatureItem(
                    R.string.feature_h264_encode_title,
                    R.string.feature_h264_encode_summary,
                    HomeDestination.VIDEO_ENCODE
                ),
                FeatureItem(
                    R.string.feature_video_soft_encode_title,
                    R.string.feature_video_soft_encode_summary,
                    HomeDestination.VIDEO_SOFT_ENCODE
                )
            )
        ),
        FeatureSection(
            titleRes = R.string.section_audio_codec,
            items = listOf(
                FeatureItem(
                    R.string.feature_audio_capture_title,
                    R.string.feature_audio_capture_summary,
                    HomeDestination.AUDIO_CAPTURE
                ),
                FeatureItem(
                    R.string.feature_aac_encode_title,
                    R.string.feature_aac_encode_summary,
                    HomeDestination.AAC_ENCODE
                )
            )
        )
    )
}
"@

[System.IO.File]::WriteAllText($targetMainActivity, $mainActivityContent, $utf8NoBom)
