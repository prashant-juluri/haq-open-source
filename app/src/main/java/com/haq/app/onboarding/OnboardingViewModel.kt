package com.haq.app.onboarding

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.data.ProfileManager
import com.haq.app.data.SessionManager
import com.haq.app.inference.GemmaManager
import com.haq.app.stt.STTManager
import com.haq.app.tts.TTSManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

sealed class OnboardingStep {
    object PreparingVoices       : OnboardingStep()  // pre-onboarding voice readiness gate
    object LanguageSelect        : OnboardingStep()
    object Introduction          : OnboardingStep()
    object AskName               : OnboardingStep()
    object AskState              : OnboardingStep()
    object AskCaste              : OnboardingStep()
    object AskOccupation         : OnboardingStep()
    object InstallingVoicePacks  : OnboardingStep()  // edge case: single language still missing
    object Complete              : OnboardingStep()
}

enum class OnboardingListenState { IDLE, LISTENING, PROCESSING }

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    init {
        ProfileManager.init(application)
        // Wait for TTS engine to become ready, then start voice readiness check.
        // ensureAllVoicesDownloading() is called inside startVoiceReadinessPolling()
        // only when voices are missing — returning users skip straight to LanguageSelect.
        viewModelScope.launch {
            TTSManager.ttsReady.first { it }
            Log.d("Haq/Onboard", "TTS ready — starting voice preparation")
            startVoiceReadinessPolling()
        }
    }

    // ── Step state ───────────────────────────────────────────────────────────────

    private val _step = MutableStateFlow<OnboardingStep>(OnboardingStep.PreparingVoices)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    private val _listenState = MutableStateFlow(OnboardingListenState.IDLE)
    val listenState: StateFlow<OnboardingListenState> = _listenState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _createdProfileId = MutableStateFlow(-1)
    val createdProfileId: StateFlow<Int> = _createdProfileId.asStateFlow()

    // Incremented each time a new question is ready for mic input.
    // MainActivity observes this and calls onMicPressed() automatically.
    private val _micActivationEvent = MutableStateFlow(0)
    val micActivationEvent: StateFlow<Int> = _micActivationEvent.asStateFlow()

    // Languages confirmed available by TTS engine on this device.
    // Computed when voice readiness polling completes.
    private val _supportedLanguages = MutableStateFlow(listOf("hi", "te", "ml", "kn", "en"))
    val supportedLanguages: StateFlow<List<String>> = _supportedLanguages.asStateFlow()

    // Status shown on PreparingVoices screen. Updated as model/voice download progresses.
    private val _preparingStatus = MutableStateFlow("Preparing...")
    val preparingStatus: StateFlow<String> = _preparingStatus.asStateFlow()

    // ── Data collected during onboarding ────────────────────────────────────────
    var selectedLanguage    = "hi"
    var collectedName       = ""
    var collectedState      = ""
    var collectedCaste      = ""
    var collectedOccupation = ""

    // Consecutive empty/error count for the current question
    private var retryCount = 0

    // Active voice readiness polling coroutine (fallback only — broadcast drives real progress)
    private var voicePollingJob: Job? = null

    // Pending speak retry after a TTS ERROR_OUTPUT (-4) recovery
    private var pendingSpeakRetry: (() -> Unit)? = null
    private var pendingStep: OnboardingStep? = null

    // ── Voice readiness polling ──────────────────────────────────────────────────

    /**
     * Checks immediately for returning users (voices + model already ready) and advances
     * at once. For first-launch, triggers silent voice downloads and starts a 5-second
     * fallback poll. The real completion signal is ACTION_TTS_DATA_INSTALLED —
     * the poll is only a safety net in case the broadcast is missed.
     *
     * Advances to LanguageSelect only when BOTH getMissingLanguages().isEmpty()
     * AND GemmaManager.isModelReady() are true.
     */
    private fun startVoiceReadinessPolling() {
        voicePollingJob?.cancel()
        voicePollingJob = viewModelScope.launch {
            // Immediate check — returning users with voices + model pass here without waiting
            val missingNow = TTSManager.getMissingLanguages()
            val modelReadyNow = GemmaManager.isModelReady()
            Log.d("Haq/Onboard", "Voice readiness immediate check: missing=$missingNow modelReady=$modelReadyNow")

            if (missingNow.isEmpty() && modelReadyNow) {
                Log.d("Haq/Onboard", "All voices and model ready immediately — advancing to LanguageSelect")
                transitionToLanguageSelect()
                voicePollingJob = null
                return@launch
            }

            // Not ready — trigger silent voice downloads if needed
            if (missingNow.isNotEmpty()) TTSManager.ensureAllVoicesDownloading()
            updatePreparingStatus(missingNow.isNotEmpty(), !modelReadyNow)

            val startTime = System.currentTimeMillis()
            val timeoutMs = 120_000L // 2-minute max fallback

            while (true) {
                delay(5_000L) // 5s interval — broadcast drives real progress for voices

                val missing = TTSManager.getMissingLanguages()
                val modelReady = GemmaManager.isModelReady()
                updatePreparingStatus(missing.isNotEmpty(), !modelReady)
                Log.d("Haq/Onboard", "Voice readiness fallback poll: missing=$missing modelReady=$modelReady")

                if (missing.isEmpty() && modelReady) {
                    Log.d("Haq/Onboard", "All voices and model ready (fallback poll) — advancing to LanguageSelect")
                    transitionToLanguageSelect()
                    break
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > timeoutMs) {
                    Log.w("Haq/Onboard", "Readiness timeout — proceeding with missing=$missing modelReady=$modelReady")
                    transitionToLanguageSelect()
                    break
                }
            }
            voicePollingJob = null
        }
    }

    private fun updatePreparingStatus(voicesMissing: Boolean, modelMissing: Boolean) {
        _preparingStatus.value = when {
            modelMissing && voicesMissing -> "Downloading model and voices..."
            modelMissing                  -> "Downloading AI model..."
            else                          -> "Preparing voices..."
        }
    }

    /**
     * Called by MainActivity when ACTION_TTS_DATA_INSTALLED broadcast fires and
     * TTSManager has been reinitialised. This is the primary signal — drives progress
     * faster than the fallback polling loop.
     */
    fun onTtsDataInstalled() {
        Log.d("Haq/Onboard", "onTtsDataInstalled — checking voice readiness")
        checkAndAdvanceIfReady()
    }

    /**
     * Checks if all voices AND the model are ready, then advances state accordingly.
     * Cancels the fallback poll so the broadcast drives progress, not the timer.
     */
    private fun checkAndAdvanceIfReady() {
        val missing = TTSManager.getMissingLanguages()
        val modelReady = GemmaManager.isModelReady()
        Log.d("Haq/Onboard", "checkAndAdvanceIfReady: missing=$missing modelReady=$modelReady step=${_step.value}")

        if (missing.isEmpty() && modelReady) {
            voicePollingJob?.cancel()
            voicePollingJob = null

            val retry = pendingSpeakRetry
            val stepToRestore = pendingStep

            if (retry != null) {
                // Mid-onboarding -4 recovery: restore step and retry the speak
                pendingSpeakRetry = null
                pendingStep = null
                if (stepToRestore != null) _step.value = stepToRestore
                retry.invoke()
            } else {
                when (_step.value) {
                    is OnboardingStep.PreparingVoices      -> transitionToLanguageSelect()
                    is OnboardingStep.InstallingVoicePacks -> _step.value = OnboardingStep.Introduction
                    else -> { /* already past voice gate — nothing to do */ }
                }
            }
        }
    }

    /**
     * Computes supported languages from actual voice availability and advances
     * the step, applying the same single-language auto-select logic as before.
     */
    private fun transitionToLanguageSelect() {
        val available = listOf("hi", "te", "ml", "kn", "en")
            .filter { TTSManager.checkLanguageSupport(it) }
        Log.d("Haq/Onboard", "Voice prep done, available=$available")

        val supported = if (available.isEmpty()) listOf("hi", "te", "ml", "kn", "en") else available
        _supportedLanguages.value = supported

        when {
            supported.isEmpty() -> {
                // Should never happen given the fallback above, but be safe
                selectedLanguage = Locale.getDefault().language
                    .takeIf { it in listOf("hi", "te", "ml", "kn", "en") } ?: "en"
                _step.value = OnboardingStep.Introduction
            }
            supported.size == 1 -> {
                selectedLanguage = supported.first()
                _step.value = OnboardingStep.Introduction
            }
            else -> _step.value = OnboardingStep.LanguageSelect
        }
    }

    // ── Question text helpers ────────────────────────────────────────────────────

    fun getIntroductionText(language: String): String = when (language) {
        "te" -> "నమస్కారం. నేను హక్ — మీ హక్కుల నావిగేటర్. నేను మీకు ప్రభుత్వ సంక్షేమ పథకాల గురించి సహాయం చేస్తాను. మీ పేరు ఏమిటి?"
        "ml" -> "നമസ്കാരം. ഞാൻ ഹഖ് — നിങ്ങളുടെ അവകാശ നാവിഗേറ്റർ. ഞാൻ നിങ്ങൾക്ക് സർക്കാർ ക്ഷേമ പദ്ധതികളെ കുറിച്ച് സഹായിക്കും. നിങ്ങളുടെ പേര് എന്താണ്?"
        "kn" -> "ನಮಸ್ಕಾರ. ನಾನು ಹಕ್ — ನಿಮ್ಮ ಹಕ್ಕುಗಳ ನ್ಯಾವಿಗೇಟರ್. ನಾನು ನಿಮಗೆ ಸರ್ಕಾರಿ ಕಲ್ಯಾಣ ಯೋಜನೆಗಳ ಬಗ್ಗೆ ಸಹಾಯ ಮಾಡುತ್ತೇನೆ. ನಿಮ್ಮ ಹೆಸರು ಏನು?"
        "en" -> "Hello. I am Haq — your rights navigator. I help you claim government welfare schemes you are entitled to. What is your name?"
        else -> "नमस्ते। मैं हक़ हूँ — आपका अधिकार नेविगेटर। मैं आपको सरकारी कल्याण योजनाओं का लाभ दिलाने में मदद करूँगा। आपका नाम क्या है?"
    }

    fun getAskStateText(language: String): String = when (language) {
        "te" -> "$collectedName, మీరు ఏ రాష్ట్రంలో నివసిస్తున్నారు?"
        "ml" -> "$collectedName, നിങ്ങൾ ഏത് സംസ്ഥാനത്താണ് താമസിക്കുന്നത്?"
        "kn" -> "$collectedName, ನೀವು ಯಾವ ರಾಜ್ಯದಲ್ಲಿ ವಾಸಿಸುತ್ತೀರಿ?"
        "en" -> "$collectedName, which state do you live in?"
        else -> "$collectedName, आप किस राज्य में रहते हैं?"
    }

    fun getAskCasteText(language: String): String = when (language) {
        "te" -> "మీరు SC, ST, OBC, లేదా General వర్గానికి చెందిన వారా?"
        "ml" -> "നിങ്ങൾ SC, ST, OBC, അല്ലെങ്കിൽ General വിഭാഗത്തിൽ പെടുന്നുവോ?"
        "kn" -> "ನೀವು SC, ST, OBC ಅಥವಾ General ವರ್ಗಕ್ಕೆ ಸೇರಿದ್ದೀರಾ?"
        "en" -> "Do you belong to SC, ST, OBC, or General category?"
        else -> "आप SC, ST, OBC, या General श्रेणी में से किसमें आते हैं?"
    }

    fun getAskOccupationText(language: String): String = when (language) {
        "te" -> "మీరు ఏమి పని చేస్తారు?"
        "ml" -> "നിങ്ങൾ എന്ത് ജോലി ചെയ്യുന്നു?"
        "kn" -> "ನೀವು ಏನು ಕೆಲಸ ಮಾಡುತ್ತೀರಿ?"
        "en" -> "What work do you do?"
        else -> "आप क्या काम करते हैं?"
    }

    fun getCompletionText(language: String): String = when (language) {
        "te" -> "అన్నీ సెట్ అయ్యాయి. మైక్ నొక్కి ఏదైనా అడగండి."
        "ml" -> "എല്ലാം സജ്ജമായി. മൈക്ക് ടാപ്പ് ചെയ്ത് ചോദിക്കൂ."
        "kn" -> "ಎಲ್ಲಾ ಸಿದ್ಧವಾಗಿದೆ. ಮೈಕ್ ಒತ್ತಿ ಏನಾದರೂ ಕೇಳಿ."
        "en" -> "All set. Tap the mic and ask anything."
        else -> "सब तैयार है। माइक दबाएं और कुछ भी पूछें।"
    }

    // ── Private TTS + mic chain driver ───────────────────────────────────────────

    /**
     * Wraps TTSManager.speak() with ERROR_OUTPUT (-4) recovery. If -4 fires mid-onboarding
     * (voice listed but audio not yet flushed), stores the pending speak, sets step back to
     * PreparingVoices, and retriggers silent downloads. onTtsDataInstalled() retries.
     */
    private fun speakWithRecovery(text: String, onComplete: (() -> Unit)? = null) {
        val capturedStep = _step.value
        TTSManager.speak(
            text = text,
            languageCode = selectedLanguage,
            onComplete = onComplete,
            onOutputError = {
                // Called from TTS internal thread — keep only thread-safe operations here.
                Log.w("Haq/Onboard", "TTS ERROR_OUTPUT on step=$capturedStep — going to PreparingVoices")
                pendingStep = capturedStep
                pendingSpeakRetry = { TTSManager.speak(text = text, languageCode = selectedLanguage, onComplete = onComplete) }
                _step.value = OnboardingStep.PreparingVoices
                TTSManager.ensureAllVoicesDownloading()
                // Reinitialise on main thread after a short delay so the current TTS callback
                // stack unwinds before we shut down the engine.
                Handler(Looper.getMainLooper()).postDelayed({
                    TTSManager.reinitialise(getApplication())
                }, 500L)
            },
        )
    }

    /**
     * Derives the question text from the current step, resets listening state,
     * speaks the question with -4 recovery, then increments [_micActivationEvent]
     * so MainActivity auto-activates the mic once TTS finishes.
     */
    private fun startNextQuestion() {
        val questionText = when (_step.value) {
            is OnboardingStep.AskState      -> getAskStateText(selectedLanguage)
            is OnboardingStep.AskCaste      -> getAskCasteText(selectedLanguage)
            is OnboardingStep.AskOccupation -> getAskOccupationText(selectedLanguage)
            else -> return
        }
        _isListening.value = false
        _listenState.value = OnboardingListenState.IDLE
        speakWithRecovery(
            text = questionText,
            onComplete = { _micActivationEvent.value++ },
        )
    }

    fun getRetryText(language: String): String = when (language) {
        "te" -> "నేను వినలేదు. దయచేసి మళ్ళీ చెప్పండి."
        "ml" -> "എനിക്ക് കേൾക്കാൻ കഴിഞ്ഞില്ല. ദയവായി വീണ്ടും പറയൂ."
        "kn" -> "ನನಗೆ ಕೇಳಿಸಲಿಲ್ಲ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಹೇಳಿ."
        "en" -> "I didn't catch that. Please say it again."
        else -> "मुझे सुनाई नहीं दिया। कृपया फिर से बोलें।"
    }

    fun getTapMicText(language: String): String = when (language) {
        "te" -> "దయచేసి మైక్ బటన్ నొక్కి మాట్లాడండి."
        "ml" -> "ദയവായി മൈക്ക് ബട്ടൺ അമർത്തി സംസാരിക്കൂ."
        "kn" -> "ದಯವಿಟ್ಟು ಮೈಕ್ ಬಟನ್ ಒತ್ತಿ ಮಾತನಾಡಿ."
        "en" -> "Please tap the mic button and speak."
        else -> "कृपया माइक बटन दबाकर बोलें।"
    }

    // ── Step transitions ─────────────────────────────────────────────────────────

    /**
     * Selects the language and advances to Introduction. After [startVoiceReadinessPolling]
     * completes, all 5 voices should be available. The InstallingVoicePacks step is
     * an edge case for when a language is still missing post-polling.
     */
    fun selectLanguage(language: String) {
        selectedLanguage = language
        val supported = TTSManager.checkLanguageSupport(language)
        Log.d("Haq/Onboard", "selectLanguage: lang=$language supported=$supported")
        _step.value = if (supported) {
            OnboardingStep.Introduction
        } else {
            // Edge case: voice still missing after PreparingVoices polling completed.
            // Show wait screen; user can tap Continue once the voice downloads.
            Log.w("Haq/Onboard", "Voice for $language still missing after prep — showing wait screen")
            OnboardingStep.InstallingVoicePacks
        }
    }

    fun onIntroductionComplete() {
        _step.value = OnboardingStep.AskName
        // Introduction TTS already asked the name question — activate mic immediately.
        _micActivationEvent.value++
    }

    fun submitName(name: String) {
        if (name.isBlank()) return
        collectedName = name.trim()
        _step.value = OnboardingStep.AskState
        startNextQuestion()
    }

    fun submitState(state: String) {
        if (state.isBlank()) return
        collectedState = state.trim()
        _step.value = OnboardingStep.AskCaste
        startNextQuestion()
    }

    fun submitCaste(caste: String) {
        if (caste.isBlank()) return
        collectedCaste = when {
            caste.contains("SC", ignoreCase = true) ||
            caste.contains("scheduled caste", ignoreCase = true) -> "SC"
            caste.contains("ST", ignoreCase = true) ||
            caste.contains("scheduled tribe", ignoreCase = true) -> "ST"
            caste.contains("OBC", ignoreCase = true) ||
            caste.contains("other backward", ignoreCase = true)  -> "OBC"
            else -> "General"
        }
        _step.value = OnboardingStep.AskOccupation
        startNextQuestion()
    }

    /** Routes a non-blank transcript to the correct step handler. */
    fun submitAnswer(transcript: String) {
        retryCount = 0
        when (_step.value) {
            is OnboardingStep.AskName       -> submitName(transcript)
            is OnboardingStep.AskState      -> submitState(transcript)
            is OnboardingStep.AskCaste      -> submitCaste(transcript)
            is OnboardingStep.AskOccupation -> submitOccupation(transcript)
            else -> {}
        }
    }

    fun submitOccupation(occupation: String) {
        if (occupation.isBlank()) return
        collectedOccupation = occupation.trim()

        // Safety net: if name was somehow missed, loop back
        if (collectedName.isBlank()) {
            _step.value = OnboardingStep.AskName
            return
        }

        viewModelScope.launch {
            val id = ProfileManager.createProfile(
                name          = collectedName,
                language      = selectedLanguage,
                state         = collectedState,
                casteCategory = collectedCaste,
                occupation    = collectedOccupation,
            )
            SessionManager.setActiveProfileId(getApplication(), id)
            _createdProfileId.value = id
            _step.value = OnboardingStep.Complete
        }
    }

    /** Advances directly to Complete — used for testing. */
    fun skipToComplete() {
        _step.value = OnboardingStep.Complete
    }

    /**
     * Called when the user taps Continue on the InstallingVoicePacks screen (edge case).
     * Always proceeds to Introduction — never blocks the user indefinitely.
     */
    fun onVoicePackInstalled() {
        val nowSupported = TTSManager.checkLanguageSupport(selectedLanguage)
        Log.d("Haq/Onboard", "onVoicePackInstalled: $selectedLanguage supported=$nowSupported")
        _step.value = OnboardingStep.Introduction
    }

    // ── Tap-to-speak STT ─────────────────────────────────────────────────────────

    /**
     * Called when the user taps the mic button, or automatically by MainActivity
     * when [micActivationEvent] increments after a TTS question completes.
     *
     * Uses generous onboarding VAD thresholds (3 s minimum, 5 s silence).
     * On blank transcript: retries 1-2 speak a retry prompt and auto-restart STT
     * via onComplete; retry 3 speaks "tap the mic" and waits for manual tap.
     * On exception: retries 1-2 delay 500 ms then restart; retry 3 waits.
     */
    fun onMicPressed() {
        if (_listenState.value == OnboardingListenState.LISTENING) return
        viewModelScope.launch {
            _listenState.value = OnboardingListenState.LISTENING
            _isListening.value = true
            try {
                val transcript = STTManager.recordForOnboarding(getApplication())
                Log.d("Haq/Onboard", "Transcript: '$transcript'")

                if (transcript.isBlank()) {
                    _listenState.value = OnboardingListenState.IDLE
                    _isListening.value = false
                    retryCount++
                    Log.d("Haq/Onboard", "Empty transcript, attempt $retryCount")
                    if (retryCount >= 3) {
                        retryCount = 0
                        TTSManager.speak(
                            text = getTapMicText(selectedLanguage),
                            languageCode = selectedLanguage,
                        )
                        // Wait for manual tap — chain stops here
                    } else {
                        TTSManager.speak(
                            text = getRetryText(selectedLanguage),
                            languageCode = selectedLanguage,
                            onComplete = { onMicPressed() },  // auto-restart after TTS
                        )
                    }
                } else {
                    retryCount = 0
                    _isListening.value = false
                    _listenState.value = OnboardingListenState.PROCESSING
                    submitAnswer(transcript)
                }

            } catch (e: Exception) {
                Log.e("Haq/Onboard", "STT error: ${e.message}")
                _listenState.value = OnboardingListenState.IDLE
                _isListening.value = false
                retryCount++
                Log.d("Haq/Onboard", "STT error, attempt $retryCount")
                if (retryCount >= 3) {
                    retryCount = 0
                    TTSManager.speak(
                        text = getTapMicText(selectedLanguage),
                        languageCode = selectedLanguage,
                    )
                } else {
                    delay(500L)
                    onMicPressed()
                }
            }
        }
    }

    /** Reset to start a fresh onboarding (for "add new profile" flow). */
    fun reset() {
        voicePollingJob?.cancel()
        selectedLanguage    = "hi"
        collectedName       = ""
        collectedState      = ""
        collectedCaste      = ""
        collectedOccupation = ""
        retryCount          = 0
        pendingSpeakRetry   = null
        pendingStep         = null
        _listenState.value  = OnboardingListenState.IDLE
        _isListening.value  = false
        _micActivationEvent.value = 0
        _supportedLanguages.value = listOf("hi", "te", "ml", "kn", "en")
        _createdProfileId.value = -1
        _step.value = OnboardingStep.PreparingVoices
        // startVoiceReadinessPolling() does an immediate check and triggers downloads only if needed
        startVoiceReadinessPolling()
    }
}
