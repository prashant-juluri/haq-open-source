package com.haq.app.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
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

    // Tracks the active conversation so it can be explicitly closed before the next
    // generateResponse() call. Reusing a Conversation object causes Status Code 13
    // (LiteRtLmJniException) — always create a fresh one per query.
    private var activeConversation: Conversation? = null

    init {
        engineScope.launch {
            try {
                val modelPath = modelFilePath()
                // Clear stale xnnpack cache files before creating the engine.
                // A DYNAMIC_UPDATE_SLICE error on first launch is caused by leftover
                // cache files from a previous install. Delete them once here so the
                // engine always starts from a clean cache directory.
                context.cacheDir.listFiles { f ->
                    f.name.endsWith(".xnnpack_cache")
                }?.forEach { it.delete() }
                Log.d("Haq/Gemma", "Cleared xnnpack caches")

                val config = EngineConfig(
                    modelPath,
                    Backend.CPU(),
                    null,          // visionBackend — Haq is text-only; the E2B vision encoder
                                   // has 3 signatures (vision_70/140/280) but the SDK expects
                                   // exactly 1, so disabling it avoids INVALID_ARGUMENT crash.
                    null,          // audioBackend  — STT uses Android SpeechRecognizer; Gemma
                                   // audio input not used in current implementation.
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
        Log.d("Haq/Gemma", "generateResponse() called, engine state: ${_state.value}")
        val eng = engine ?: run {
            close(IllegalStateException("Engine not ready"))
            return@callbackFlow
        }

        // Close any previous conversation before creating a new one.
        // Reusing a Conversation causes Status Code 13 (LiteRtLmJniException).
        try { activeConversation?.cancelProcess() } catch (e: Exception) { }
        try { activeConversation?.close() } catch (e: Exception) { }
        activeConversation = null

        val conversation: Conversation = eng.createConversation(
            ConversationConfig(Contents.of(SYSTEM_PROMPT))
        )
        activeConversation = conversation
        Log.d("Haq/Gemma", "New conversation created hashCode=${conversation.hashCode()}")

        // tokenBuffer is local so parallel calls (guarded by isGenerating) can't share state.
        val tokenBuffer = StringBuilder()

        conversation.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }

                tokenBuffer.append(text)
                val buf = tokenBuffer.toString()

                // If buffer contains a complete end marker, close
                if (buf.contains("<end_of_turn>") ||
                    buf.contains("<start_of_turn>model") ||
                    buf.contains("</s>")) {
                    close()
                    return
                }

                // No '<' in buffer — safe to emit all of it
                if (!buf.contains("<")) {
                    if (buf.isNotEmpty()) trySend(buf)
                    tokenBuffer.clear()
                    return
                }

                // Buffer contains '<' — emit everything before it, keep rest
                val ltIndex = buf.indexOf("<")
                if (ltIndex > 0) {
                    val safe = buf.substring(0, ltIndex)
                    trySend(safe)
                    tokenBuffer.clear()
                    tokenBuffer.append(buf.substring(ltIndex))
                }
                // ltIndex == 0: keep buffering until we know if it's a control token
            }

            override fun onDone() {
                // Emit anything remaining that isn't a control token
                val remaining = tokenBuffer.toString()
                    .replace("<end_of_turn>", "")
                    .replace("<start_of_turn>", "")
                    .trim()
                if (remaining.isNotEmpty()) trySend(remaining)
                tokenBuffer.clear()
                close()
            }

            override fun onError(e: Throwable) {
                close(e)
            }
        })

        awaitClose {
            tokenBuffer.clear()
            try { conversation.cancelProcess() } catch (e: Exception) { }
            try { conversation.close() } catch (e: Exception) { }
            if (activeConversation === conversation) activeConversation = null
        }
    }.flowOn(Dispatchers.IO)

    fun shutdown() {
        engine?.close()
        engineScope.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun modelFilePath(): String {
        val f = File(context.filesDir, MODEL_ASSET)
        if (!f.exists()) error("Model not downloaded yet")
        return f.absolutePath
    }

    companion object {
        private const val MODEL_ASSET = "gemma-4-E2B-it.litertlm"
        // prefill_1024 writes a 1024-slot chunk starting at the current KV cache
        // position. System prompt (~70 tokens) is prefilled first, so the user-turn
        // prefill runs from ~position 70 and needs slots [70…1094]. The KV cache must
        // be larger than 1094 — 2048 gives comfortable headroom for longer queries too.
        private const val MAX_TOKENS  = 2048

        private const val SYSTEM_PROMPT =
            "You are Haq, a trusted guide helping marginalised Indian citizens " +
            "understand their government entitlements and legal rights. Always " +
            "respond in the same language the user speaks. Every answer must be " +
            "grounded in the person's specific profile and question — never give " +
            "a generic response. Write in flowing prose, never use bullet points " +
            "or numbered lists. When an entitlement or legal remedy has a specific " +
            "value (rupees, ration quantity, days of relief, etc.), state it " +
            "precisely. Name all documents the person needs and explain briefly " +
            "why each matters. Close every response with the relevant helpline " +
            "number or authority to approach. If you do not know something, say " +
            "so plainly — never invent a scheme or law, and never suggest the " +
            "user needs a middleman or paid agent. Draw on your knowledge of " +
            "Indian government schemes and laws when answering."
    }
}
