package com.haq.app.tts

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

object TTSManager {

    private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    private var tts: TextToSpeech? = null
    private var isReady = false

    // Stored on first init() so reinitialiseAndWait() can restart without a Context parameter.
    private var appContext: Context? = null

    // ── Ready state ───────────────────────────────────────────────────────────

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    // ── Speaking state ─────────────────────────────────────────────────────────

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // ── Voice cache ───────────────────────────────────────────────────────────
    // Prevents gender/quality flicker between consecutive speak() calls.
    // Cleared on reinitialise() so the engine rescan picks up newly installed packs.

    private val cachedVoices = mutableMapOf<String, Voice>()

    // ── Required languages ────────────────────────────────────────────────────
    // All 5 languages Haq supports. Used for pre-onboarding voice readiness checks.

    private val REQUIRED_LANGUAGES = listOf(
        "hi" to Locale("hi", "IN"),
        "te" to Locale("te", "IN"),
        "ml" to Locale("ml", "IN"),
        "kn" to Locale("kn", "IN"),
        "ta" to Locale("ta", "IN"),
        "bn" to Locale("bn", "IN"),
        "gu" to Locale("gu", "IN"),
        "mr" to Locale("mr", "IN"),
        "or" to Locale("or", "IN"),
        "as" to Locale("as", "IN"),
        "ne" to Locale("ne", "IN"),
        "en" to Locale("en", "IN"),
    )

    private val TEST_UTTERANCES = mapOf(
        "hi" to "नमस्ते",
        "te" to "నమస్తే",
        "ml" to "നമസ്കാരം",
        "kn" to "ನಮಸ್ಕಾರ",
        "ta" to "வணக்கம்",
        "bn" to "নমস্কার",
        "gu" to "નમસ્તે",
        "mr" to "नमस्कार",
        "or" to "ନମସ୍କାର",
        "as" to "নমস্কাৰ",
        "ne" to "नमस्ते",
        "en" to "hello",
    )

    // ── Locale helper ─────────────────────────────────────────────────────────

    private fun localeFor(languageCode: String): Locale = when (languageCode) {
        "te" -> Locale("te", "IN")
        "ml" -> Locale("ml", "IN")
        "kn" -> Locale("kn", "IN")
        "ta" -> Locale("ta", "IN")
        "bn" -> Locale("bn", "IN")
        "gu" -> Locale("gu", "IN")
        "mr" -> Locale("mr", "IN")
        "or" -> Locale("or", "IN")
        "as" -> Locale("as", "IN")
        "ne" -> Locale("ne", "IN")
        "en" -> Locale("en", "IN")
        else -> Locale("hi", "IN")
    }

    // ── Voice selection ───────────────────────────────────────────────────────
    //
    // Voices are cached after first selection to prevent gender/quality flicker.
    // Priority order (offline voices preferred over online to avoid network waits):
    //   P1: non-OEM offline voice, exact locale
    //   P2: non-OEM online voice, exact locale
    //   P3: any offline voice, exact locale
    //   P4: any voice, exact locale
    //   P5: any voice, language only
    //   P6: null — no voice found for this language

    fun findBestVoice(languageCode: String): Voice? {
        // Return cached voice if still in the current engine's voice list
        cachedVoices[languageCode]?.let { cached ->
            if (tts?.voices?.contains(cached) == true) {
                Log.d("Haq/TTS", "Cached voice: ${cached.name}")
                return cached
            }
            cachedVoices.remove(languageCode)
        }

        val targetLocale = localeFor(languageCode)
        val voices = tts?.voices ?: return null
        Log.d("Haq/TTS", "Finding voice for $languageCode total=${voices.size}")

        // Stubs (names ending in "-language") are excluded at every priority tier.
        // Google TTS lists stub entries for languages whose data is not yet downloaded.
        // If a stub wins here, speak() rejects it and fires onOutputError — preventing
        // any real voice (including Samsung's) at lower priorities from being tried.
        // By excluding stubs throughout, real Samsung voices are reachable at P3/P4
        // whenever Google's local voice is missing or has been evicted from the list.

        // P1: non-OEM offline non-stub voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.name.endsWith("-language") &&
            !v.name.contains("samsung", ignoreCase = true) &&
            !v.name.contains("smt", ignoreCase = true) &&
            !v.isNetworkConnectionRequired
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P1 offline non-OEM: ${it.name}"); return it }

        // P2: non-OEM online non-stub voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.name.endsWith("-language") &&
            !v.name.contains("samsung", ignoreCase = true) &&
            !v.name.contains("smt", ignoreCase = true)
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P2 online non-OEM: ${it.name}"); return it }

        // P3: any offline non-stub voice, exact locale (Samsung fallback)
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.name.endsWith("-language") &&
            !v.isNetworkConnectionRequired
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P3 any offline non-stub: ${it.name}"); return it }

        // P4: any non-stub voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.name.endsWith("-language")
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P4 any non-stub exact: ${it.name}"); return it }

        // P5: any non-stub voice, language only
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            !v.name.endsWith("-language")
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P5 language only: ${it.name}"); return it }

        // P6: only stub or nothing found — voice data may have been removed after a -4 error.
        // Log warning but do NOT call reinitialise() here — let the onOutputError → broadcast flow handle it.
        val stubOrNull = voices.firstOrNull { v -> v.locale.language == targetLocale.language }
        Log.w("Haq/TTS", "findBestVoice $languageCode: only stub or null found (${stubOrNull?.name}) " +
            "— voice data may have been removed, reinitialise needed")
        return stubOrNull  // null or stub; speak() handles stub via endsWith("-language") guard
    }

    // ── Voice pack availability ────────────────────────────────────────────────

    fun isLanguageAvailable(locale: Locale): Int =
        tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

    /**
     * Returns true if a real (non-stub) voice exists for [languageCode].
     * Stub voices ending in "-language" have no synthesis data and produce
     * ERROR_OUTPUT (-4) when used — treat them as unsupported.
     */
    fun checkLanguageSupport(languageCode: String): Boolean {
        val voice = findBestVoice(languageCode)
        if (voice == null) {
            Log.d("Haq/TTS", "checkLanguageSupport $languageCode: no voice found")
            return false
        }
        if (voice.name.endsWith("-language")) {
            Log.d("Haq/TTS", "checkLanguageSupport $languageCode: stub voice ${voice.name} — unsupported")
            return false
        }
        Log.d("Haq/TTS", "checkLanguageSupport $languageCode: supported voice=${voice.name}")
        return true
    }

    /**
     * Calls setLanguage() for a single language. When Google TTS detects missing
     * synthesis data, it begins a silent background download — no UI is shown.
     * Safe to call at any time; idempotent if voice data is already present.
     *
     * Runs on [Dispatchers.IO] because setLanguage() IPC can block ~500 ms.
     */
    suspend fun ensureVoiceDownloading(languageCode: String) = withContext(Dispatchers.IO) {
        val locale = REQUIRED_LANGUAGES.firstOrNull { it.first == languageCode }?.second
            ?: Locale(languageCode, "IN")
        val result = tts?.setLanguage(locale)
        Log.d("Haq/TTS", "ensureVoiceDownloading($languageCode) result=$result")
    }

    /**
     * Calls setLanguage() for all 5 required languages. When Google TTS detects
     * missing synthesis data, it begins a silent background download — no UI is shown.
     * Safe to call at any time after init(). Idempotent for languages already present.
     *
     * Runs on [Dispatchers.IO] because each setLanguage() IPC call can block ~500 ms;
     * keeping them off the main thread prevents dropped frames during PreparingVoices.
     */
    suspend fun ensureAllVoicesDownloading() = withContext(Dispatchers.IO) {
        Log.d("Haq/TTS", "Requesting silent download for all languages")
        REQUIRED_LANGUAGES.forEach { (code, locale) ->
            val result = tts?.setLanguage(locale)
            Log.d("Haq/TTS", "setLanguage($code) result=$result")
        }
    }

    /** Returns true only if all 5 required languages have a real (non-stub) voice. */
    fun areAllVoicesReady(): Boolean =
        REQUIRED_LANGUAGES.all { (code, _) -> checkLanguageSupport(code) }

    /** Returns the language codes that do not yet have a usable voice. */
    fun getMissingLanguages(): List<String> =
        REQUIRED_LANGUAGES
            .filter { (code, _) -> !checkLanguageSupport(code) }
            .map { (code, _) -> code }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        Log.d("Haq/TTS", "init() called, already=${tts != null}, " +
            "caller=${Thread.currentThread().stackTrace.getOrNull(3)}")
        if (tts != null) {
            Log.d("Haq/TTS", "Already initialised, skipping. engine=${tts?.defaultEngine}")
            return
        }
        appContext = context.applicationContext

        val googleInstalled = isGoogleTtsInstalled(context)
        Log.d("Haq/TTS", "Google TTS installed: $googleInstalled")

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
                // defaultEngine returns the *system* default (may be Samsung SMT even when we
                // explicitly bound to Google TTS). Log the engine list instead so we can verify
                // the binding actually worked.
                val engines = tts?.engines ?: emptyList()
                Log.d("Haq/TTS", "TTS init SUCCESS — available engines: ${engines.map { it.name }}")
                val googleBound = engines.any { it.name == GOOGLE_TTS_PACKAGE }
                Log.d("Haq/TTS", "Google TTS in engine list: $googleBound  systemDefault=${tts?.defaultEngine}")
                val voices = tts?.voices ?: emptySet()
                val googleVoiceCount = voices.count { v ->
                    // Google TTS voices follow xx-yy-x-zzz-local/network pattern.
                    // Samsung SMT voices use smt-* or samsung-* naming.
                    v.name.matches(Regex("[a-z]{2}-[a-z]{2}-x-[a-z]{3,5}-(local|network)"))
                }
                Log.d("Haq/TTS", "Voices: total=${voices.size} google-pattern=$googleVoiceCount")
                if (googleVoiceCount == 0) {
                    Log.e("Haq/TTS", "WARN: no Google TTS voices found — OEM engine may have taken over")
                }
                _ttsReady.value = true
            } else {
                Log.e("Haq/TTS", "TTS init failed: $status")
            }
        }

        tts = if (googleInstalled) {
            Log.d("Haq/TTS", "Using Google TTS engine")
            createTtsInstance(context, listener)
        } else {
            Log.w("Haq/TTS", "Google TTS not found, using system default")
            createTtsInstance(context, listener)
        }
    }

    private fun isGoogleTtsInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GOOGLE_TTS_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun createTtsInstance(
        context: Context,
        listener: TextToSpeech.OnInitListener,
    ): TextToSpeech {
        return if (isGoogleTtsInstalled(context)) {
            Log.d("Haq/TTS", "Creating Google TTS instance")
            TextToSpeech(context, listener, GOOGLE_TTS_PACKAGE)
        } else {
            Log.w("Haq/TTS", "Google TTS not found, creating system-default TTS instance")
            TextToSpeech(context, listener)
        }
    }

    // ── speak() ────────────────────────────────────────────────────────────────
    //
    // CRITICAL: setOnUtteranceProgressListener() is called BEFORE tts.speak().
    // Setting it after speak() risks missing onDone for short utterances because
    // the engine can complete and fire callbacks before the listener is registered.
    //
    // A fresh per-call listener is used so each onComplete callback is isolated
    // to its own utteranceId — no shared map, no race condition.

    fun speak(
        text: String,
        languageCode: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        onComplete: (() -> Unit)? = null,
        onOutputError: (() -> Unit)? = null,
        silent: Boolean = false,
    ) {
        if (!isReady) {
            // TTS unavailable — treat as output error for callers that distinguish
            // (e.g. testSpeak must return false, not true, when engine is not ready).
            onOutputError?.invoke() ?: onComplete?.invoke()
            return
        }

        val voice = findBestVoice(languageCode)
        if (voice == null || voice.name.endsWith("-language")) {
            // No real voice available — fire onOutputError so recovery can reinitialise.
            // Calling onComplete here would leave the app thinking speech succeeded.
            Log.w("Haq/TTS", "speak(): stub or no voice for $languageCode — invoking onOutputError")
            cachedVoices.remove(languageCode)
            onOutputError?.invoke() ?: onComplete?.invoke()
            return
        }
        tts?.voice = voice
        Log.d("Haq/TTS", "speak() using voice=${voice.name} for lang=$languageCode")

        // Strip markdown formatting before speaking
        val ttsText = text.replace("**", "").replace("*", "").replace("#", "").trim()
        if (ttsText.isEmpty()) {
            onComplete?.invoke()
            return
        }

        val utteranceId = "haq_${System.currentTimeMillis()}"

        // CRITICAL: register listener BEFORE speak() so no events are missed.
        // Always registered (not just when onComplete != null) so _isSpeaking and
        // error handling work on fire-and-forget calls too.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String?) {
                Log.d("Haq/TTS", "onStart: $uid")
                _isSpeaking.value = true
            }

            override fun onDone(uid: String?) {
                Log.d("Haq/TTS", "onDone: $uid")
                _isSpeaking.value = false
                if (uid == utteranceId) onComplete?.invoke()
            }

            // Samsung with Google voices fires the no-code onError instead of the
            // two-arg version for -4 output errors. Since speak() only reaches this
            // point with a real (non-stub) voice, any codeless error is treated as
            // an output error so speakOnboarding() receives the correct signal.
            @Suppress("DEPRECATION")
            override fun onError(uid: String?) {
                if (uid != utteranceId) return
                Log.e("Haq/TTS", "onError uid=$uid code=(none) invoking onOutputError for $languageCode")
                _isSpeaking.value = false
                clearCachedVoice(languageCode)
                if (onOutputError != null) {
                    onOutputError.invoke()
                } else {
                    onComplete?.invoke()
                }
            }

            override fun onError(uid: String?, errorCode: Int) {
                if (uid != utteranceId) return
                Log.e("Haq/TTS", "onError uid=$uid code=$errorCode invoking onOutputError for $languageCode")
                _isSpeaking.value = false
                if (errorCode == TextToSpeech.ERROR_OUTPUT) {
                    clearCachedVoice(languageCode)
                }
                if (onOutputError != null) {
                    onOutputError.invoke()
                } else {
                    onComplete?.invoke()
                }
            }
        })

        // Use 0.01f (not 0f) for silent mode. A volume of exactly 0f bypasses the synthesis
        // pipeline on some engines (returning ERROR_OUTPUT immediately) and never triggers
        // the background voice-data download. At 0.01f the utterance is inaudible but the
        // engine runs the full synthesis path, which triggers lazy voice data downloads.
        val params: Bundle? = if (silent) Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.01f)
        } else null
        Log.d("Haq/TTS", "speak() utteranceId=$utteranceId queueMode=$queueMode textLength=${ttsText.length} silent=$silent")
        tts?.speak(ttsText, queueMode, params, utteranceId)
    }

    /**
     * Shuts down the current TTS engine and re-creates it so the engine picks up
     * newly installed voice data. Always uses Google TTS if installed.
     * [onReady] fires once the new instance reports SUCCESS, or immediately on
     * failure so callers are never blocked.
     */
    fun reinitialise(context: Context, onReady: () -> Unit = {}) {
        Log.d("Haq/TTS", "Reinitialising TTS")
        cachedVoices.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        _ttsReady.value = false
        _isSpeaking.value = false

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
                Log.d("Haq/TTS", "Reinitialised engine=${tts?.defaultEngine}")
                tts?.voices?.forEach { v ->
                    Log.d("Haq/TTS", "Voice: ${v.name} ${v.locale}")
                }
                _ttsReady.value = true
                onReady()
            } else {
                Log.e("Haq/TTS", "Reinit failed: $status")
                onReady() // never block
            }
        }

        tts = createTtsInstance(context, listener)
    }

    /**
     * Shuts down the current TTS engine, creates a new one, and waits until the voice list
     * stabilises. Samsung loads voices asynchronously after onInit fires — the initial list
     * may contain only ~14 stubs. Polling continues until the count exceeds 100 and is
     * stable for 3 consecutive 300 ms checks, or until the 10-second timeout.
     * Clears [cachedVoices] at the end so [findBestVoice] re-scans the fresh list.
     * No delay() is needed after calling this function.
     */
    suspend fun reinitialiseAndWait() {
        val context = appContext ?: run {
            Log.e("Haq/TTS", "reinitialiseAndWait: appContext missing")
            return
        }

        suspendCancellableCoroutine<Unit> { cont ->
            Log.d("Haq/TTS", "reinitialiseAndWait: shutting down")
            cachedVoices.clear()
            isReady = false
            _ttsReady.value = false
            _isSpeaking.value = false
            tts?.stop()
            tts?.shutdown()
            tts = createTtsInstance(context, TextToSpeech.OnInitListener { status ->
                Log.d("Haq/TTS", "reinitialiseAndWait: onInit status=$status")
                if (status == TextToSpeech.SUCCESS) {
                    isReady = true
                    tts?.setSpeechRate(0.9f)
                    tts?.setPitch(1.0f)
                    _ttsReady.value = true
                    // Log engine list, not defaultEngine — defaultEngine is the system default
                    // (e.g. com.samsung.SMT) even when we are bound to com.google.android.tts.
                    val engines = tts?.engines?.map { it.name } ?: emptyList()
                    Log.d("Haq/TTS", "reinitialiseAndWait: ready engines=$engines systemDefault=${tts?.defaultEngine}")
                } else {
                    Log.e("Haq/TTS", "reinitialiseAndWait: init failed status=$status")
                }
                if (cont.isActive) cont.resume(Unit)
            })
        }

        // Poll until voice list stabilises or timeout.
        // Samsung loads voices asynchronously after onInit.
        val timeoutMs = 10_000L
        val startTime = System.currentTimeMillis()
        var lastCount = 0
        var stableCount = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(300L)
            val currentCount = tts?.voices?.size ?: 0
            Log.d("Haq/TTS", "reinitialiseAndWait: voices=$currentCount")
            if (currentCount > 100 && currentCount == lastCount) {
                stableCount++
                if (stableCount >= 3) {
                    // Count stable for 3 checks (900 ms) — done
                    Log.d("Haq/TTS",
                        "reinitialiseAndWait: voice list stable at $currentCount voices")
                    break
                }
            } else {
                stableCount = 0
            }
            lastCount = currentCount
        }

        cachedVoices.clear()
        Log.d("Haq/TTS",
            "reinitialiseAndWait: complete, final count=${tts?.voices?.size ?: 0}")
    }

    /** Removes a single language's cached voice so the next speak() runs a fresh findBestVoice(). */
    fun clearCachedVoice(languageCode: String) {
        cachedVoices.remove(languageCode)
        Log.d("Haq/TTS", "clearCachedVoice: evicted $languageCode")
    }

    /**
     * Attempts a silent test utterance in [languageCode] to verify voice data is downloaded.
     * Returns true if synthesis succeeds (onDone fires), false if ERROR_OUTPUT (-4) fires.
     *
     * [checkLanguageSupport] only checks voice list entries and returns true for stub voices
     * that have no synthesis data. Always use this function during PreparingVoices to confirm
     * real voice data is present before advancing.
     */
    suspend fun testSpeak(languageCode: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            speak(
                text = TEST_UTTERANCES[languageCode] ?: TEST_UTTERANCES.getValue("en"),
                languageCode = languageCode,
                silent = true,
                onComplete = { if (cont.isActive) cont.resume(true) },
                onOutputError = { if (cont.isActive) cont.resume(false) },
            )
        }
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        _isSpeaking.value = false
        _ttsReady.value = false
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
