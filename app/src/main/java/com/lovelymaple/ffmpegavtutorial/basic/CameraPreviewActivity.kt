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
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityCameraPreviewBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import java.util.Collections
import kotlin.math.abs

class CameraPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraPreviewActivity"
        const val EXTRA_CAMERA_ID = "extra_camera_id"
    }

    private lateinit var binding: ActivityCameraPreviewBinding

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var selectedCameraIdOverride: String? = null
    @Volatile private var isOpeningCamera = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "permission result granted=$granted textureAvailable=${binding.previewTexture.isAvailable}")
            if (granted) {
                binding.previewPlaceholder.text = getString(R.string.camera_preview_status_idle)
                if (backgroundHandler == null) {
                    Log.d(TAG, "permission granted but backgroundHandler is null, wait for onResume")
                } else {
                    openCameraWhenReady()
                }
            } else {
                showPermissionRequiredState(R.string.camera_preview_status_permission_denied)
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
            runOnUiThread {
                createCameraPreviewSession()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "camera onDisconnected id=${camera.id}")
            isOpeningCamera = false
            camera.close()
            cameraDevice = null
            postUiUpdate {
                updateStatus(getString(R.string.camera_preview_status_error, "camera disconnected"))
                binding.previewPlaceholder.visibility = View.VISIBLE
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "camera onError id=${camera.id} error=$error")
            isOpeningCamera = false
            camera.close()
            cameraDevice = null
            postUiUpdate {
                updateStatus(getString(R.string.camera_preview_status_error, "camera error $error"))
                binding.previewPlaceholder.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityCameraPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)
        selectedCameraIdOverride = intent.getStringExtra(EXTRA_CAMERA_ID)
        selectedCameraIdOverride?.let { cameraId ->
            runCatching {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()?.let { currentLensFacing = it }
        }

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { ensureCameraPermissionAndStart() }
        binding.retryButton.setOnClickListener { openCameraWhenReady(forceReopen = true) }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.previewTexture.surfaceTextureListener = surfaceTextureListener

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
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun ensureCameraPermissionAndStart() {
        Log.d(TAG, "ensureCameraPermissionAndStart hasPermission=${hasCameraPermission()}")
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
            showPermissionRequiredState(R.string.camera_preview_status_permission_required)
            return
        }
        if (!binding.previewTexture.isAvailable) {
            updateStatus(getString(R.string.camera_preview_status_idle))
            return
        }
        if (forceReopen) {
            closeCamera()
        }
        if (cameraDevice != null) {
            updateStatus(getString(R.string.camera_preview_status_previewing, currentLensLabel()))
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
            val selectedCameraId = selectedCameraIdOverride ?: findCameraId(currentLensFacing)
            Log.d(
                TAG,
                "openCamera lensFacing=$currentLensFacing selectedCameraId=$selectedCameraId " +
                    "selectedCameraIdOverride=$selectedCameraIdOverride"
            )
            if (selectedCameraId == null) {
                val message = getString(R.string.camera_preview_status_no_camera, currentLensLabel())
                updateStatus(message)
                binding.previewPlaceholder.text = message
                binding.previewPlaceholder.visibility = View.VISIBLE
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)
            val map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: run {
                        Log.e(TAG, "openCamera stream configuration map unavailable for cameraId=$selectedCameraId")
                        updateStatus(getString(R.string.camera_preview_status_error, "preview output unavailable"))
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

            val openingText = getString(R.string.camera_preview_status_opening, currentLensLabel())
            updateStatus(openingText)
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text = openingText

            isOpeningCamera = true
            Log.d(TAG, "calling cameraManager.openCamera cameraId=$selectedCameraId")
            cameraManager.openCamera(selectedCameraId, stateCallback, backgroundHandler)
        } catch (exception: CameraAccessException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera CameraAccessException", exception)
            updateStatus(getString(R.string.camera_preview_status_error, exception.message ?: "camera access error"))
        } catch (exception: SecurityException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera SecurityException", exception)
            showPermissionRequiredState(R.string.camera_preview_status_permission_required)
        }
    }

    private fun createCameraPreviewSession() {
        val texture = binding.previewTexture.surfaceTexture ?: return
        val camera = cameraDevice ?: return
        val size = previewSize ?: return
        Log.d(TAG, "createCameraPreviewSession cameraId=${camera.id} previewSize=${size.width}x${size.height}")

        try {
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(texture)
            Log.d(TAG, "createCameraPreviewSession surface created")

            previewRequestBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                }
            Log.d(TAG, "createCameraPreviewSession request builder prepared")

            camera.createCaptureSession(
                Collections.singletonList(surface),
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
                            postUiUpdate {
                                binding.previewPlaceholder.visibility = View.GONE
                                updateStatus(
                                    getString(
                                        R.string.camera_preview_status_previewing,
                                        currentLensLabel()
                                    )
                                )
                            }
                        } catch (exception: CameraAccessException) {
                            Log.e(TAG, "setRepeatingRequest CameraAccessException", exception)
                            postUiUpdate {
                                updateStatus(
                                    getString(
                                        R.string.camera_preview_status_error,
                                        exception.message ?: "failed to start preview"
                                    )
                                )
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "captureSession onConfigureFailed")
                        postUiUpdate {
                            updateStatus(
                                getString(
                                    R.string.camera_preview_status_error,
                                    "preview session configuration failed"
                                )
                            )
                        }
                    }
                },
                backgroundHandler
            )
        } catch (exception: CameraAccessException) {
            isOpeningCamera = false
            Log.e(TAG, "createCameraPreviewSession CameraAccessException", exception)
            updateStatus(getString(R.string.camera_preview_status_error, exception.message ?: "preview session error"))
        }
    }

    private fun switchCamera() {
        selectedCameraIdOverride = null
        currentLensFacing =
            if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
        Log.d(TAG, "switchCamera newLensFacing=$currentLensFacing")
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

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraPreviewThread").also { thread ->
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

    private fun updatePermissionUi() {
        val granted = hasCameraPermission()
        binding.permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.switchCameraButton.isEnabled = granted
        binding.retryButton.isEnabled = granted
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.previewPlaceholder.text =
            if (granted) {
                getString(R.string.camera_preview_status_idle)
            } else {
                getString(R.string.camera_preview_status_permission_required)
            }
        updateStatus(binding.previewPlaceholder.text.toString())
    }

    private fun showPermissionRequiredState(messageRes: Int) {
        binding.permissionButton.visibility = View.VISIBLE
        binding.switchCameraButton.isEnabled = false
        binding.retryButton.isEnabled = false
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.previewPlaceholder.text = getString(messageRes)
        updateStatus(getString(messageRes))
    }

    private fun updateStatus(text: String) {
        Log.d(TAG, "updateStatus text=$text")
        binding.statusText.text = text
        if (hasCameraPermission()) {
            binding.switchCameraButton.isEnabled = true
            binding.retryButton.isEnabled = true
            binding.permissionButton.visibility = View.GONE
        }
    }

    private fun postUiUpdate(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                action()
            }
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
            return Size(1280, 720)
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
}
