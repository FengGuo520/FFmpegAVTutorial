package com.lovelymaple.ffmpegavtutorial

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityFeatureDetailBinding

class FeatureDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeatureDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val featureId = intent.getStringExtra(FeatureCatalog.EXTRA_FEATURE_ID)
        val detail = featureId?.let { FeatureCatalog.details[it] }
        if (detail == null) {
            finish()
            return
        }

        binding = ActivityFeatureDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.detailTitle.text = getString(detail.titleRes)
        binding.toolbarTitle.text = getString(detail.titleRes)
        binding.detailSummary.text = getString(detail.summaryRes)
        binding.overviewContent.text = getString(detail.overviewRes)
        binding.focusContent.text = getString(detail.focusRes)
        binding.practiceContent.text = getString(detail.practiceRes)
    }
}
