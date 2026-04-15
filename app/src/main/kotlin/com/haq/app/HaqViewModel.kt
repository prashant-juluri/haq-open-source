package com.haq.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.inference.DownloadState
import com.haq.app.inference.EngineState
import com.haq.app.inference.GemmaManager
import com.haq.app.inference.ModelDownloadManager
import com.haq.app.stt.STTManager
import com.haq.app.tts.TTSManager
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

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        TTSManager.init(application)
        startDownload()

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
                val transcript = STTManager.recognize(getApplication())
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
        Log.d("Haq/Debug", "submitQuery called with: $prompt")
        viewModelScope.launch {
            _appState.value = AppState.THINKING
            _responseText.value = ""

            try {
                val fullResponse = StringBuilder()
                Log.d("Haq/Gemma", "Starting flow collection")
                GemmaManager.generateResponse(prompt).collect { token ->
                    Log.d("Haq/Gemma", "Token received: '$token'")
                    fullResponse.append(token)
                    _responseText.value = fullResponse.toString()
                }
                Log.d("Haq/Gemma", "Collection complete, response length=${fullResponse.length}")
                Log.d("Haq/Gemma", "Calling TTS speak()")
                val ttsText = fullResponse.toString()
                    .replace("**", "")
                    .replace("*", "")
                    .replace("#", "")
                    .trim()
                TTSManager.speak(text = ttsText, languageCode = "hi")
            } catch (e: Exception) {
                Log.e("Haq/Gemma", "Exception in submitQuery", e)
            } finally {
                Log.d("Haq/Gemma", "finally block — setting READY")
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
