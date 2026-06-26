package com.lovelymaple.codec

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.codec.audio.AacEncodeActivity
import com.lovelymaple.codec.audio.AudioCaptureActivity
import com.lovelymaple.codec.databinding.ActivityMainBinding
import com.lovelymaple.codec.databinding.ItemFeatureRowBinding
import com.lovelymaple.codec.databinding.ItemFeatureSectionBinding
import com.lovelymaple.codec.ui.setupNavigationBarSpace
import com.lovelymaple.codec.ui.setupStatusBarSpace
import com.lovelymaple.codec.video.CameraInfoActivity
import com.lovelymaple.codec.video.CameraPreviewActivity
import com.lovelymaple.codec.video.DeviceCodecInfoActivity
import com.lovelymaple.codec.video.H264StreamAnalyzerActivity
import com.lovelymaple.codec.video.H265StreamAnalyzerActivity
import com.lovelymaple.codec.video.VideoEncodeActivity
import com.lovelymaple.codec.video.VideoSoftEncodeActivity

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

                        HomeDestination.H264_ANALYZER ->
                            startActivity(Intent(this, H264StreamAnalyzerActivity::class.java))

                        HomeDestination.H265_ANALYZER ->
                            startActivity(Intent(this, H265StreamAnalyzerActivity::class.java))

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
    H264_ANALYZER,
    H265_ANALYZER,
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
                    R.string.feature_h264_analyzer_title,
                    R.string.feature_h264_analyzer_summary,
                    HomeDestination.H264_ANALYZER
                ),
                FeatureItem(
                    R.string.feature_h265_analyzer_title,
                    R.string.feature_h265_analyzer_summary,
                    HomeDestination.H265_ANALYZER
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
