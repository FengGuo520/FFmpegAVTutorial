package com.lovelymaple.ffmpegavtutorial.basic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityCameraInfoBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace

class CameraInfoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraInfoActivity"
    }

    private data class CameraSummary(
        val total: Int,
        val front: Int,
        val back: Int,
        val external: Int
    )

    private data class CameraSection(
        val title: String,
        val content: String
    )

    private data class CameraCardModel(
        val cameraId: String,
        val badge: String,
        val headline: String,
        val summary: String,
        val sections: List<CameraSection>,
        val expandedByDefault: Boolean = false
    )

    private lateinit var binding: ActivityCameraInfoBinding
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCameraInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener { renderCameraInfo() }
        renderCameraInfo()
    }

    private fun renderCameraInfo() {
        binding.refreshButton.isEnabled = false
        binding.summaryChipGroup.removeAllViews()
        binding.cameraCardContainer.removeAllViews()
        binding.errorText.isVisible = false

        try {
            val cameraIds = cameraManager.cameraIdList.toList()
            val cards = cameraIds.map { buildCameraCard(it) }
            val summary = buildSummary(cameraIds)
            logCameraSummary(summary)
            cards.forEach(::logCameraCard)

            renderSummary(summary)
            cards.forEach { binding.cameraCardContainer.addView(createCameraCard(it)) }
            binding.subtitleText.text =
                getString(
                    R.string.camera_info_description,
                    summary.total,
                    summary.back,
                    summary.front
                )
        } catch (t: Throwable) {
            Log.e(TAG, "renderCameraInfo failed", t)
            binding.errorText.isVisible = true
            binding.errorText.text =
                getString(R.string.camera_info_load_failed, t.message ?: t.javaClass.simpleName)
        } finally {
            binding.refreshButton.isEnabled = true
        }
    }

    private fun buildSummary(cameraIds: List<String>): CameraSummary {
        var front = 0
        var back = 0
        var external = 0
        cameraIds.forEach { cameraId ->
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> front++
                CameraCharacteristics.LENS_FACING_BACK -> back++
                CameraCharacteristics.LENS_FACING_EXTERNAL -> external++
            }
        }
        return CameraSummary(
            total = cameraIds.size,
            front = front,
            back = back,
            external = external
        )
    }

    private fun buildCameraCard(cameraId: String): CameraCardModel {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val lensFacing = lensFacingLabel(characteristics.get(CameraCharacteristics.LENS_FACING))
        val hardwareLevel = hardwareLevelLabel(
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        )
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = streamMap
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.sortedByDescending { it.width * it.height }
            .orEmpty()
        val previewSummary = outputSizes
            .take(6)
            .joinToString(separator = ", ") { "${it.width}x${it.height}" }
            .ifBlank { "Unavailable" }
        val imageReaderSizes = streamMap
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.sortedByDescending { it.width * it.height }
            .orEmpty()
        val imageReaderSummary =
            if (imageReaderSizes.isEmpty()) {
                "Unavailable"
            } else {
                buildString {
                    appendLine("Count: ${imageReaderSizes.size}")
                    append(
                        imageReaderSizes.joinToString(separator = ", ") {
                            "${it.width}x${it.height}"
                        }
                    )
                }
            }
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.joinToString(separator = ", ") { capabilityLabel(it) }
            ?: "Unavailable"
        val afModes = characteristics
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.joinToString(separator = ", ") { afModeLabel(it) }
            ?: "Unavailable"
        val focalLengths = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.joinToString(separator = ", ") { "${trimFloat(it)} mm" }
            ?: "Unavailable"
        val physicalIds = characteristics.physicalCameraIds
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ")
            ?: "None"

        val sections = listOf(
            CameraSection(
                title = "Identity",
                content = buildString {
                    appendLine("Camera ID: $cameraId")
                    appendLine("Lens facing: $lensFacing")
                    appendLine("Hardware level: $hardwareLevel")
                    append("Physical camera ids: $physicalIds")
                }
            ),
            CameraSection(
                title = "Lens and Sensor",
                content = buildString {
                    appendLine("Sensor orientation: ${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0}°")
                    appendLine("Flash available: ${yesNo(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)}")
                    appendLine("Max digital zoom: ${trimFloat(characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 0f)}x")
                    append("Focal lengths: $focalLengths")
                }
            ),
            CameraSection(
                title = "Focus and Capabilities",
                content = buildString {
                    appendLine("AF modes: $afModes")
                    append("Capabilities: $capabilities")
                }
            ),
            CameraSection(
                title = "Preview Output Sizes",
                content = previewSummary
            ),
            CameraSection(
                title = "ImageReader (YUV_420_888) Output Sizes",
                content = imageReaderSummary
            )
        )

        return CameraCardModel(
            cameraId = cameraId,
            badge = "Camera $cameraId",
            headline = "$lensFacing lens",
            summary = "Hardware level $hardwareLevel",
            sections = sections,
            expandedByDefault = cameraId == "0"
        )
    }

    private fun renderSummary(summary: CameraSummary) {
        val chips = listOf(
            "Total ${summary.total}",
            "Back ${summary.back}",
            "Front ${summary.front}",
            "External ${summary.external}",
            if (hasCameraPermission()) "Permission granted" else "Permission not granted"
        )
        chips.forEach { label ->
            binding.summaryChipGroup.addView(
                Chip(this).apply {
                    text = label
                    isClickable = false
                    isCheckable = false
                    chipBackgroundColor =
                        android.content.res.ColorStateList.valueOf(0xFFEEF4FF.toInt())
                    setTextColor(0xFF355070.toInt())
                    chipStrokeWidth = dp(1).toFloat()
                    chipStrokeColor =
                        android.content.res.ColorStateList.valueOf(0xFFD7E3F8.toInt())
                }
            )
        }
    }

    private fun logCameraSummary(summary: CameraSummary) {
        Log.d(
            TAG,
            "camera summary total=${summary.total} back=${summary.back} front=${summary.front} " +
                "external=${summary.external} permission=${hasCameraPermission()}"
        )
    }

    private fun logCameraCard(model: CameraCardModel) {
        Log.d(TAG, "camera card badge=${model.badge} headline=${model.headline} summary=${model.summary}")
        model.sections.forEach { section ->
            Log.d(TAG, "section[${section.title}]\n${section.content}")
        }
    }

    private fun createCameraCard(model: CameraCardModel): View {
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

        infoColumn.addView(
            TextView(this).apply {
                text = model.badge
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF355070.toInt())
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setBackgroundColor(0xFFEEF4FF.toInt())
            }
        )
        infoColumn.addView(
            TextView(this).apply {
                text = model.headline
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
                setPadding(0, dp(12), 0, 0)
            }
        )
        infoColumn.addView(
            TextView(this).apply {
                text = model.summary
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF64748B.toInt())
                setPadding(0, dp(6), 0, 0)
            }
        )

        val toggleView = TextView(this).apply {
            text = if (model.expandedByDefault) "v" else ">"
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
            isVisible = model.expandedByDefault
        }

        model.sections.forEachIndexed { index, section ->
            body.addView(createSectionBlock(section))
            if (index != model.sections.lastIndex) {
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

        body.addView(
            android.widget.Button(this).apply {
                text = getString(R.string.camera_info_open_preview)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(16)
                }
                setOnClickListener {
                    startActivity(
                        Intent(this@CameraInfoActivity, CameraPreviewActivity::class.java)
                            .putExtra(CameraPreviewActivity.EXTRA_CAMERA_ID, model.cameraId)
                    )
                }
            }
        )

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

    private fun createSectionBlock(section: CameraSection): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@CameraInfoActivity).apply {
                    text = section.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(0xFF1E293B.toInt())
                }
            )
            addView(
                TextView(this@CameraInfoActivity).apply {
                    text = section.content
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(0xFF334155.toInt())
                    typeface = android.graphics.Typeface.MONOSPACE
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setBackgroundColor(0xFFF8FAFC.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(10)
                    }
                }
            )
        }
    }

    private fun lensFacingLabel(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
        CameraCharacteristics.LENS_FACING_BACK -> "Back"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
        else -> "Unknown"
    }

    private fun hardwareLevelLabel(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
        else -> "Unknown"
    }

    private fun capabilityLabel(capability: Int): String = when (capability) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "Backward Compatible"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "Manual Sensor"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "Manual Post"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "Private Reprocess"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "Read Sensor Settings"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "Burst"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV Reprocess"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "Depth Output"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "High Speed Video"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "Motion Tracking"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "Logical Multi Camera"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "Monochrome"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "Secure Image Data"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "System Camera"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "Offline Processing"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "Ultra High Resolution"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "Remosaic Reprocess"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "Stream Use Case"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "Color Space Profiles"
        else -> "Capability $capability"
    }

    private fun afModeLabel(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AF_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraCharacteristics.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraCharacteristics.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "MODE_$mode"
    }

    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

    private fun trimFloat(value: Float): String {
        return if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", value)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun resolveSelectableItemBackground() = TypedValue().let { outValue ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        getDrawable(outValue.resourceId)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
