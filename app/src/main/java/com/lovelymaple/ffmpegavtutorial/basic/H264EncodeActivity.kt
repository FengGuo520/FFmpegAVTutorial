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
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityH264EncodeBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class H264EncodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityH264EncodeBinding

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private val encodeWidth = 1280
    private val encodeHeight = 720
    private val encodeFrameRate = 30
    private val encodeBitrate = 2_000_000

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderOutputFile: File? = null
    private var encoderOutputStream: FileOutputStream? = null
    @Volatile private var isEncoding = false
    @Volatile private var encodedFrameCount = 0
    private var drainThread: Thread? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updatePermissionUi()
                openCameraWhenReady()
            } else {
                showPermissionRequiredState(R.string.h264_encode_status_permission_denied)
            }
        }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCameraWhenReady()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            postUi {
                createCameraSession()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            postUi {
                binding.previewPlaceholder.visibility = View.VISIBLE
                updateStatus(getString(R.string.h264_encode_status_error, "camera disconnected"))
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
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

        binding = ActivityH264EncodeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { ensurePermissionAndStartPreview() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.startButton.setOnClickListener { startEncoding() }
        binding.stopButton.setOnClickListener { stopEncoding() }
        binding.previewTexture.surfaceTextureListener = surfaceTextureListener

        updateStaticInfo()
        updatePermissionUi()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.previewTexture.isAvailable) {
            openCameraWhenReady()
        } else {
            binding.previewTexture.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        stopEncoding()
        closeCamera()
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

    private fun openCameraWhenReady(forceReopen: Boolean = false) {
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
        openCamera()
    }

    private fun openCamera() {
        try {
            val cameraId = findCameraId(currentLensFacing)
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
            configureTransform(binding.previewTexture.width, binding.previewTexture.height)
            val message = getString(R.string.h264_encode_status_opening, currentLensLabel())
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text = message
            updateStatus(message)
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (exception: CameraAccessException) {
            updateStatus(getString(R.string.h264_encode_status_error, exception.message ?: "camera access error"))
        } catch (exception: SecurityException) {
            showPermissionRequiredState(R.string.h264_encode_status_permission_required)
        }
    }

    private fun createCameraSession() {
        val texture = binding.previewTexture.surfaceTexture ?: return
        val camera = cameraDevice ?: return
        val size = previewSize ?: return

        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null

            texture.setDefaultBufferSize(size.width, size.height)
            val previewSurface = Surface(texture)
            val surfaces = mutableListOf(previewSurface)

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
                                updateStatus(
                                    if (isEncoding) {
                                        getString(R.string.h264_encode_status_encoding)
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
            binding.frameCountText.text =
                getString(R.string.h264_encode_frame_count_value, encodedFrameCount)
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = true
            createCameraSession()
            startDrainThread()
            updateStatus(getString(R.string.h264_encode_status_encoding))
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
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun setupEncoder() {
        val outputFile = createOutputFile()
        encoderOutputFile = outputFile
        encoderOutputStream = FileOutputStream(outputFile)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, encodeBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, encodeFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
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
        drainThread = thread(start = true, name = "H264DrainThread") {
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
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val bytes = ByteArray(bufferInfo.size)
                                outputBuffer.get(bytes)
                                outputStream.write(bytes)
                                encodedFrameCount++
                                postUi {
                                    binding.frameCountText.text =
                                        getString(
                                            R.string.h264_encode_frame_count_value,
                                            encodedFrameCount
                                        )
                                }
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
        listOf("csd-0", "csd-1").forEach { key ->
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

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("H264EncodeCameraThread").also { thread ->
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

    private fun updateStaticInfo() {
        binding.resolutionText.text =
            getString(R.string.h264_encode_resolution_value, encodeWidth, encodeHeight, encodeFrameRate)
        binding.bitrateText.text =
            getString(R.string.h264_encode_bitrate_value, encodeBitrate)
        binding.frameCountText.text =
            getString(R.string.h264_encode_frame_count_value, 0)
        binding.outputPathText.text =
            getString(
                R.string.h264_encode_output_path_value,
                getString(R.string.h264_encode_output_path_empty)
            )
    }

    private fun updatePermissionUi() {
        val granted = hasCameraPermission()
        binding.permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.switchCameraButton.isEnabled = granted
        binding.startButton.isEnabled = granted && !isEncoding
        binding.stopButton.isEnabled = granted && isEncoding
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
            return Size(encodeWidth, encodeHeight)
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
        return File(videoDir, "video_${formatter.format(Date())}.h264")
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
