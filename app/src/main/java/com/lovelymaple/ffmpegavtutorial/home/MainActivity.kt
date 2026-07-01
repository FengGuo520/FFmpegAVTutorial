package com.lovelymaple.ffmpegavtutorial.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.basic.AvBufferGuideActivity
import com.lovelymaple.ffmpegavtutorial.basic.AvFrameGuideActivity
import com.lovelymaple.ffmpegavtutorial.basic.AvPacketGuideActivity
import com.lovelymaple.ffmpegavtutorial.basic.AvRationalGuideActivity
import com.lovelymaple.ffmpegavtutorial.basic.BufferGuideActivity
import com.lovelymaple.ffmpegavtutorial.basic.FFmpegInfoActivity
import com.lovelymaple.ffmpegavtutorial.basic.MovieExtractActivity
import com.lovelymaple.ffmpegavtutorial.basic.MovieProberActivity
import com.lovelymaple.ffmpegavtutorial.basic.StreamingTopicActivity
import com.lovelymaple.ffmpegavtutorial.container.FlvMuxActivity
import com.lovelymaple.ffmpegavtutorial.container.LiveFlvMuxActivity
import com.lovelymaple.ffmpegavtutorial.container.RtmpPushActivity
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityMainBinding
import com.lovelymaple.ffmpegavtutorial.databinding.ItemFeatureRowBinding
import com.lovelymaple.ffmpegavtutorial.databinding.ItemFeatureSectionBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace

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
                        FeatureDestination.AvRationalGuide -> {
                            startActivity(Intent(this, AvRationalGuideActivity::class.java))
                        }
                        FeatureDestination.BufferGuide -> {
                            startActivity(Intent(this, BufferGuideActivity::class.java))
                        }
                        FeatureDestination.AvBufferGuide -> {
                            startActivity(Intent(this, AvBufferGuideActivity::class.java))
                        }
                        FeatureDestination.AvPacketGuide -> {
                            startActivity(Intent(this, AvPacketGuideActivity::class.java))
                        }
                        FeatureDestination.AvFrameGuide -> {
                            startActivity(Intent(this, AvFrameGuideActivity::class.java))
                        }
                        FeatureDestination.MovieProber -> {
                            startActivity(Intent(this, MovieProberActivity::class.java))
                        }
                        FeatureDestination.MovieExtract -> {
                            startActivity(Intent(this, MovieExtractActivity::class.java))
                        }
                        FeatureDestination.StreamingTopic -> {
                            startActivity(Intent(this, StreamingTopicActivity::class.java))
                        }
                        FeatureDestination.FlvMux -> {
                            startActivity(Intent(this, FlvMuxActivity::class.java))
                        }
                        FeatureDestination.LiveFlvMux -> {
                            startActivity(Intent(this, LiveFlvMuxActivity::class.java))
                        }
                        FeatureDestination.RtmpPush -> {
                            startActivity(Intent(this, RtmpPushActivity::class.java))
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
