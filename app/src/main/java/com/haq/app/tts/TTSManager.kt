package com.haq.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object TTSManager {

    private var tts: TextToSpeech? = null
    private var isReady = false

    // ── Speaking state ─────────────────────────────────────────────────────────

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Counts queued utterances so isSpeaking stays true across sentence-by-sentence TTS
    private val pendingCount = AtomicInteger(0)
    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()

    // Single persistent listener — set once in init() so all utterances route here
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = handleUtteranceDone(utteranceId)

        @Suppress("DEPRECATION")
        override fun onError(utteranceId: String?) = handleUtteranceDone(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = handleUtteranceDone(utteranceId)
    }

    private fun handleUtteranceDone(utteranceId: String?) {
        val cb = utteranceId?.let { completionCallbacks.remove(it) }
        if (pendingCount.decrementAndGet() <= 0) {
            pendingCount.set(0)
            _isSpeaking.value = false
        }
        cb?.invoke()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
            Log.d("Haq/TTS", "TTS init status=$status isReady=$isReady")
            if (isReady) {
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(utteranceListener)
            }
        }
    }

    // ── speak() ────────────────────────────────────────────────────────────────
    //
    // onComplete fires when THIS utterance finishes (or immediately if TTS not ready).
    // isSpeaking stays true until all queued utterances have completed.

    fun speak(text: String, languageCode: String, onComplete: (() -> Unit)? = null) {
        Log.d("Haq/TTS", "speak() called isReady=$isReady textLength=${text.length}")
        if (!isReady) {
            // TTS unavailable — fire callback immediately so flow can continue
            onComplete?.invoke()
            return
        }
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

        val utteranceId = "haq_${System.currentTimeMillis()}"
        pendingCount.incrementAndGet()
        _isSpeaking.value = true
        if (onComplete != null) completionCallbacks[utteranceId] = onComplete

        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.d("Haq/TTS", "tts.speak() dispatched utteranceId=$utteranceId")
    }

    fun stop() {
        pendingCount.set(0)
        completionCallbacks.clear()
        _isSpeaking.value = false
        tts?.stop()
    }

    fun shutdown() {
        pendingCount.set(0)
        completionCallbacks.clear()
        _isSpeaking.value = false
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
