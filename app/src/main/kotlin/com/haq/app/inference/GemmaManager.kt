package com.haq.app.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The only inference entry point for the rest of the app.
 * No LiteRT types are exposed beyond this file.
 */
object GemmaManager {

    private lateinit var engine: LiteRTEngine
    private var appContext: Context? = null

    val state: StateFlow<EngineState>
        get() = engine.state

    /** Call once after [ModelDownloadManager] emits [DownloadState.Complete]. */
    fun init(context: Context) {
        if (::engine.isInitialized) return
        appContext = context.applicationContext
        engine = LiteRTEngine(context.applicationContext)
        Log.d("Haq/Gemma", "Engine created hashCode=${engine.hashCode()}")
    }

    /**
     * Returns true only if the model file exists and meets the minimum size threshold.
     * A file smaller than [MIN_MODEL_SIZE] is an incomplete download — treat as not ready.
     */
    fun isModelReady(): Boolean {
        val ctx = appContext ?: return false
        val modelFile = File(ctx.filesDir, ModelDownloadManager.MODEL_FILENAME)
        val ready = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE
        Log.d("Haq/Gemma", "isModelReady=$ready size=${if (modelFile.exists()) modelFile.length() else 0}")
        return ready
    }

    private const val MIN_MODEL_SIZE = 1_000_000_000L // 1GB — incomplete downloads are smaller

    /** Tear down the current engine and create a fresh one (for error recovery). */
    fun reinit(context: Context) {
        appContext = context.applicationContext
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
