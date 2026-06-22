package com.lovelymaple.ffmpegavtutorial.basic

import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityH264ProfileLevelGuideBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace

class H264ProfileLevelGuideActivity : AppCompatActivity() {

    private data class GuideCard(
        val badge: String,
        val title: String,
        val overview: String,
        val bullets: List<String>
    )

    private lateinit var binding: ActivityH264ProfileLevelGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityH264ProfileLevelGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        binding.backButton.setOnClickListener { finish() }
        renderGuideCards()
    }

    private fun renderGuideCards() {
        val profileCards = listOf(
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_profile),
                title = getString(R.string.h264_profile_level_profile_baseline_title),
                overview = getString(R.string.h264_profile_level_profile_baseline_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_profile_baseline_bullet_1),
                    getString(R.string.h264_profile_level_profile_baseline_bullet_2),
                    getString(R.string.h264_profile_level_profile_baseline_bullet_3)
                )
            ),
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_profile),
                title = getString(R.string.h264_profile_level_profile_main_title),
                overview = getString(R.string.h264_profile_level_profile_main_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_profile_main_bullet_1),
                    getString(R.string.h264_profile_level_profile_main_bullet_2),
                    getString(R.string.h264_profile_level_profile_main_bullet_3)
                )
            ),
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_profile),
                title = getString(R.string.h264_profile_level_profile_high_title),
                overview = getString(R.string.h264_profile_level_profile_high_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_profile_high_bullet_1),
                    getString(R.string.h264_profile_level_profile_high_bullet_2),
                    getString(R.string.h264_profile_level_profile_high_bullet_3)
                )
            )
        )

        val levelCards = listOf(
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_level),
                title = getString(R.string.h264_profile_level_level_31_title),
                overview = getString(R.string.h264_profile_level_level_31_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_level_31_bullet_1),
                    getString(R.string.h264_profile_level_level_31_bullet_2),
                    getString(R.string.h264_profile_level_level_31_bullet_3)
                )
            ),
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_level),
                title = getString(R.string.h264_profile_level_level_40_title),
                overview = getString(R.string.h264_profile_level_level_40_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_level_40_bullet_1),
                    getString(R.string.h264_profile_level_level_40_bullet_2),
                    getString(R.string.h264_profile_level_level_40_bullet_3)
                )
            ),
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_level),
                title = getString(R.string.h264_profile_level_level_41_title),
                overview = getString(R.string.h264_profile_level_level_41_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_level_41_bullet_1),
                    getString(R.string.h264_profile_level_level_41_bullet_2),
                    getString(R.string.h264_profile_level_level_41_bullet_3)
                )
            )
        )

        val mappingCards = listOf(
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_mapping),
                title = getString(R.string.h264_profile_level_mapping_combo_title),
                overview = getString(R.string.h264_profile_level_mapping_combo_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_mapping_combo_bullet_1),
                    getString(R.string.h264_profile_level_mapping_combo_bullet_2),
                    getString(R.string.h264_profile_level_mapping_combo_bullet_3)
                )
            ),
            GuideCard(
                badge = getString(R.string.h264_profile_level_badge_practice),
                title = getString(R.string.h264_profile_level_mapping_verify_title),
                overview = getString(R.string.h264_profile_level_mapping_verify_overview),
                bullets = listOf(
                    getString(R.string.h264_profile_level_mapping_verify_bullet_1),
                    getString(R.string.h264_profile_level_mapping_verify_bullet_2),
                    getString(R.string.h264_profile_level_mapping_verify_bullet_3),
                    getString(R.string.h264_profile_level_mapping_verify_bullet_4)
                )
            )
        )

        profileCards.forEachIndexed { index, card ->
            binding.profileContainer.addView(createGuideCard(card))
            if (index != profileCards.lastIndex) {
                binding.profileContainer.addView(createSpacer())
            }
        }

        levelCards.forEachIndexed { index, card ->
            binding.levelContainer.addView(createGuideCard(card))
            if (index != levelCards.lastIndex) {
                binding.levelContainer.addView(createSpacer())
            }
        }

        mappingCards.forEachIndexed { index, card ->
            binding.mappingContainer.addView(createGuideCard(card))
            if (index != mappingCards.lastIndex) {
                binding.mappingContainer.addView(createSpacer())
            }
        }
    }

    private fun createGuideCard(card: GuideCard): MaterialCardView {
        val container = createBaseCard()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val badgeView = TextView(this).apply {
            text = card.badge
            setTextColor(0xFF355070.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0xFFEEF4FF.toInt())
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        val titleView = TextView(this).apply {
            text = card.title
            setTextColor(0xFF0F172A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        }

        val overviewView = TextView(this).apply {
            text = card.overview
            setTextColor(0xFF475569.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        body.addView(badgeView)
        body.addView(titleView)
        body.addView(overviewView)

        card.bullets.forEach { bullet ->
            body.addView(
                TextView(this).apply {
                    text = "\u2022 $bullet"
                    setTextColor(0xFF334155.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(0f, 1.15f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(10)
                    }
                }
            )
        }

        container.addView(body)
        return container
    }

    private fun createBaseCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            strokeWidth = dp(1)
            strokeColor = 0xFFD9E2F2.toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createSpacer(): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(16)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
