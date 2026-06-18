package com.lovelymaple.ffmpegavtutorial.basic

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
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class H264StreamAnalyzerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOCAL_FILE_PATH = "extra_local_file_path"

        fun createIntent(context: android.content.Context, localFilePath: String): Intent {
            return Intent(context, H264StreamAnalyzerActivity::class.java)
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

    private lateinit var binding: ActivityH264StreamAnalyzerBinding
    private lateinit var nativeInstance: NativeInstance

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

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.selectFileButton.setOnClickListener { pickFile() }
        binding.analyzeButton.setOnClickListener { analyzeSelectedFile() }

        renderSelectedFile()
        renderState()
        updateStatus(getString(R.string.h264_analyzer_status_idle))
        handleIncomingLocalFile()
    }

    private fun pickFile() {
        if (isWorking) return
        pickerLauncher.launch(arrayOf("*/*"))
    }

    private fun importFile(uri: Uri) {
        isWorking = true
        renderState()
        updateStatus(getString(R.string.h264_analyzer_status_importing))

        thread(start = true, name = "H264AnalyzerImport") {
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
                        updateStatus(
                            getString(R.string.h264_analyzer_status_ready, file.displayName)
                        )
                    },
                    onFailure = { throwable ->
                        renderState()
                        updateStatus(
                            getString(
                                R.string.h264_analyzer_status_error,
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
            updateStatus(getString(R.string.h264_analyzer_status_select_file_first))
            return
        }
        if (isWorking) return

        isWorking = true
        binding.sectionContainer.removeAllViews()
        renderState()
        updateStatus(getString(R.string.h264_analyzer_status_analyzing, file.displayName))

        thread(start = true, name = "H264AnalyzerNative") {
            val result = runCatching {
                nativeInstance.analyzeH264Stream(file.localFile.absolutePath)
            }

            postUi {
                isWorking = false
                renderState()
                result.fold(
                    onSuccess = { raw ->
                        if (raw.startsWith("ERROR:")) {
                            showSingleSection("Error", raw)
                            updateStatus(
                                getString(
                                    R.string.h264_analyzer_status_error,
                                    raw.removePrefix("ERROR: ").trim()
                                )
                            )
                        } else {
                            renderAnalysis(raw)
                            updateStatus(
                                getString(R.string.h264_analyzer_status_done, file.displayName)
                            )
                        }
                    },
                    onFailure = { throwable ->
                        showSingleSection("Error", throwable.message ?: throwable.javaClass.simpleName)
                        updateStatus(
                            getString(
                                R.string.h264_analyzer_status_error,
                                throwable.message ?: throwable.javaClass.simpleName
                            )
                        )
                    }
                )
            }
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
                if (lines.isEmpty()) {
                    return@mapNotNull null
                }
                val title = lines.first()
                if (title == "H264 Stream Analysis" || title.startsWith("===")) {
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
            "level" to "Level",
            "nal_unit_count" to "NAL",
            "sps_count" to "SPS",
            "pps_count" to "PPS",
            "access_unit_count" to "AU"
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
        if (localFilePath.isEmpty()) {
            return
        }

        val localFile = File(localFilePath)
        if (!localFile.exists() || !localFile.isFile) {
            updateStatus(
                getString(
                    R.string.h264_analyzer_status_error,
                    getString(R.string.h264_analyzer_invalid_input_file)
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
        updateStatus(getString(R.string.h264_analyzer_status_ready, localFile.name))
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
        val collapsible = isCollapsibleSection(titleKey)
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
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(0xFFF3E8FF.toInt())
            }
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

    private fun isCollapsibleSection(titleKey: String): Boolean {
        return titleKey == "nal_units"
    }

    private fun buildSectionSpannable(titleKey: String, content: String): CharSequence {
        val normalizedContent = if (titleKey == "nal_units") {
            formatNalUnitsContent(content)
        } else {
            content
        }

        val builder = SpannableStringBuilder()
        normalizedContent.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trimStart()
            val start = builder.length
            builder.append(line)
            val end = builder.length
            applyLineHighlight(builder, line, start, end)
            if (index != normalizedContent.lines().lastIndex) {
                builder.append('\n')
            }
        }
        return builder
    }

    private fun formatNalUnitsContent(content: String): String {
        val entries = content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return entries.joinToString("\n\n")
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
            line.contains("(sps)", ignoreCase = true) ||
                line.contains("sps_count", ignoreCase = true) ->
                HighlightStyle(0xFFE8FFF3.toInt(), 0xFF047857.toInt())
            line.contains("(pps)", ignoreCase = true) ||
                line.contains("pps_count", ignoreCase = true) ->
                HighlightStyle(0xFFFFF4E5.toInt(), 0xFFC2410C.toInt())
            line.contains("(idr_slice)", ignoreCase = true) ||
                line.contains("idr_count", ignoreCase = true) ->
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
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
    }

    private fun fallbackName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "input_${formatter.format(Date())}.h264"
    }

    private fun copyUriToSandbox(uri: Uri, displayName: String): File {
        val safeName = displayName.ifBlank { fallbackName() }
            .replace(Regex("""[^\w.\-]"""), "_")
        val targetDir = File(cacheDir, "h264_probe").apply { mkdirs() }
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
