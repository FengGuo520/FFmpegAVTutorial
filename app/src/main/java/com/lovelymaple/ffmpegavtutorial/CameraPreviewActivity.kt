package com.lovelymaple.ffmpegavtutorial

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
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityCameraPreviewBinding
import java.util.Collections
import kotlin.math.abs

class CameraPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraPreviewBinding

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.previewPlaceholder.text = getString(R.string.camera_preview_status_idle)
                openCameraWhenReady()
            } else {
                showPermissionRequiredState(R.string.camera_preview_status_permission_denied)
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
            runOnUiThread {
                createCameraPreviewSession()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            postUiUpdate {
                updateStatus(getString(R.string.camera_preview_status_error, "camera disconnected"))
                binding.previewPlaceholder.visibility = View.VISIBLE
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
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

        binding = ActivityCameraPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { ensureCameraPermissionAndStart() }
        binding.retryButton.setOnClickListener { openCameraWhenReady(forceReopen = true) }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.previewTexture.surfaceTextureListener = surfaceTextureListener

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
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun ensureCameraPermissionAndStart() {
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
        openCamera()
    }

    private fun openCamera() {
        try {
            val selectedCameraId = findCameraId(currentLensFacing)
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
                        updateStatus(getString(R.string.camera_preview_status_error, "preview output unavailable"))
                        return
                    }

            previewSize = choosePreviewSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                binding.previewTexture.width,
                binding.previewTexture.height
            )
            configureTransform(binding.previewTexture.width, binding.previewTexture.height)

            val openingText = getString(R.string.camera_preview_status_opening, currentLensLabel())
            updateStatus(openingText)
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text = openingText

            cameraManager.openCamera(selectedCameraId, stateCallback, backgroundHandler)
        } catch (exception: CameraAccessException) {
            updateStatus(getString(R.string.camera_preview_status_error, exception.message ?: "camera access error"))
        } catch (exception: SecurityException) {
            showPermissionRequiredState(R.string.camera_preview_status_permission_required)
        }
    }

    private fun createCameraPreviewSession() {
        val texture = binding.previewTexture.surfaceTexture ?: return
        val camera = cameraDevice ?: return
        val size = previewSize ?: return

        try {
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(texture)

            previewRequestBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                }

            camera.createCaptureSession(
                Collections.singletonList(surface),
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
            updateStatus(getString(R.string.camera_preview_status_error, exception.message ?: "preview session error"))
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

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraPreviewThread").also { thread ->
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
