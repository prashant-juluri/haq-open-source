package com.haq.app.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only inference entry point for the rest of the app.
 * No LiteRT types are exposed beyond this file.
 */
object GemmaManager {

    private lateinit var engine: LiteRTEngine

    val state: StateFlow<EngineState>
        get() = engine.state

    /** Call once after [ModelDownloadManager] emits [DownloadState.Complete]. */
    fun init(context: Context) {
        if (::engine.isInitialized) return
        engine = LiteRTEngine(context.applicationContext)
        Log.d("Haq/Gemma", "Engine created hashCode=${engine.hashCode()}")
    }

    /** Tear down the current engine and create a fresh one (for error recovery). */
    fun reinit(context: Context) {
        if (::engine.isInitialized) engine.shutdown()
        engine = LiteRTEngine(context.applicationContext)
    }

    fun generateResponse(prompt: String): Flow<String> {
        Log.d("Haq/Gemma", "generateResponse() engine hashCode=${engine.hashCode()}")
        return engine.generateResponse(prompt)
    }

    fun shutdown() {
        if (::engine.isInitialized) engine.shutdown()
    }
}
