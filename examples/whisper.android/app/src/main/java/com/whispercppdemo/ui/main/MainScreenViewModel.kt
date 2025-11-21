package com.whispercppdemo.ui.main

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.Recorder
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set

    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")
    private var recorder: Recorder = Recorder()
    private var whisperContext: WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedFile: File? = null

    init {
        viewModelScope.launch {
            printSystemInfo()
            loadData()
        }
    }

    private suspend fun printSystemInfo() {
        printMessage(String.format("System Info: %s\n", WhisperContext.getSystemInfo()))
    }

    private suspend fun loadData() {
        printMessage("Loading data...\n")
        try {
            copyAssets()
            loadBaseModel()
            canTranscribe = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        if (!modelsPath.exists()) modelsPath.mkdirs()
        if (!samplesPath.exists()) samplesPath.mkdirs()
        application.copyData("samples", samplesPath, ::printMessage)
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        printMessage("Loading Small model...\n")
        val models = application.assets.list("models")
        if (models != null && models.isNotEmpty()) {
            whisperContext = WhisperContext.createContextFromAsset(application.assets, "models/" + models[0])
            printMessage("Loaded model ${models[0]}.\n")
        } else {
            printMessage("No models found in assets.\n")
        }
    }

    // --- تم إصلاح دالة البنشمارك ---
    fun benchmark() = viewModelScope.launch {
        if (!canTranscribe) return@launch
        canTranscribe = false
        printMessage("Running benchmark...\n")
        withContext(Dispatchers.IO) {
            whisperContext?.benchMemory(6)?.let { printMessage(it) }
            printMessage("\n")
            whisperContext?.benchGgmlMulMat(6)?.let { printMessage(it) }
        }
        canTranscribe = true
    }

    // --- تم إصلاح دالة العينة ---
    fun transcribeSample() = viewModelScope.launch {
        val files = samplesPath.listFiles()
        if (files != null && files.isNotEmpty()) {
            transcribeAudio(files.first())
        } else {
            printMessage("No sample files found.\n")
        }
    }

    private suspend fun transcribeAudio(file: File) {
        if (!canTranscribe) return
        canTranscribe = false

        try {
            printMessage("Transcribing...\n")
            val data = withContext(Dispatchers.IO) { decodeWaveFile(file) }
            val start = System.currentTimeMillis()
            
            // المكتبة الحالية تدعم فقط إرجاع النص كاملاً
            val text = withContext(Dispatchers.IO) {
                whisperContext?.transcribeData(data)
            }
            
            val elapsed = System.currentTimeMillis() - start
            printMessage("Done ($elapsed ms): \n$text\n")

            // حفظ النص في ملف
            if (!text.isNullOrEmpty()) {
                saveTextToFile(text)
            }

        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("Error: ${e.localizedMessage}\n")
        }

        canTranscribe = true
    }

    // دالة حفظ النص (بدلاً من SRT لأن التوقيت غير مدعوم حالياً)
    private suspend fun saveTextToFile(text: String) = withContext(Dispatchers.IO) {
        try {
            val path = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val fileName = "whisper_${System.currentTimeMillis()}.txt"
            val file = File(path, fileName)
            file.writeText(text)
            printMessage("\n✅ Text Saved to Downloads: ${file.name}\n")
        } catch (e: Exception) {
            printMessage("\n❌ Save failed. Check Permissions manually.\n")
        }
    }

    fun onFileSelected(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                printMessage("Processing file...\n")
                val contentResolver = application.contentResolver
                val inputFile = File.createTempFile("input", ".tmp", application.cacheDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(inputFile).use { output -> input.copyTo(output) }
                }
                
                // التحويل باستخدام FFmpeg
                val outputFile = File.createTempFile("converted", ".wav", application.cacheDir)
                val cmd = "-y -i \"${inputFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${outputFile.absolutePath}\""
                
                val session = FFmpegKit.execute(cmd)
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    printMessage("✅ Converted. Transcribing...\n")
                    transcribeAudio(outputFile)
                } else {
                    printMessage("❌ Conversion failed.\n")
                }
            } catch (e: Exception) {
                printMessage("Error: ${e.message}\n")
            }
        }
    }

    fun toggleRecord() = viewModelScope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                recordedFile?.let { transcribeAudio(it) }
            } else {
                stopPlayback()
                val file = File.createTempFile("recording", "wav")
                recorder.startRecording(file) { e ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) {
                            printMessage("${e.localizedMessage}\n")
                            isRecording = false
                        }
                    }
                }
                isRecording = true
                recordedFile = file
            }
        } catch (e: Exception) {
            printMessage("Record Error: ${e.localizedMessage}\n")
            isRecording = false
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onCleared() {
        runBlocking {
            whisperContext?.release()
            whisperContext = null
            stopPlayback()
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

private suspend fun Context.copyData(
    assetDirName: String,
    destDir: File,
    printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        val destFile = File(destDir, name)
        if (!destFile.exists()) {
            try {
                assets.open(assetPath).use { input ->
                    java.io.FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed copy", e)
            }
        }
    }
}
