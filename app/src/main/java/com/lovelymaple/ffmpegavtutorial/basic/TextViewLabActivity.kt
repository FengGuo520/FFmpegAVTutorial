package com.lovelymaple.ffmpegavtutorial.basic

import android.os.Bundle
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.config.AppConfig
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityTextViewLabBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace

class TextViewLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextViewLabBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTextViewLabBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)
        binding.backButton.setOnClickListener { finish() }

        binding.chipEndText.setTypeface(AppConfig.mBoldTypeface, Typeface.ITALIC)
        binding.chipStartText.setTypeface(AppConfig.mBoldTypeface, Typeface.ITALIC)

        binding.quoteText.typeface = AppConfig.mBoldTypeface
        binding.outlineText.typeface = AppConfig.mBoldTypeface
        binding.warningText.setTypeface(AppConfig.mBoldTypeface, Typeface.ITALIC)
    }
}
