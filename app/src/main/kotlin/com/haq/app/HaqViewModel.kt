package com.haq.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.inference.EngineState
import com.haq.app.inference.GemmaManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class HaqViewModel(application: Application) : AndroidViewModel(application) {

    val engineState: StateFlow<EngineState> by lazy { GemmaManager.state }

    private val _appState = MutableStateFlow(AppState.LOADING)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    init {
        GemmaManager.init(application)

        viewModelScope.launch {
            engineState.collect { state ->
                _appState.value = when (state) {
                    is EngineState.Loading -> AppState.LOADING
                    is EngineState.Ready   -> AppState.READY
                    is EngineState.Error   -> AppState.ERROR
                }
            }
        }
    }

    fun submitQuery(prompt: String) {
        if (engineState.value !is EngineState.Ready) return

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

    override fun onCleared() {
        super.onCleared()
        GemmaManager.shutdown()
    }
}
