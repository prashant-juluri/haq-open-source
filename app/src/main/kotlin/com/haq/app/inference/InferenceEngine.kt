package com.haq.app.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed class EngineState {
    object Loading : EngineState()
    object Ready : EngineState()
    data class Error(val message: String) : EngineState()
}

/**
 * Single abstraction over all on-device language-model backends.
 * Week 1: [LiteRTEngine]. Post-hackathon: swap to ML Kit GenAI / AICore
 * by dropping in a new implementation — no other code changes required.
 */
interface InferenceEngine {
    val state: StateFlow<EngineState>
    fun generateResponse(prompt: String): Flow<String>

    /**
     * Exploration: transcribe [pcmSamples] (16 kHz, 16-bit mono from AudioRecord)
     * using the model's built-in audio encoder. Streams the raw transcript tokens.
     * [languageName] is the full language name ("Telugu", "Malayalam", etc.) used
     * to instruct the model which script to output.
     *
     * Only meaningful when the underlying model has an audio encoder (Gemma 4 E2B/E4B).
     * Returns an empty flow if audio is not supported by the implementation.
     */
    fun transcribeAudio(pcmSamples: FloatArray, languageName: String): Flow<String> =
        kotlinx.coroutines.flow.emptyFlow()
}
