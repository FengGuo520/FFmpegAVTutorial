package com.lovelymaple.ffmpegavtutorial.basic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityBufferGuideBinding
import com.lovelymaple.ffmpegavtutorial.databinding.ItemFeatureRowBinding
import com.lovelymaple.ffmpegavtutorial.databinding.ItemFeatureSectionBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace

class BufferGuideActivity : AppCompatActivity() {

    private data class GuideItem(
        val titleRes: Int,
        val summaryRes: Int,
        val targetClass: Class<*>
    )

    private lateinit var binding: ActivityBufferGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBufferGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = true)
        setupNavigationBarSpace(binding.navigationBarSpace)
        binding.backButton.setOnClickListener { finish() }

        renderGuideList()
    }

    private fun renderGuideList() {
        val inflater = LayoutInflater.from(this)
        val sectionBinding =
            ItemFeatureSectionBinding.inflate(inflater, binding.guideListContainer, false)
        sectionBinding.sectionTitle.text = getString(R.string.buffer_guide_section_title)
        binding.guideListContainer.addView(sectionBinding.root)

        val items = listOf(
            GuideItem(
                titleRes = R.string.feature_av_buffer_title,
                summaryRes = R.string.feature_av_buffer_summary,
                targetClass = AvBufferGuideActivity::class.java
            ),
            GuideItem(
                titleRes = R.string.feature_av_packet_title,
                summaryRes = R.string.feature_av_packet_summary,
                targetClass = AvPacketGuideActivity::class.java
            ),
            GuideItem(
                titleRes = R.string.feature_av_frame_title,
                summaryRes = R.string.feature_av_frame_summary,
                targetClass = AvFrameGuideActivity::class.java
            )
        )

        items.forEachIndexed { index, item ->
            val rowBinding =
                ItemFeatureRowBinding.inflate(inflater, binding.guideListContainer, false)
            rowBinding.featureTitle.text = getString(item.titleRes)
            rowBinding.featureSummary.text = getString(item.summaryRes)
            rowBinding.featureRowRoot.setOnClickListener {
                startActivity(Intent(this, item.targetClass))
            }
            binding.guideListContainer.addView(rowBinding.root)

            if (index != items.lastIndex) {
                binding.guideListContainer.addView(createDivider())
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
