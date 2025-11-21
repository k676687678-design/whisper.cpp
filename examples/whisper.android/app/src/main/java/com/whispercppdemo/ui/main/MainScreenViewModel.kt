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

    // --- دوال تحويل الوقت لصيغة SRT ---
    private fun formatSrtTime(timeIn10Ms: Long): String {
        // الوقت يأتي من المكتبة بوحدات 10 مللي ثانية
        val timeInMs = timeIn10Ms * 10
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
            
            // 1. تشغيل المعالجة (Inference)
            // هذه الدالة تقوم بتشغيل النموذج وتخزين النتائج في الذاكرة
            withContext(Dispatchers.IO) {
                whisperContext?.transcribeData(data)
            }
            
            val elapsed = System.currentTimeMillis() - start
            
            // 2. استخراج النتائج وبناء ملف SRT
            val sb = StringBuilder()
            // نستخدم الدالة الجديدة التي أضفناها في LibWhisper.kt
            val segmentCount = whisperContext?.getTextSegmentCount() ?: 0
            
            if (segmentCount > 0) {
                for (i in 0 until segmentCount) {
                    val text = whisperContext?.getTextSegment(i) ?: ""
                    val startTime = whisperContext?.getTextSegmentStartTime(i) ?: 0
                    val endTime = whisperContext?.getTextSegmentEndTime(i) ?: 0
                    
                    sb.append("${i + 1}\n")
                    sb.append("${formatSrtTime(startTime)} --> ${formatSrtTime(endTime)}\n")
                    sb.append("${text.trim()}\n\n")
                }
                
                val finalSrt = sb.toString()
                printMessage("Done ($elapsed ms).\n")
                
                // الحفظ
                saveSrtToFile(finalSrt)
            } else {
                printMessage("No segments found. Check model or audio.\n")
            }

        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("Error: ${e.message}\n")
        }

        canTranscribe = true
    }

    // دالة حفظ الملف في التنزيلات
    private suspend fun saveSrtToFile(content: String) = withContext(Dispatchers.IO) {
        try {
            val path = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val fileName = "whisper_${System.currentTimeMillis()}.srt"
            val file = File(path, fileName)
            file.writeText(content)
            printMessage("\n✅ SAVED SRT: ${file.absolutePath}\n")
        } catch (e: Exception) {
            printMessage("\n❌ Save failed. Grant Storage Permissions manually!\n")
        }
    }

    // دالة رفع وتحويل الملفات
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
                // تحويل إلى 16kHz 16-bit Mono باستخدام FFmpeg
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

    // دوال التسجيل والتحكم
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
    
    fun benchmark() = viewModelScope.launch {
        if (!canTranscribe) return@launch
        canTranscribe = false
        printMessage("Running benchmark...\n")
        withContext(Dispatchers.IO) {
            whisperContext?.benchMemory(6)?.let{ printMessage(it) }
            printMessage("\n")
            whisperContext?.benchGgmlMulMat(6)?.let{ printMessage(it) }
        }
        canTranscribe = true
    }
    
    fun transcribeSample() = viewModelScope.launch {
        val files = samplesPath.listFiles()
        if (files != null && files.isNotEmpty()) {
            transcribeAudio(files.first())
        } else {
            printMessage("No sample file found.\n")
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
