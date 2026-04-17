package com.haq.app.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object STTManager {

    private const val GOOGLE_STT_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val GOOGLE_STT_SERVICE =
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"

    // Application context stored on first call — avoids threading issues
    // with Activity contexts and survives configuration changes.
    private var appContext: Context? = null

    // Tracks the active recognizer so it can be destroyed before a new one
    // is created. Must only be accessed/mutated on the main thread.
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Onboarding: no language hint (device auto-detects), generous VAD
     * thresholds to give users time to gather their thoughts.
     */
    suspend fun recordForOnboarding(context: Context): String {
        appContext = context.applicationContext
        return recordAndTranscribeWithLanguage(languageTag = null, isOnboarding = true)
    }

    /**
     * Main app: pass the user's profile language code ("hi", "te", "ml",
     * "kn", "en") or a BCP-47 tag. Pass null for device auto-detection.
     */
    suspend fun recordAndTranscribe(
        context: Context,
        languageTag: String? = null,
    ): String {
        appContext = context.applicationContext
        return recordAndTranscribeWithLanguage(languageTag = languageTag, isOnboarding = false)
    }

    // ── Internal ──────────────────────────────────────────────────────────────
    //
    // SpeechRecognizer MUST be created AND startListening() called on the main
    // thread. Handler(Looper.getMainLooper()).post() posts directly to the
    // Android main Looper message queue — the only mechanism SpeechRecognizer's
    // internal Handler machinery reliably responds to. withContext(Dispatchers.Main)
    // is insufficient because Kotlin's dispatcher doesn't guarantee posting
    // through the same Looper that SpeechRecognizer monitors internally.

    private suspend fun recordAndTranscribeWithLanguage(
        languageTag: String?,
        isOnboarding: Boolean,
    ): String {
        val ctx = appContext
            ?: throw IllegalStateException("STTManager: context not set")

        // Map two-letter codes to BCP-47; already-formatted tags pass through.
        // null → no language extra → device auto-detects.
        val bcp47: String? = when (languageTag) {
            null -> null
            "hi" -> "hi-IN"
            "te" -> "te-IN"
            "ml" -> "ml-IN"
            "kn" -> "kn-IN"
            "en" -> "en-IN"
            else -> languageTag
        }

        return suspendCancellableCoroutine { continuation ->
            // Post the entire recognizer lifecycle to the main thread.
            Handler(Looper.getMainLooper()).post {
                // Destroy any lingering recognizer before creating a new one.
                recognizer?.destroy()
                recognizer = null

                val googleSttAvailable = SpeechRecognizer.isRecognitionAvailable(ctx) &&
                    try {
                        ctx.packageManager.getPackageInfo(GOOGLE_STT_PACKAGE, 0)
                        true
                    } catch (e: PackageManager.NameNotFoundException) {
                        false
                    }
                Log.d("Haq/STT", "Using recognizer: google=$googleSttAvailable")

                val freshRecognizer = if (googleSttAvailable) {
                    SpeechRecognizer.createSpeechRecognizer(
                        ctx,
                        ComponentName(GOOGLE_STT_PACKAGE, GOOGLE_STT_SERVICE)
                    )
                } else {
                    SpeechRecognizer.createSpeechRecognizer(ctx)
                }
                recognizer = freshRecognizer

                freshRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("Haq/STT", "Ready for speech")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d("Haq/STT", "Speech detected")
                    }
                    override fun onResults(results: Bundle?) {
                        val transcript = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: ""
                        Log.d("Haq/STT", "Transcript: '$transcript'")
                        freshRecognizer.destroy()
                        recognizer = null
                        if (continuation.isActive) continuation.resume(transcript)
                    }
                    override fun onError(error: Int) {
                        Log.e("Haq/STT", "Error: $error")
                        freshRecognizer.destroy()
                        recognizer = null
                        if (continuation.isActive) continuation.resumeWithException(
                            Exception("Speech recognition error code $error")
                        )
                    }
                    override fun onEndOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onPartialResults(results: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                // Generous thresholds for onboarding; tighter for main app queries.
                val minMs      = if (isOnboarding) 3000L else 2000L
                val completeMs = if (isOnboarding) 5000L else 2500L
                val possibleMs = if (isOnboarding) 3000L else 1500L

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minMs)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeMs)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleMs)
                    if (!bcp47.isNullOrBlank()) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, bcp47)
                    }
                }

                Log.d("Haq/STT",
                    "Starting recognition on main thread, " +
                    "languageTag=$languageTag, isOnboarding=$isOnboarding, " +
                    "thread=${Looper.myLooper() == Looper.getMainLooper()}")

                freshRecognizer.startListening(intent)

                continuation.invokeOnCancellation {
                    // Cancellation can fire on any thread; post destruction to main.
                    Handler(Looper.getMainLooper()).post {
                        freshRecognizer.destroy()
                        recognizer = null
                    }
                }
            }
        }
    }
}
