package com.lovelymaple.ffmpegavtutorial

import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityFfmpegInfoBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import io.ffmpegtutotial.player.internal.NativeInstance

class FFmpegInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFfmpegInfoBinding
    private lateinit var nativeInstance: NativeInstance

    private data class InfoSection(
        val title: String,
        val content: String,
        val itemCount: Int? = null
    )

    private data class InfoCategory(
        val key: String,
        val label: String,
        val summary: String,
        val sections: List<InfoSection>,
        val expandedByDefault: Boolean = false
    )

    private data class InfoUiModel(
        val summaryTags: List<String>,
        val categories: List<InfoCategory>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFfmpegInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        binding.backButton.setOnClickListener {
            finish()
        }

        nativeInstance = NativeInstance()
        binding.refreshButton.setOnClickListener {
            renderFFmpegInfo()
        }
        renderFFmpegInfo()
    }

    private fun renderFFmpegInfo() {
        binding.refreshButton.isEnabled = false
        binding.sectionContainer.removeAllViews()
        binding.summaryChipGroup.removeAllViews()

        try {
            val rawInfo = nativeInstance.getInfo(0L)
            val uiModel = buildUiModel(rawInfo)
            binding.errorText.isVisible = false
            binding.subtitleText.text =
                "Grouped by runtime, protocols, formats, decoders, and encoders. Tap a card header to expand or collapse."
            renderSummaryTags(uiModel.summaryTags)
            renderCategories(uiModel.categories)
        } catch (t: Throwable) {
            binding.errorText.isVisible = true
            binding.errorText.text =
                getString(R.string.ffmpeg_info_load_failed, t.message ?: t.javaClass.simpleName)
            renderSummaryTags(listOf("Load failed", "Check native initialization"))
            renderCategories(
                listOf(
                    InfoCategory(
                        key = "error",
                        label = "Error",
                        summary = "Unable to load FFmpeg runtime info",
                        sections = listOf(
                            InfoSection(
                                title = "Details",
                                content = binding.errorText.text.toString()
                            )
                        ),
                        expandedByDefault = true
                    )
                )
            )
        } finally {
            binding.refreshButton.isEnabled = true
        }
    }

    private fun buildUiModel(rawInfo: String): InfoUiModel {
        val parsedSections = rawInfo
            .split(Regex("""\n\s*\n"""))
            .mapNotNull(::parseSection)

        val groupedSections = linkedMapOf<String, MutableList<InfoSection>>()
        parsedSections.forEach { section ->
            val key = categoryKeyFor(section.title)
            groupedSections.getOrPut(key) { mutableListOf() }.add(section)
        }

        val categories = buildList {
            groupedSections["runtime"]?.let { sections ->
                add(
                    InfoCategory(
                        key = "runtime",
                        label = "Runtime",
                        summary = "Versions, licenses, and build configuration",
                        sections = sections,
                        expandedByDefault = true
                    )
                )
            }
            groupedSections["protocols"]?.let { sections ->
                add(
                    InfoCategory(
                        key = "protocols",
                        label = "Protocols",
                        summary = "Supported input and output protocols",
                        sections = sections
                    )
                )
            }
            groupedSections["formats"]?.let { sections ->
                add(
                    InfoCategory(
                        key = "formats",
                        label = "Formats",
                        summary = "Available demuxers and muxers",
                        sections = sections
                    )
                )
            }
            groupedSections["decoders"]?.let { sections ->
                add(
                    InfoCategory(
                        key = "decoders",
                        label = "Decoders",
                        summary = "Video, audio, and subtitle decoder support",
                        sections = sections
                    )
                )
            }
            groupedSections["encoders"]?.let { sections ->
                add(
                    InfoCategory(
                        key = "encoders",
                        label = "Encoders",
                        summary = "Video, audio, and subtitle encoder support",
                        sections = sections
                    )
                )
            }
            groupedSections["misc"]?.let { sections ->
                add(
                    InfoCategory(
                        key = "misc",
                        label = "Misc",
                        summary = "Unclassified output",
                        sections = sections
                    )
                )
            }
            add(
                InfoCategory(
                    key = "raw",
                    label = "Raw Output",
                    summary = "Full original text for debugging and comparison",
                    sections = listOf(
                        InfoSection(
                            title = "FFmpeg Runtime Info",
                            content = rawInfo.trim()
                        )
                    )
                )
            )
        }

        return InfoUiModel(
            summaryTags = buildSummaryTags(parsedSections),
            categories = categories
        )
    }

    private fun parseSection(block: String): InfoSection? {
        val lines = block
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            return null
        }

        val firstLine = lines.first()
        if (firstLine == "FFmpeg Runtime Info" || firstLine.startsWith("===")) {
            return null
        }

        val contentLines = lines.drop(1).map { it.removePrefix("  ") }
        val itemCount = contentLines.lastOrNull()
            ?.takeIf { it.startsWith("count=") }
            ?.substringAfter("=")
            ?.toIntOrNull()

        return InfoSection(
            title = firstLine.removeSuffix(":"),
            content = contentLines.joinToString("\n").trim(),
            itemCount = itemCount
        )
    }

    private fun categoryKeyFor(title: String): String = when {
        title.startsWith("libav") || title == "configuration" -> "runtime"
        title.contains("protocol", ignoreCase = true) -> "protocols"
        title == "demuxers" || title == "muxers" -> "formats"
        title.startsWith("decoders", ignoreCase = true) -> "decoders"
        title.startsWith("encoders", ignoreCase = true) -> "encoders"
        else -> "misc"
    }

    private fun buildSummaryTags(sections: List<InfoSection>): List<String> {
        val libavutilVersion = sections.firstOrNull { it.title == "libavutil" }
            ?.content
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("version:") }
            ?.substringAfter(":")
            ?.trim()

        val protocolSummary = buildCountSummary(
            prefix = "Protocols",
            first = sections.firstOrNull { it.title == "input protocols" }?.itemCount,
            second = sections.firstOrNull { it.title == "output protocols" }?.itemCount,
            firstLabel = "In",
            secondLabel = "Out"
        )

        val formatSummary = buildCountSummary(
            prefix = "Formats",
            first = sections.firstOrNull { it.title == "demuxers" }?.itemCount,
            second = sections.firstOrNull { it.title == "muxers" }?.itemCount,
            firstLabel = "Demux",
            secondLabel = "Mux"
        )

        val decoderSummary = buildCountSummary(
            prefix = "Decoders",
            first = sections.firstOrNull { it.title == "decoders (video)" }?.itemCount,
            second = sections.firstOrNull { it.title == "decoders (audio)" }?.itemCount,
            firstLabel = "Video",
            secondLabel = "Audio"
        )

        val encoderSummary = buildCountSummary(
            prefix = "Encoders",
            first = sections.firstOrNull { it.title == "encoders (video)" }?.itemCount,
            second = sections.firstOrNull { it.title == "encoders (audio)" }?.itemCount,
            firstLabel = "Video",
            secondLabel = "Audio"
        )

        return buildList {
            add(libavutilVersion?.let { "FFmpeg $it" } ?: "FFmpeg Runtime")
            protocolSummary?.let(::add)
            formatSummary?.let(::add)
            decoderSummary?.let(::add)
            encoderSummary?.let(::add)
            add("${sections.size} parsed sections")
        }
    }

    private fun buildCountSummary(
        prefix: String,
        first: Int?,
        second: Int?,
        firstLabel: String,
        secondLabel: String
    ): String? {
        if (first == null && second == null) {
            return null
        }
        return "$prefix $firstLabel:${first ?: "-"} / $secondLabel:${second ?: "-"}"
    }

    private fun renderSummaryTags(tags: List<String>) {
        binding.summaryChipGroup.removeAllViews()
        tags.forEach { label ->
            binding.summaryChipGroup.addView(
                Chip(this).apply {
                    text = label
                    isClickable = false
                    isCheckable = false
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(0xFFEEF4FF.toInt())
                    setTextColor(0xFF355070.toInt())
                    chipStrokeWidth = dp(1).toFloat()
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(0xFFD7E3F8.toInt())
                }
            )
        }
    }

    private fun renderCategories(categories: List<InfoCategory>) {
        binding.sectionContainer.removeAllViews()
        categories.forEach { category ->
            binding.sectionContainer.addView(createCategoryCard(category))
        }
    }

    private fun createCategoryCard(category: InfoCategory): View {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = 0xFFD9E2F2.toInt()
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            isClickable = true
            isFocusable = true
            foreground = resolveSelectableItemBackground()
        }

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val labelView = TextView(this).apply {
            text = category.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF355070.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(0xFFEEF4FF.toInt())
        }

        val titleView = TextView(this).apply {
            text = prettifyCategoryTitle(category)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, dp(12), 0, 0)
        }

        val summaryView = TextView(this).apply {
            text = category.summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(6), 0, 0)
        }

        val toggleView = TextView(this).apply {
            text = if (category.expandedByDefault) "v" else ">"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(0xFF355070.toInt())
            setPadding(dp(12), 0, 0, 0)
        }

        val divider = View(this).apply {
            setBackgroundColor(0xFFE8EEF7.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            isVisible = category.expandedByDefault
        }

        category.sections.forEachIndexed { index, section ->
            body.addView(createSectionBlock(section, category.key == "raw"))
            if (index != category.sections.lastIndex) {
                body.addView(
                    View(this).apply {
                        setBackgroundColor(0xFFF1F5F9.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1)
                        ).apply {
                            topMargin = dp(14)
                            bottomMargin = dp(14)
                        }
                    }
                )
            }
        }

        infoColumn.addView(labelView)
        infoColumn.addView(titleView)
        infoColumn.addView(summaryView)
        header.addView(infoColumn)
        header.addView(toggleView)
        root.addView(header)
        root.addView(divider)
        root.addView(body)
        card.addView(root)

        header.setOnClickListener {
            TransitionManager.beginDelayedTransition(card, AutoTransition())
            body.isVisible = !body.isVisible
            toggleView.text = if (body.isVisible) "v" else ">"
        }

        return card
    }

    private fun createSectionBlock(section: InfoSection, selectable: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = prettifySectionTitle(section.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleRow.addView(titleView)

        section.itemCount?.let { count ->
            titleRow.addView(
                TextView(this).apply {
                    text = "$count items"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(0xFF475569.toInt())
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setBackgroundColor(0xFFF1F5F9.toInt())
                }
            )
        }

        val contentView = TextView(this).apply {
            text = section.content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(0xFF334155.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFF8FAFC.toInt())
            setTextIsSelectable(selectable)
        }

        container.addView(titleRow)
        container.addView(
            contentView.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                }
            }
        )
        return container
    }

    private fun prettifyCategoryTitle(category: InfoCategory): String {
        val totalItems = category.sections.mapNotNull { it.itemCount }.sum()
        return if (totalItems > 0) {
            "${category.label} - ${category.sections.size} groups / $totalItems items"
        } else {
            "${category.label} - ${category.sections.size} groups"
        }
    }

    private fun prettifySectionTitle(title: String): String = when (title) {
        "libavutil" -> "libavutil"
        "libavcodec" -> "libavcodec"
        "libavformat" -> "libavformat"
        "configuration" -> "Build Configuration"
        "input protocols" -> "Input Protocols"
        "output protocols" -> "Output Protocols"
        "demuxers" -> "Demuxers"
        "muxers" -> "Muxers"
        "decoders (video)" -> "Video Decoders"
        "decoders (audio)" -> "Audio Decoders"
        "decoders (subtitle)" -> "Subtitle Decoders"
        "encoders (video)" -> "Video Encoders"
        "encoders (audio)" -> "Audio Encoders"
        "encoders (subtitle)" -> "Subtitle Encoders"
        else -> title
    }

    private fun resolveSelectableItemBackground() = TypedValue().let { outValue ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        getDrawable(outValue.resourceId)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
