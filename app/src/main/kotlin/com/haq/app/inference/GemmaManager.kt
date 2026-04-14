package com.haq.app.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

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
    }

    /** Tear down the current engine and create a fresh one (for error recovery). */
    fun reinit(context: Context) {
        if (::engine.isInitialized) engine.shutdown()
        engine = LiteRTEngine(context.applicationContext)
    }

    fun generateResponse(prompt: String): Flow<String> =
        engine.generateResponse(prompt)

    fun generateResponseFromAudio(audioFile: File): Flow<String> =
        engine.generateResponseFromAudio(audioFile)

    fun shutdown() {
        if (::engine.isInitialized) engine.shutdown()
    }
}
