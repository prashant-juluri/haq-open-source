package com.haq.app.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.data.ProfileManager
import com.haq.app.data.SessionManager
import com.haq.app.stt.STTManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class OnboardingStep {
    object LanguageSelect  : OnboardingStep()
    object Introduction    : OnboardingStep()
    object AskName         : OnboardingStep()
    object AskState        : OnboardingStep()
    object AskCaste        : OnboardingStep()
    object AskOccupation   : OnboardingStep()
    object Complete        : OnboardingStep()
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

    private val _createdProfileId = MutableStateFlow(-1)
    val createdProfileId: StateFlow<Int> = _createdProfileId.asStateFlow()

    // ── Data collected during onboarding ────────────────────────────────────────
    var selectedLanguage = "hi"
    var collectedName    = ""
    var collectedState   = ""
    var collectedCaste   = ""
    var collectedOccupation = ""

    // ── Question text helpers ────────────────────────────────────────────────────

    fun getIntroductionText(language: String): String = when (language) {
        "te" -> "నమస్కారం. నేను హక్ — మీ హక్కుల నావిగేటర్. నేను మీకు ప్రభుత్వ సంక్షేమ పథకాల గురించి సహాయం చేస్తాను. మీ పేరు ఏమిటి?"
        "ml" -> "നമസ്കാരം. ഞാൻ ഹഖ് — നിങ്ങളുടെ അവകാശ നാവിഗേറ്റർ. ഞാൻ നിങ്ങൾക്ക് സർക്കാർ ക്ഷേമ പദ്ധതികളെ കുറിച്ച് സഹായിക്കും. നിങ്ങളുടെ പേര് എന്താണ്?"
        else -> "नमस्ते। मैं हक़ हूँ — आपका अधिकार नेविगेटर। मैं आपको सरकारी कल्याण योजनाओं का लाभ दिलाने में मदद करूँगा। आपका नाम क्या है?"
    }

    fun getAskStateText(language: String): String = when (language) {
        "te" -> "$collectedName, మీరు ఏ రాష్ట్రంలో నివసిస్తున్నారు?"
        "ml" -> "$collectedName, നിങ്ങൾ ഏത് സംസ്ഥാനത്താണ് താമസിക്കുന്നത്?"
        else -> "$collectedName, आप किस राज्य में रहते हैं?"
    }

    fun getAskCasteText(language: String): String = when (language) {
        "te" -> "మీరు SC, ST, OBC, లేదా General వర్గానికి చెందిన వారా?"
        "ml" -> "നിങ്ങൾ SC, ST, OBC, അല്ലെങ്കിൽ General വിഭാഗത്തിൽ പെടുന്നുവോ?"
        else -> "आप SC, ST, OBC, या General श्रेणी में से किसमें आते हैं?"
    }

    fun getAskOccupationText(language: String): String = when (language) {
        "te" -> "మీరు ఏమి పని చేస్తారు?"
        "ml" -> "നിങ്ങൾ എന്ത് ജോലി ചെയ്യുന്നു?"
        else -> "आप क्या काम करते हैं?"
    }

    fun getCompletionText(language: String): String = when (language) {
        "te" -> "అన్నీ సెట్ అయ్యాయి. మైక్ నొక్కి ఏదైనా అడగండి."
        "ml" -> "എല്ലാം സജ്ജമായി. മൈക്ക് ടാപ്പ് ചെയ്ത് ചോദിക്കൂ."
        else -> "सब तैयार है। माइक दबाएं और कुछ भी पूछें।"
    }

    // ── Step transitions ─────────────────────────────────────────────────────────

    fun selectLanguage(language: String) {
        selectedLanguage = language
        _step.value = OnboardingStep.Introduction
    }

    fun onIntroductionComplete() {
        _step.value = OnboardingStep.AskName
    }

    fun submitName(name: String) {
        collectedName = name.trim()
        _step.value = OnboardingStep.AskState
    }

    fun submitState(state: String) {
        collectedState = state.trim()
        _step.value = OnboardingStep.AskCaste
    }

    fun submitCaste(caste: String) {
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
    }

    /** Routes a transcript to the correct step handler based on current step. */
    fun submitAnswer(transcript: String) {
        when (_step.value) {
            is OnboardingStep.AskName       -> submitName(transcript)
            is OnboardingStep.AskState      -> submitState(transcript)
            is OnboardingStep.AskCaste      -> submitCaste(transcript)
            is OnboardingStep.AskOccupation -> submitOccupation(transcript)
            else -> {}
        }
    }

    fun submitOccupation(occupation: String) {
        collectedOccupation = occupation.trim()
        viewModelScope.launch {
            val id = ProfileManager.createProfile(
                name         = collectedName,
                language     = selectedLanguage,
                state        = collectedState,
                casteCategory = collectedCaste,
                occupation   = collectedOccupation
            )
            SessionManager.setActiveProfileId(getApplication(), id)
            _createdProfileId.value = id
            _step.value = OnboardingStep.Complete
        }
    }

    // ── STT ─────────────────────────────────────────────────────────────────────

    /** Listen for one utterance and submit it to the current step's handler. */
    fun startListening() {
        if (_listenState.value == OnboardingListenState.LISTENING) return
        viewModelScope.launch {
            _listenState.value = OnboardingListenState.LISTENING
            try {
                val transcript = STTManager.recognize(getApplication())
                Log.d("Haq/Onboard", "Transcript: \"$transcript\"")
                _listenState.value = OnboardingListenState.PROCESSING
                when (_step.value) {
                    is OnboardingStep.AskName       -> submitName(transcript)
                    is OnboardingStep.AskState      -> submitState(transcript)
                    is OnboardingStep.AskCaste      -> submitCaste(transcript)
                    is OnboardingStep.AskOccupation -> submitOccupation(transcript)
                    else -> _listenState.value = OnboardingListenState.IDLE
                }
            } catch (e: Exception) {
                Log.e("Haq/Onboard", "Listen error: ${e.message}")
                _listenState.value = OnboardingListenState.IDLE
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
        _listenState.value  = OnboardingListenState.IDLE
        _createdProfileId.value = -1
        _step.value = OnboardingStep.LanguageSelect
    }
}
