package com.haq.app.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object STTManager {

    private const val GOOGLE_STT_PACKAGE = "com.google.android.googlequicksearchbox"
    // On devices where googlequicksearchbox is absent, Google STT is bundled
    // into the TTS package. This service supports Indian languages and should
    // be preferred over AiAiSpeechRecognitionService (com.google.android.as)
    // which only accepts a small set of language tags.
    private const val GOOGLE_TTS_PACKAGE  = "com.google.android.tts"

    // Application context stored on first call — avoids threading issues
    // with Activity contexts and survives configuration changes.
    private var appContext: Context? = null

    // Tracks the active recognizer so it can be destroyed before a new one
    // is created. Must only be accessed/mutated on the main thread.
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * On API 33+, proactively triggers download of the on-device speech
     * recognition model for [languageCode]. Called once during onboarding
     * (PreparingVoices) while the device has WiFi. On API 29-32 there is
     * no programmatic download API — the implicit download via
     * EXTRA_PREFER_OFFLINE=true on first main-app use handles those devices.
     */
    fun triggerOfflineModelDownload(context: Context, languageCode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return  // API 33+
        val ctx = context.applicationContext
        val bcp47 = toBcp47(languageCode)
        Handler(Looper.getMainLooper()).post {
            try {
                val preferredService = preferredRecognitionService(ctx).component
                val recognizerForDownload = if (preferredService != null) {
                    SpeechRecognizer.createSpeechRecognizer(ctx, preferredService)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(ctx)
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
                }
                recognizerForDownload.checkRecognitionSupport(
                    intent,
                    Executors.newSingleThreadExecutor(),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            val installed = recognitionSupport.installedOnDeviceLanguages
                            val supported = recognitionSupport.supportedOnDeviceLanguages
                            Log.d("Haq/STT",
                                "triggerOfflineModelDownload: $bcp47 " +
                                "installed=$installed supported=$supported")
                            if (bcp47 !in installed && bcp47 in supported) {
                                recognizerForDownload.triggerModelDownload(intent)
                                Log.d("Haq/STT", "triggerModelDownload() called for $bcp47")
                            } else {
                                Log.d("Haq/STT",
                                    "triggerOfflineModelDownload: skipped " +
                                    "(installed=${ bcp47 in installed }, " +
                                    "supported=${ bcp47 in supported })")
                            }
                            recognizerForDownload.destroy()
                        }
                        override fun onError(error: Int) {
                            Log.w("Haq/STT",
                                "checkRecognitionSupport error $error for $bcp47")
                            recognizerForDownload.destroy()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w("Haq/STT", "triggerOfflineModelDownload failed: ${e.message}")
            }
        }
    }

    /**
     * Whether the selected recognition service supports EXTRA_PREFER_OFFLINE.
     * GoogleTTSRecognitionService and googlequicksearchbox both have on-device
     * Indian-language models and honour the flag correctly.
     * AiAi (com.google.android.as) is already on-device by design and returns
     * ERROR_RECOGNIZER_BUSY (12) when EXTRA_PREFER_OFFLINE=true is set.
     */
    private data class RecognitionServiceResult(
        val component: ComponentName?,
        val supportsPreferOffline: Boolean,
    )

    private fun preferredRecognitionService(context: Context): RecognitionServiceResult {
        // Log ALL available recognition services once so we can identify
        // the correct package/component on this device.
        val allServices = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0)
        if (allServices.isEmpty()) {
            Log.w("Haq/STT", "No RecognitionService found on device at all")
        } else {
            allServices.forEach { info ->
                Log.d("Haq/STT", "RecognitionService available: " +
                    "pkg=${info.serviceInfo?.packageName} " +
                    "cls=${info.serviceInfo?.name}")
            }
        }

        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE).setPackage(GOOGLE_STT_PACKAGE),
            0,
        )
        val googleService = services.firstOrNull { info ->
            info.serviceInfo?.packageName == GOOGLE_STT_PACKAGE
        }?.serviceInfo

        if (googleService != null) {
            val component = ComponentName(googleService.packageName, googleService.name)
            Log.d("Haq/STT", "Using Google STT service: $component")
            return RecognitionServiceResult(component, supportsPreferOffline = true)
        }

        // googlequicksearchbox not found — check if Google STT is bundled in the
        // TTS package (com.google.android.tts). This is the case on devices where
        // the traditional Google Search app is absent. Prefer it over AiAi because
        // it supports Indian language tags (mr-IN, te-IN, hi-IN, etc.) while AiAi
        // returns ERROR_LANGUAGE_NOT_SUPPORTED (12) for non-English tags.
        // This service has downloadable on-device models and honours EXTRA_PREFER_OFFLINE.
        val ttsBundled = allServices.firstOrNull { info ->
            info.serviceInfo?.packageName == GOOGLE_TTS_PACKAGE
        }?.serviceInfo
        if (ttsBundled != null) {
            val component = ComponentName(ttsBundled.packageName, ttsBundled.name)
            Log.d("Haq/STT", "Using GoogleTTS-bundled STT service: $component")
            return RecognitionServiceResult(component, supportsPreferOffline = true)
        }

        // Last resort: AiAi or any other google-named service. AiAi is on-device
        // by design so EXTRA_PREFER_OFFLINE is irrelevant (and causes ERROR 12).
        val googleFallback = allServices.firstOrNull { info ->
            info.serviceInfo?.packageName?.contains("google", ignoreCase = true) == true
        }?.serviceInfo
        if (googleFallback != null) {
            val component = ComponentName(googleFallback.packageName, googleFallback.name)
            Log.d("Haq/STT", "Using Google STT last-resort fallback: $component")
            return RecognitionServiceResult(component, supportsPreferOffline = false)
        }

        Log.w("Haq/STT", "No suitable Google STT service found, using system default")
        return RecognitionServiceResult(null, supportsPreferOffline = false)
    }

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
        val bcp47: String? = languageTag?.let { toBcp47(it) }

        return suspendCancellableCoroutine { continuation ->
            // Post the entire recognizer lifecycle to the main thread.
            Handler(Looper.getMainLooper()).post {
                // Destroy any lingering recognizer before creating a new one.
                recognizer?.destroy()
                recognizer = null

                val serviceResult = preferredRecognitionService(ctx)
                val freshRecognizer = if (serviceResult.component != null) {
                    SpeechRecognizer.createSpeechRecognizer(ctx, serviceResult.component)
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
                        // stopListening() before destroy() so the service cancels the
                        // active session before we release the binding. Without this,
                        // destroy() initiates cleanup asynchronously and the service
                        // remains "busy" for ~1-2 s, causing ERROR_RECOGNIZER_BUSY (12)
                        // on the next attempt.
                        try { freshRecognizer.stopListening() } catch (_: Exception) {}
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

                // Onboarding: generous thresholds (users gathering their thoughts).
                // Main app: longer silence detection so natural pauses aren't cut off.
                val minMs      = if (isOnboarding) 3000L else 3000L
                val completeMs = if (isOnboarding) 5000L else 4000L
                val possibleMs = if (isOnboarding) 3000L else 2000L

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    // Set EXTRA_PREFER_OFFLINE=true only for services that have
                    // downloadable on-device models and honour the flag correctly
                    // (googlequicksearchbox, GoogleTTSRecognitionService).
                    // AiAi (com.google.android.as) is on-device by design and returns
                    // ERROR_RECOGNIZER_BUSY (12) when this flag is true — leave it false
                    // for that fallback path.
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, serviceResult.supportsPreferOffline)
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

    /** Maps two-letter language codes to BCP-47 tags for SpeechRecognizer. */
    private fun toBcp47(languageCode: String): String = when (languageCode) {
        "hi" -> "hi-IN"
        "te" -> "te-IN"
        "ml" -> "ml-IN"
        "kn" -> "kn-IN"
        "ta" -> "ta-IN"
        "bn" -> "bn-IN"
        "gu" -> "gu-IN"
        "mr" -> "mr-IN"
        "or" -> "or-IN"
        "as" -> "as-IN"
        "ne" -> "ne-IN"
        "en" -> "en-IN"
        else -> languageCode
    }
}
