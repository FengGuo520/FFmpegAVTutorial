package com.lovelymaple.ffmpegavtutorial.basic

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lovelymaple.ffmpegavtutorial.R
import com.lovelymaple.ffmpegavtutorial.databinding.ActivityMovieExtractBinding
import com.lovelymaple.ffmpegavtutorial.ui.setupNavigationBarSpace
import com.lovelymaple.ffmpegavtutorial.ui.setupStatusBarSpace
import io.ffmpegtutotial.player.internal.NativeInstance
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MovieExtractActivity : AppCompatActivity() {

    private data class SelectedFile(
        val uri: Uri,
        val displayName: String,
        val localFile: File
    )

    private lateinit var binding: ActivityMovieExtractBinding
    private lateinit var nativeInstance: NativeInstance

    @Volatile
    private var isWorking = false

    private var selectedFile: SelectedFile? = null
    private var latestOutputDir: File? = null

    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMovieExtractBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupStatusBarSpace(this, binding.statusBarSpace, lightStatusBarIcons = false)
        setupNavigationBarSpace(binding.navigationBarSpace)

        nativeInstance = NativeInstance.getSharedInstance() ?: NativeInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.selectFileButton.setOnClickListener { pickFile() }
        binding.extractButton.setOnClickListener { extractSelectedFile() }

        renderSelectedFile()
        renderState()
        updateStatus(getString(R.string.movie_extract_status_idle))
    }

    private fun pickFile() {
        if (isWorking) return
        pickerLauncher.launch(arrayOf("video/*", "audio/*", "application/octet-stream", "*/*"))
    }

    private fun importFile(uri: Uri) {
        isWorking = true
        renderState()
        updateStatus(getString(R.string.movie_extract_status_importing))

        thread(start = true, name = "MovieExtractImport") {
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
                        latestOutputDir = null
                        binding.resultText.text = getString(R.string.movie_extract_result_empty)
                        renderSelectedFile()
                        renderState()
                        updateStatus(getString(R.string.movie_extract_status_ready, file.displayName))
                    },
                    onFailure = { throwable ->
                        renderState()
                        updateStatus(
                            getString(
                                R.string.movie_extract_status_error,
                                throwable.message ?: throwable.javaClass.simpleName
                            )
                        )
                    }
                )
            }
        }
    }

    private fun extractSelectedFile() {
        val file = selectedFile ?: run {
            updateStatus(getString(R.string.movie_extract_status_select_file_first))
            return
        }
        if (isWorking) return

        val outputDir = createOutputDirectory(file.displayName)
        latestOutputDir = outputDir
        renderSelectedFile()

        isWorking = true
        renderState()
        binding.resultText.text = getString(R.string.movie_extract_status_extracting, file.displayName)
        updateStatus(getString(R.string.movie_extract_status_extracting, file.displayName))

        thread(start = true, name = "MovieExtractNative") {
            val result = runCatching {
                nativeInstance.extractMediaStreams(file.localFile.absolutePath, outputDir.absolutePath)
            }

            postUi {
                isWorking = false
                renderState()
                result.fold(
                    onSuccess = { raw ->
                        binding.resultText.text = raw
                        if (raw.startsWith("ERROR:")) {
                            updateStatus(
                                getString(
                                    R.string.movie_extract_status_error,
                                    raw.removePrefix("ERROR: ").trim()
                                )
                            )
                        } else {
                            updateStatus(getString(R.string.movie_extract_status_done, file.displayName))
                        }
                    },
                    onFailure = { throwable ->
                        val message = throwable.message ?: throwable.javaClass.simpleName
                        binding.resultText.text = message
                        updateStatus(getString(R.string.movie_extract_status_error, message))
                    }
                )
            }
        }
    }

    private fun createOutputDirectory(displayName: String): File {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val stem = displayName.substringBeforeLast('.', displayName)
            .ifBlank { "media" }
            .replace(Regex("""[^\w.\-]"""), "_")
        return File(
            File(filesDir, "movie_extract"),
            "${stem}_${formatter.format(Date())}"
        ).apply { mkdirs() }
    }

    private fun renderSelectedFile() {
        val file = selectedFile
        binding.sourceNameText.text = getString(
            R.string.movie_extract_selected_file_value,
            file?.displayName ?: getString(R.string.movie_extract_empty_value)
        )
        binding.sourceUriText.text = getString(
            R.string.movie_extract_source_uri_value,
            file?.uri?.toString() ?: getString(R.string.movie_extract_empty_value)
        )
        binding.localCopyText.text = getString(
            R.string.movie_extract_local_copy_value,
            file?.localFile?.absolutePath ?: getString(R.string.movie_extract_empty_value)
        )
        binding.outputDirText.text = getString(
            R.string.movie_extract_output_dir_value,
            latestOutputDir?.absolutePath ?: getString(R.string.movie_extract_empty_value)
        )
    }

    private fun renderState() {
        binding.selectFileButton.isEnabled = !isWorking
        binding.extractButton.isEnabled = !isWorking && selectedFile != null
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
        return "input_${formatter.format(Date())}.media"
    }

    private fun copyUriToSandbox(uri: Uri, displayName: String): File {
        val safeName = displayName.ifBlank { fallbackName() }
            .replace(Regex("""[^\w.\-]"""), "_")
        val targetDir = File(cacheDir, "movie_extract_input").apply { mkdirs() }
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
