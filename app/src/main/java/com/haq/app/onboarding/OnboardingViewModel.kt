package com.haq.app.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.data.ProfileManager
import com.haq.app.data.SessionManager
import com.haq.app.stt.STTManager
import com.haq.app.tts.TTSManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

sealed class OnboardingStep {
    object LanguageSelect        : OnboardingStep()
    object Introduction          : OnboardingStep()
    object AskName               : OnboardingStep()
    object AskState              : OnboardingStep()
    object AskCaste              : OnboardingStep()
    object AskOccupation         : OnboardingStep()
    object InstallingVoicePacks  : OnboardingStep()
    object Complete              : OnboardingStep()
}

enum class OnboardingListenState { IDLE, LISTENING, PROCESSING }

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    init {
        ProfileManager.init(application)
    }

    private val _step = MutableStateFlow<OnboardingStep>(OnboardingStep.LanguageSelect)
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
    // Default to all 5 until TTSManager reports actual availability.
    private val _supportedLanguages = MutableStateFlow(listOf("hi", "te", "ml", "kn", "en"))
    val supportedLanguages: StateFlow<List<String>> = _supportedLanguages.asStateFlow()

    // Current retry attempt number while waiting for voice pack install to register.
    // 0 = not retrying; 1-5 = active attempt. Shown in InstallingVoicePacks screen.
    private val _installAttempt = MutableStateFlow(0)
    val installAttempt: StateFlow<Int> = _installAttempt.asStateFlow()

    // ── Data collected during onboarding ────────────────────────────────────────
    var selectedLanguage    = "hi"
    var collectedName       = ""
    var collectedState      = ""
    var collectedCaste      = ""
    var collectedOccupation = ""

    // Consecutive empty/error count for the current question
    private var retryCount = 0

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
        "kn" -> "ನೀవು SC, ST, OBC ಅಥವಾ General ವರ್ಗಕ್ಕೆ ಸೇರಿದ್ದೀರಾ?"
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
     * Derives the question text from the current step, resets listening state,
     * speaks the question, then increments [_micActivationEvent] so MainActivity
     * auto-activates the mic once TTS finishes.
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
        TTSManager.speak(
            text = questionText,
            languageCode = selectedLanguage,
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
     * Called once when TTSManager reports ready. Determines which of the 5
     * supported languages are actually available on this device and adjusts the
     * opening step accordingly:
     *  - 0 supported → fall back to device locale (or "en"), skip language screen
     *  - 1 supported → auto-select it, skip language screen
     *  - 2+ supported → show language screen with only those languages
     *
     * Guard: if the user has already progressed past LanguageSelect (TTS fired
     * late), this is a no-op to avoid resetting their progress.
     */
    fun setSupportedLanguages(languages: List<String>) {
        _supportedLanguages.value = languages
        Log.d("Haq/Onboard", "Supported TTS languages: $languages")
        if (_step.value != OnboardingStep.LanguageSelect) return
        when {
            languages.isEmpty() -> {
                selectedLanguage = Locale.getDefault().language.let { lang ->
                    if (lang in listOf("hi", "te", "ml", "kn", "en")) lang else "en"
                }
                _step.value = OnboardingStep.Introduction
            }
            languages.size == 1 -> {
                selectedLanguage = languages.first()
                _step.value = OnboardingStep.Introduction
            }
            else -> { /* 2+ languages — show LanguageSelect with filtered list */ }
        }
    }

    fun selectLanguage(language: String) {
        selectedLanguage = language
        val supported = TTSManager.checkLanguageSupport(language)
        Log.d("Haq/Onboard", "Language=$language supported=$supported")
        if (!supported) {
            // Voice pack missing — install before starting the conversation
            _step.value = OnboardingStep.InstallingVoicePacks
            TTSManager.installVoiceData(getApplication())
            // MainActivity's ON_RESUME handler retries and calls onVoicePackInstalled()
        } else {
            _step.value = OnboardingStep.Introduction
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

        // Voice pack check was already handled at language selection time.
        // Just save the profile and complete onboarding.
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

    /** Advances directly to Complete — used after voice pack install or for testing. */
    fun skipToComplete() {
        _step.value = OnboardingStep.Complete
    }

    /** Called once the voice-pack retry loop finishes (success or exhausted). */
    fun onVoicePackInstalled() {
        val nowSupported = TTSManager.checkLanguageSupport(selectedLanguage)
        Log.d("Haq/Onboard", "Post-install: $selectedLanguage supported=$nowSupported")
        _installAttempt.value = 0
        // Always proceed to Introduction — never block the user
        _step.value = OnboardingStep.Introduction
    }

    /** Updated by MainActivity's retry loop so the wait screen can show progress. */
    fun setInstallAttempt(attempt: Int) {
        _installAttempt.value = attempt
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
        selectedLanguage    = "hi"
        collectedName       = ""
        collectedState      = ""
        collectedCaste      = ""
        collectedOccupation = ""
        retryCount          = 0
        _listenState.value  = OnboardingListenState.IDLE
        _isListening.value  = false
        _micActivationEvent.value = 0
        _installAttempt.value = 0
        _supportedLanguages.value = listOf("hi", "te", "ml", "kn", "en")
        _createdProfileId.value = -1
        _step.value = OnboardingStep.LanguageSelect
    }
}
