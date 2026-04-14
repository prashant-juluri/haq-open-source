package com.haq.app.stt

import android.content.Context

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
        else -> WhisperConfig.LANG_HINDI   // default — includes "hi"
    }

    suspend fun recordAndTranscribe(languageCode: String): String {
        val samples = recorder.record(WhisperConfig.CHUNK_DURATION_MS)
        return whisper.transcribe(samples, languageToken(languageCode))
    }

    fun shutdown() {
        if (::whisper.isInitialized) whisper.close()
    }
}
