package com.lovelymaple.ffmpegavtutorial.basic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.RadioButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityVideoEncodeBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class VideoEncodeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoEncodeActivity"
    }

    private enum class ResolutionOption(
        val width: Int,
        val height: Int,
        val labelRes: Int
    ) {
        P540(960, 540, R.string.h264_encode_resolution_540p),
        P720(1280, 720, R.string.h264_encode_resolution_720p),
        P1080(1920, 1080, R.string.h264_encode_resolution_1080p)
    }

    private enum class BitrateOption(
        val bitrate: Int,
        val labelRes: Int
    ) {
        BR_1M(1_000_000, R.string.h264_encode_bitrate_1m),
        BR_2M(2_000_000, R.string.h264_encode_bitrate_2m),
        BR_4M(4_000_000, R.string.h264_encode_bitrate_4m)
    }

    private enum class VideoCodecOption(
        val mimeType: String,
        val labelRes: Int,
        val fileTag: String,
        val fileExtension: String
    ) {
        H264(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            R.string.h264_encode_codec_h264,
            "h264",
            "h264"
        ),
        H265(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            R.string.h264_encode_codec_h265,
            "h265",
            "h265"
        )
    }

    private enum class IFrameIntervalOption(
        val seconds: Int,
        val labelRes: Int
    ) {
        SEC_1(1, R.string.h264_encode_iframe_1s),
        SEC_2(2, R.string.h264_encode_iframe_2s),
        SEC_5(5, R.string.h264_encode_iframe_5s)
    }

    private lateinit var binding: ActivityVideoEncodeBinding
    private lateinit var nativeInstance: NativeInstance

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private val encodeFrameRate = 30

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var isOpeningCamera = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderOutputFile: File? = null
    private var encoderOutputStream: FileOutputStream? = null
    @Volatile private var isEncoding = false
    @Volatile private var encodedFrameCount = 0
    private var drainThread: Thread? = null
    private var latestAnalyzableFile: File? = null
    private var latestAnalyzableCodec: VideoCodecOption? = null
    private var currentCodec = VideoCodecOption.H264
    private var currentResolution = ResolutionOption.P720
    private var currentBitrate = BitrateOption.BR_2M
    private var currentAvcProfile: Int? = null
    private var currentAvcLevel: Int? = null
    private var currentHevcProfile: Int? = null
    private var currentHevcLevel: Int? = null
    private var currentIFrameInterval = IFrameIntervalOption.SEC_2
    private val profileRadioValues = mutableMapOf<Int, Int>()
    private val levelRadioValues = mutableMapOf<Int, Int>()
    private var suppressProfileLevelCallbacks = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "permission result granted=$granted textureAvailable=${binding.previewTexture.isAvailable}")
            if (granted) {
                updatePermissionUi()
                if (backgroundHandler == null) {
                    Log.d(TAG, "permission granted but backgroundHandler is null, wait for onResume")
                } else {
                    openCameraWhenReady()
                }
            } else {
                showPermissionRequiredState(R.string.h264_encode_status_permission_denied)
            }
        }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "surface available width=$width height=$height")
            openCameraWhenReady()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "surface size changed width=$width height=$height")
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Log.d(TAG, "surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "camera onOpened id=${camera.id}")
            isOpeningCamera = false
            cameraDevice = camera
            postUi {
                createCameraSession()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "camera onDisconnected id=${camera.id}")
            isOpeningCamera = false
            camera.close()
            cameraDevice = null
            postUi {
                binding.previewPlaceholder.visibility = View.VISIBLE
                updateStatus(getString(R.string.h264_encode_status_error, "camera disconnected"))
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "camera onError id=${camera.id} error=$error")
            isOpeningCamera = false
            camera.close()
            cameraDevice = null
            postUi {
                binding.previewPlaceholder.visibility = View.VISIBLE
                updateStatus(getString(R.string.h264_encode_status_error, "camera error $error"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityVideoEncodeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)
        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { ensurePermissionAndStartPreview() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.startButton.setOnClickListener { startEncoding() }
        binding.stopButton.setOnClickListener { stopEncoding() }
        binding.openFullAnalyzerButton.setOnClickListener { openFullAnalyzer() }
        binding.previewTexture.surfaceTextureListener = surfaceTextureListener
        binding.codecGroup.setOnCheckedChangeListener { _, checkedId ->
            currentCodec =
                when (checkedId) {
                    binding.codecH265Radio.id -> VideoCodecOption.H265
                    else -> VideoCodecOption.H264
                }
            rebuildProfileLevelControls()
            updateStaticInfo()
        }
        binding.resolutionGroup.setOnCheckedChangeListener { _, checkedId ->
            currentResolution =
                when (checkedId) {
                    binding.resolution540Radio.id -> ResolutionOption.P540
                    binding.resolution1080Radio.id -> ResolutionOption.P1080
                    else -> ResolutionOption.P720
                }
            rebuildProfileLevelControls()
            updateStaticInfo()
        }
        binding.bitrateGroup.setOnCheckedChangeListener { _, checkedId ->
            currentBitrate =
                when (checkedId) {
                    binding.bitrate1mRadio.id -> BitrateOption.BR_1M
                    binding.bitrate4mRadio.id -> BitrateOption.BR_4M
                    else -> BitrateOption.BR_2M
                }
            updateStaticInfo()
        }
        binding.profileGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressProfileLevelCallbacks || checkedId == -1) return@setOnCheckedChangeListener
            val profile = profileRadioValues[checkedId] ?: return@setOnCheckedChangeListener
            if (currentCodec == VideoCodecOption.H264) {
                currentAvcProfile = profile
                rebuildLevelControlsForCurrentProfile()
            } else {
                currentHevcProfile = profile
                rebuildLevelControlsForCurrentProfile()
            }
            updateStaticInfo()
        }
        binding.levelGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressProfileLevelCallbacks || checkedId == -1) return@setOnCheckedChangeListener
            val level = levelRadioValues[checkedId] ?: return@setOnCheckedChangeListener
            if (currentCodec == VideoCodecOption.H264) {
                currentAvcLevel = level
            } else {
                currentHevcLevel = level
            }
            updateStaticInfo()
        }
        binding.iframeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentIFrameInterval =
                when (checkedId) {
                    binding.iframe1Radio.id -> IFrameIntervalOption.SEC_1
                    binding.iframe5Radio.id -> IFrameIntervalOption.SEC_5
                    else -> IFrameIntervalOption.SEC_2
                }
            updateStaticInfo()
        }

        rebuildProfileLevelControls()
        updateStaticInfo()
        updatePermissionUi()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume textureAvailable=${binding.previewTexture.isAvailable}")
        startBackgroundThread()
        if (binding.previewTexture.isAvailable) {
            openCameraWhenReady()
        } else {
            binding.previewTexture.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        stopEncoding()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun ensurePermissionAndStartPreview() {
        Log.d(TAG, "ensurePermissionAndStartPreview hasPermission=${hasCameraPermission()}")
        if (hasCameraPermission()) {
            openCameraWhenReady(forceReopen = true)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openCameraWhenReady(forceReopen: Boolean = false) {
        Log.d(
            TAG,
            "openCameraWhenReady forceReopen=$forceReopen hasPermission=${hasCameraPermission()} " +
                "textureAvailable=${binding.previewTexture.isAvailable} cameraDeviceNull=${cameraDevice == null} " +
                "isOpeningCamera=$isOpeningCamera " +
                "textureSize=${binding.previewTexture.width}x${binding.previewTexture.height} " +
                "handlerNull=${backgroundHandler == null}"
        )
        if (!hasCameraPermission()) {
            showPermissionRequiredState(R.string.h264_encode_status_permission_required)
            return
        }
        if (!binding.previewTexture.isAvailable) {
            updateStatus(getString(R.string.h264_encode_status_idle))
            return
        }
        if (forceReopen) {
            closeCamera()
        }
        if (cameraDevice != null) {
            createCameraSession()
            return
        }
        if (isOpeningCamera) {
            Log.d(TAG, "openCameraWhenReady ignored because camera is already opening")
            return
        }
        openCamera()
    }

    private fun openCamera() {
        try {
            val cameraId = findCameraId(currentLensFacing)
            Log.d(TAG, "openCamera lensFacing=$currentLensFacing cameraId=$cameraId")
            if (cameraId == null) {
                val message = getString(R.string.h264_encode_status_no_camera, currentLensLabel())
                binding.previewPlaceholder.visibility = View.VISIBLE
                binding.previewPlaceholder.text = message
                updateStatus(message)
                return
            }
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: run {
                        updateStatus(getString(R.string.h264_encode_status_error, "preview output unavailable"))
                        return
                    }
            previewSize = choosePreviewSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                binding.previewTexture.width,
                binding.previewTexture.height
            )
            Log.d(
                TAG,
                "openCamera previewSize=${previewSize?.width}x${previewSize?.height} " +
                    "viewSize=${binding.previewTexture.width}x${binding.previewTexture.height}"
            )
            configureTransform(binding.previewTexture.width, binding.previewTexture.height)
            val message = getString(R.string.h264_encode_status_opening, currentLensLabel())
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text = message
            updateStatus(message)
            isOpeningCamera = true
            Log.d(TAG, "calling cameraManager.openCamera cameraId=$cameraId")
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (exception: CameraAccessException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera CameraAccessException", exception)
            updateStatus(getString(R.string.h264_encode_status_error, exception.message ?: "camera access error"))
        } catch (exception: SecurityException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera SecurityException", exception)
            showPermissionRequiredState(R.string.h264_encode_status_permission_required)
        }
    }

    private fun createCameraSession() {
        val texture = binding.previewTexture.surfaceTexture
        val camera = cameraDevice
        val size = previewSize
        if (texture == null || camera == null || size == null) {
            Log.w(
                TAG,
                "createCameraSession skipped textureNull=${texture == null} " +
                    "cameraNull=${camera == null} sizeNull=${size == null}"
            )
            return
        }
        Log.d(TAG, "createCameraSession cameraId=${camera.id} previewSize=${size.width}x${size.height}")

        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null

            texture.setDefaultBufferSize(size.width, size.height)
            val previewSurface = Surface(texture)
            val surfaces = mutableListOf(previewSurface)
            Log.d(TAG, "createCameraSession preview surface created")

            val template =
                if (isEncoding && encoderSurface != null) {
                    CameraDevice.TEMPLATE_RECORD
                } else {
                    CameraDevice.TEMPLATE_PREVIEW
                }

            previewRequestBuilder = camera.createCaptureRequest(template).apply {
                addTarget(previewSurface)
                encoderSurface?.takeIf { isEncoding }?.let { encodeSurface ->
                    addTarget(encodeSurface)
                    surfaces.add(encodeSurface)
                }
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "captureSession onConfigured")
                        if (cameraDevice == null) return
                        cameraCaptureSession = session
                        try {
                            session.setRepeatingRequest(
                                previewRequestBuilder!!.build(),
                                null,
                                backgroundHandler
                            )
                            Log.d(TAG, "captureSession setRepeatingRequest success")
                            postUi {
                                binding.previewPlaceholder.visibility = View.GONE
                                updateStatus(
                                    if (isEncoding) {
                                        getString(
                                            R.string.h264_encode_status_encoding,
                                            currentCodecLabel()
                                        )
                                    } else {
                                        getString(
                                            R.string.h264_encode_status_previewing,
                                            currentLensLabel()
                                        )
                                    }
                                )
                            }
                        } catch (exception: CameraAccessException) {
                            postUi {
                                updateStatus(
                                    getString(
                                        R.string.h264_encode_status_error,
                                        exception.message ?: "failed to start camera session"
                                    )
                                )
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "captureSession onConfigureFailed")
                        postUi {
                            updateStatus(
                                getString(
                                    R.string.h264_encode_status_error,
                                    "camera session configuration failed"
                                )
                            )
                        }
                    }
                },
                backgroundHandler
            )
        } catch (exception: CameraAccessException) {
            Log.e(TAG, "createCameraSession CameraAccessException", exception)
            updateStatus(getString(R.string.h264_encode_status_error, exception.message ?: "camera session error"))
        }
    }

    private fun startEncoding() {
        if (isEncoding) return
        if (!hasCameraPermission()) {
            showPermissionRequiredState(R.string.h264_encode_status_permission_required)
            return
        }
        if (cameraDevice == null) {
            openCameraWhenReady()
        }
        try {
            setupEncoder()
            encodedFrameCount = 0
            isEncoding = true
            latestAnalyzableFile = null
            latestAnalyzableCodec = null
            binding.frameCountText.text =
                getString(R.string.h264_encode_frame_count_value, encodedFrameCount)
            binding.probeInfoText.text = getString(R.string.h264_encode_probe_empty)
            binding.openFullAnalyzerButton.isEnabled = false
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = true
            createCameraSession()
            startDrainThread()
            updateStatus(getString(R.string.h264_encode_status_encoding, currentCodecLabel()))
        } catch (exception: Exception) {
            releaseEncoder()
            updateStatus(
                getString(
                    R.string.h264_encode_status_error,
                    exception.message ?: "failed to start encoder"
                )
            )
        }
    }

    private fun stopEncoding() {
        if (!isEncoding && mediaCodec == null) return
        val outputFile = encoderOutputFile
        isEncoding = false
        drainThread?.join(1000)
        drainThread = null
        releaseEncoder()
        if (!isFinishing && !isDestroyed) {
            updatePermissionUi()
            if (cameraDevice != null) {
                createCameraSession()
            }
            updateStatus(getString(R.string.h264_encode_status_stopped))
            if (outputFile != null && outputFile.exists() && outputFile.length() > 0L) {
                probeOutputFile(outputFile)
            }
        }
    }

    private fun switchCamera() {
        currentLensFacing =
            if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
        openCameraWhenReady(forceReopen = true)
    }

    private fun closeCamera() {
        Log.d(TAG, "closeCamera sessionNull=${cameraCaptureSession == null} cameraNull=${cameraDevice == null}")
        isOpeningCamera = false
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun setupEncoder() {
        val outputFile = createOutputFile()
        encoderOutputFile = outputFile
        encoderOutputStream = FileOutputStream(outputFile)
        val encodeWidth = currentResolution.width
        val encodeHeight = currentResolution.height

        val encoderInfo = findHardwareEncoder(currentCodec.mimeType, encodeWidth, encodeHeight)
            ?: throw IllegalStateException("No hardware ${currentCodecLabel()} encoder found")

        val format = MediaFormat.createVideoFormat(currentCodec.mimeType, encodeWidth, encodeHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, encodeFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, currentIFrameInterval.seconds)
            if (currentCodec == VideoCodecOption.H264) {
                val avcProfile = currentAvcProfile
                val avcLevel = currentAvcLevel
                if (avcProfile != null && avcLevel != null) {
                    setInteger(MediaFormat.KEY_PROFILE, avcProfile)
                    setInteger(MediaFormat.KEY_LEVEL, avcLevel)
                }
            } else {
                val hevcProfile = currentHevcProfile
                val hevcLevel = currentHevcLevel
                if (hevcProfile != null && hevcLevel != null) {
                    setInteger(MediaFormat.KEY_PROFILE, hevcProfile)
                    setInteger(MediaFormat.KEY_LEVEL, hevcLevel)
                }
            }
        }

        Log.d(TAG, "setupEncoder codec=${currentCodec.mimeType} encoder=${encoderInfo.name}")
        mediaCodec = MediaCodec.createByCodecName(encoderInfo.name).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
            start()
        }

        binding.outputPathText.text =
            getString(R.string.h264_encode_output_path_value, outputFile.absolutePath)
    }

    private fun startDrainThread() {
        val codec = mediaCodec ?: return
        val outputStream = encoderOutputStream ?: return
        drainThread = thread(start = true, name = "${currentCodec.fileTag.uppercase(Locale.US)}DrainThread") {
            val bufferInfo = MediaCodec.BufferInfo()
            var wroteCodecConfig = false
            while (isEncoding || codecHasPendingOutput(codec, bufferInfo)) {
                when (val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (!wroteCodecConfig) {
                            writeCodecSpecificData(newFormat, outputStream)
                            wroteCodecConfig = true
                        }
                    }
                    else -> if (index >= 0) {
                        val outputBuffer = codec.getOutputBuffer(index)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                outputStream.write(bytes)
                                encodedFrameCount++
                                postUi {
                                    binding.frameCountText.text =
                                        getString(
                                            R.string.h264_encode_frame_count_value,
                                            encodedFrameCount
                                        )
                                }
                            } else if (!wroteCodecConfig) {
                                outputStream.write(bytes)
                                wroteCodecConfig = true
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }
            try {
                outputStream.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun codecHasPendingOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo): Boolean {
        val index = codec.dequeueOutputBuffer(bufferInfo, 0)
        return if (index >= 0) {
            codec.releaseOutputBuffer(index, false)
            true
        } else {
            false
        }
    }

    private fun writeCodecSpecificData(format: MediaFormat, outputStream: FileOutputStream) {
        listOf("csd-0", "csd-1", "csd-2").forEach { key ->
            format.getByteBuffer(key)?.let { buffer ->
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                outputStream.write(bytes)
            }
        }
    }

    private fun releaseEncoder() {
        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null
        try {
            encoderSurface?.release()
        } catch (_: Exception) {
        }
        encoderSurface = null
        try {
            encoderOutputStream?.flush()
        } catch (_: Exception) {
        }
        try {
            encoderOutputStream?.close()
        } catch (_: Exception) {
        }
        encoderOutputStream = null
    }

    private fun probeOutputFile(file: File) {
        latestAnalyzableFile = null
        latestAnalyzableCodec = null
        binding.openFullAnalyzerButton.isEnabled = false
        if (currentCodec != VideoCodecOption.H264) {
            latestAnalyzableFile = if (file.exists() && file.length() > 0L) file else null
            latestAnalyzableCodec = if (latestAnalyzableFile != null) currentCodec else null
            binding.openFullAnalyzerButton.isEnabled = latestAnalyzableFile != null
            binding.probeInfoText.text =
                getString(
                    R.string.h264_encode_probe_basic_summary,
                    file.length(),
                    currentCodec.fileExtension,
                    currentCodecLabel(),
                    file.absolutePath
                )
            return
        }

        binding.probeInfoText.text = getString(R.string.h264_encode_probe_loading)
        thread(start = true, name = "H264ProbeThread") {
            val raw = runCatching {
                nativeInstance.analyzeH264Stream(file.absolutePath)
            }
            postUi {
                latestAnalyzableFile = if (file.exists() && file.length() > 0L) file else null
                latestAnalyzableCodec = if (latestAnalyzableFile != null) VideoCodecOption.H264 else null
                binding.openFullAnalyzerButton.isEnabled = latestAnalyzableFile != null
                binding.probeInfoText.text =
                    raw.fold(
                        onSuccess = { result ->
                            if (result.startsWith("ERROR:")) {
                                Log.e(
                                    TAG,
                                    "Output Probe failed for ${file.absolutePath}: " +
                                        result.removePrefix("ERROR: ").trim()
                                )
                                getString(
                                    R.string.h264_encode_probe_failed,
                                    result.removePrefix("ERROR: ").trim()
                                )
                            } else {
                                val summary = buildProbeSummary(result)
                                Log.d(TAG, "Output Probe raw result for ${file.absolutePath}:\n$result")
                                Log.d(TAG, "Output Probe summary for ${file.absolutePath}:\n$summary")
                                summary
                            }
                        },
                        onFailure = { throwable ->
                            Log.e(
                                TAG,
                                "Output Probe exception for ${file.absolutePath}",
                                throwable
                            )
                            getString(
                                R.string.h264_encode_probe_failed,
                                throwable.message ?: throwable.javaClass.simpleName
                            )
                        }
                    )
            }
        }
    }

    private fun buildProbeSummary(raw: String): String {
        val summaryLines = raw
            .split(Regex("""\n\s*\n"""))
            .firstOrNull { block ->
                block.lineSequence().firstOrNull()?.trim() == "summary"
            }
            ?.lines()
            ?.drop(1)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (summaryLines.isEmpty()) {
            return raw.trim()
        }

        val interestingKeys = listOf(
            "file_size_bytes",
            "format",
            "codec",
            "width",
            "height",
            "profile",
            "level",
            "avg_frame_rate",
            "nal_unit_count",
            "sps_count",
            "pps_count",
            "access_unit_count"
        )

        return buildString {
            appendLine(getString(R.string.h264_encode_probe_title))
            interestingKeys.forEach { key ->
                summaryLines.firstOrNull { it.startsWith("$key:") }?.let { line ->
                    appendLine(line)
                }
            }
        }.trim()
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("H264EncodeCameraThread").also { thread ->
            thread.start()
            backgroundHandler = Handler(thread.looper)
            Log.d(TAG, "startBackgroundThread thread=${thread.name}")
        }
    }

    private fun stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread")
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun rebuildProfileLevelControls() {
        suppressProfileLevelCallbacks = true
        profileRadioValues.clear()
        binding.profileGroup.removeAllViews()

        if (currentCodec == VideoCodecOption.H264) {
            binding.profileTitleText.text = getString(R.string.h264_encode_profile_title)
            val profileLevels = currentEncoderProfileLevels()
            val profiles = profileLevels.map { it.profile }.distinct().sorted()
            if (profiles.isEmpty()) {
                currentAvcProfile = null
                addUnavailableRadioButton(binding.profileGroup)
            } else {
                if (currentAvcProfile !in profiles) {
                    currentAvcProfile = profiles.first()
                }
                profiles.forEach { profile ->
                    addProfileRadioButton(avcProfileName(profile), profile)
                }
                binding.profileGroup.check(
                    profileRadioValues.entries.firstOrNull { it.value == currentAvcProfile }?.key
                        ?: binding.profileGroup.getChildAt(0)?.id
                        ?: -1
                )
            }
        } else {
            binding.profileTitleText.text = getString(R.string.h264_encode_hevc_profile_title)
            val profileLevels = currentEncoderProfileLevels()
            val profiles = profileLevels.map { it.profile }.distinct().sorted()
            if (profiles.isEmpty()) {
                currentHevcProfile = null
                addUnavailableRadioButton(binding.profileGroup)
            } else {
                if (currentHevcProfile !in profiles) {
                    currentHevcProfile = profiles.first()
                }
                profiles.forEach { profile ->
                    addProfileRadioButton(hevcProfileName(profile), profile)
                }
                binding.profileGroup.check(
                    profileRadioValues.entries.firstOrNull { it.value == currentHevcProfile }?.key
                        ?: binding.profileGroup.getChildAt(0)?.id
                        ?: -1
                )
            }
        }

        suppressProfileLevelCallbacks = false
        rebuildLevelControlsForCurrentProfile()
    }

    private fun rebuildLevelControlsForCurrentProfile() {
        val previousSuppressState = suppressProfileLevelCallbacks
        suppressProfileLevelCallbacks = true
        levelRadioValues.clear()
        binding.levelGroup.removeAllViews()

        if (currentCodec == VideoCodecOption.H264) {
            binding.levelTitleText.text = getString(R.string.h264_encode_level_title)
            val selectedProfile = currentAvcProfile
            val levels =
                if (selectedProfile == null) {
                    emptyList()
                } else {
                    currentEncoderProfileLevels()
                        .filter { it.profile == selectedProfile }
                        .map { it.level }
                        .distinct()
                        .sorted()
                }

            if (levels.isEmpty()) {
                currentAvcLevel = null
                addUnavailableRadioButton(binding.levelGroup)
            } else {
                if (currentAvcLevel !in levels) {
                    currentAvcLevel = levels.first()
                }
                levels.forEach { level ->
                    addLevelRadioButton(avcLevelName(level), level)
                }
                binding.levelGroup.check(
                    levelRadioValues.entries.firstOrNull { it.value == currentAvcLevel }?.key
                        ?: binding.levelGroup.getChildAt(0)?.id
                        ?: -1
                )
            }
        } else {
            binding.levelTitleText.text = getString(R.string.h264_encode_hevc_level_title)
            val selectedProfile = currentHevcProfile
            val levels =
                if (selectedProfile == null) {
                    emptyList()
                } else {
                    currentEncoderProfileLevels()
                        .filter { it.profile == selectedProfile }
                        .map { it.level }
                        .distinct()
                        .sorted()
                }

            if (levels.isEmpty()) {
                currentHevcLevel = null
                addUnavailableRadioButton(binding.levelGroup)
            } else {
                if (currentHevcLevel !in levels) {
                    currentHevcLevel = levels.first()
                }
                levels.forEach { level ->
                    addLevelRadioButton(hevcLevelName(level), level)
                }
                binding.levelGroup.check(
                    levelRadioValues.entries.firstOrNull { it.value == currentHevcLevel }?.key
                        ?: binding.levelGroup.getChildAt(0)?.id
                        ?: -1
                )
            }
        }

        suppressProfileLevelCallbacks = previousSuppressState
    }

    private fun addProfileRadioButton(label: String, profile: Int) {
        val id = addRadioButton(binding.profileGroup, label)
        profileRadioValues[id] = profile
    }

    private fun addLevelRadioButton(label: String, level: Int) {
        val id = addRadioButton(binding.levelGroup, label)
        levelRadioValues[id] = level
    }

    private fun addRadioButton(group: android.widget.RadioGroup, label: String): Int {
        val id = View.generateViewId()
        group.addView(
            RadioButton(this).apply {
                this.id = id
                text = label
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    android.widget.RadioGroup.LayoutParams.MATCH_PARENT,
                    android.widget.RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (group.childCount > 0) {
                        topMargin = dp(4)
                    }
                }
            }
        )
        return id
    }

    private fun addUnavailableRadioButton(group: android.widget.RadioGroup) {
        group.addView(
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.h264_encode_profile_level_unavailable)
                isEnabled = false
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    android.widget.RadioGroup.LayoutParams.MATCH_PARENT,
                    android.widget.RadioGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )
    }

    private fun updateStaticInfo() {
        binding.codecText.text =
            getString(
                R.string.h264_encode_codec_value,
                currentCodecLabel(),
                currentCodec.mimeType
            )
        binding.resolutionText.text =
            getString(
                R.string.h264_encode_resolution_value,
                currentResolution.width,
                currentResolution.height,
                encodeFrameRate
            )
        binding.bitrateText.text =
            getString(R.string.h264_encode_bitrate_value, currentBitrate.bitrate)
        binding.profileTitleText.visibility = View.VISIBLE
        binding.profileGroup.visibility = View.VISIBLE
        binding.levelTitleText.visibility = View.VISIBLE
        binding.levelGroup.visibility = View.VISIBLE
        binding.profileText.visibility = View.VISIBLE
        binding.levelText.visibility = View.VISIBLE
        if (currentCodec == VideoCodecOption.H264) {
            binding.profileText.text =
                getString(
                    R.string.h264_encode_profile_value,
                    currentAvcProfile?.let(::avcProfileName)
                        ?: getString(R.string.h264_encode_profile_level_unavailable)
                )
            binding.levelText.text =
                getString(
                    R.string.h264_encode_level_value,
                    currentAvcLevel?.let(::avcLevelName)
                        ?: getString(R.string.h264_encode_profile_level_unavailable)
                )
        } else {
            binding.profileText.text =
                getString(
                    R.string.h264_encode_profile_value,
                    currentHevcProfile?.let(::hevcProfileName)
                        ?: getString(R.string.h264_encode_profile_level_unavailable)
                )
            binding.levelText.text =
                getString(
                    R.string.h264_encode_level_value,
                    currentHevcLevel?.let(::hevcLevelName)
                        ?: getString(R.string.h264_encode_profile_level_unavailable)
                )
        }
        binding.iframeIntervalText.text =
            getString(R.string.h264_encode_iframe_value, currentIFrameInterval.seconds)
        binding.frameCountText.text =
            getString(R.string.h264_encode_frame_count_value, 0)
        binding.outputPathText.text =
            getString(
                R.string.h264_encode_output_path_value,
                getString(R.string.h264_encode_output_path_empty)
            )
        binding.probeInfoText.text = getString(R.string.h264_encode_probe_empty)
        latestAnalyzableFile = null
        latestAnalyzableCodec = null
        binding.openFullAnalyzerButton.isEnabled = false
    }

    private fun openFullAnalyzer() {
        val file = latestAnalyzableFile
        if (file == null || !file.exists()) {
            binding.openFullAnalyzerButton.isEnabled = false
            updateStatus(getString(R.string.h264_encode_probe_open_full_missing))
            return
        }
        val analyzerCodec = latestAnalyzableCodec ?: currentCodec
        val intent =
            when (analyzerCodec) {
                VideoCodecOption.H264 -> H264StreamAnalyzerActivity.createIntent(this, file.absolutePath)
                VideoCodecOption.H265 -> H265StreamAnalyzerActivity.createIntent(this, file.absolutePath)
            }
        startActivity(intent)
    }

    private fun updatePermissionUi() {
        val granted = hasCameraPermission()
        binding.permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.switchCameraButton.isEnabled = granted
        binding.startButton.isEnabled = granted && !isEncoding
        binding.stopButton.isEnabled = granted && isEncoding
        setParameterControlsEnabled(!isEncoding)
        binding.previewPlaceholder.visibility = if (granted) View.VISIBLE else View.VISIBLE
        binding.previewPlaceholder.text =
            if (granted) {
                getString(R.string.h264_encode_status_idle)
            } else {
                getString(R.string.h264_encode_status_permission_required)
            }
        if (!granted) {
            updateStatus(getString(R.string.h264_encode_status_permission_required))
        } else if (!isEncoding) {
            updateStatus(getString(R.string.h264_encode_status_idle))
        }
    }

    private fun showPermissionRequiredState(messageRes: Int) {
        binding.permissionButton.visibility = View.VISIBLE
        binding.switchCameraButton.isEnabled = false
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = false
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.previewPlaceholder.text = getString(messageRes)
        updateStatus(getString(messageRes))
    }

    private fun updateStatus(text: String) {
        Log.d(TAG, "updateStatus text=$text")
        binding.statusText.text = text
    }

    private fun setParameterControlsEnabled(enabled: Boolean) {
        binding.codecGroup.isEnabled = enabled
        binding.resolutionGroup.isEnabled = enabled
        binding.bitrateGroup.isEnabled = enabled
        binding.profileGroup.isEnabled = enabled
        binding.levelGroup.isEnabled = enabled
        binding.iframeGroup.isEnabled = enabled
        for (index in 0 until binding.codecGroup.childCount) {
            binding.codecGroup.getChildAt(index).isEnabled = enabled
        }
        for (index in 0 until binding.resolutionGroup.childCount) {
            binding.resolutionGroup.getChildAt(index).isEnabled = enabled
        }
        for (index in 0 until binding.bitrateGroup.childCount) {
            binding.bitrateGroup.getChildAt(index).isEnabled = enabled
        }
        for (index in 0 until binding.profileGroup.childCount) {
            val child = binding.profileGroup.getChildAt(index)
            child.isEnabled = enabled && profileRadioValues.containsKey(child.id)
        }
        for (index in 0 until binding.levelGroup.childCount) {
            val child = binding.levelGroup.getChildAt(index)
            child.isEnabled = enabled && levelRadioValues.containsKey(child.id)
        }
        for (index in 0 until binding.iframeGroup.childCount) {
            binding.iframeGroup.getChildAt(index).isEnabled = enabled
        }
    }

    private fun currentLensLabel(): String {
        return getString(
            if (currentLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                R.string.camera_preview_front
            } else {
                R.string.camera_preview_back
            }
        )
    }

    private fun findCameraId(lensFacing: Int): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == lensFacing
        }
    }

    private fun choosePreviewSize(sizes: Array<Size>, width: Int, height: Int): Size {
        if (sizes.isEmpty()) {
            return Size(currentResolution.width, currentResolution.height)
        }
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val targetRatio = safeWidth.toFloat() / safeHeight.toFloat()
        return sizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .minWithOrNull(
                compareBy<Size> { abs((it.width.toFloat() / it.height.toFloat()) - targetRatio) }
                    .thenBy { abs(it.width - safeWidth) + abs(it.height - safeHeight) }
            ) ?: sizes.first()
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val size = previewSize ?: return
        val textureView = binding.previewTexture
        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewHeight.toFloat() / size.height.toFloat(),
                viewWidth.toFloat() / size.width.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }

        textureView.setTransform(matrix)
    }

    private fun createOutputFile(): File {
        val videoDir = File(filesDir, "video").apply { mkdirs() }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName =
            "video_" +
                currentCodec.fileTag +
                "_" +
                resolutionTag() +
                "_" +
                bitrateTag() +
                profileLevelFileTag() +
                "_" +
                iframeTag() +
                "_" +
                formatter.format(Date()) +
                "." +
                currentCodec.fileExtension
        return File(videoDir, fileName)
    }

    private fun resolutionTag(): String = when (currentResolution) {
        ResolutionOption.P540 -> "540p"
        ResolutionOption.P720 -> "720p"
        ResolutionOption.P1080 -> "1080p"
    }

    private fun bitrateTag(): String = when (currentBitrate) {
        BitrateOption.BR_1M -> "1m"
        BitrateOption.BR_2M -> "2m"
        BitrateOption.BR_4M -> "4m"
    }

    private fun profileLevelFileTag(): String {
        return when (currentCodec) {
            VideoCodecOption.H264 -> {
                val profile = currentAvcProfile
                val level = currentAvcLevel
                if (profile != null && level != null) {
                    "_avc_p${profile}_l${level}"
                } else {
                    ""
                }
            }
            VideoCodecOption.H265 -> {
                val profile = currentHevcProfile
                val level = currentHevcLevel
                if (profile != null && level != null) {
                    "_hevc_p${profile}_l${level}"
                } else {
                    ""
                }
            }
        }
    }

    private fun iframeTag(): String = "gop${currentIFrameInterval.seconds}s"

    private fun currentCodecLabel(): String = getString(currentCodec.labelRes)

    private fun currentEncoderProfileLevels(): List<MediaCodecInfo.CodecProfileLevel> {
        val encoderInfo = findHardwareEncoder(
            currentCodec.mimeType,
            currentResolution.width,
            currentResolution.height
        ) ?: return emptyList()

        return runCatching {
            encoderInfo.getCapabilitiesForType(currentCodec.mimeType)
                .profileLevels
                .distinctBy { "${it.profile}:${it.level}" }
                .sortedWith(compareBy({ it.profile }, { it.level }))
        }.getOrDefault(emptyList())
    }

    private fun findHardwareEncoder(mimeType: String, width: Int, height: Int): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .asSequence()
            .filter { codecInfo ->
                codecInfo.isEncoder &&
                    codecInfo.supportedTypes.any { supportedType ->
                        supportedType.equals(mimeType, ignoreCase = true)
                    }
            }
            .filter { codecInfo -> codecInfo.supportsVideoSize(mimeType, width, height) }
            .firstOrNull(::isHardwareEncoder)
    }

    private fun MediaCodecInfo.supportsVideoSize(mimeType: String, width: Int, height: Int): Boolean {
        return runCatching {
            val videoCapabilities = getCapabilitiesForType(mimeType).videoCapabilities
            videoCapabilities == null || videoCapabilities.isSizeSupported(width, height)
        }.getOrDefault(false)
    }

    private fun isHardwareEncoder(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codecInfo.isHardwareAccelerated
        }

        val name = codecInfo.name.lowercase(Locale.US)
        return !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.") &&
            !name.contains(".sw.") &&
            !name.contains("software")
    }

    private fun avcProfileName(profile: Int): String {
        return when (profile) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
            MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "Constrained High"
            else -> "Profile $profile"
        }
    }

    private fun avcLevelName(level: Int): String {
        return when (level) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> "Level 1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> "Level 1b"
            MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> "Level 1.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> "Level 1.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> "Level 1.3"
            MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> "Level 2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> "Level 2.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> "Level 2.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> "Level 3"
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> "Level 3.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> "Level 3.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> "Level 4"
            MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> "Level 4.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> "Level 4.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> "Level 5"
            MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> "Level 5.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> "Level 5.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel6 -> "Level 6"
            MediaCodecInfo.CodecProfileLevel.AVCLevel61 -> "Level 6.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel62 -> "Level 6.2"
            else -> "Level $level"
        }
    }

    private fun hevcProfileName(profile: Int): String {
        return when (profile) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
            else -> "Profile $profile"
        }
    }

    private fun hevcLevelName(level: Int): String {
        return when (level) {
            1 -> "Main Tier Level 1"
            2 -> "High Tier Level 1"
            4 -> "Main Tier Level 2"
            8 -> "High Tier Level 2"
            16 -> "Main Tier Level 2.1"
            32 -> "High Tier Level 2.1"
            64 -> "Main Tier Level 3"
            128 -> "High Tier Level 3"
            256 -> "Main Tier Level 3.1"
            512 -> "High Tier Level 3.1"
            1024 -> "Main Tier Level 4"
            2048 -> "High Tier Level 4"
            4096 -> "Main Tier Level 4.1"
            8192 -> "High Tier Level 4.1"
            16384 -> "Main Tier Level 5"
            32768 -> "High Tier Level 5"
            65536 -> "Main Tier Level 5.1"
            131072 -> "High Tier Level 5.1"
            262144 -> "Main Tier Level 5.2"
            524288 -> "High Tier Level 5.2"
            1048576 -> "Main Tier Level 6"
            2097152 -> "High Tier Level 6"
            4194304 -> "Main Tier Level 6.1"
            8388608 -> "High Tier Level 6.1"
            16777216 -> "Main Tier Level 6.2"
            33554432 -> "High Tier Level 6.2"
            else -> "Level $level"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun postUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                action()
            }
        }
    }
}
