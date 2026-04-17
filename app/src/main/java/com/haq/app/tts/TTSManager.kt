package com.haq.app.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
    // Priority order so Google voices always win over OEM voices:
    //   P1: Google voice, exact locale (language + country)
    //   P2: non-OEM voice, exact locale
    //   P3: any voice, exact locale
    //   P4: Google voice, language only
    //   P5: any voice, language only
    //   P6: null — no voice found for this language

    fun findBestVoice(languageCode: String): Voice? {
        val targetLocale = localeFor(languageCode)
        val voices = tts?.voices ?: return null

        Log.d("Haq/TTS", "Finding voice for $languageCode total=${voices.size}")

        // P1: Google voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            v.name.contains("google", ignoreCase = true)
        }?.also { Log.d("Haq/TTS", "P1 Google exact: ${it.name}"); return it }

        // P2: Non-OEM voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country &&
            !v.name.contains("samsung", ignoreCase = true) &&
            !v.name.contains("smt", ignoreCase = true)
        }?.also { Log.d("Haq/TTS", "P2 non-OEM exact: ${it.name}"); return it }

        // P3: Any voice, exact locale
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.locale.country  == targetLocale.country
        }?.also { Log.d("Haq/TTS", "P3 any exact: ${it.name}"); return it }

        // P4: Google voice, language only
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language &&
            v.name.contains("google", ignoreCase = true)
        }?.also { Log.d("Haq/TTS", "P4 Google language: ${it.name}"); return it }

        // P5: Any voice, language only
        voices.firstOrNull { v ->
            v.locale.language == targetLocale.language
        }?.also { Log.d("Haq/TTS", "P5 any language: ${it.name}"); return it }

        // P6: True last resort
        Log.w("Haq/TTS", "P6 LAST RESORT — no voice for $languageCode")
        return null
    }

    // ── Voice pack availability ────────────────────────────────────────────────

    /** Still used by MainAppFlow in MainActivity to check whether to launch installer. */
    fun isLanguageAvailable(locale: Locale): Int =
        tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

    /**
     * Returns true if a usable voice exists for [languageCode].
     * Uses [findBestVoice] so the result reflects the same priority as speak().
     */
    fun checkLanguageSupport(languageCode: String): Boolean {
        val voice = findBestVoice(languageCode)
        val supported = voice != null
        Log.d("Haq/TTS", "checkLanguageSupport $languageCode: supported=$supported voice=${voice?.name}")
        return supported
    }

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

    fun speak(text: String, languageCode: String, onComplete: (() -> Unit)? = null) {
        if (!isReady) {
            // TTS unavailable — fire callback immediately so flow can continue
            onComplete?.invoke()
            return
        }

        val voice = findBestVoice(languageCode)
        if (voice != null) {
            tts?.voice = voice
            Log.d("Haq/TTS", "speak() using voice=${voice.name} for lang=$languageCode")
        } else {
            Log.w("Haq/TTS", "speak() falling back to device locale")
            tts?.setLanguage(Locale.getDefault())
        }

        // Strip markdown formatting before speaking
        val ttsText = text.replace("**", "").replace("*", "").replace("#", "").trim()
        if (ttsText.isEmpty()) {
            onComplete?.invoke()
            return
        }

        val utteranceId = "haq_${System.currentTimeMillis()}"

        // CRITICAL: register listener BEFORE speak() so no events are missed
        if (onComplete != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {
                    Log.d("Haq/TTS", "onStart: $uid")
                    _isSpeaking.value = true
                }
                override fun onDone(uid: String?) {
                    Log.d("Haq/TTS", "onDone: $uid")
                    _isSpeaking.value = false
                    if (uid == utteranceId) onComplete.invoke()
                }
                @Suppress("DEPRECATION")
                override fun onError(uid: String?) {
                    Log.e("Haq/TTS", "onError: $uid")
                    _isSpeaking.value = false
                    if (uid == utteranceId) onComplete.invoke()
                }
                override fun onError(uid: String?, errorCode: Int) {
                    Log.e("Haq/TTS", "onError: $uid errorCode=$errorCode")
                    _isSpeaking.value = false
                    if (uid == utteranceId) onComplete.invoke()
                }
            })
        }

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

    /**
     * Launches the Google TTS voice data installer. Targets Google TTS explicitly;
     * falls back to the generic system installer if that intent fails.
     */
    fun installVoiceData(context: Context) {
        val engine = tts?.defaultEngine ?: GOOGLE_TTS_PACKAGE
        Log.d("Haq/TTS", "Installing voice data for engine=$engine")
        try {
            val intent = Intent().apply {
                action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                `package` = GOOGLE_TTS_PACKAGE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d("Haq/TTS", "Install intent sent to Google TTS")
        } catch (e: ActivityNotFoundException) {
            Log.w("Haq/TTS", "Google TTS install intent failed, trying generic")
            try {
                val fallback = Intent().apply {
                    action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (e2: Exception) {
                Log.e("Haq/TTS", "All install attempts failed")
            }
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
