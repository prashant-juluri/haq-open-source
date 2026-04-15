package com.haq.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object TTSManager {

    private var tts: TextToSpeech? = null
    private var isReady = false

    fun init(context: Context) {
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
            Log.d("Haq/TTS", "TTS init status=$status isReady=$isReady")
            if (isReady) {
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
            }
        }
    }

    fun speak(text: String, languageCode: String) {
        Log.d("Haq/TTS", "speak() called isReady=$isReady textLength=${text.length}")
        if (!isReady) return
        val locale = when (languageCode) {
            "te" -> Locale("te", "IN")
            "ta" -> Locale("ta", "IN")
            "ml" -> Locale("ml", "IN")
            else -> Locale("hi", "IN")
        }
        val result = tts?.setLanguage(locale)
        Log.d("Haq/TTS", "setLanguage result=$result locale=$languageCode")
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.getDefault())
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "haq_response")
        Log.d("Haq/TTS", "tts.speak() dispatched")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
