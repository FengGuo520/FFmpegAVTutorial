package com.lovelymaple.ffmpegavtutorial.basic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityVideoSoftEncodeBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class VideoSoftEncodeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoSoftEncodeActivity"
        private const val ENCODE_FRAME_RATE = 30
    }

    private enum class ResolutionOption(
        val width: Int,
        val height: Int,
        val labelRes: Int,
        val fileTag: String
    ) {
        P540(960, 540, R.string.h264_encode_resolution_540p, "540p"),
        P720(1280, 720, R.string.h264_encode_resolution_720p, "720p"),
        P1080(1920, 1080, R.string.h264_encode_resolution_1080p, "1080p")
    }

    private enum class BitrateOption(
        val bitrate: Int,
        val labelRes: Int,
        val fileTag: String
    ) {
        BR_1M(1_000_000, R.string.h264_encode_bitrate_1m, "1m"),
        BR_2M(2_000_000, R.string.h264_encode_bitrate_2m, "2m"),
        BR_4M(4_000_000, R.string.h264_encode_bitrate_4m, "4m")
    }

    private enum class ProfileOption(
        val profileTag: String,
        val labelRes: Int
    ) {
        BASELINE("baseline", R.string.h264_encode_profile_baseline),
        MAIN("main", R.string.h264_encode_profile_main),
        HIGH("high", R.string.h264_encode_profile_high)
    }

    private enum class IFrameIntervalOption(
        val seconds: Int,
        val labelRes: Int,
        val fileTag: String
    ) {
        SEC_1(1, R.string.h264_encode_iframe_1s, "gop1s"),
        SEC_2(2, R.string.h264_encode_iframe_2s, "gop2s"),
        SEC_5(5, R.string.h264_encode_iframe_5s, "gop5s")
    }

    private lateinit var binding: ActivityVideoSoftEncodeBinding
    private lateinit var nativeInstance: NativeInstance

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var captureSize: Size? = null
    private var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var isOpeningCamera = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null
    private var imageReader: ImageReader? = null

    @Volatile private var isEncoding = false
    @Volatile private var capturedFrameCount = 0
    @Volatile private var encodedFrameCount = 0
    @Volatile private var pendingStartEncoding = false
    private var latestOutputFile: File? = null
    private var latestAnalyzableFile: File? = null

    private var currentResolution = ResolutionOption.P720
    private var currentBitrate = BitrateOption.BR_2M
    private var currentProfile = ProfileOption.BASELINE
    private var currentIFrameInterval = IFrameIntervalOption.SEC_2

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "permission result granted=$granted")
            if (granted) {
                updatePermissionUi()
                openCameraWhenReady()
            } else {
                showPermissionRequiredState(R.string.video_soft_encode_status_permission_denied)
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

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val captureCount = ++capturedFrameCount
            if (!isEncoding) {
                if (captureCount % 15 == 0) {
                    postUi { updateRuntimeSnapshot() }
                }
                return@OnImageAvailableListener
            }

            val i420Bytes = imageToI420(image)
            val encodedBytes =
                if (image.width == currentResolution.width && image.height == currentResolution.height) {
                    i420Bytes
                } else {
                    scaleI420(
                        i420Bytes,
                        image.width,
                        image.height,
                        currentResolution.width,
                        currentResolution.height
                    )
                }
            val result =
                nativeInstance.writeSoftVideoFrame(
                    encodedBytes,
                    currentResolution.width,
                    currentResolution.height,
                    image.timestamp / 1000L
                )
            if (result < 0) {
                Log.e(
                    TAG,
                    "writeSoftVideoFrame failed result=$result width=${image.width} height=${image.height} " +
                        "target=${currentResolution.width}x${currentResolution.height} " +
                        "capture=${captureSize?.width}x${captureSize?.height} " +
                        "reader=${imageReader?.width}x${imageReader?.height}"
                )
                handleEncodingError("writeSoftVideoFrame failed: $result")
                return@OnImageAvailableListener
            }

            val encodedCount = ++encodedFrameCount
            if (encodedCount % 10 == 0) {
                Log.d(
                    TAG,
                    "soft encode progress captured=$captureCount encoded=$encodedCount resolution=${image.width}x${image.height}"
                )
                postUi { updateRuntimeSnapshot() }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "imageAvailableListener failed", exception)
            handleEncodingError(exception.message ?: "soft video encoding error")
        } finally {
            image.close()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "camera onOpened id=${camera.id}")
            isOpeningCamera = false
            cameraDevice = camera
            postUi { createCameraSession() }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "camera onDisconnected id=${camera.id}")
            isOpeningCamera = false
            camera.close()
            cameraDevice = null
            postUi {
                binding.previewPlaceholder.visibility = View.VISIBLE
                updateStatus(getString(R.string.video_soft_encode_status_error, "camera disconnected"))
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "camera onError id=${camera.id} error=$error")
            isOpeningCamera = false
            camera.close()
            cameraDevice = null
            postUi {
                binding.previewPlaceholder.visibility = View.VISIBLE
                updateStatus(getString(R.string.video_soft_encode_status_error, "camera error $error"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoSoftEncodeBinding.inflate(layoutInflater)
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

        binding.resolutionGroup.setOnCheckedChangeListener { _, checkedId ->
            currentResolution =
                when (checkedId) {
                    binding.resolution540Radio.id -> ResolutionOption.P540
                    binding.resolution1080Radio.id -> ResolutionOption.P1080
                    else -> ResolutionOption.P720
                }
            updateStaticInfo()
            restartPreviewForParameterChange()
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
            currentProfile =
                when (checkedId) {
                    binding.profileMainRadio.id -> ProfileOption.MAIN
                    binding.profileHighRadio.id -> ProfileOption.HIGH
                    else -> ProfileOption.BASELINE
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

        updateStaticInfo()
        updatePermissionUi()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        startEncodeThread()
        if (binding.previewTexture.isAvailable) {
            openCameraWhenReady()
        } else {
            binding.previewTexture.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        stopEncoding()
        closeCamera()
        stopEncodeThread()
        stopBackgroundThread()
        super.onPause()
    }

    private fun ensurePermissionAndStartPreview() {
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

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("SoftVideoCameraThread").also { thread ->
            thread.start()
            backgroundHandler = Handler(thread.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun startEncodeThread() {
        if (encodeThread != null) return
        encodeThread = HandlerThread("SoftVideoEncodeThread").also { thread ->
            thread.start()
            encodeHandler = Handler(thread.looper)
        }
    }

    private fun stopEncodeThread() {
        imageReader?.setOnImageAvailableListener(null, null)
        encodeThread?.quitSafely()
        encodeThread?.join()
        encodeThread = null
        encodeHandler = null
    }

    private fun openCameraWhenReady(forceReopen: Boolean = false) {
        Log.d(
            TAG,
            "openCameraWhenReady forceReopen=$forceReopen hasPermission=${hasCameraPermission()} " +
                "textureAvailable=${binding.previewTexture.isAvailable} cameraDeviceNull=${cameraDevice == null} " +
                "isOpeningCamera=$isOpeningCamera handlerNull=${backgroundHandler == null}"
        )
        if (!hasCameraPermission()) {
            showPermissionRequiredState(R.string.video_soft_encode_status_permission_required)
            return
        }
        if (!binding.previewTexture.isAvailable || backgroundHandler == null || encodeHandler == null) {
            updateStatus(getString(R.string.video_soft_encode_status_idle))
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
            return
        }
        openCamera()
    }

    private fun openCamera() {
        try {
            val cameraId = findCameraId(currentLensFacing)
            Log.d(TAG, "openCamera lensFacing=$currentLensFacing cameraId=$cameraId")
            if (cameraId == null) {
                val message = getString(R.string.video_soft_encode_status_no_camera, currentLensLabel())
                binding.previewPlaceholder.visibility = View.VISIBLE
                binding.previewPlaceholder.text = message
                updateStatus(message)
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: run {
                        updateStatus(getString(R.string.video_soft_encode_status_error, "preview output unavailable"))
                        return
                    }
            previewSize =
                choosePreviewSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    binding.previewTexture.width,
                    binding.previewTexture.height
                )
            captureSize =
                chooseCaptureSize(
                    map.getOutputSizes(ImageFormat.YUV_420_888),
                    currentResolution.width,
                    currentResolution.height
                )
            Log.d(
                TAG,
                "openCamera previewSize=${previewSize?.width}x${previewSize?.height} " +
                    "captureSize=${captureSize?.width}x${captureSize?.height} " +
                    "target=${currentResolution.width}x${currentResolution.height}"
            )
            configureTransform(binding.previewTexture.width, binding.previewTexture.height)
            val openingText =
                if (isEncoding) {
                    getString(R.string.video_soft_encode_status_starting)
                } else {
                    getString(R.string.video_soft_encode_status_opening, currentLensLabel())
                }
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text = openingText
            updateStatus(openingText)
            isOpeningCamera = true
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (exception: CameraAccessException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera CameraAccessException", exception)
            updateStatus(getString(R.string.video_soft_encode_status_error, exception.message ?: "camera access error"))
        } catch (exception: SecurityException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera SecurityException", exception)
            showPermissionRequiredState(R.string.video_soft_encode_status_permission_required)
        }
    }

    private fun createCameraSession() {
        val texture = binding.previewTexture.surfaceTexture
        val camera = cameraDevice
        val size = previewSize
        val targetCaptureSize = captureSize
        if (texture == null || camera == null || size == null || targetCaptureSize == null || encodeHandler == null) {
            return
        }

        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null

            texture.setDefaultBufferSize(size.width, size.height)
            prepareImageReader()
            Log.d(
                TAG,
                "createCameraSession preview=${size.width}x${size.height} " +
                    "capture=${imageReader?.width}x${imageReader?.height} " +
                    "captureTarget=${targetCaptureSize.width}x${targetCaptureSize.height} " +
                    "encodeTarget=${currentResolution.width}x${currentResolution.height}"
            )
            val previewSurface = Surface(texture)
            val captureSurface = imageReader?.surface ?: return
            previewRequestBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    addTarget(captureSurface)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                }

            camera.createCaptureSession(
                listOf(previewSurface, captureSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        cameraCaptureSession = session
                        try {
                            session.setRepeatingRequest(
                                previewRequestBuilder!!.build(),
                                null,
                                backgroundHandler
                            )
                            postUi {
                                binding.previewPlaceholder.visibility = View.GONE
                                if (pendingStartEncoding && isCurrentCaptureResolutionReady()) {
                                    Log.d(
                                        TAG,
                                        "capture session ready for pending start: " +
                                            "${imageReader?.width}x${imageReader?.height}"
                                    )
                                    pendingStartEncoding = false
                                    startEncodingInternal()
                                } else {
                                    updateStatus(
                                        if (isEncoding) {
                                            getString(R.string.video_soft_encode_status_encoding)
                                        } else {
                                            getString(
                                                R.string.video_soft_encode_status_previewing,
                                                currentLensLabel()
                                            )
                                        }
                                    )
                                }
                            }
                        } catch (exception: CameraAccessException) {
                            postUi {
                                updateStatus(
                                    getString(
                                        R.string.video_soft_encode_status_error,
                                        exception.message ?: "session request failed"
                                    )
                                )
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        postUi {
                            updateStatus(
                                getString(
                                    R.string.video_soft_encode_status_error,
                                    "camera session configure failed"
                                )
                            )
                        }
                    }
                },
                backgroundHandler
            )
        } catch (exception: CameraAccessException) {
            Log.e(TAG, "createCameraSession CameraAccessException", exception)
            updateStatus(getString(R.string.video_soft_encode_status_error, exception.message ?: "camera session error"))
        }
    }

    private fun prepareImageReader() {
        val targetCaptureSize = captureSize ?: Size(currentResolution.width, currentResolution.height)
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader =
            ImageReader.newInstance(
                targetCaptureSize.width,
                targetCaptureSize.height,
                ImageFormat.YUV_420_888,
                3
            ).apply {
                Log.d(TAG, "prepareImageReader width=$width height=$height")
                setOnImageAvailableListener(imageAvailableListener, encodeHandler)
            }
    }

    private fun closeCamera() {
        isOpeningCamera = false
        imageReader?.setOnImageAvailableListener(null, null)
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
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

    private fun startEncoding() {
        if (!hasCameraPermission()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (isEncoding) return
        if (cameraDevice == null) {
            pendingStartEncoding = true
            updateStatus(getString(R.string.video_soft_encode_status_wait_preview))
            openCameraWhenReady()
            return
        }
        if (!isCurrentCaptureResolutionReady()) {
            pendingStartEncoding = true
            Log.d(
                TAG,
                "startEncoding waits for capture size target=${captureSize?.width}x${captureSize?.height} " +
                    "reader=${imageReader?.width}x${imageReader?.height} encodeTarget=${currentResolution.width}x${currentResolution.height}"
            )
            updateStatus(getString(R.string.video_soft_encode_status_reconfiguring_capture))
            createCameraSession()
            return
        }
        pendingStartEncoding = false
        startEncodingInternal()
    }

    private fun startEncodingInternal() {
        if (isEncoding) return
        if (!isCurrentCaptureResolutionReady()) {
            updateStatus(getString(R.string.video_soft_encode_status_reconfiguring_capture))
            return
        }

        val outputFile = createOutputFile()
        val result =
            nativeInstance.openSoftVideoEncoder(
                outputFile.absolutePath,
                currentResolution.width,
                currentResolution.height,
                ENCODE_FRAME_RATE,
                currentBitrate.bitrate,
                currentProfile.profileTag,
                currentIFrameInterval.seconds
            )
        if (!result.startsWith("OK:")) {
            updateStatus(getString(R.string.video_soft_encode_status_error, result))
            return
        }

        Log.d(
            TAG,
            "startEncodingInternal encodeTarget=${currentResolution.width}x${currentResolution.height} " +
                "capture=${captureSize?.width}x${captureSize?.height} " +
                "reader=${imageReader?.width}x${imageReader?.height} output=${outputFile.absolutePath}"
        )
        latestOutputFile = outputFile
        latestAnalyzableFile = null
        capturedFrameCount = 0
        encodedFrameCount = 0
        isEncoding = true
        binding.openFullAnalyzerButton.isEnabled = false
        binding.probeInfoText.text = getString(R.string.video_soft_encode_probe_empty)
        updateStatus(getString(R.string.video_soft_encode_status_encoding))
        setParameterControlsEnabled(false)
        updatePermissionUi()
        updateRuntimeSnapshot()
    }

    private fun stopEncoding(probeAfterStop: Boolean = true, forcedError: String? = null) {
        val wasEncoding = isEncoding
        isEncoding = false
        pendingStartEncoding = false

        if (wasEncoding) {
            val closeResult = nativeInstance.closeSoftVideoEncoder()
            Log.d(TAG, "closeSoftVideoEncoder result=$closeResult")
        }

        setParameterControlsEnabled(true)
        updatePermissionUi()
        updateRuntimeSnapshot()

        if (forcedError != null) {
            binding.probeInfoText.text = forcedError
            updateStatus(getString(R.string.video_soft_encode_status_error, forcedError))
            return
        }

        if (wasEncoding) {
            updateStatus(getString(R.string.video_soft_encode_status_stopped))
        }

        val outputFile = latestOutputFile
        if (wasEncoding && probeAfterStop && outputFile != null && outputFile.exists()) {
            probeOutputFile(outputFile)
        }
    }

    private fun probeOutputFile(file: File) {
        latestAnalyzableFile = file
        binding.probeInfoText.text = getString(R.string.video_soft_encode_probe_loading)
        thread(name = "soft-video-probe") {
            val probeInfo = nativeInstance.analyzeH264Stream(file.absolutePath)
            Log.d(TAG, "Soft video probe info:\n$probeInfo")
            postUi {
                binding.probeInfoText.text = probeInfo
                binding.openFullAnalyzerButton.isEnabled = !probeInfo.startsWith("ERROR:")
                if (probeInfo.startsWith("ERROR:")) {
                    updateStatus(getString(R.string.video_soft_encode_status_error, probeInfo))
                }
            }
        }
    }

    private fun openFullAnalyzer() {
        val file = latestAnalyzableFile
        if (file == null || !file.exists()) {
            binding.openFullAnalyzerButton.isEnabled = false
            updateStatus(getString(R.string.h264_encode_probe_open_full_missing))
            return
        }
        startActivity(H264StreamAnalyzerActivity.createIntent(this, file.absolutePath))
    }

    private fun handleEncodingError(message: String) {
        if (!isEncoding) return
        postUi {
            if (!isEncoding) return@postUi
            stopEncoding(probeAfterStop = false, forcedError = message)
        }
    }

    private fun restartPreviewForParameterChange() {
        if (isEncoding || !hasCameraPermission()) return
        if (cameraDevice != null && binding.previewTexture.isAvailable && backgroundHandler != null) {
            pendingStartEncoding = false
            openCameraWhenReady(forceReopen = true)
        }
    }

    private fun isCurrentCaptureResolutionReady(): Boolean {
        return imageReader?.width == captureSize?.width &&
            imageReader?.height == captureSize?.height
    }

    private fun chooseCaptureSize(
        sizes: Array<Size>,
        targetWidth: Int,
        targetHeight: Int
    ): Size {
        Log.d(
            TAG,
            buildString {
                append("chooseCaptureSize target=")
                append(targetWidth)
                append("x")
                append(targetHeight)
                append(" candidates=")
                if (sizes.isEmpty()) {
                    append("[]")
                } else {
                    append(
                        sizes.joinToString(
                            prefix = "[",
                            postfix = "]"
                        ) { size -> "${size.width}x${size.height}" }
                    )
                }
            }
        )
        if (sizes.isEmpty()) {
            val fallback = Size(targetWidth, targetHeight)
            Log.d(TAG, "chooseCaptureSize fallback=${fallback.width}x${fallback.height}")
            return fallback
        }
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val exact = sizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }
        if (exact != null) {
            Log.d(TAG, "chooseCaptureSize exact match=${exact.width}x${exact.height}")
            return exact
        }

        val sameRatioCandidates =
            sizes.filter { size ->
                abs((size.width.toFloat() / size.height.toFloat()) - targetRatio) < 0.02f
            }
        val candidates = if (sameRatioCandidates.isNotEmpty()) sameRatioCandidates else sizes.toList()
        val selected = candidates.minWithOrNull(
            compareBy<Size> { abs(it.width - targetWidth) + abs(it.height - targetHeight) }
                .thenBy { abs((it.width.toFloat() / it.height.toFloat()) - targetRatio) }
        ) ?: sizes.first()
        Log.d(
            TAG,
            "chooseCaptureSize selected=${selected.width}x${selected.height} " +
                "sameRatioCandidates=" +
                sameRatioCandidates.joinToString(
                    prefix = "[",
                    postfix = "]"
                ) { size -> "${size.width}x${size.height}" }
        )
        return selected
    }

    private fun updateStaticInfo() {
        binding.codecText.text =
            getString(
                R.string.video_soft_encode_codec_value,
                "FFmpeg libx264",
                "video/avc"
            )
        binding.captureText.text =
            getString(
                R.string.video_soft_encode_capture_value,
                currentResolution.width,
                currentResolution.height,
                ENCODE_FRAME_RATE
            )
        binding.bitrateText.text =
            getString(R.string.h264_encode_bitrate_value, currentBitrate.bitrate)
        binding.profileText.text =
            getString(R.string.h264_encode_profile_value, getString(currentProfile.labelRes))
        binding.iframeIntervalText.text =
            getString(R.string.h264_encode_iframe_value, currentIFrameInterval.seconds)
        updateRuntimeSnapshot()
        if (latestOutputFile == null) {
            binding.outputPathText.text =
                getString(
                    R.string.video_soft_encode_output_path_value,
                    getString(R.string.h264_encode_output_path_empty)
                )
        }
    }

    private fun updateRuntimeSnapshot() {
        binding.inputFormatText.text = getString(R.string.video_soft_encode_input_format_value, "YUV_420_888 -> I420")
        binding.frameCountText.text =
            getString(
                R.string.video_soft_encode_frame_count_value,
                capturedFrameCount,
                encodedFrameCount
            )
        binding.outputPathText.text =
            getString(
                R.string.video_soft_encode_output_path_value,
                latestOutputFile?.absolutePath ?: getString(R.string.h264_encode_output_path_empty)
            )
    }

    private fun updatePermissionUi() {
        val granted = hasCameraPermission()
        binding.permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.switchCameraButton.isEnabled = granted
        binding.startButton.isEnabled = granted && !isEncoding
        binding.stopButton.isEnabled = granted && isEncoding
        if (!granted) {
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text =
                getString(R.string.video_soft_encode_status_permission_required)
            updateStatus(getString(R.string.video_soft_encode_status_permission_required))
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

    private fun setParameterControlsEnabled(enabled: Boolean) {
        for (group in listOf(binding.resolutionGroup, binding.bitrateGroup, binding.profileGroup, binding.iframeGroup)) {
            group.isEnabled = enabled
            for (index in 0 until group.childCount) {
                group.getChildAt(index).isEnabled = enabled
            }
        }
    }

    private fun updateStatus(text: String) {
        Log.d(TAG, "updateStatus text=$text")
        binding.statusText.text = text
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

    private fun imageToI420(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * 3 / 2)
        var offset = 0
        offset = copyPlaneToByteArray(image.planes[0], width, height, output, offset)
        offset = copyPlaneToByteArray(image.planes[1], width / 2, height / 2, output, offset)
        copyPlaneToByteArray(image.planes[2], width / 2, height / 2, output, offset)
        return output
    }

    private fun copyPlaneToByteArray(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int
    ): Int {
        val buffer = plane.buffer.duplicate().apply { rewind() }
        var offset = outputOffset
        for (row in 0 until height) {
            val rowStart = row * plane.rowStride
            for (column in 0 until width) {
                output[offset++] = buffer.get(rowStart + column * plane.pixelStride)
            }
        }
        return offset
    }

    private fun scaleI420(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray {
        val output = ByteArray(targetWidth * targetHeight * 3 / 2)
        scalePlane(
            source = source,
            sourceOffset = 0,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            target = output,
            targetOffset = 0,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )

        val sourceYSize = sourceWidth * sourceHeight
        val targetYSize = targetWidth * targetHeight
        val sourceChromaWidth = sourceWidth / 2
        val sourceChromaHeight = sourceHeight / 2
        val targetChromaWidth = targetWidth / 2
        val targetChromaHeight = targetHeight / 2
        val sourceUOffset = sourceYSize
        val sourceVOffset = sourceYSize + sourceChromaWidth * sourceChromaHeight
        val targetUOffset = targetYSize
        val targetVOffset = targetYSize + targetChromaWidth * targetChromaHeight

        scalePlane(
            source = source,
            sourceOffset = sourceUOffset,
            sourceWidth = sourceChromaWidth,
            sourceHeight = sourceChromaHeight,
            target = output,
            targetOffset = targetUOffset,
            targetWidth = targetChromaWidth,
            targetHeight = targetChromaHeight
        )
        scalePlane(
            source = source,
            sourceOffset = sourceVOffset,
            sourceWidth = sourceChromaWidth,
            sourceHeight = sourceChromaHeight,
            target = output,
            targetOffset = targetVOffset,
            targetWidth = targetChromaWidth,
            targetHeight = targetChromaHeight
        )
        return output
    }

    private fun scalePlane(
        source: ByteArray,
        sourceOffset: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        target: ByteArray,
        targetOffset: Int,
        targetWidth: Int,
        targetHeight: Int
    ) {
        for (targetY in 0 until targetHeight) {
            val sourceY = targetY * sourceHeight / targetHeight
            for (targetX in 0 until targetWidth) {
                val sourceX = targetX * sourceWidth / targetWidth
                target[targetOffset + targetY * targetWidth + targetX] =
                    source[sourceOffset + sourceY * sourceWidth + sourceX]
            }
        }
    }

    private fun createOutputFile(): File {
        val videoDir = File(filesDir, "video_soft").apply { mkdirs() }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName =
            "video_soft_x264_" +
                currentResolution.fileTag +
                "_" +
                currentBitrate.fileTag +
                "_" +
                currentProfile.profileTag +
                "_" +
                currentIFrameInterval.fileTag +
                "_" +
                formatter.format(Date()) +
                ".h264"
        return File(videoDir, fileName)
    }

    private fun postUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                action()
            }
        }
    }
}
