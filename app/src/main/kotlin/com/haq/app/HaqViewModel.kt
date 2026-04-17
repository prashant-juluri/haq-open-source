package com.haq.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.data.ProfileManager
import com.haq.app.data.SessionManager
import com.haq.app.data.UserProfile
import com.haq.app.inference.DownloadState
import com.haq.app.inference.EngineState
import com.haq.app.inference.GemmaManager
import com.haq.app.inference.ModelDownloadManager
import com.haq.app.stt.STTManager
import com.haq.app.tts.TTSManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HaqViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = ModelDownloadManager(application)

    // ── Exposed state ─────────────────────────────────────────────────────────

    val downloadState: StateFlow<DownloadState> = downloadManager.downloadState

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _appState = MutableStateFlow(AppState.LOADING)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    // null = still checking, true = needs onboarding, false = ready to use
    private val _needsOnboarding = MutableStateFlow<Boolean?>(null)
    val needsOnboarding: StateFlow<Boolean?> = _needsOnboarding.asStateFlow()

    private val _activeProfileName = MutableStateFlow("")
    val activeProfileName: StateFlow<String> = _activeProfileName.asStateFlow()

    private val _activeLanguage = MutableStateFlow("hi")
    val activeLanguage: StateFlow<String> = _activeLanguage.asStateFlow()

    fun getAllProfiles(): Flow<List<UserProfile>> = ProfileManager.getAllProfiles()

    // Active profile — updated on load, onboarding complete, and profile switch.
    // Used to prepend context to the user query and to select STT/TTS language.
    private var activeProfile: UserProfile? = null

    // Prevents concurrent generateResponse() calls — LiteRT-LM does not support them.
    private var isGenerating = false

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        startDownload()
        checkOnboardingState()

        viewModelScope.launch {
            downloadState.collect { dl ->
                if (dl is DownloadState.Complete) {
                    GemmaManager.init(getApplication())
                    observeEngineState()
                }
            }
        }
    }

    private var engineObserving = false

    private fun observeEngineState() {
        if (engineObserving) return
        engineObserving = true
        viewModelScope.launch {
            GemmaManager.state.collect { state ->
                _engineState.value = state
                _appState.value = when (state) {
                    is EngineState.Loading -> AppState.LOADING
                    is EngineState.Ready   -> AppState.READY
                    is EngineState.Error   -> AppState.ERROR
                }
            }
        }
    }

    private fun checkOnboardingState() {
        viewModelScope.launch {
            val profileId = SessionManager.getActiveProfileId(getApplication())
            if (profileId == -1) {
                _needsOnboarding.value = true
                return@launch
            }
            val profile = ProfileManager.getActiveProfile(profileId)
            if (profile == null || !profile.isOnboarded) {
                _needsOnboarding.value = true
            } else {
                _activeProfileName.value = profile.name
                _activeLanguage.value = profile.preferredLanguage
                _needsOnboarding.value = false
            }
        }
    }

    fun setOnboardingComplete() {
        _needsOnboarding.value = false
        reloadActiveProfile(getApplication())
    }

    fun switchProfile(profileId: Int) {
        SessionManager.setActiveProfileId(getApplication(), profileId)
        viewModelScope.launch {
            ProfileManager.updateLastActive(profileId)
        }
        reloadActiveProfile(getApplication())
    }

    fun reloadActiveProfile(context: Context) {
        Log.d("Haq/VM", "reloadActiveProfile() called")
        viewModelScope.launch {
            val profileId = SessionManager.getActiveProfileId(context)
            val profile = ProfileManager.getActiveProfile(profileId) ?: return@launch
            activeProfile = profile
            _activeProfileName.value = profile.name
            _activeLanguage.value = profile.preferredLanguage
            Log.d("Haq/VM", "Profile reloaded: name=${profile.name} " +
                "lang=${profile.preferredLanguage} " +
                "state=${profile.state} " +
                "caste=${profile.casteCategory}")
        }
    }

    fun startNewProfile() {
        _needsOnboarding.value = true
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun retryDownload() {
        viewModelScope.launch { downloadManager.startDownload() }
    }

    fun retryEngine() {
        engineObserving = false
        GemmaManager.reinit(getApplication())
        observeEngineState()
    }

    /**
     * Stops any in-progress TTS, listens via the on-device SpeechRecognizer,
     * pipes the transcript into Gemma, then speaks the full response aloud
     * in the detected language.
     */
    fun onMicButtonPressed() {
        if (_engineState.value !is EngineState.Ready) return
        if (_appState.value != AppState.READY) return

        viewModelScope.launch {
            TTSManager.stop()
            delay(300) // allow TTS to release audio focus
            _appState.value = AppState.LISTENING
            try {
                val transcript = STTManager.recordAndTranscribe(getApplication(), _activeLanguage.value)
                Log.d("Haq/STT", "Transcript: \"$transcript\"")
                if (transcript.isNotBlank()) {
                    submitQuery(transcript)
                } else {
                    _appState.value = AppState.READY
                }
            } catch (e: Exception) {
                Log.e("Haq/STT", "Recognition error: ${e.message}")
                _appState.value = AppState.READY
            }
        }
    }

    private fun submitQuery(prompt: String) {
        viewModelScope.launch {
            if (_engineState.value !is EngineState.Ready) {
                Log.e("Haq/Gemma", "Engine not ready (state=${_engineState.value}), skipping query")
                _appState.value = AppState.READY
                return@launch
            }

            if (isGenerating) {
                Log.w("Haq/Gemma", "Already generating, ignoring query")
                return@launch
            }
            isGenerating = true

            _appState.value = AppState.THINKING
            _responseText.value = ""

            try {
                val fullResponse = StringBuilder()
                val sentenceBuffer = StringBuilder()

                // Profile context is prepended to the user query, not the system prompt.
                // LiteRT-LM's system prompt is set in ConversationConfig inside LiteRTEngine
                // and must not be duplicated here — doing so causes Status Code 13.
                val contextualQuery = activeProfile
                    ?.takeIf { it.isOnboarded }
                    ?.let { p ->
                        "User context: Name=${p.name}, State=${p.state}, " +
                        "Category=${p.casteCategory}, Occupation=${p.occupation}. " +
                        "Query: $prompt"
                    }
                    ?: prompt

                Log.d("Haq/Gemma", "submitQuery() contextualQuery length=${contextualQuery.length}")

                GemmaManager.generateResponse(contextualQuery).collect { token ->
                    fullResponse.append(token)
                    sentenceBuffer.append(token)
                    _responseText.value = fullResponse.toString()

                    // Speak when we hit a sentence boundary
                    val buf = sentenceBuffer.toString()
                    val boundaryIndex = buf.indexOfFirst {
                        it == '।' || it == '.' || it == '?' || it == '!'
                    }

                    if (boundaryIndex >= 0) {
                        val sentence = buf.substring(0, boundaryIndex + 1).trim()
                        if (sentence.length > 10) {
                            val ttsText = sentence
                                .replace("**", "")
                                .replace("*", "")
                                .replace("#", "")
                                .trim()
                            TTSManager.speak(text = ttsText, languageCode = _activeLanguage.value)
                        }
                        // Keep everything after the boundary in buffer
                        sentenceBuffer.clear()
                        sentenceBuffer.append(buf.substring(boundaryIndex + 1))
                    }
                }

                // Speak any remaining text after stream ends
                val remaining = sentenceBuffer.toString()
                    .replace("**", "")
                    .replace("*", "")
                    .replace("#", "")
                    .trim()
                if (remaining.length > 10) {
                    TTSManager.speak(text = remaining, languageCode = _activeLanguage.value)
                }

            } catch (e: Exception) {
                Log.e("Haq/Gemma", "submitQuery error", e)
            } finally {
                isGenerating = false
                _appState.value = AppState.READY
            }
        }
    }

    private fun startDownload() {
        viewModelScope.launch { downloadManager.startDownload() }
    }

    override fun onCleared() {
        super.onCleared()
        TTSManager.shutdown()
        GemmaManager.shutdown()
    }
}
