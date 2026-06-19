package com.lovelymaple.ffmpegavtutorial

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityDeviceCodecInfoBinding

class DeviceCodecInfoActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "DeviceCodecInfo"
    }

    private lateinit var binding: ActivityDeviceCodecInfoBinding

    private data class CodecEntry(
        val name: String,
        val isEncoder: Boolean,
        val supportedTypes: List<String>,
        val supportedDetails: List<String>,
        val hardwareLabel: String
    )

    private data class CodecSection(
        val key: String,
        val title: String,
        val summary: String,
        val entries: List<CodecEntry>,
        val expandedByDefault: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceCodecInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        binding.backButton.setOnClickListener {
            finish()
        }

        renderCodecInfo()
    }

    private fun renderCodecInfo() {
        val codecs = queryDeviceCodecs()
        val sections = buildSections(codecs)

        logCodecInfo(codecs, sections)
        renderSummaryTags(codecs, sections)
        binding.codecSectionContainer.removeAllViews()
        sections.forEach { section ->
            binding.codecSectionContainer.addView(createSectionCard(section))
        }
    }

    private fun queryDeviceCodecs(): List<CodecEntry> {
        return MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .map { codecInfo ->
                CodecEntry(
                    name = codecInfo.name,
                    isEncoder = codecInfo.isEncoder,
                    supportedTypes = codecInfo.supportedTypes
                        .map { it.lowercase() }
                        .sorted(),
                    supportedDetails = codecInfo.supportedTypes
                        .map { buildTypeDetail(codecInfo, it) }
                        .sorted(),
                    hardwareLabel = hardwareLabelFor(codecInfo)
                )
            }
            .sortedWith(
                compareBy<CodecEntry> { if (it.supportedTypes.any(::isVideoType)) 0 else 1 }
                    .thenBy { if (it.isEncoder) 1 else 0 }
                    .thenBy { hardwareSortOrder(it.hardwareLabel) }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun buildSections(codecs: List<CodecEntry>): List<CodecSection> {
        val videoDecoders = codecs.filter { !it.isEncoder && it.supportedTypes.any(::isVideoType) }
        val videoEncoders = codecs.filter { it.isEncoder && it.supportedTypes.any(::isVideoType) }
        val audioDecoders = codecs.filter { !it.isEncoder && it.supportedTypes.any(::isAudioType) }
        val audioEncoders = codecs.filter { it.isEncoder && it.supportedTypes.any(::isAudioType) }
        val others = codecs.filterNot { codec ->
            codec.supportedTypes.any(::isVideoType) || codec.supportedTypes.any(::isAudioType)
        }

        return buildList {
            add(
                CodecSection(
                    key = "video_decoders",
                    title = "Video Decoders",
                    summary = "Video playback codecs available on this device",
                    entries = videoDecoders,
                    expandedByDefault = true
                )
            )
            add(
                CodecSection(
                    key = "video_encoders",
                    title = "Video Encoders",
                    summary = "Video recording and transcoding codecs",
                    entries = videoEncoders
                )
            )
            add(
                CodecSection(
                    key = "audio_decoders",
                    title = "Audio Decoders",
                    summary = "Audio playback codecs available on this device",
                    entries = audioDecoders
                )
            )
            add(
                CodecSection(
                    key = "audio_encoders",
                    title = "Audio Encoders",
                    summary = "Audio recording and transcoding codecs",
                    entries = audioEncoders
                )
            )
            if (others.isNotEmpty()) {
                add(
                    CodecSection(
                        key = "other",
                        title = "Other Codecs",
                        summary = "Non audio/video codec entries reported by the platform",
                        entries = others
                    )
                )
            }
        }
    }

    private fun hardwareSortOrder(hardwareLabel: String): Int {
        return when (hardwareLabel) {
            "Hardware" -> 0
            "Vendor" -> 1
            "Platform" -> 2
            "Software" -> 3
            else -> 4
        }
    }

    private fun buildTypeDetail(codecInfo: MediaCodecInfo, type: String): String {
        val normalizedType = type.lowercase()
        val capabilities = runCatching {
            codecInfo.getCapabilitiesForType(type)
        }.getOrNull() ?: return normalizedType

        return buildString {
            append(normalizedType)

            if (capabilities.profileLevels.isNotEmpty()) {
                appendLine()
                append("  profiles: ")
                append(
                    capabilities.profileLevels.joinToString { profileLevel ->
                        formatProfileLevel(normalizedType, profileLevel)
                    }
                )
            }

            capabilities.videoCapabilities?.let { videoCapabilities ->
                appendLine()
                append("  width: ${videoCapabilities.supportedWidths}")
                appendLine()
                append("  height: ${videoCapabilities.supportedHeights}")
                appendLine()
                append("  frame rates: ${videoCapabilities.supportedFrameRates}")
                appendLine()
                append("  bitrate: ${videoCapabilities.bitrateRange}")
                appendLine()
                append("  alignment: ${videoCapabilities.widthAlignment}x${videoCapabilities.heightAlignment}")
            }

            capabilities.audioCapabilities?.let { audioCapabilities ->
                appendLine()
                append("  sample rates: ${audioCapabilities.supportedSampleRateRanges.joinToString()}")
                appendLine()
                append("  max channels: ${audioCapabilities.maxInputChannelCount}")
                appendLine()
                append("  bitrate: ${audioCapabilities.bitrateRange}")
            }

            if (capabilities.colorFormats.isNotEmpty()) {
                appendLine()
                append(
                    "  color formats: ${
                        capabilities.colorFormats.joinToString { colorFormat ->
                            "${colorFormatName(colorFormat)}($colorFormat)"
                        }
                    }"
                )
            }
        }
    }

    private fun formatProfileLevel(type: String, profileLevel: MediaCodecInfo.CodecProfileLevel): String {
        val profileName = profileName(type, profileLevel.profile)
        val levelName = levelName(type, profileLevel.level)
        return "$profileName(${profileLevel.profile})/$levelName(${profileLevel.level})"
    }

    private fun profileName(type: String, profile: Int): String {
        return when (type) {
            "video/avc" -> avcProfileName(profile)
            "video/hevc" -> hevcProfileName(profile)
            "video/x-vnd.on2.vp9" -> vp9ProfileName(profile)
            "video/av01" -> av1ProfileName(profile)
            "audio/mp4a-latm" -> aacProfileName(profile)
            else -> "Profile"
        }
    }

    private fun levelName(type: String, level: Int): String {
        return when (type) {
            "video/avc" -> avcLevelName(level)
            "video/hevc" -> hevcLevelName(level)
            "video/x-vnd.on2.vp9" -> vp9LevelName(level)
            "video/av01" -> av1LevelName(level)
            else -> "Level"
        }
    }

    private fun avcProfileName(profile: Int): String {
        return when (profile) {
            1 -> "AVCProfileBaseline"
            2 -> "AVCProfileMain"
            4 -> "AVCProfileExtended"
            8 -> "AVCProfileHigh"
            16 -> "AVCProfileHigh10"
            32 -> "AVCProfileHigh422"
            64 -> "AVCProfileHigh444"
            65536 -> "AVCProfileConstrainedBaseline"
            524288 -> "AVCProfileConstrainedHigh"
            else -> "AVCProfileUnknown"
        }
    }

    private fun avcLevelName(level: Int): String {
        return when (level) {
            1 -> "AVCLevel1"
            2 -> "AVCLevel1b"
            4 -> "AVCLevel11"
            8 -> "AVCLevel12"
            16 -> "AVCLevel13"
            32 -> "AVCLevel2"
            64 -> "AVCLevel21"
            128 -> "AVCLevel22"
            256 -> "AVCLevel3"
            512 -> "AVCLevel31"
            1024 -> "AVCLevel32"
            2048 -> "AVCLevel4"
            4096 -> "AVCLevel41"
            8192 -> "AVCLevel42"
            16384 -> "AVCLevel5"
            32768 -> "AVCLevel51"
            65536 -> "AVCLevel52"
            131072 -> "AVCLevel6"
            262144 -> "AVCLevel61"
            524288 -> "AVCLevel62"
            else -> "AVCLevelUnknown"
        }
    }

    private fun hevcProfileName(profile: Int): String {
        return when (profile) {
            1 -> "HEVCProfileMain"
            2 -> "HEVCProfileMain10"
            4 -> "HEVCProfileMainStill"
            4096 -> "HEVCProfileMain10HDR10"
            8192 -> "HEVCProfileMain10HDR10Plus"
            else -> "HEVCProfileUnknown"
        }
    }

    private fun hevcLevelName(level: Int): String {
        return when (level) {
            1 -> "HEVCMainTierLevel1"
            2 -> "HEVCHighTierLevel1"
            4 -> "HEVCMainTierLevel2"
            8 -> "HEVCHighTierLevel2"
            16 -> "HEVCMainTierLevel21"
            32 -> "HEVCHighTierLevel21"
            64 -> "HEVCMainTierLevel3"
            128 -> "HEVCHighTierLevel3"
            256 -> "HEVCMainTierLevel31"
            512 -> "HEVCHighTierLevel31"
            1024 -> "HEVCMainTierLevel4"
            2048 -> "HEVCHighTierLevel4"
            4096 -> "HEVCMainTierLevel41"
            8192 -> "HEVCHighTierLevel41"
            16384 -> "HEVCMainTierLevel5"
            32768 -> "HEVCHighTierLevel5"
            65536 -> "HEVCMainTierLevel51"
            131072 -> "HEVCHighTierLevel51"
            262144 -> "HEVCMainTierLevel52"
            524288 -> "HEVCHighTierLevel52"
            1048576 -> "HEVCMainTierLevel6"
            2097152 -> "HEVCHighTierLevel6"
            4194304 -> "HEVCMainTierLevel61"
            8388608 -> "HEVCHighTierLevel61"
            16777216 -> "HEVCMainTierLevel62"
            33554432 -> "HEVCHighTierLevel62"
            else -> "HEVCLevelUnknown"
        }
    }

    private fun vp9ProfileName(profile: Int): String {
        return when (profile) {
            1 -> "VP9Profile0"
            2 -> "VP9Profile1"
            4 -> "VP9Profile2"
            8 -> "VP9Profile3"
            4096 -> "VP9Profile2HDR"
            8192 -> "VP9Profile3HDR"
            16384 -> "VP9Profile2HDR10Plus"
            32768 -> "VP9Profile3HDR10Plus"
            else -> "VP9ProfileUnknown"
        }
    }

    private fun vp9LevelName(level: Int): String {
        return when (level) {
            1 -> "VP9Level1"
            2 -> "VP9Level11"
            4 -> "VP9Level2"
            8 -> "VP9Level21"
            16 -> "VP9Level3"
            32 -> "VP9Level31"
            64 -> "VP9Level4"
            128 -> "VP9Level41"
            256 -> "VP9Level5"
            512 -> "VP9Level51"
            1024 -> "VP9Level52"
            2048 -> "VP9Level6"
            4096 -> "VP9Level61"
            8192 -> "VP9Level62"
            else -> "VP9LevelUnknown"
        }
    }

    private fun av1ProfileName(profile: Int): String {
        return when (profile) {
            1 -> "AV1ProfileMain8"
            2 -> "AV1ProfileMain10"
            4096 -> "AV1ProfileMain10HDR10"
            8192 -> "AV1ProfileMain10HDR10Plus"
            else -> "AV1ProfileUnknown"
        }
    }

    private fun av1LevelName(level: Int): String {
        return when (level) {
            1 -> "AV1Level2"
            2 -> "AV1Level21"
            4 -> "AV1Level22"
            8 -> "AV1Level23"
            16 -> "AV1Level3"
            32 -> "AV1Level31"
            64 -> "AV1Level32"
            128 -> "AV1Level33"
            256 -> "AV1Level4"
            512 -> "AV1Level41"
            1024 -> "AV1Level42"
            2048 -> "AV1Level43"
            4096 -> "AV1Level5"
            8192 -> "AV1Level51"
            16384 -> "AV1Level52"
            32768 -> "AV1Level53"
            65536 -> "AV1Level6"
            131072 -> "AV1Level61"
            262144 -> "AV1Level62"
            524288 -> "AV1Level63"
            1048576 -> "AV1Level7"
            2097152 -> "AV1Level71"
            4194304 -> "AV1Level72"
            8388608 -> "AV1Level73"
            else -> "AV1LevelUnknown"
        }
    }

    private fun aacProfileName(profile: Int): String {
        return when (profile) {
            1 -> "AACObjectMain"
            2 -> "AACObjectLC"
            3 -> "AACObjectSSR"
            4 -> "AACObjectLTP"
            5 -> "AACObjectHE"
            6 -> "AACObjectScalable"
            17 -> "AACObjectERLC"
            20 -> "AACObjectERScalable"
            23 -> "AACObjectLD"
            29 -> "AACObjectHE_PS"
            39 -> "AACObjectELD"
            42 -> "AACObjectXHE"
            else -> "AACObjectUnknown"
        }
    }

    private fun colorFormatName(colorFormat: Int): String {
        return when (colorFormat) {
            1 -> "COLOR_FormatMonochrome"
            2 -> "COLOR_Format8bitRGB332"
            3 -> "COLOR_Format12bitRGB444"
            4 -> "COLOR_Format16bitARGB4444"
            5 -> "COLOR_Format16bitARGB1555"
            6 -> "COLOR_Format16bitRGB565"
            7 -> "COLOR_Format16bitBGR565"
            8 -> "COLOR_Format18bitRGB666"
            9 -> "COLOR_Format18bitARGB1665"
            10 -> "COLOR_Format19bitARGB1666"
            11 -> "COLOR_Format24bitRGB888"
            12 -> "COLOR_Format24bitBGR888"
            13 -> "COLOR_Format24bitARGB1887"
            14 -> "COLOR_Format25bitARGB1888"
            15 -> "COLOR_Format32bitBGRA8888"
            16 -> "COLOR_Format32bitARGB8888"
            17 -> "COLOR_FormatYUV411Planar"
            18 -> "COLOR_FormatYUV411PackedPlanar"
            19 -> "COLOR_FormatYUV420Planar"
            20 -> "COLOR_FormatYUV420PackedPlanar"
            21 -> "COLOR_FormatYUV420SemiPlanar"
            22 -> "COLOR_FormatYUV422Planar"
            23 -> "COLOR_FormatYUV422PackedPlanar"
            24 -> "COLOR_FormatYUV422SemiPlanar"
            25 -> "COLOR_FormatYCbYCr"
            26 -> "COLOR_FormatYCrYCb"
            27 -> "COLOR_FormatCbYCrY"
            28 -> "COLOR_FormatCrYCbY"
            29 -> "COLOR_FormatYUV444Interleaved"
            30 -> "COLOR_FormatRawBayer8bit"
            31 -> "COLOR_FormatRawBayer10bit"
            32 -> "COLOR_FormatRawBayer8bitcompressed"
            33 -> "COLOR_FormatL2"
            34 -> "COLOR_FormatL4"
            35 -> "COLOR_FormatL8"
            36 -> "COLOR_FormatL16"
            37 -> "COLOR_FormatL24"
            38 -> "COLOR_FormatL32"
            39 -> "COLOR_FormatYUV420PackedSemiPlanar"
            40 -> "COLOR_FormatYUV422PackedSemiPlanar"
            41 -> "COLOR_Format18BitBGR666"
            42 -> "COLOR_Format24BitARGB6666"
            43 -> "COLOR_Format24BitABGR6666"
            54 -> "COLOR_FormatYUVP010"
            2130706433 -> "COLOR_FormatAndroidOpaque"
            2130706688 -> "COLOR_Format32bitABGR8888"
            2130708361 -> "COLOR_FormatSurface"
            2135033992 -> "COLOR_FormatYUV420Flexible"
            2135181442 -> "COLOR_FormatYUV422Flexible"
            2135181443 -> "COLOR_FormatYUV444Flexible"
            2135033993 -> "COLOR_FormatYUVP010"
            else -> "COLOR_FormatUnknown"
        }
    }

    private fun hardwareLabelFor(codecInfo: MediaCodecInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "Platform"
        }

        return when {
            codecInfo.isHardwareAccelerated -> "Hardware"
            codecInfo.isSoftwareOnly -> "Software"
            codecInfo.isVendor -> "Vendor"
            else -> "Platform"
        }
    }

    private fun renderSummaryTags(codecs: List<CodecEntry>, sections: List<CodecSection>) {
        binding.summaryChipGroup.removeAllViews()

        val hardwareCount = codecs.count { it.hardwareLabel == "Hardware" }
        val softwareCount = codecs.count { it.hardwareLabel == "Software" }
        val tags = listOf(
            "Total ${codecs.size}",
            "Encoders ${codecs.count { it.isEncoder }}",
            "Decoders ${codecs.count { !it.isEncoder }}",
            "Video ${sections.first { it.key == "video_decoders" }.entries.size + sections.first { it.key == "video_encoders" }.entries.size}",
            "Audio ${sections.first { it.key == "audio_decoders" }.entries.size + sections.first { it.key == "audio_encoders" }.entries.size}",
            "HW $hardwareCount",
            "SW $softwareCount"
        )

        tags.forEach { label ->
            binding.summaryChipGroup.addView(createChip(label))
        }
    }

    private fun logCodecInfo(codecs: List<CodecEntry>, sections: List<CodecSection>) {
        val hardwareCount = codecs.count { it.hardwareLabel == "Hardware" }
        val softwareCount = codecs.count { it.hardwareLabel == "Software" }

        Log.i(
            TAG,
            "Device codec capability scan: total=${codecs.size}, " +
                "encoders=${codecs.count { it.isEncoder }}, " +
                "decoders=${codecs.count { !it.isEncoder }}, " +
                "hardware=$hardwareCount, software=$softwareCount"
        )

        sections.forEach { section ->
            Log.i(TAG, "===== ${section.title} (${section.entries.size}) =====")
            if (section.entries.isEmpty()) {
                Log.i(TAG, "No codec reported by MediaCodecList.")
            } else {
                section.entries.forEach { entry ->
                    Log.i(TAG, formatCodecEntryForLog(entry))
                }
            }
        }
    }

    private fun formatCodecEntryForLog(entry: CodecEntry): String {
        return buildString {
            appendLine(entry.name)
            appendLine("  role: ${if (entry.isEncoder) "Encoder" else "Decoder"}")
            appendLine("  type: ${entry.hardwareLabel}")
            appendLine("  supported mime types:")
            entry.supportedDetails.forEach { detail ->
                detail.lines().forEach { line ->
                    appendLine("    $line")
                }
            }
        }.trimEnd()
    }

    private fun createSectionCard(section: CodecSection): View {
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
            text = section.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF355070.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(0xFFEEF4FF.toInt())
        }

        val titleView = TextView(this).apply {
            text = "${section.title} - ${section.entries.size} items"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, dp(12), 0, 0)
        }

        val summaryView = TextView(this).apply {
            text = section.summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(6), 0, 0)
        }

        val toggleView = TextView(this).apply {
            text = if (section.expandedByDefault) "v" else ">"
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
            isVisible = section.expandedByDefault
        }

        if (section.entries.isEmpty()) {
            body.addView(createEmptyText())
        } else {
            section.entries.forEachIndexed { index, entry ->
                body.addView(createCodecBlock(entry))
                if (index != section.entries.lastIndex) {
                    body.addView(createThinDivider())
                }
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

    private fun createCodecBlock(entry: CodecEntry): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            foreground = resolveSelectableItemBackground()
        }

        header.addView(
            TextView(this).apply {
                text = entry.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )

        header.addView(createSmallLabel(if (entry.isEncoder) "Encoder" else "Decoder"))
        header.addView(createSmallLabel(entry.hardwareLabel))

        val toggleView = TextView(this).apply {
            text = ">"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFF355070.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(toggleView)

        val typesText = TextView(this).apply {
            text = entry.supportedDetails.joinToString(separator = "\n\n")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(0xFF334155.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFF8FAFC.toInt())
            setTextIsSelectable(true)
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }

        header.setOnClickListener {
            TransitionManager.beginDelayedTransition(container, AutoTransition())
            typesText.isVisible = !typesText.isVisible
            toggleView.text = if (typesText.isVisible) "v" else ">"
        }

        container.addView(header)
        container.addView(typesText)
        return container
    }

    private fun createSmallLabel(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF475569.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xFFF1F5F9.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
        }
    }

    private fun createChip(label: String): Chip {
        return Chip(this).apply {
            text = label
            isClickable = false
            isCheckable = false
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(0xFFEEF4FF.toInt())
            setTextColor(0xFF355070.toInt())
            chipStrokeWidth = dp(1).toFloat()
            chipStrokeColor = android.content.res.ColorStateList.valueOf(0xFFD7E3F8.toInt())
        }
    }

    private fun createEmptyText(): TextView {
        return TextView(this).apply {
            text = "No codec reported by MediaCodecList."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFF8FAFC.toInt())
        }
    }

    private fun createThinDivider(): View {
        return View(this).apply {
            setBackgroundColor(0xFFF1F5F9.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        }
    }

    private fun resolveSelectableItemBackground() = TypedValue().let { outValue ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        getDrawable(outValue.resourceId)
    }

    private fun isVideoType(type: String): Boolean = type.startsWith("video/")

    private fun isAudioType(type: String): Boolean = type.startsWith("audio/")

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
