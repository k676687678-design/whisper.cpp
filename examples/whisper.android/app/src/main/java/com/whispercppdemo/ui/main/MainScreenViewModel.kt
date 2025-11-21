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
        printMessage("Initializing...\n")
        try {
            // لم نعد بحاجة لنسخ النموذج للخارج، سنقرأه من Assets مباشرة
            // copyAssets() 
            loadBaseModel()
            canTranscribe = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("Error: ${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    // تحميل النموذج المحقون في التطبيق
    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        printMessage("Loading Embedded Model (Small)...\n")
        val models = application.assets.list("models")
        if (models != null && models.isNotEmpty()) {
            // هذه الدالة تقرأ النموذج مباشرة من داخل الـ APK دون الحاجة لنسخه
            whisperContext = WhisperContext.createContextFromAsset(application.assets, "models/" + models[0])
            printMessage("Model Loaded Successfully: ${models[0]}.\n")
        } else {
            printMessage("❌ Error: No embedded model found!\n")
        }
    }

    private fun formatSrtTime(timeInMs: Long): String {
        val sec = timeInMs / 1000
        val m = timeInMs % 1000
        val s = sec % 60
        val min = (sec / 60) % 60
        val h = sec / 3600
        return "%02d:%02d:%02d,%03d".format(h, min, s, m)
    }

    private suspend fun transcribeAudio(file: File) {
        if (!canTranscribe) return
        canTranscribe = false

        try {
            printMessage("Transcribing...\n")
            val data = withContext(Dispatchers.IO) { decodeWaveFile(file) }
            val start = System.currentTimeMillis()
            
            withContext(Dispatchers.IO) {
                whisperContext?.transcribeData(data)
            }
            
            val elapsed = System.currentTimeMillis() - start
            
            // محاولة بناء SRT
            try {
                val sb = StringBuilder()
                val segmentCount = whisperContext?.textSegmentCount ?: 0
                
                if (segmentCount > 0) {
                    for (i in 0 until segmentCount) {
                        val text = whisperContext?.getTextSegment(i) ?: ""
                        val startTime = (whisperContext?.getTextSegmentStartTime(i) ?: 0) * 10
                        val endTime = (whisperContext?.getTextSegmentEndTime(i) ?: 0) * 10
                        
                        sb.append("${i + 1}\n")
                        sb.append("${formatSrtTime(startTime)} --> ${formatSrtTime(endTime)}\n")
                        sb.append("${text.trim()}\n\n")
                    }
                    val srtContent = sb.toString()
                    printMessage("Done ($elapsed ms).\n")
                    saveSrtToFile(srtContent)
                } else {
                    val text = whisperContext?.transcribeData(data)
                    printMessage("Done ($elapsed ms).\n")
                    if (!text.isNullOrEmpty()) saveSrtToFile(text, isPlainText = true)
                }
            } catch (e: Exception) {
                val text = whisperContext?.transcribeData(data)
                if (!text.isNullOrEmpty()) saveSrtToFile(text, isPlainText = true)
            }

        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("Error: ${e.localizedMessage}\n")
        }

        canTranscribe = true
    }

    private suspend fun saveSrtToFile(content: String, isPlainText: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val path = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val ext = if (isPlainText) "txt" else "srt"
            val fileName = "whisper_${System.currentTimeMillis()}.$ext"
            val file = File(path, fileName)
            file.writeText(content)
            printMessage("\n✅ Saved: ${file.absolutePath}\n")
        } catch (e: Exception) {
            printMessage("\n❌ Save failed. Grant Permissions manually.\n")
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
                
                val outputFile = File.createTempFile("converted", ".wav", application.cacheDir)
                val cmd = "-y -i \"${inputFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${outputFile.absolutePath}\""
                
                val session = FFmpegKit.execute(cmd)
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    printMessage("✅ Converted. Starting...\n")
                    transcribeAudio(outputFile)
                } else {
                    printMessage("❌ Conversion Failed.\n")
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
