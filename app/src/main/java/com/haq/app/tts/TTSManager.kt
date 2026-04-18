package com.haq.app.tts

import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object TTSManager {

    private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    private var tts: TextToSpeech? = null
    private var isReady = false

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
        "en" to Locale("en", "IN"),
    )

    // ── Locale helper ─────────────────────────────────────────────────────────

    private fun localeFor(languageCode: String): Locale = when (languageCode) {
        "te" -> Locale("te", "IN")
        "ml" -> Locale("ml", "IN")
        "kn" -> Locale("kn", "IN")
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

        // P1: non-OEM offline voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.name.contains("samsung", ignoreCase = true) &&
            !v.name.contains("smt", ignoreCase = true) &&
            !v.isNetworkConnectionRequired
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P1 offline non-OEM: ${it.name}"); return it }

        // P2: non-OEM online voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.name.contains("samsung", ignoreCase = true) &&
            !v.name.contains("smt", ignoreCase = true)
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P2 online non-OEM: ${it.name}"); return it }

        // P3: any offline voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.isNetworkConnectionRequired
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P3 any offline: ${it.name}"); return it }

        // P4: any voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country
        }?.also { cachedVoices[languageCode] = it; Log.d("Haq/TTS", "P4 any exact: ${it.name}"); return it }

        // P5: any non-stub voice, language only (stubs are excluded so they don't get cached)
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
     * Calls setLanguage() for all 5 required languages. When Google TTS detects
     * missing synthesis data, it begins a silent background download — no UI is shown.
     * Safe to call at any time after init(). Idempotent for languages already present.
     */
    fun ensureAllVoicesDownloading() {
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

        val googleInstalled = isGoogleTtsInstalled(context)
        Log.d("Haq/TTS", "Google TTS installed: $googleInstalled")

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
                Log.d("Haq/TTS", "TTS ready engine=${tts?.defaultEngine}")
                tts?.voices?.forEach { v ->
                    Log.d("Haq/TTS", "Voice: ${v.name} ${v.locale} q=${v.quality} net=${v.isNetworkConnectionRequired}")
                }
                _ttsReady.value = true
            } else {
                Log.e("Haq/TTS", "TTS init failed: $status")
            }
        }

        tts = if (googleInstalled) {
            Log.d("Haq/TTS", "Using Google TTS engine")
            TextToSpeech(context, listener, GOOGLE_TTS_PACKAGE)
        } else {
            Log.w("Haq/TTS", "Google TTS not found, using system default")
            TextToSpeech(context, listener)
        }
    }

    private fun isGoogleTtsInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GOOGLE_TTS_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ── speak() ────────────────────────────────────────────────────────────────
    //
    // CRITICAL: setOnUtteranceProgressListener() is called BEFORE tts.speak().
    // Setting it after speak() risks missing onDone for short utterances because
    // the engine can complete and fire callbacks before the listener is registered.
    //
    // A fresh per-call listener is used so each onComplete callback is isolated
    // to its own utteranceId — no shared map, no race condition.

    fun speak(text: String, languageCode: String, onComplete: (() -> Unit)? = null, onOutputError: (() -> Unit)? = null) {
        if (!isReady) {
            // TTS unavailable — fire callback immediately so flow can continue
            onComplete?.invoke()
            return
        }

        val voice = findBestVoice(languageCode)
        if (voice != null) {
            // Stub voices ending in "-language" have no synthesis data — skip them.
            if (voice.name.endsWith("-language")) {
                Log.w("Haq/TTS", "Voice ${voice.name} is a stub — no audio data, skipping")
                onComplete?.invoke()
                return
            }
            tts?.voice = voice
            Log.d("Haq/TTS", "speak() using voice=${voice.name} for lang=$languageCode")
        } else {
            Log.w("Haq/TTS", "speak() no voice found, using device locale")
            tts?.setLanguage(Locale.getDefault())
        }

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

            // ERROR_OUTPUT (-4) means voice synthesis data is missing.
            // Evict the cached voice so the next call rescans.
            @Suppress("DEPRECATION")
            override fun onError(uid: String?) {
                Log.e("Haq/TTS", "onError: $uid — evicting cached voice for $languageCode")
                _isSpeaking.value = false
                cachedVoices.remove(languageCode)
                if (uid == utteranceId) onComplete?.invoke()
            }

            override fun onError(uid: String?, errorCode: Int) {
                Log.e("Haq/TTS", "onError: $uid code=$errorCode — evicting cached voice for $languageCode")
                _isSpeaking.value = false
                cachedVoices.remove(languageCode)
                if (uid == utteranceId) {
                    if (errorCode == TextToSpeech.ERROR_OUTPUT && onOutputError != null) {
                        onOutputError.invoke()
                    } else {
                        onComplete?.invoke()
                    }
                }
            }
        })

        Log.d("Haq/TTS", "speak() utteranceId=$utteranceId textLength=${ttsText.length}")
        tts?.speak(ttsText, TextToSpeech.QUEUE_ADD, null, utteranceId)
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

        val googleInstalled = isGoogleTtsInstalled(context)

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

        tts = if (googleInstalled) {
            TextToSpeech(context, listener, GOOGLE_TTS_PACKAGE)
        } else {
            TextToSpeech(context, listener)
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
