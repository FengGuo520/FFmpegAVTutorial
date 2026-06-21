package com.lovelymaple.ffmpegavtutorial.basic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityH264StreamAnalyzerBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class H265StreamAnalyzerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOCAL_FILE_PATH = "extra_local_file_path"

        fun createIntent(context: Context, localFilePath: String): Intent {
            return Intent(context, H265StreamAnalyzerActivity::class.java)
                .putExtra(EXTRA_LOCAL_FILE_PATH, localFilePath)
        }
    }

    private data class SelectedFile(
        val uri: Uri,
        val displayName: String,
        val localFile: File
    )

    private data class AnalyzerSection(
        val title: String,
        val content: String
    )

    private data class NalUnit(
        val index: Int,
        val offset: Int,
        val size: Int,
        val type: Int,
        val layerId: Int,
        val temporalId: Int,
        val bytes: ByteArray
    )

    private data class ProfileTierLevelInfo(
        val profileSpace: Int,
        val tierFlag: Boolean,
        val profileIdc: Int,
        val compatibilityFlags: Long,
        val progressiveSource: Boolean,
        val interlacedSource: Boolean,
        val nonPackedConstraint: Boolean,
        val frameOnlyConstraint: Boolean,
        val levelIdc: Int
    )

    private data class VpsInfo(
        val id: Int,
        val maxLayers: Int,
        val maxSubLayers: Int,
        val temporalIdNesting: Boolean,
        val profileTierLevel: ProfileTierLevelInfo
    )

    private data class SpsInfo(
        val vpsId: Int,
        val id: Int,
        val maxSubLayers: Int,
        val chromaFormatIdc: Int,
        val separateColourPlane: Boolean,
        val codedWidth: Int,
        val codedHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val profileTierLevel: ProfileTierLevelInfo
    )

    private data class PpsInfo(
        val id: Int,
        val spsId: Int,
        val dependentSliceSegmentsEnabled: Boolean,
        val outputFlagPresent: Boolean,
        val numExtraSliceHeaderBits: Int
    )

    private lateinit var binding: ActivityH264StreamAnalyzerBinding

    @Volatile
    private var isWorking = false

    private val collapsedSections = mutableMapOf<String, Boolean>()
    private var selectedFile: SelectedFile? = null

    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityH264StreamAnalyzerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        binding.pageTitleText.text = getString(R.string.h265_analyzer_page_title)
        binding.badgeText.text = getString(R.string.h265_analyzer_badge)
        binding.headlineText.text = getString(R.string.h265_analyzer_headline)
        binding.subtitleText.text = getString(R.string.h265_analyzer_description)
        binding.selectFileButton.text = getString(R.string.h265_analyzer_select_file)

        binding.backButton.setOnClickListener { finish() }
        binding.selectFileButton.setOnClickListener { pickFile() }
        binding.analyzeButton.setOnClickListener { analyzeSelectedFile() }

        renderSelectedFile()
        renderState()
        updateStatus(getString(R.string.h265_analyzer_status_idle))
        handleIncomingLocalFile()
    }

    private fun pickFile() {
        if (isWorking) return
        pickerLauncher.launch(arrayOf("*/*"))
    }

    private fun importFile(uri: Uri) {
        isWorking = true
        renderState()
        updateStatus(getString(R.string.h265_analyzer_status_importing))

        thread(start = true, name = "H265AnalyzerImport") {
            val result = runCatching {
                val displayName = queryDisplayName(uri) ?: fallbackName()
                val localFile = copyUriToSandbox(uri, displayName)
                SelectedFile(uri = uri, displayName = displayName, localFile = localFile)
            }

            postUi {
                isWorking = false
                result.fold(
                    onSuccess = { file ->
                        selectedFile = file
                        binding.sectionContainer.removeAllViews()
                        renderSelectedFile()
                        renderState()
                        updateStatus(getString(R.string.h265_analyzer_status_ready, file.displayName))
                    },
                    onFailure = { throwable ->
                        renderState()
                        updateStatus(
                            getString(
                                R.string.h265_analyzer_status_error,
                                throwable.message ?: throwable.javaClass.simpleName
                            )
                        )
                    }
                )
            }
        }
    }

    private fun analyzeSelectedFile() {
        val file = selectedFile ?: run {
            updateStatus(getString(R.string.h265_analyzer_status_select_file_first))
            return
        }
        if (isWorking) return

        isWorking = true
        binding.sectionContainer.removeAllViews()
        renderState()
        updateStatus(getString(R.string.h265_analyzer_status_analyzing, file.displayName))

        thread(start = true, name = "H265AnalyzerParser") {
            val result = runCatching {
                analyzeH265Stream(file.localFile)
            }

            postUi {
                isWorking = false
                renderState()
                result.fold(
                    onSuccess = { raw ->
                        renderAnalysis(raw)
                        updateStatus(getString(R.string.h265_analyzer_status_done, file.displayName))
                    },
                    onFailure = { throwable ->
                        showSingleSection("Error", throwable.message ?: throwable.javaClass.simpleName)
                        updateStatus(
                            getString(
                                R.string.h265_analyzer_status_error,
                                throwable.message ?: throwable.javaClass.simpleName
                            )
                        )
                    }
                )
            }
        }
    }

    private fun analyzeH265Stream(file: File): String {
        val bytes = file.readBytes()
        val nalUnits = findAnnexBNalUnits(bytes)
        require(nalUnits.isNotEmpty()) {
            "No HEVC Annex B start codes were found. Please select a raw .h265 elementary stream."
        }

        val vpsInfos = nalUnits.filter { it.type == 32 }.mapNotNull { parseVps(it) }
        val spsInfos = nalUnits.filter { it.type == 33 }.mapNotNull { parseSps(it) }
        val ppsInfos = nalUnits.filter { it.type == 34 }.mapNotNull { parsePps(it) }
        val typeCounts = nalUnits.groupingBy { it.type }.eachCount().toSortedMap()
        val firstSps = spsInfos.firstOrNull()
        val ptl = firstSps?.profileTierLevel ?: vpsInfos.firstOrNull()?.profileTierLevel
        val irapCount = nalUnits.count { it.type in 16..23 }
        val vclCount = nalUnits.count { it.type in 0..31 }

        return buildString {
            appendLine("H265 Stream Analysis")
            appendLine()
            appendLine("summary")
            appendLine("file_size_bytes: ${file.length()}")
            appendLine("format: Annex B elementary stream")
            appendLine("codec: HEVC/H.265")
            firstSps?.let {
                appendLine("width: ${it.displayWidth}")
                appendLine("height: ${it.displayHeight}")
                appendLine("coded_width: ${it.codedWidth}")
                appendLine("coded_height: ${it.codedHeight}")
                appendLine("chroma_format_idc: ${it.chromaFormatIdc}")
                appendLine("bit_depth_luma: ${it.bitDepthLuma}")
                appendLine("bit_depth_chroma: ${it.bitDepthChroma}")
            }
            ptl?.let {
                appendLine("profile: ${profileName(it.profileIdc, it.compatibilityFlags)}")
                appendLine("tier: ${if (it.tierFlag) "High" else "Main"}")
                appendLine("level: ${levelName(it.levelIdc)}")
            }
            appendLine("nal_unit_count: ${nalUnits.size}")
            appendLine("vps_count: ${typeCounts[32] ?: 0}")
            appendLine("sps_count: ${typeCounts[33] ?: 0}")
            appendLine("pps_count: ${typeCounts[34] ?: 0}")
            appendLine("vcl_count: $vclCount")
            appendLine("irap_count: $irapCount")
            appendLine("access_unit_estimate: ${estimateAccessUnits(nalUnits)}")

            appendLine()
            appendLine("parameter_sets")
            appendLine(formatVpsInfos(vpsInfos))
            appendLine(formatSpsInfos(spsInfos))
            appendLine(formatPpsInfos(ppsInfos))

            appendLine()
            appendLine("nal_type_counts")
            typeCounts.forEach { (type, count) ->
                appendLine("type $type (${nalTypeName(type)}): $count")
            }

            appendLine()
            appendLine("nal_units")
            nalUnits.forEach { nal ->
                appendLine(
                    "#${nal.index} offset=${nal.offset} size=${nal.size} " +
                        "type=${nal.type} (${nalTypeName(nal.type)}) " +
                        "layer_id=${nal.layerId} temporal_id=${nal.temporalId}"
                )
            }
        }.trim()
    }

    private fun findAnnexBNalUnits(bytes: ByteArray): List<NalUnit> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index <= bytes.size - 3) {
            val codeLength = startCodeLengthAt(bytes, index)
            if (codeLength > 0) {
                starts += index to codeLength
                index += codeLength
            } else {
                index++
            }
        }

        return starts.mapIndexedNotNull { nalIndex, (startOffset, codeLength) ->
            val payloadStart = startOffset + codeLength
            val nextStart = starts.getOrNull(nalIndex + 1)?.first ?: bytes.size
            val payloadEnd = trimTrailingZeros(bytes, payloadStart, nextStart)
            if (payloadEnd - payloadStart < 2) {
                return@mapIndexedNotNull null
            }
            val nalBytes = bytes.copyOfRange(payloadStart, payloadEnd)
            val first = nalBytes[0].toInt() and 0xFF
            val second = nalBytes[1].toInt() and 0xFF
            NalUnit(
                index = nalIndex,
                offset = payloadStart,
                size = nalBytes.size,
                type = (first and 0x7E) ushr 1,
                layerId = ((first and 0x01) shl 5) or ((second and 0xF8) ushr 3),
                temporalId = (second and 0x07) - 1,
                bytes = nalBytes
            )
        }
    }

    private fun startCodeLengthAt(bytes: ByteArray, index: Int): Int {
        if (index + 3 <= bytes.size &&
            bytes[index] == 0.toByte() &&
            bytes[index + 1] == 0.toByte() &&
            bytes[index + 2] == 1.toByte()
        ) {
            return 3
        }
        if (index + 4 <= bytes.size &&
            bytes[index] == 0.toByte() &&
            bytes[index + 1] == 0.toByte() &&
            bytes[index + 2] == 0.toByte() &&
            bytes[index + 3] == 1.toByte()
        ) {
            return 4
        }
        return 0
    }

    private fun trimTrailingZeros(bytes: ByteArray, start: Int, end: Int): Int {
        var trimmedEnd = end
        while (trimmedEnd > start && bytes[trimmedEnd - 1] == 0.toByte()) {
            trimmedEnd--
        }
        return trimmedEnd
    }

    private fun parseVps(nal: NalUnit): VpsInfo? {
        return runCatching {
            val reader = BitReader(toRbsp(nal.bytes))
            val id = reader.readBits(4).toInt()
            reader.readBit()
            reader.readBit()
            val maxLayers = reader.readBits(6).toInt() + 1
            val maxSubLayersMinus1 = reader.readBits(3).toInt()
            val temporalIdNesting = reader.readBit()
            reader.readBits(16)
            val ptl = readProfileTierLevel(reader, maxSubLayersMinus1)
            VpsInfo(id, maxLayers, maxSubLayersMinus1 + 1, temporalIdNesting, ptl)
        }.getOrNull()
    }

    private fun parseSps(nal: NalUnit): SpsInfo? {
        return runCatching {
            val reader = BitReader(toRbsp(nal.bytes))
            val vpsId = reader.readBits(4).toInt()
            val maxSubLayersMinus1 = reader.readBits(3).toInt()
            reader.readBit()
            val ptl = readProfileTierLevel(reader, maxSubLayersMinus1)
            val spsId = reader.readUE()
            val chromaFormatIdc = reader.readUE()
            val separateColourPlane = chromaFormatIdc == 3 && reader.readBit()
            val codedWidth = reader.readUE()
            val codedHeight = reader.readUE()
            var confWinLeft = 0
            var confWinRight = 0
            var confWinTop = 0
            var confWinBottom = 0
            if (reader.readBit()) {
                confWinLeft = reader.readUE()
                confWinRight = reader.readUE()
                confWinTop = reader.readUE()
                confWinBottom = reader.readUE()
            }
            val bitDepthLuma = reader.readUE() + 8
            val bitDepthChroma = reader.readUE() + 8
            val subWidthC =
                if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
            val subHeightC =
                if (chromaFormatIdc == 1) 2 else 1
            val cropUnitX = if (separateColourPlane || chromaFormatIdc == 0) 1 else subWidthC
            val cropUnitY = if (separateColourPlane || chromaFormatIdc == 0) 1 else subHeightC
            val displayWidth = codedWidth - cropUnitX * (confWinLeft + confWinRight)
            val displayHeight = codedHeight - cropUnitY * (confWinTop + confWinBottom)
            SpsInfo(
                vpsId = vpsId,
                id = spsId,
                maxSubLayers = maxSubLayersMinus1 + 1,
                chromaFormatIdc = chromaFormatIdc,
                separateColourPlane = separateColourPlane,
                codedWidth = codedWidth,
                codedHeight = codedHeight,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                bitDepthLuma = bitDepthLuma,
                bitDepthChroma = bitDepthChroma,
                profileTierLevel = ptl
            )
        }.getOrNull()
    }

    private fun parsePps(nal: NalUnit): PpsInfo? {
        return runCatching {
            val reader = BitReader(toRbsp(nal.bytes))
            PpsInfo(
                id = reader.readUE(),
                spsId = reader.readUE(),
                dependentSliceSegmentsEnabled = reader.readBit(),
                outputFlagPresent = reader.readBit(),
                numExtraSliceHeaderBits = reader.readBits(3).toInt()
            )
        }.getOrNull()
    }

    private fun toRbsp(nalBytes: ByteArray): ByteArray {
        if (nalBytes.size <= 2) return ByteArray(0)
        val output = ArrayList<Byte>(nalBytes.size)
        var zeroCount = 0
        for (index in 2 until nalBytes.size) {
            val value = nalBytes[index]
            if (zeroCount >= 2 && value == 0x03.toByte()) {
                zeroCount = 0
                continue
            }
            output += value
            zeroCount = if (value == 0.toByte()) zeroCount + 1 else 0
        }
        return output.toByteArray()
    }

    private fun readProfileTierLevel(
        reader: BitReader,
        maxSubLayersMinus1: Int
    ): ProfileTierLevelInfo {
        val profileSpace = reader.readBits(2).toInt()
        val tierFlag = reader.readBit()
        val profileIdc = reader.readBits(5).toInt()
        val compatibilityFlags = reader.readBits(32)
        val progressiveSource = reader.readBit()
        val interlacedSource = reader.readBit()
        val nonPackedConstraint = reader.readBit()
        val frameOnlyConstraint = reader.readBit()
        reader.readBits(44)
        val levelIdc = reader.readBits(8).toInt()

        val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1)
        val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1)
        for (index in 0 until maxSubLayersMinus1) {
            subLayerProfilePresent[index] = reader.readBit()
            subLayerLevelPresent[index] = reader.readBit()
        }
        if (maxSubLayersMinus1 > 0) {
            for (index in maxSubLayersMinus1 until 8) {
                reader.readBits(2)
            }
        }
        for (index in 0 until maxSubLayersMinus1) {
            if (subLayerProfilePresent[index]) {
                reader.readBits(2)
                reader.readBit()
                reader.readBits(5)
                reader.readBits(32)
                reader.readBit()
                reader.readBit()
                reader.readBit()
                reader.readBit()
                reader.readBits(44)
            }
            if (subLayerLevelPresent[index]) {
                reader.readBits(8)
            }
        }

        return ProfileTierLevelInfo(
            profileSpace = profileSpace,
            tierFlag = tierFlag,
            profileIdc = profileIdc,
            compatibilityFlags = compatibilityFlags,
            progressiveSource = progressiveSource,
            interlacedSource = interlacedSource,
            nonPackedConstraint = nonPackedConstraint,
            frameOnlyConstraint = frameOnlyConstraint,
            levelIdc = levelIdc
        )
    }

    private fun estimateAccessUnits(nalUnits: List<NalUnit>): Int {
        return nalUnits.count { nal ->
            if (nal.type !in 0..31) return@count false
            runCatching {
                val reader = BitReader(toRbsp(nal.bytes))
                reader.readBit()
            }.getOrDefault(false)
        }
    }

    private fun formatVpsInfos(infos: List<VpsInfo>): String {
        if (infos.isEmpty()) return "vps: not found"
        return infos.joinToString("\n") { info ->
            "vps[${info.id}]: max_layers=${info.maxLayers}, " +
                "max_sub_layers=${info.maxSubLayers}, " +
                "temporal_id_nesting=${info.temporalIdNesting}, " +
                "profile=${profileName(info.profileTierLevel.profileIdc, info.profileTierLevel.compatibilityFlags)}, " +
                "tier=${if (info.profileTierLevel.tierFlag) "High" else "Main"}, " +
                "level=${levelName(info.profileTierLevel.levelIdc)}"
        }
    }

    private fun formatSpsInfos(infos: List<SpsInfo>): String {
        if (infos.isEmpty()) return "sps: not found"
        return infos.joinToString("\n") { info ->
            "sps[${info.id}]: vps_id=${info.vpsId}, " +
                "max_sub_layers=${info.maxSubLayers}, " +
                "profile=${profileName(info.profileTierLevel.profileIdc, info.profileTierLevel.compatibilityFlags)}, " +
                "tier=${if (info.profileTierLevel.tierFlag) "High" else "Main"}, " +
                "level=${levelName(info.profileTierLevel.levelIdc)}, " +
                "coded_size=${info.codedWidth}x${info.codedHeight}, " +
                "display_size=${info.displayWidth}x${info.displayHeight}, " +
                "chroma_format_idc=${info.chromaFormatIdc}, " +
                "bit_depth=${info.bitDepthLuma}/${info.bitDepthChroma}"
        }
    }

    private fun formatPpsInfos(infos: List<PpsInfo>): String {
        if (infos.isEmpty()) return "pps: not found"
        return infos.joinToString("\n") { info ->
            "pps[${info.id}]: sps_id=${info.spsId}, " +
                "dependent_slice_segments_enabled=${info.dependentSliceSegmentsEnabled}, " +
                "output_flag_present=${info.outputFlagPresent}, " +
                "num_extra_slice_header_bits=${info.numExtraSliceHeaderBits}"
        }
    }

    private fun profileName(profileIdc: Int, compatibilityFlags: Long): String {
        val compatible = (0..31)
            .filter { bit -> ((compatibilityFlags ushr (31 - bit)) and 1L) == 1L }
            .joinToString(prefix = " [compat:", postfix = "]") { it.toString() }
            .takeIf { it != " [compat:]" }
            .orEmpty()
        return when (profileIdc) {
            1 -> "Main"
            2 -> "Main 10"
            3 -> "Main Still Picture"
            else -> "Profile $profileIdc"
        } + compatible
    }

    private fun levelName(levelIdc: Int): String {
        if (levelIdc <= 0) return "unknown"
        val major = levelIdc / 30
        val minor = (levelIdc % 30) / 3
        return if (minor == 0) {
            "Level $major"
        } else {
            "Level $major.$minor"
        }
    }

    private fun nalTypeName(type: Int): String {
        return when (type) {
            0 -> "TRAIL_N"
            1 -> "TRAIL_R"
            2 -> "TSA_N"
            3 -> "TSA_R"
            4 -> "STSA_N"
            5 -> "STSA_R"
            6 -> "RADL_N"
            7 -> "RADL_R"
            8 -> "RASL_N"
            9 -> "RASL_R"
            16 -> "BLA_W_LP"
            17 -> "BLA_W_RADL"
            18 -> "BLA_N_LP"
            19 -> "IDR_W_RADL"
            20 -> "IDR_N_LP"
            21 -> "CRA_NUT"
            32 -> "VPS"
            33 -> "SPS"
            34 -> "PPS"
            35 -> "AUD"
            36 -> "EOS"
            37 -> "EOB"
            38 -> "FD"
            39 -> "PREFIX_SEI"
            40 -> "SUFFIX_SEI"
            else -> "reserved"
        }
    }

    private fun renderAnalysis(raw: String) {
        val sections = parseSections(raw)
        if (sections.isEmpty()) {
            showSingleSection("Raw Output", raw.trim())
            return
        }

        val summaryChips = extractSummaryChips(sections)
        binding.sectionContainer.removeAllViews()

        if (summaryChips.isNotEmpty()) {
            binding.sectionContainer.addView(createSummaryCard(summaryChips))
            binding.sectionContainer.addView(createSpacer())
        }

        sections.forEachIndexed { index, section ->
            binding.sectionContainer.addView(createSectionCard(section.title, section.content))
            if (index != sections.lastIndex) {
                binding.sectionContainer.addView(createSpacer())
            }
        }
    }

    private fun parseSections(raw: String): List<AnalyzerSection> {
        return raw
            .split(Regex("""\n\s*\n"""))
            .mapNotNull { block ->
                val lines = block.lines()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                if (lines.isEmpty()) return@mapNotNull null
                val title = lines.first()
                if (title == "H265 Stream Analysis" || title.startsWith("===")) {
                    return@mapNotNull null
                }
                AnalyzerSection(
                    title = title.removeSuffix(":"),
                    content = lines.drop(1).joinToString("\n").trim()
                )
            }
    }

    private fun extractSummaryChips(sections: List<AnalyzerSection>): List<String> {
        val summary = sections.firstOrNull { it.title.equals("summary", ignoreCase = true) }
            ?.content
            ?: return emptyList()
        val interestingKeys = linkedMapOf(
            "width" to "Width",
            "height" to "Height",
            "profile" to "Profile",
            "tier" to "Tier",
            "level" to "Level",
            "nal_unit_count" to "NAL",
            "vps_count" to "VPS",
            "sps_count" to "SPS",
            "pps_count" to "PPS",
            "irap_count" to "IRAP"
        )
        return interestingKeys.mapNotNull { (key, label) ->
            summary.lineSequence()
                .firstOrNull { line -> line.trimStart().startsWith("$key:") }
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$label $it" }
        }
    }

    private fun renderSelectedFile() {
        val file = selectedFile
        binding.sourceNameText.text = getString(
            R.string.h264_analyzer_selected_file_value,
            file?.displayName ?: getString(R.string.h264_analyzer_empty_value)
        )
        binding.sourceUriText.text = getString(
            R.string.h264_analyzer_source_uri_value,
            file?.uri?.toString() ?: getString(R.string.h264_analyzer_empty_value)
        )
        binding.localCopyText.text = getString(
            R.string.h264_analyzer_local_copy_value,
            file?.localFile?.absolutePath ?: getString(R.string.h264_analyzer_empty_value)
        )
    }

    private fun renderState() {
        binding.selectFileButton.isEnabled = !isWorking
        binding.analyzeButton.isEnabled = !isWorking && selectedFile != null
    }

    private fun handleIncomingLocalFile() {
        val localFilePath = intent.getStringExtra(EXTRA_LOCAL_FILE_PATH)?.trim().orEmpty()
        if (localFilePath.isEmpty()) return

        val localFile = File(localFilePath)
        if (!localFile.exists() || !localFile.isFile) {
            updateStatus(
                getString(
                    R.string.h265_analyzer_status_error,
                    getString(R.string.h265_analyzer_invalid_input_file)
                )
            )
            return
        }

        selectedFile = SelectedFile(
            uri = Uri.fromFile(localFile),
            displayName = localFile.name,
            localFile = localFile
        )
        renderSelectedFile()
        renderState()
        updateStatus(getString(R.string.h265_analyzer_status_ready, localFile.name))
        analyzeSelectedFile()
    }

    private fun showSingleSection(title: String, content: String) {
        binding.sectionContainer.removeAllViews()
        binding.sectionContainer.addView(createSectionCard(title, content))
    }

    private fun createSummaryCard(chips: List<String>): MaterialCardView {
        val card = createBaseCard()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val titleView = TextView(this).apply {
            text = getString(R.string.h264_analyzer_summary_title)
            setTextColor(0xFF0F172A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val chipGroup = ChipGroup(this).apply {
            isSingleLine = false
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(8)
            setPadding(0, dp(12), 0, 0)
        }
        chips.forEach { chipText ->
            chipGroup.addView(
                Chip(this).apply {
                    text = chipText
                    isClickable = false
                    isCheckable = false
                }
            )
        }
        body.addView(titleView)
        body.addView(chipGroup)
        card.addView(body)
        return card
    }

    private fun createSectionCard(title: String, content: String): MaterialCardView {
        val card = createBaseCard()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val titleKey = title.lowercase(Locale.US)
        val collapsible = titleKey == "nal_units"
        val isCollapsed = collapsedSections.getOrPut(titleKey) { collapsible }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val titleView = TextView(this).apply {
            text = title.replaceFirstChar { it.uppercase() }
            setTextColor(0xFF0F172A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val toggleView = TextView(this).apply {
            visibility = if (collapsible) View.VISIBLE else View.GONE
            text = getString(
                if (isCollapsed) R.string.h264_analyzer_expand else R.string.h264_analyzer_collapse
            )
            setTextColor(0xFF6D28D9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        headerRow.addView(titleView)
        headerRow.addView(toggleView)

        val collapsedHintView = TextView(this).apply {
            visibility = if (collapsible && isCollapsed) View.VISIBLE else View.GONE
            text = getString(R.string.h264_analyzer_collapsed_hint)
            setTextColor(0xFF334155.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        val contentView = TextView(this).apply {
            text = buildSectionSpannable(titleKey, content)
            setTextColor(0xFF334155.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            visibility = if (collapsible && isCollapsed) View.GONE else View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        if (collapsible) {
            val toggleAction = {
                val nextCollapsed = !(collapsedSections[titleKey] ?: true)
                collapsedSections[titleKey] = nextCollapsed
                toggleView.text = getString(
                    if (nextCollapsed) R.string.h264_analyzer_expand else R.string.h264_analyzer_collapse
                )
                collapsedHintView.visibility = if (nextCollapsed) View.VISIBLE else View.GONE
                contentView.visibility = if (nextCollapsed) View.GONE else View.VISIBLE
            }
            toggleView.setOnClickListener { toggleAction() }
            headerRow.setOnClickListener { toggleAction() }
        }

        body.addView(headerRow)
        body.addView(collapsedHintView)
        body.addView(contentView)
        card.addView(body)
        return card
    }

    private fun buildSectionSpannable(titleKey: String, content: String): CharSequence {
        val normalizedContent = if (titleKey == "nal_units") {
            content.lines().filter { it.isNotBlank() }.joinToString("\n\n")
        } else {
            content
        }
        val builder = SpannableStringBuilder()
        val lines = normalizedContent.lines()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimStart()
            val start = builder.length
            builder.append(line)
            val end = builder.length
            applyLineHighlight(builder, line, start, end)
            if (index != lines.lastIndex) {
                builder.append('\n')
            }
        }
        return builder
    }

    private fun applyLineHighlight(
        builder: SpannableStringBuilder,
        line: String,
        start: Int,
        end: Int
    ) {
        if (start >= end) return
        if (line.startsWith("#")) {
            val markerEnd = line.indexOf(' ').takeIf { it > 0 } ?: line.length
            builder.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                start,
                start + markerEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                ForegroundColorSpan(0xFF6D28D9.toInt()),
                start,
                start + markerEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val highlightStyle = when {
            line.contains("(VPS)") -> HighlightStyle(0xFFEFF6FF.toInt(), 0xFF1D4ED8.toInt())
            line.contains("(SPS)") -> HighlightStyle(0xFFE8FFF3.toInt(), 0xFF047857.toInt())
            line.contains("(PPS)") -> HighlightStyle(0xFFFFF4E5.toInt(), 0xFFC2410C.toInt())
            line.contains("IDR", ignoreCase = true) ||
                line.contains("CRA", ignoreCase = true) ||
                line.contains("irap_count", ignoreCase = true) ->
                HighlightStyle(0xFFF3E8FF.toInt(), 0xFF6D28D9.toInt())
            else -> null
        } ?: return
        builder.setSpan(
            BackgroundColorSpan(highlightStyle.backgroundColor),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            ForegroundColorSpan(highlightStyle.textColor),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private data class HighlightStyle(
        val backgroundColor: Int,
        val textColor: Int
    )

    private class BitReader(private val bytes: ByteArray) {
        private var bitOffset = 0

        fun readBit(): Boolean = readBits(1) == 1L

        fun readBits(count: Int): Long {
            require(count in 0..63) { "Unsupported bit count: $count" }
            require(bitOffset + count <= bytes.size * 8) { "Unexpected end of RBSP." }
            var value = 0L
            repeat(count) {
                val byteIndex = bitOffset / 8
                val bitIndex = 7 - (bitOffset % 8)
                value = (value shl 1) or (((bytes[byteIndex].toInt() ushr bitIndex) and 1).toLong())
                bitOffset++
            }
            return value
        }

        fun readUE(): Int {
            var leadingZeroBits = 0
            while (bitOffset < bytes.size * 8 && !readBit()) {
                leadingZeroBits++
            }
            if (leadingZeroBits == 0) return 0
            val suffix = readBits(leadingZeroBits).toInt()
            return (1 shl leadingZeroBits) - 1 + suffix
        }
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

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }

    private fun fallbackName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "input_${formatter.format(Date())}.h265"
    }

    private fun copyUriToSandbox(uri: Uri, displayName: String): File {
        val safeName = displayName.ifBlank { fallbackName() }
            .replace(Regex("""[^\w.\-]"""), "_")
        val targetDir = File(cacheDir, "h265_probe").apply { mkdirs() }
        val targetFile = File(targetDir, safeName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected file.")
        return targetFile
    }

    private fun updateStatus(text: String) {
        binding.statusText.text = text
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun postUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { action() }
    }
}
