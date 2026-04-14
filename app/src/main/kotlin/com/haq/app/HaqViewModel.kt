package com.haq.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.inference.DownloadState
import com.haq.app.inference.EngineState
import com.haq.app.inference.GemmaManager
import com.haq.app.inference.ModelDownloadManager
import com.haq.app.stt.STTManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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

    // Preferred language for STT — "hi" until user profile is implemented
    private val preferredLanguage = "hi"

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        STTManager.init(application)
        startDownload()

        // When download completes → initialise Gemma; mirror engine state to UI
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

    // ── Actions ───────────────────────────────────────────────────────────────

    fun retryDownload() {
        viewModelScope.launch { downloadManager.startDownload() }
    }

    fun retryEngine() {
        engineObserving = false
        GemmaManager.reinit(getApplication())
        observeEngineState()
    }

    /** Called when the user taps the mic button. Records 10 s then queries Gemma. */
    fun onMicButtonPressed() {
        if (_engineState.value !is EngineState.Ready) return
        if (_appState.value != AppState.READY) return

        viewModelScope.launch {
            _appState.value = AppState.LISTENING
            try {
                val transcript = STTManager.recordAndTranscribe(preferredLanguage)
                if (transcript.isNotBlank()) {
                    submitQuery(transcript)
                } else {
                    _appState.value = AppState.READY
                }
            } catch (e: Exception) {
                _appState.value = AppState.READY
            }
        }
    }

    private fun submitQuery(prompt: String) {
        viewModelScope.launch {
            _appState.value = AppState.THINKING
            _responseText.value = ""

            GemmaManager.generateResponse(prompt)
                .catch { e ->
                    _responseText.value += "\n\n[Error: ${e.message}]"
                    _appState.value = AppState.ERROR
                }
                .collect { token ->
                    _responseText.value += token
                }

            if (_appState.value == AppState.THINKING) {
                _appState.value = AppState.READY
            }
        }
    }

    private fun startDownload() {
        viewModelScope.launch { downloadManager.startDownload() }
    }

    override fun onCleared() {
        super.onCleared()
        GemmaManager.shutdown()
        STTManager.shutdown()
    }
}
