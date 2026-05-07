package com.haq.app.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
     *
     * Accepts an optional [context] so callers (e.g. OnboardingViewModel) that run before
     * [GemmaManager.init] is called can still check the model file directly, without
     * depending on [appContext] being set. Falls back to [appContext] if null.
     *
     * Dispatches to [Dispatchers.IO] so the [File.exists] and [File.length] syscalls
     * never block the main thread.
     */
    suspend fun isModelReady(context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        val ctx = context?.applicationContext ?: appContext ?: return@withContext false
        val modelFile = File(ctx.filesDir, ModelDownloadManager.MODEL_FILENAME)
        val ready = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE
        Log.d("Haq/Gemma", "isModelReady=$ready size=${if (modelFile.exists()) modelFile.length() else 0}")
        ready
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
            .catch { e ->
                // Status Code 13 = DYNAMIC_UPDATE_SLICE failure in prefill_1024 subgraph.
                // On first launch the xnnpack delegate for subgraph 1 lazily initialises
                // and conflicts with the partially-built cache from subgraph 0's init.
                // Reinitialising gives the engine a clean slate; the second attempt
                // reliably succeeds — which is exactly what "works on restart" demonstrates.
                val isSubgraphInitFailure = e.message?.contains("Status Code: 13") == true
                val ctx = appContext
                if (isSubgraphInitFailure && ctx != null) {
                    Log.w("Haq/Gemma", "Status Code 13 — reinitialising engine and retrying once")
                    reinit(ctx)
                    // Wait for the new engine to finish initialising before retrying
                    engine.state.first { it !is EngineState.Loading }
                    Log.d("Haq/Gemma", "Engine reinit complete, retrying query")
                    emitAll(engine.generateResponse(prompt))
                } else {
                    throw e
                }
            }
    }

    fun shutdown() {
        if (::engine.isInitialized) engine.shutdown()
    }
}
