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
}
