package com.haq.app.stt

import android.content.Context
import android.util.Log

object STTManager {

    private lateinit var recorder: AudioRecorder
    private lateinit var whisper: WhisperEngine

    fun init(context: Context) {
        recorder = AudioRecorder(context)
        whisper  = WhisperEngine(context)
    }

    fun languageToken(languageCode: String): Int = when (languageCode) {
        "te" -> WhisperConfig.LANG_TELUGU
        "ta" -> WhisperConfig.LANG_TAMIL
        else -> WhisperConfig.LANG_HINDI
    }

    suspend fun recordAndTranscribe(languageCode: String): String {
        return try {
            val samples = recorder.record(WhisperConfig.CHUNK_DURATION_MS)
            whisper.transcribe(samples, languageToken(languageCode))
        } catch (e: OutOfMemoryError) {
            Log.e("Haq/STT", "OOM during Whisper transcription", e)
            "ERROR_OOM"
        }
    }

    fun shutdown() {
        if (::whisper.isInitialized) whisper.close()
    }
}
