package com.lovelymaple.ffmpegavtutorial.container

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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
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
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityLiveFlvMuxBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class LiveFlvMuxActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveFlvMuxActivity"
    }

    private lateinit var binding: ActivityLiveFlvMuxBinding
    private lateinit var nativeInstance: NativeInstance

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private val encodeWidth = 1280
    private val encodeHeight = 720
    private val encodeFrameRate = 30
    private val videoBitrate = 2_000_000
    private val audioSampleRate = 44_100
    private val audioChannelCount = 1
    private val audioChannelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioBitrate = 128_000

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var isOpeningCamera = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var encoderSurface: Surface? = null
    private var audioBufferSizeInBytes: Int = 0

    @Volatile
    private var isStreaming = false

    @Volatile
    private var isStopping = false

    private var videoDrainThread: Thread? = null
    private var audioEncodeThread: Thread? = null
    private var outputFile: File? = null
    private var fatalErrorMessage: String? = null
    private var muxerStarted = false
    private var videoPacketCount = 0
    private var audioPacketCount = 0
    private var videoCsd0: ByteArray? = null
    private var videoCsd1: ByteArray? = null
    private var audioCsd0: ByteArray? = null
    private var firstVideoPtsUs: Long? = null
    private var firstAudioPtsUs: Long? = null

    private val muxerLock = Any()
    private val pendingPackets = mutableListOf<PendingPacket>()

    private data class PendingPacket(
        val isVideo: Boolean,
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            Log.d(
                TAG,
                "permission result camera=${hasCameraPermission()} audio=${hasAudioPermission()} " +
                    "textureAvailable=${binding.previewTexture.isAvailable}"
            )
            updateControls()
            if (hasCameraPermission()) {
                if (backgroundHandler == null) {
                    Log.d(TAG, "camera permission granted but backgroundHandler is null, wait for onResume")
                } else {
                    openCameraWhenReady()
                }
            }
            if (!hasAllPermissions()) {
                updateStatus(getString(R.string.live_flv_mux_status_permission_required))
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
                handleFatalError("camera disconnected")
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "camera onError id=${camera.id} error=$error")
            isOpeningCamera = false
            camera.close()
            cameraDevice = null
            postUi {
                binding.previewPlaceholder.visibility = View.VISIBLE
                handleFatalError("camera error $error")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityLiveFlvMuxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.permissionButton.setOnClickListener { requestAllPermissions() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.startButton.setOnClickListener { ensurePermissionsAndStart() }
        binding.stopButton.setOnClickListener { stopStreaming() }
        binding.previewTexture.surfaceTextureListener = surfaceTextureListener

        updateStaticInfo()
        updateControls()
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
        stopStreaming()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun requestAllPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    private fun ensurePermissionsAndStart() {
        if (!hasAllPermissions()) {
            requestAllPermissions()
            return
        }
        startStreaming()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasAllPermissions(): Boolean = hasCameraPermission() && hasAudioPermission()

    private fun openCameraWhenReady(forceReopen: Boolean = false) {
        Log.d(
            TAG,
            "openCameraWhenReady forceReopen=$forceReopen hasCameraPermission=${hasCameraPermission()} " +
                "textureAvailable=${binding.previewTexture.isAvailable} " +
                "textureSize=${binding.previewTexture.width}x${binding.previewTexture.height} " +
                "cameraDeviceNull=${cameraDevice == null} isOpeningCamera=$isOpeningCamera " +
                "handlerNull=${backgroundHandler == null}"
        )
        if (!hasCameraPermission()) {
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text =
                getString(R.string.live_flv_mux_status_permission_required)
            return
        }
        if (!binding.previewTexture.isAvailable) {
            return
        }
        if (backgroundHandler == null) {
            Log.d(TAG, "openCameraWhenReady skipped because backgroundHandler is null")
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
                val message = getString(R.string.live_flv_mux_status_no_camera, currentLensLabel())
                binding.previewPlaceholder.visibility = View.VISIBLE
                binding.previewPlaceholder.text = message
                updateStatus(message)
                return
            }
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: run {
                        updateStatus(getString(R.string.live_flv_mux_status_error, "preview output unavailable"))
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
            val message = getString(R.string.live_flv_mux_status_opening, currentLensLabel())
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text = message
            updateStatus(message)
            isOpeningCamera = true
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (exception: CameraAccessException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera CameraAccessException", exception)
            updateStatus(getString(R.string.live_flv_mux_status_error, exception.message ?: "camera access error"))
        } catch (exception: SecurityException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera SecurityException", exception)
            updateStatus(getString(R.string.live_flv_mux_status_permission_required))
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
                if (isStreaming && encoderSurface != null) {
                    CameraDevice.TEMPLATE_RECORD
                } else {
                    CameraDevice.TEMPLATE_PREVIEW
                }

            previewRequestBuilder = camera.createCaptureRequest(template).apply {
                addTarget(previewSurface)
                encoderSurface?.takeIf { isStreaming }?.let { encodeSurface ->
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
                                    if (isStreaming) {
                                        getString(R.string.live_flv_mux_status_streaming)
                                    } else {
                                        getString(
                                            R.string.live_flv_mux_status_previewing,
                                            currentLensLabel()
                                        )
                                    }
                                )
                            }
                        } catch (exception: CameraAccessException) {
                            handleFatalError(exception.message ?: "failed to start camera session")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        handleFatalError("camera session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (exception: CameraAccessException) {
            updateStatus(getString(R.string.live_flv_mux_status_error, exception.message ?: "camera session error"))
        }
    }

    private fun startStreaming() {
        if (isStreaming || isStopping) return
        if (!hasAllPermissions()) {
            updateStatus(getString(R.string.live_flv_mux_status_permission_required))
            return
        }

        fatalErrorMessage = null
        outputFile = createOutputFile()
        videoPacketCount = 0
        audioPacketCount = 0
        updatePacketCountTexts()
        binding.outputPathText.text =
            getString(
                R.string.live_flv_mux_output_path_value,
                outputFile?.absolutePath ?: getString(R.string.h264_encode_output_path_empty)
            )

        try {
            resetMuxerState()
            setupVideoEncoder()
            setupAudioPipeline()
            isStreaming = true
            updateControls()
            if (cameraDevice != null) {
                createCameraSession()
            } else {
                openCameraWhenReady()
            }
            startVideoDrainThread()
            startAudioEncodeThread()
            updateStatus(getString(R.string.live_flv_mux_status_starting))
        } catch (exception: Exception) {
            fatalErrorMessage = exception.message ?: "failed to start live FLV pipeline"
            releaseVideoEncoder()
            releaseAudioPipeline()
            resetMuxerState()
            updateControls()
            updateStatus(getString(R.string.live_flv_mux_status_error, fatalErrorMessage!!))
        }
    }

    private fun stopStreaming() {
        if ((!isStreaming && !isStopping) && videoCodec == null && audioCodec == null) {
            return
        }
        if (isStopping) return

        isStopping = true
        val statusBeforeStop = fatalErrorMessage
        isStreaming = false

        try {
            videoCodec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }

        audioEncodeThread?.join(2000)
        audioEncodeThread = null
        videoDrainThread?.join(2000)
        videoDrainThread = null

        val muxSummary = synchronized(muxerLock) {
            val summary = if (muxerStarted) {
                muxerStarted = false
                nativeInstance.closeLiveFlvMuxer()
            } else {
                null
            }
            pendingPackets.clear()
            videoCsd0 = null
            videoCsd1 = null
            audioCsd0 = null
            summary
        }

        releaseAudioPipeline()
        releaseVideoEncoder()

        if (!isFinishing && !isDestroyed) {
            updateControls()
            if (cameraDevice != null) {
                createCameraSession()
            }
            val finalMessage = when {
                statusBeforeStop != null -> {
                    getString(R.string.live_flv_mux_status_error, statusBeforeStop)
                }
                muxSummary != null && muxSummary.startsWith("ERROR:") -> {
                    getString(R.string.live_flv_mux_status_error, muxSummary)
                }
                muxSummary != null -> {
                    muxSummary
                }
                else -> {
                    getString(R.string.live_flv_mux_status_stopped)
                }
            }
            updateStatus(finalMessage)
        }

        fatalErrorMessage = null
        isStopping = false
    }

    private fun setupVideoEncoder() {
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                encodeWidth,
                encodeHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, encodeFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
            start()
        }
    }

    private fun setupAudioPipeline() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            audioSampleRate,
            audioChannelConfig,
            audioFormat
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("invalid min buffer size: $minBufferSize")
        }
        audioBufferSizeInBytes = minBufferSize * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            audioSampleRate,
            audioChannelConfig,
            audioFormat,
            audioBufferSizeInBytes
        ).also { recorder ->
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("AudioRecord initialization failed")
            }
        }

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSampleRate,
                audioChannelCount
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioBufferSizeInBytes)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun startVideoDrainThread() {
        val codec = videoCodec ?: return
        videoDrainThread = thread(start = true, name = "LiveFlvVideoDrain") {
            val bufferInfo = MediaCodec.BufferInfo()
            var outputEnded = false
            while (!outputEnded) {
                when (val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoCsd0 = cloneBuffer(codec.outputFormat, "csd-0")
                            videoCsd1 = cloneBuffer(codec.outputFormat, "csd-1")
                            maybeStartMuxerLocked()
                        }
                    }
                    else -> if (index >= 0) {
                        val outputBuffer = codec.getOutputBuffer(index)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val packet = ByteArray(bufferInfo.size)
                                outputBuffer.get(packet)
                                handleEncodedPacket(
                                    isVideo = true,
                                    data = packet,
                                    ptsUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags
                                )
                            }
                        }
                        outputEnded =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun startAudioEncodeThread() {
        val recorder = audioRecord ?: return
        val codec = audioCodec ?: return
        audioEncodeThread = thread(start = true, name = "LiveFlvAudioEncode") {
            val pcmBuffer = ByteArray(audioBufferSizeInBytes)
            val bufferInfo = MediaCodec.BufferInfo()
            var presentationTimeUs = 0L
            var eosQueued = false
            try {
                recorder.startRecording()
                postUi {
                    if (isStreaming) {
                        updateStatus(getString(R.string.live_flv_mux_status_streaming))
                    }
                }

                while (isStreaming && fatalErrorMessage == null) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        queueAudioInput(codec, pcmBuffer, read, presentationTimeUs)
                        presentationTimeUs += bytesToDurationUs(read)
                        drainAudioEncoder(codec, bufferInfo, false)
                        val level = calculateLevel(pcmBuffer, read)
                        postUi {
                            binding.levelText.text =
                                getString(R.string.audio_capture_level_value, level)
                        }
                    } else if (read < 0) {
                        handleFatalError("AudioRecord read returned $read")
                        break
                    }
                }

                eosQueued = true
                queueAudioEndOfStream(codec, presentationTimeUs)
                drainAudioEncoder(codec, bufferInfo, true)
            } catch (exception: Exception) {
                handleFatalError(exception.message ?: "audio encode failed")
            } finally {
                if (!eosQueued) {
                    try {
                        queueAudioEndOfStream(codec, presentationTimeUs)
                        drainAudioEncoder(codec, bufferInfo, true)
                    } catch (_: Exception) {
                    }
                }
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun queueAudioInput(
        codec: MediaCodec,
        pcmBuffer: ByteArray,
        size: Int,
        ptsUs: Long
    ) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(pcmBuffer, 0, size)
            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
        }
    }

    private fun queueAudioEndOfStream(codec: MediaCodec, ptsUs: Long) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                ptsUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
    }

    private fun drainAudioEncoder(
        codec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean
    ) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        audioCsd0 = cloneBuffer(codec.outputFormat, "csd-0")
                        maybeStartMuxerLocked()
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val packet = ByteArray(bufferInfo.size)
                        outputBuffer.get(packet)
                        handleEncodedPacket(
                            isVideo = false,
                            data = packet,
                            ptsUs = bufferInfo.presentationTimeUs,
                            flags = bufferInfo.flags
                        )
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
                else -> return
            }
        }
    }

    private fun handleEncodedPacket(
        isVideo: Boolean,
        data: ByteArray,
        ptsUs: Long,
        flags: Int
    ) {
        val normalizedPtsUs = normalizePtsUs(isVideo, ptsUs)
        synchronized(muxerLock) {
            if (!muxerStarted) {
                pendingPackets.add(PendingPacket(isVideo, data, normalizedPtsUs, flags))
                maybeStartMuxerLocked()
                return
            }
            writePacketLocked(PendingPacket(isVideo, data, normalizedPtsUs, flags))
        }
    }

    private fun maybeStartMuxerLocked() {
        if (muxerStarted) return
        val videoConfig = videoCsd0 ?: return
        val audioConfig = audioCsd0 ?: return
        val file = outputFile ?: return

        val result = nativeInstance.openLiveFlvMuxer(
            file.absolutePath,
            encodeWidth,
            encodeHeight,
            encodeFrameRate,
            videoBitrate,
            videoConfig,
            videoCsd1 ?: ByteArray(0),
            audioSampleRate,
            audioChannelCount,
            audioBitrate,
            audioConfig
        )

        if (!result.startsWith("OK:")) {
            handleFatalError(result)
            return
        }

        muxerStarted = true
        val packetsToFlush = pendingPackets.sortedBy { it.ptsUs }
        pendingPackets.clear()
        packetsToFlush.forEach { packet ->
            writePacketLocked(packet)
        }
        postUi {
            updateStatus(
                getString(
                    R.string.live_flv_mux_status_muxer_ready,
                    file.absolutePath
                )
            )
        }
    }

    private fun writePacketLocked(packet: PendingPacket) {
        val result =
            if (packet.isVideo) {
                nativeInstance.writeLiveVideoPacket(packet.data, packet.ptsUs, packet.flags)
            } else {
                nativeInstance.writeLiveAudioPacket(packet.data, packet.ptsUs, packet.flags)
            }

        if (result < 0) {
            handleFatalError(
                (if (packet.isVideo) "video" else "audio") + " packet write failed: $result"
            )
            return
        }

        if (packet.isVideo) {
            videoPacketCount += 1
        } else {
            audioPacketCount += 1
        }
        postUi {
            updatePacketCountTexts()
        }
    }

    private fun handleFatalError(message: String) {
        if (fatalErrorMessage != null) return
        fatalErrorMessage = message
        postUi {
            if (!isStopping) {
                stopStreaming()
            }
        }
    }

    private fun releaseVideoEncoder() {
        try {
            videoCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            videoCodec?.release()
        } catch (_: Exception) {
        }
        videoCodec = null
        try {
            encoderSurface?.release()
        } catch (_: Exception) {
        }
        encoderSurface = null
    }

    private fun releaseAudioPipeline() {
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            audioCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            audioCodec?.release()
        } catch (_: Exception) {
        }
        audioCodec = null
    }

    private fun resetMuxerState() {
        synchronized(muxerLock) {
            pendingPackets.clear()
            muxerStarted = false
            videoCsd0 = null
            videoCsd1 = null
            audioCsd0 = null
        }
        firstVideoPtsUs = null
        firstAudioPtsUs = null
    }

    private fun normalizePtsUs(isVideo: Boolean, ptsUs: Long): Long {
        return if (isVideo) {
            val firstPts = firstVideoPtsUs ?: ptsUs.also { firstVideoPtsUs = it }
            (ptsUs - firstPts).coerceAtLeast(0L)
        } else {
            val firstPts = firstAudioPtsUs ?: ptsUs.also { firstAudioPtsUs = it }
            (ptsUs - firstPts).coerceAtLeast(0L)
        }
    }

    private fun switchCamera() {
        if (isStreaming || isStopping) return
        currentLensFacing =
            if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
        openCameraWhenReady(forceReopen = true)
    }

    private fun closeCamera() {
        Log.d(TAG, "closeCamera")
        isOpeningCamera = false
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        Log.d(TAG, "startBackgroundThread")
        backgroundThread = HandlerThread("LiveFlvCameraThread").also { thread ->
            thread.start()
            backgroundHandler = Handler(thread.looper)
        }
    }

    private fun stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread")
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun updateStaticInfo() {
        binding.resolutionText.text =
            getString(
                R.string.live_flv_mux_resolution_value,
                encodeWidth,
                encodeHeight,
                encodeFrameRate
            )
        binding.videoBitrateText.text =
            getString(R.string.live_flv_mux_video_bitrate_value, videoBitrate)
        binding.audioSampleRateText.text =
            getString(R.string.audio_capture_sample_rate_value, audioSampleRate)
        binding.audioBitrateText.text =
            getString(R.string.live_flv_mux_audio_bitrate_value, audioBitrate)
        binding.outputPathText.text =
            getString(
                R.string.live_flv_mux_output_path_value,
                getString(R.string.h264_encode_output_path_empty)
            )
        binding.levelText.text = getString(R.string.audio_capture_level_value, 0)
        updatePacketCountTexts()
    }

    private fun updatePacketCountTexts() {
        binding.videoPacketCountText.text =
            getString(R.string.live_flv_mux_video_packet_count_value, videoPacketCount)
        binding.audioPacketCountText.text =
            getString(R.string.live_flv_mux_audio_packet_count_value, audioPacketCount)
    }

    private fun updateControls() {
        val hasPermissions = hasAllPermissions()
        binding.permissionButton.visibility = if (hasPermissions) View.GONE else View.VISIBLE
        binding.switchCameraButton.isEnabled = hasCameraPermission() && !isStreaming && !isStopping
        binding.startButton.isEnabled = hasPermissions && !isStreaming && !isStopping
        binding.stopButton.isEnabled = isStreaming && !isStopping

        if (!hasCameraPermission()) {
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text =
                getString(R.string.live_flv_mux_status_permission_required)
        } else if (!isStreaming && cameraDevice == null) {
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholder.text = getString(R.string.live_flv_mux_status_idle)
        }

        if (!hasPermissions) {
            updateStatus(getString(R.string.live_flv_mux_status_permission_required))
        } else if (!isStreaming && !isStopping && fatalErrorMessage == null) {
            updateStatus(getString(R.string.live_flv_mux_status_idle))
        }
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
        val liveDir = File(filesDir, "live").apply { mkdirs() }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return File(liveDir, "live_${formatter.format(Date())}.flv")
    }

    private fun cloneBuffer(format: MediaFormat, key: String): ByteArray? {
        val buffer = format.getByteBuffer(key) ?: return null
        val duplicate = buffer.duplicate()
        duplicate.position(0)
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun bytesToDurationUs(byteCount: Int): Long {
        val bytesPerSample = 2
        val sampleCount = byteCount / bytesPerSample / audioChannelCount
        return (sampleCount * 1_000_000L) / audioSampleRate
    }

    private fun calculateLevel(buffer: ByteArray, count: Int): Int {
        var sum = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < count) {
            val low = buffer[index].toInt() and 0xFF
            val high = buffer[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toDouble()
            sum += sample * sample
            sampleCount++
            index += 2
        }
        val rms = sqrt(sum / sampleCount.coerceAtLeast(1))
        return ((rms / Short.MAX_VALUE) * 100.0).toInt().coerceIn(0, 100)
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
