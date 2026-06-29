package com.haq.app.stt

import android.content.ComponentName
import com.haq.app.inference.InferenceEngine
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    // Exploration flag — set true to route Whisper languages through Gemma's
    // built-in audio encoder instead. Flip to false to revert to Whisper path.
    const val USE_GEMMA_AUDIO = true

    private const val GOOGLE_STT_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val GOOGLE_TTS_PACKAGE  = "com.google.android.tts"
    private const val AIAI_PACKAGE        = "com.google.android.as"

    // BCP-47 tags that AiAi handles on-device (installed or downloadable via
    // triggerModelDownload). All other languages skip AiAi and fall through to
    // googlequicksearchbox with network.
    private val AIAI_CAPABLE = setOf("en-IN", "hi-IN")

    // Two-letter codes handled by Whisper ONNX on-device — no network required.
    // or/as are NOT in this set (Odia/Assamese are not in Whisper's 99 languages).
    private val WHISPER_LANGUAGES = setOf("te", "ml", "kn", "ta", "bn", "gu", "mr", "ne")

    // Singleton WhisperEngine — initialised lazily on first use.
    private var whisperEngine: WhisperEngine? = null

    /** True when [languageCode] is handled by Whisper ONNX (not SpeechRecognizer). */
    fun isWhisperLanguage(languageCode: String): Boolean = languageCode in WHISPER_LANGUAGES

    /**
     * Returns true when [languageCode] needs a network connection for STT.
     * AiAi languages (en, hi) and Whisper languages are fully offline.
     * or/as require network.
     */
    fun requiresNetwork(languageCode: String): Boolean {
        if (languageCode in WHISPER_LANGUAGES) return false
        return toBcp47(languageCode) !in AIAI_CAPABLE
    }

    /**
     * Returns true when the device has an active internet-capable network.
     * Uses [NetworkCapabilities] (API 29+, matching our minSdk).
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Application context stored on first call — avoids threading issues
    // with Activity contexts and survives configuration changes.
    private var appContext: Context? = null

    // Tracks the active recognizer so it can be destroyed before a new one
    // is created. Must only be accessed/mutated on the main thread.
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Returns true when the on-device STT model for [languageCode] is installed
     * and ready to use in airplane mode.
     *
     * On API 33+ this wraps [SpeechRecognizer.checkRecognitionSupport] and checks
     * whether [languageCode] appears in [RecognitionSupport.installedOnDeviceLanguages].
     * On API 29-32 there is no programmatic way to query installation status, so
     * this returns true immediately (the device will fall back gracefully).
     * On any error the check returns true to avoid blocking the gate indefinitely.
     */
    suspend fun isOfflineModelInstalled(context: Context, languageCode: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d("Haq/STT", "isOfflineModelInstalled: API < 33, skipping gate")
            return true
        }
        val ctx = context.applicationContext
        val bcp47 = toBcp47(languageCode)
        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                try {
                    val preferredService = preferredRecognitionService(ctx, bcp47).component
                    val recognizer = if (preferredService != null) {
                        SpeechRecognizer.createSpeechRecognizer(ctx, preferredService)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(ctx)
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
                    }
                    recognizer.checkRecognitionSupport(
                        intent,
                        Executors.newSingleThreadExecutor(),
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                val installed = support.installedOnDeviceLanguages
                                val ready = bcp47 in installed
                                Log.d("Haq/STT",
                                    "isOfflineModelInstalled: $bcp47 " +
                                    "installed=$installed → ready=$ready")
                                // destroy() must run on the main thread — the
                                // ServiceConnection is bound to the main Looper.
                                // Calling it from the executor thread fails to
                                // release the connection, leaving the service busy.
                                Handler(Looper.getMainLooper()).post { recognizer.destroy() }
                                if (continuation.isActive) continuation.resume(ready)
                            }
                            override fun onError(error: Int) {
                                Log.w("Haq/STT",
                                    "isOfflineModelInstalled: checkRecognitionSupport " +
                                    "error=$error for $bcp47 — assuming installed")
                                Handler(Looper.getMainLooper()).post { recognizer.destroy() }
                                if (continuation.isActive) continuation.resume(true)
                            }
                        }
                    )
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { recognizer.destroy() }
                    }
                } catch (e: Exception) {
                    Log.w("Haq/STT", "isOfflineModelInstalled failed: ${e.message} — assuming installed")
                    if (continuation.isActive) continuation.resume(true)
                }
            }
        }
    }

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
                val serviceResult = preferredRecognitionService(ctx, bcp47)
                val preferredService = serviceResult.component
                Log.d("Haq/STT", "triggerOfflineModelDownload: querying service=$preferredService for $bcp47")
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
                            Handler(Looper.getMainLooper()).post { recognizerForDownload.destroy() }
                        }
                        override fun onError(error: Int) {
                            Log.w("Haq/STT",
                                "checkRecognitionSupport error $error for $bcp47")
                            Handler(Looper.getMainLooper()).post { recognizerForDownload.destroy() }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w("Haq/STT", "triggerOfflineModelDownload failed: ${e.message}")
            }
        }
    }

    /**
     * One-shot diagnostic: queries every target Indian language against the
     * AiAi service specifically (regardless of [preferredRecognitionService])
     * and logs the full installed+supported lists. Call once from MainActivity
     * or onboarding to answer: "can AiAi download Hindi/Telugu/etc. offline?"
     *
     * Noop on API < 33 or if AiAi is not installed.
     */
    fun logAiAiSupportedLanguages(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val ctx = context.applicationContext
        val allServices = ctx.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val aiaiService = allServices.firstOrNull { info ->
            info.serviceInfo?.packageName == AIAI_PACKAGE
        }?.serviceInfo ?: run {
            Log.d("Haq/STT", "logAiAiSupportedLanguages: AiAi not installed")
            return
        }
        val aiaiComponent = ComponentName(aiaiService.packageName, aiaiService.name)
        Log.d("Haq/STT", "logAiAiSupportedLanguages: probing $aiaiComponent")

        // Probe all target languages in one shot using a generic intent —
        // AiAi returns the same installed/supported lists regardless of the
        // EXTRA_LANGUAGE value; we use en-IN as a neutral probe language.
        Handler(Looper.getMainLooper()).post {
            try {
                val rec = SpeechRecognizer.createSpeechRecognizer(ctx, aiaiComponent)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                }
                rec.checkRecognitionSupport(
                    intent,
                    Executors.newSingleThreadExecutor(),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            val installed = support.installedOnDeviceLanguages
                            val supported = support.supportedOnDeviceLanguages
                            Log.d("Haq/STT",
                                "AiAi ALL installed languages: $installed")
                            Log.d("Haq/STT",
                                "AiAi ALL supported (downloadable) languages: $supported")
                            val targets = listOf("hi-IN","te-IN","ml-IN","kn-IN",
                                "ta-IN","bn-IN","gu-IN","mr-IN","en-IN")
                            targets.forEach { lang ->
                                val status = when {
                                    lang in installed -> "INSTALLED (offline ready)"
                                    lang in supported -> "SUPPORTED (can download)"
                                    else             -> "NOT SUPPORTED"
                                }
                                Log.d("Haq/STT", "AiAi $lang → $status")
                            }
                            Handler(Looper.getMainLooper()).post { rec.destroy() }
                        }
                        override fun onError(error: Int) {
                            Log.w("Haq/STT",
                                "logAiAiSupportedLanguages: checkRecognitionSupport error=$error")
                            Handler(Looper.getMainLooper()).post { rec.destroy() }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w("Haq/STT", "logAiAiSupportedLanguages failed: ${e.message}")
            }
        }
    }

    /**
     * Whether the selected recognition service supports EXTRA_PREFER_OFFLINE.
     * AiAi is on-device by design (flag not needed, causes ERROR_RECOGNIZER_BUSY).
     * googlequicksearchbox honours the flag for its downloaded on-device models.
     * GoogleTTSRecognitionService is network-only; flag causes immediate error 12.
     */
    private data class RecognitionServiceResult(
        val component: ComponentName?,
        val supportsPreferOffline: Boolean,
    )

    /**
     * Returns the best recognition service for [bcp47].
     *
     * - null [bcp47]: onboarding auto-detect path — always prefers AiAi (on-device).
     * - [bcp47] in [AIAI_CAPABLE]: AiAi handles it on-device (installed or downloadable).
     * - everything else: AiAi is skipped; falls through to googlequicksearchbox
     *   with network, which supports all Indian language tags.
     */
    private fun preferredRecognitionService(context: Context, bcp47: String?): RecognitionServiceResult {
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

        // P1: AiAi — on-device, no network. Only for languages it can handle:
        // null (onboarding auto-detect), en-IN (installed), hi-IN (downloadable).
        val aiaiService = allServices.firstOrNull { info ->
            info.serviceInfo?.packageName == AIAI_PACKAGE
        }?.serviceInfo
        if (aiaiService != null && (bcp47 == null || bcp47 in AIAI_CAPABLE)) {
            val component = ComponentName(aiaiService.packageName, aiaiService.name)
            Log.d("Haq/STT", "Using AiAi on-device STT: $component (lang=$bcp47)")
            return RecognitionServiceResult(component, supportsPreferOffline = false)
        }

        // P2: googlequicksearchbox — all Indian language tags, network-dependent.
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE).setPackage(GOOGLE_STT_PACKAGE), 0)
        val googleService = services.firstOrNull { info ->
            info.serviceInfo?.packageName == GOOGLE_STT_PACKAGE
        }?.serviceInfo
        if (googleService != null) {
            val component = ComponentName(googleService.packageName, googleService.name)
            Log.d("Haq/STT", "Using Google STT service (network): $component (lang=$bcp47)")
            return RecognitionServiceResult(component, supportsPreferOffline = false)
        }

        // P3: GoogleTTSRecognitionService — network-only fallback.
        // EXTRA_PREFER_OFFLINE=true causes immediate ERROR_RECOGNIZER_BUSY (12).
        val ttsBundled = allServices.firstOrNull { info ->
            info.serviceInfo?.packageName == GOOGLE_TTS_PACKAGE
        }?.serviceInfo
        if (ttsBundled != null) {
            val component = ComponentName(ttsBundled.packageName, ttsBundled.name)
            Log.d("Haq/STT", "Using GoogleTTS-bundled STT service (network): $component")
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
     *
     * For [WHISPER_LANGUAGES] (te/ml/kn/ta/bn/gu/mr/ne) the call bypasses
     * SpeechRecognizer entirely and runs Whisper ONNX on-device.
     * For en/hi it uses AiAi (on-device via SpeechRecognizer).
     * For or/as it falls through to the network STT path.
     */
    suspend fun recordAndTranscribe(
        context: Context,
        languageTag: String? = null,
        onVadComplete: (() -> Unit)? = null,
        inferenceEngine: InferenceEngine? = null,
    ): String {
        appContext = context.applicationContext
        val twoLetter = languageTag?.let { toTwoLetter(it) }
        if (twoLetter != null && twoLetter in WHISPER_LANGUAGES) {
            if (USE_GEMMA_AUDIO && inferenceEngine != null) {
                return transcribeWithGemmaAudio(
                    context.applicationContext, twoLetter, inferenceEngine, onVadComplete
                )
            }
            return transcribeWithWhisper(context.applicationContext, twoLetter, onVadComplete)
        }
        return recordAndTranscribeWithLanguage(languageTag = languageTag, isOnboarding = false)
    }

    // ── Whisper path ──────────────────────────────────────────────────────────

    /**
     * Records audio with VAD and transcribes using Whisper ONNX on-device.
     * Initialises [WhisperEngine] lazily on first call (~1-2 s overhead once).
     * If model files are not present, throws so the caller can surface an error.
     */
    private suspend fun transcribeWithWhisper(
        context: Context,
        langCode: String,
        onVadComplete: (() -> Unit)? = null,
    ): String {
        if (!WhisperEngine.isAvailable(context)) {
            throw IllegalStateException(
                "Whisper models not found in filesDir/whisper/ — " +
                "adb push encoder_model.onnx, decoder_model_merged.onnx, tokenizer.json"
            )
        }
        val engine = whisperEngine ?: WhisperEngine(context).also {
            it.init()
            whisperEngine = it
        }
        val recorder = AudioRecorder()
        val samples  = recorder.recordWithVad()
        if (samples.isEmpty()) return ""
        // VAD complete — recording stopped, inference about to start.
        // Signal caller so UI can transition from LISTENING to PROCESSING.
        onVadComplete?.invoke()
        return engine.transcribe(samples, WhisperConfig.languageToken(langCode))
    }

    // ── Gemma audio path ─────────────────────────────────────────────────────

    /**
     * Records audio with VAD then transcribes using Gemma 4 E2B's built-in
     * audio encoder. Replaces the Whisper path when [USE_GEMMA_AUDIO] is true.
     *
     * The same [AudioRecorder] and VAD logic is reused — only the inference
     * backend changes. The collected transcript is returned as a plain string,
     * identical to what [transcribeWithWhisper] would return, so the rest of
     * the ViewModel pipeline is unaffected.
     */
    private suspend fun transcribeWithGemmaAudio(
        context: Context,
        langCode: String,
        engine: InferenceEngine,
        onVadComplete: (() -> Unit)? = null,
    ): String {
        val languageName = langCodeToName(langCode)
        val recorder = AudioRecorder()
        val samples = recorder.recordWithVad()
        if (samples.isEmpty()) return ""
        onVadComplete?.invoke()

        val transcript = StringBuilder()
        engine.transcribeAudio(samples, languageName).collect { chunk ->
            transcript.append(chunk)
        }
        val result = transcript.toString().trim()
        Log.d("Haq/GemmaAudio", "Transcript ($langCode): \"$result\"")
        return result
    }

    /** Maps two-letter language codes to full names for the Gemma transcription prompt. */
    private fun langCodeToName(code: String): String = when (code) {
        "te" -> "Telugu"
        "ml" -> "Malayalam"
        "kn" -> "Kannada"
        "ta" -> "Tamil"
        "bn" -> "Bengali"
        "gu" -> "Gujarati"
        "mr" -> "Marathi"
        "ne" -> "Nepali"
        else -> "English"
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

                val serviceResult = preferredRecognitionService(ctx, bcp47)
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

    /**
     * Releases the WhisperEngine ONNX sessions and native memory.
     * Call from HaqViewModel.onCleared() so the ~144 MB of ONNX session memory
     * is released when the ViewModel is destroyed rather than waiting for GC.
     */
    fun shutdown() {
        whisperEngine?.close()
        whisperEngine = null
        Log.d("Haq/STT", "STTManager shutdown — WhisperEngine released")
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

    /**
     * Strips the region suffix from a BCP-47 tag or passes a two-letter code through.
     * "te-IN" → "te", "te" → "te", null → null.
     */
    private fun toTwoLetter(tag: String): String = tag.substringBefore('-')
}
