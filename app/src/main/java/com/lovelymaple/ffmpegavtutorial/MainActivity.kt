package com.lovelymaple.ffmpegavtutorial

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityMainBinding
import com.lovelymaple.ffmpegavtutorial.databinding.ItemFeatureRowBinding
import com.lovelymaple.ffmpegavtutorial.databinding.ItemFeatureSectionBinding

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
        val sections = FeatureCatalog.sections

        sections.forEach { section ->
            val sectionBinding = ItemFeatureSectionBinding.inflate(inflater, binding.featureListContainer, false)
            sectionBinding.sectionTitle.text = getString(section.titleRes)
            binding.featureListContainer.addView(sectionBinding.root)

            section.items.forEachIndexed { index, item ->
                val rowBinding = ItemFeatureRowBinding.inflate(inflater, binding.featureListContainer, false)
                rowBinding.featureTitle.text = getString(item.titleRes)
                rowBinding.featureSummary.text = getString(item.summaryRes)
                rowBinding.featureRowRoot.setOnClickListener {
                    when (val destination = item.destination) {
                        FeatureDestination.FFmpegInfo -> {
                            startActivity(Intent(this, FFmpegInfoActivity::class.java))
                        }
                        FeatureDestination.CameraPreview -> {
                            startActivity(Intent(this, CameraPreviewActivity::class.java))
                        }
                        FeatureDestination.AudioCapture -> {
                            startActivity(Intent(this, AudioCaptureActivity::class.java))
                        }
                        FeatureDestination.AacEncode -> {
                            startActivity(Intent(this, AacEncodeActivity::class.java))
                        }
                        FeatureDestination.FlvMux -> {
                            startActivity(Intent(this, FlvMuxActivity::class.java))
                        }
                        FeatureDestination.LiveFlvMux -> {
                            startActivity(Intent(this, LiveFlvMuxActivity::class.java))
                        }
                        FeatureDestination.H264Encode -> {
                            startActivity(Intent(this, H264EncodeActivity::class.java))
                        }
                        is FeatureDestination.Detail -> {
                            startActivity(
                                Intent(this, FeatureDetailActivity::class.java)
                                    .putExtra(FeatureCatalog.EXTRA_FEATURE_ID, destination.featureId)
                            )
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
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.density.toInt().coerceAtLeast(1)
        )
        setBackgroundColor(0xFFE8E8E8.toInt())
    }
}
