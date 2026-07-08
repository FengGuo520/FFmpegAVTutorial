package com.lovelymaple.ffmpegavtutorial.basic

import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityNativeAudioTrackPlayerBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class NativeAudioTrackPlayerActivity : AppCompatActivity() {

    private data class SelectedFile(
        val uri: Uri,
        val displayName: String,
        val localFile: File
    )

    private lateinit var binding: ActivityNativeAudioTrackPlayerBinding
    private lateinit var nativeInstance: NativeInstance

    @Volatile
    private var isImporting = false

    @Volatile
    private var isPlaying = false

    @Volatile
    private var isPaused = false

    private var selectedFile: SelectedFile? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotPoller = object : Runnable {
        override fun run() {
            if (isDestroyed || !isPlaying) {
                return
            }
            val snapshot = nativeInstance.getNativeAudioTrackPlayerSnapshot()
            applySnapshot(snapshot)
            if (isPlaying) {
                mainHandler.postDelayed(this, 600L)
            }
        }
    }

    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNativeAudioTrackPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()
        nativeInstance.setNativeAudioTrackPlayerListener { state, snapshot ->
            postUi {
                applyNativeEvent(state, snapshot)
            }
        }

        binding.backButton.setOnClickListener { finish() }
        binding.selectFileButton.setOnClickListener { pickFile() }
        binding.startButton.setOnClickListener { startPlayback() }
        binding.pauseButton.setOnClickListener { pausePlayback() }
        binding.resumeButton.setOnClickListener { resumePlayback() }
        binding.stopButton.setOnClickListener { stopPlayback() }

        renderSelectedFile()
        renderState()
        updateStatus(getString(R.string.native_audio_track_player_status_idle))
        binding.snapshotText.text = getString(R.string.native_audio_track_player_snapshot_empty)
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying) {
            startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroy() {
        stopPolling()
        if (isPlaying) {
            runCatching { nativeInstance.stopNativeAudioTrackPlayer() }
        }
        nativeInstance.setNativeAudioTrackPlayerListener(null)
        super.onDestroy()
    }

    private fun pickFile() {
        if (isImporting || isPlaying) return
        pickerLauncher.launch(arrayOf("audio/*", "video/*", "application/octet-stream", "*/*"))
    }

    private fun importFile(uri: Uri) {
        isImporting = true
        renderState()
        updateStatus(getString(R.string.native_audio_track_player_status_importing))

        thread(start = true, name = "NativeAudioTrackImport") {
            val result = runCatching {
                val displayName = queryDisplayName(uri) ?: fallbackName()
                val localFile = copyUriToSandbox(uri, displayName)
                SelectedFile(uri = uri, displayName = displayName, localFile = localFile)
            }

            postUi {
                isImporting = false
                result.fold(
                    onSuccess = { file ->
                        selectedFile = file
                        renderSelectedFile()
                        renderState()
                        updateStatus(
                            getString(
                                R.string.native_audio_track_player_status_ready,
                                file.displayName
                            )
                        )
                    },
                    onFailure = { throwable ->
                        renderState()
                        updateStatus(
                            getString(
                                R.string.native_audio_track_player_status_error,
                                throwable.message ?: throwable.javaClass.simpleName
                            )
                        )
                    }
                )
            }
        }
    }

    private fun startPlayback() {
        val file = selectedFile ?: run {
            updateStatus(getString(R.string.native_audio_track_player_status_select_file_first))
            return
        }
        if (isImporting || isPlaying) return

        isPlaying = true
        isPaused = false
        renderState()
        updateStatus(getString(R.string.native_audio_track_player_status_starting, file.displayName))

        val snapshot = nativeInstance.startNativeAudioTrackPlayer(file.localFile.absolutePath)
        applySnapshot(snapshot)
        if (isPlaying) {
            startPolling()
        }
    }

    private fun stopPlayback() {
        if (!isPlaying) return
        updateStatus(getString(R.string.native_audio_track_player_status_stopping))
        isPlaying = false
        isPaused = false
        renderState()
        stopPolling()

        thread(start = true, name = "NativeAudioTrackStop") {
            val snapshot = runCatching { nativeInstance.stopNativeAudioTrackPlayer() }
                .getOrElse { throwable ->
                    "state: error\nmessage: ${throwable.message ?: throwable.javaClass.simpleName}\n"
                }
            postUi {
                applySnapshot(snapshot)
            }
        }
    }

    private fun pausePlayback() {
        if (!isPlaying || isPaused) return
        updateStatus(getString(R.string.native_audio_track_player_status_pausing))
        thread(start = true, name = "NativeAudioTrackPause") {
            val snapshot = runCatching { nativeInstance.pauseNativeAudioTrackPlayer() }
                .getOrElse { throwable ->
                    "state: error\nmessage: ${throwable.message ?: throwable.javaClass.simpleName}\n"
                }
            postUi {
                applySnapshot(snapshot)
            }
        }
    }

    private fun resumePlayback() {
        if (!isPlaying || !isPaused) return
        updateStatus(getString(R.string.native_audio_track_player_status_resuming))
        thread(start = true, name = "NativeAudioTrackResume") {
            val snapshot = runCatching { nativeInstance.resumeNativeAudioTrackPlayer() }
                .getOrElse { throwable ->
                    "state: error\nmessage: ${throwable.message ?: throwable.javaClass.simpleName}\n"
                }
            postUi {
                applySnapshot(snapshot)
            }
        }
    }

    private fun applyNativeEvent(state: String, snapshot: String) {
        applySnapshot(snapshot)
        when (state) {
            "finished" -> updateStatus(getString(R.string.native_audio_track_player_status_finished_callback))
            "error" -> updateStatus(getString(R.string.native_audio_track_player_status_error_callback))
        }
    }

    private fun applySnapshot(snapshot: String) {
        binding.snapshotText.text = snapshot

        val state = extractSnapshotField(snapshot, "state")
        val message = extractSnapshotField(snapshot, "message")
        if (!message.isNullOrBlank()) {
            updateStatus(message)
        }

        isPaused = state == "paused"

        if (state == "finished" || state == "stopped" || state == "error" || state == "idle") {
            isPlaying = false
            isPaused = false
            renderState()
            stopPolling()
            return
        }
        renderState()
    }

    private fun extractSnapshotField(snapshot: String, key: String): String? {
        val prefix = "$key: "
        return snapshot.lineSequence()
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
    }

    private fun startPolling() {
        stopPolling()
        mainHandler.post(snapshotPoller)
    }

    private fun stopPolling() {
        mainHandler.removeCallbacks(snapshotPoller)
    }

    private fun renderSelectedFile() {
        val file = selectedFile
        binding.sourceNameText.text = getString(
            R.string.native_audio_track_player_selected_file_value,
            file?.displayName ?: getString(R.string.native_audio_track_player_empty_value)
        )
        binding.sourceUriText.text = getString(
            R.string.native_audio_track_player_source_uri_value,
            file?.uri?.toString() ?: getString(R.string.native_audio_track_player_empty_value)
        )
        binding.localCopyText.text = getString(
            R.string.native_audio_track_player_local_copy_value,
            file?.localFile?.absolutePath ?: getString(R.string.native_audio_track_player_empty_value)
        )
    }

    private fun renderState() {
        binding.selectFileButton.isEnabled = !isImporting && !isPlaying
        binding.startButton.isEnabled = !isImporting && !isPlaying && selectedFile != null
        binding.pauseButton.isEnabled = isPlaying && !isPaused
        binding.resumeButton.isEnabled = isPlaying && isPaused
        binding.stopButton.isEnabled = isPlaying
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
        return "audio_input_${formatter.format(Date())}.media"
    }

    private fun copyUriToSandbox(uri: Uri, displayName: String): File {
        val safeName = displayName.ifBlank { fallbackName() }
            .replace(Regex("""[^\w.\-]"""), "_")
        val targetDir = File(cacheDir, "native_audio_track_input").apply { mkdirs() }
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

    private fun postUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { action() }
    }
}
