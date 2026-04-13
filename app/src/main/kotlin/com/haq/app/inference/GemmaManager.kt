package com.haq.app.inference

import android.content.Context
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

    fun init(context: Context) {
        if (::engine.isInitialized) return
        engine = LiteRTEngine(context.applicationContext)
    }

    fun generateResponse(prompt: String): Flow<String> =
        engine.generateResponse(prompt)

    fun shutdown() = engine.shutdown()
}
