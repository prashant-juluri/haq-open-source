package com.haq.app.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

class LiteRTEngine(private val context: Context) : InferenceEngine {

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<EngineState>(EngineState.Loading)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var engine: Engine? = null

    init {
        engineScope.launch {
            try {
                val modelPath = modelFilePath()
                val config = EngineConfig(
                    modelPath,
                    Backend.CPU(),
                    Backend.CPU(),
                    Backend.CPU(),
                    MAX_TOKENS,
                    context.cacheDir.absolutePath,
                )
                val eng = Engine(config)
                eng.initialize()
                engine = eng
                _state.value = EngineState.Ready
            } catch (e: Exception) {
                _state.value = EngineState.Error(e.message ?: "Failed to load model")
            }
        }
    }

    override fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        val eng = engine ?: run {
            close(IllegalStateException("Engine not ready"))
            return@callbackFlow
        }

        val session: Session = eng.createSession(SessionConfig())
        val inputs = listOf(InputData.Text(buildPrompt(prompt)))

        session.generateContentStream(inputs, object : ResponseCallback {
            override fun onNext(token: String) {
                if (token.isNotEmpty()) trySend(token)
            }
            override fun onDone() {
                session.close()
                close()
            }
            override fun onError(e: Throwable) {
                session.close()
                close(e)
            }
        })

        awaitClose {
            session.cancelProcess()
            session.close()
        }
    }.flowOn(Dispatchers.IO)

    fun shutdown() {
        engine?.close()
        engineScope.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the absolute path to the model in filesDir.
     * The model is NOT bundled in the APK — push it once before first run:
     *
     *   adb push app/src/main/assets/gemma-4-E2B-it.litertlm \
     *       /data/data/com.haq.app/files/gemma-4-E2B-it.litertlm
     */
    private fun modelFilePath(): String {
        val f = File(context.filesDir, MODEL_ASSET)
        if (!f.exists()) {
            error(
                "Model not found at ${f.absolutePath}. " +
                "Push it with: adb push gemma-4-E2B-it.litertlm " +
                "/data/data/com.haq.app/files/gemma-4-E2B-it.litertlm"
            )
        }
        return f.absolutePath
    }

    private fun buildPrompt(userQuery: String): String =
        "<start_of_turn>user\n$SYSTEM_PROMPT\n\n$userQuery<end_of_turn>\n" +
        "<start_of_turn>model\n"

    companion object {
        private const val MODEL_ASSET = "gemma-4-E2B-it.litertlm"
        private const val MAX_TOKENS  = 1024

        private const val SYSTEM_PROMPT =
            "You are Haq, an AI that helps marginalised Indian citizens " +
            "understand and claim their government entitlements. Always " +
            "respond in the same language the user speaks. Calculate " +
            "specific rupee amounts. List documents in order of difficulty " +
            "to obtain. End every response with the relevant helpline number. " +
            "Never suggest the user needs a middleman or agent."
    }
}
