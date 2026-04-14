package com.haq.app.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object STTManager {

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens until the recognizer detects end-of-speech, then returns the
     * best transcript. Must be called from the main thread (viewModelScope is
     * fine — it defaults to Dispatchers.Main).
     */
    suspend fun recognize(context: Context): String = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Don't set EXTRA_LANGUAGE — lets the recognizer use the device locale,
            // which matches the user's chosen system language (Hindi / Telugu / etc.)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                recognizer.destroy()
                if (cont.isActive) cont.resume(matches?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                recognizer.destroy()
                if (cont.isActive) cont.resumeWithException(
                    Exception("Speech recognition error code $error")
                )
            }
            // Mandatory no-op overrides
            override fun onReadyForSpeech(params: Bundle) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onEvent(eventType: Int, params: Bundle) {}
        })

        recognizer.startListening(intent)

        cont.invokeOnCancellation {
            recognizer.stopListening()
            recognizer.destroy()
        }
    }
}
