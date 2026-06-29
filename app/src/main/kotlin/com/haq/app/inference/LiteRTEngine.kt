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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
                    null,           // visionBackend — E2B vision encoder has 3 signatures
                                    // (vision_70/140/280) but SDK expects exactly 1; disabled
                                    // to avoid INVALID_ARGUMENT crash.
                    Backend.CPU(),  // audioBackend — enabled for Gemma audio STT exploration.
                                    // Activates the E2B audio encoder (~300 M params, 16 kHz,
                                    // max 30 s). Set to null to revert to Whisper path.
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

    /**
     * Exploration: transcribe [pcmSamples] (16 kHz, 16-bit mono from AudioRecord)
     * via Gemma 4 E2B's built-in audio encoder.
     *
     * Converts int16 PCM → float32 [-1, 1] as expected by the encoder, then sends
     * a minimal Contents message (AudioBytes + Text instruction) on a fresh
     * conversation so it doesn't pollute the welfare assistant's KV cache.
     *
     * Max audio: 30 s (750 encoder frames @ 25 fps). Longer audio is silently
     * truncated by the model. Streams raw transcript tokens — caller collects
     * and joins into the final transcript string.
     */
    override fun transcribeAudio(pcmSamples: FloatArray, languageName: String): Flow<String> =
        callbackFlow {
            Log.d(TAG_AUDIO, "transcribeAudio() called — ${pcmSamples.size} samples " +
                "(${pcmSamples.size / 16000f}s), lang=$languageName")
            val eng = engine ?: run {
                Log.e(TAG_AUDIO, "Engine not ready")
                close()
                return@callbackFlow
            }

            // Use a separate conversation so audio transcription doesn't share
            // KV cache with the welfare assistant conversation.
            val conversation: Conversation = eng.createConversation(
                ConversationConfig(Contents.of(TRANSCRIPTION_SYSTEM_PROMPT))
            )
            Log.d(TAG_AUDIO, "Transcription conversation created")

            val audioBytes = floatArrayToBytes(pcmSamples)
            val tokenBuffer = StringBuilder()

            conversation.sendMessageAsync(
                Contents.of(
                    Content.AudioBytes(audioBytes),
                    Content.Text(
                        "Transcribe exactly what was spoken in the audio above. " +
                        "Output ONLY the transcription in $languageName. " +
                        "Do not translate, summarise, or add any commentary."
                    )
                ),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        tokenBuffer.append(text)
                        val buf = tokenBuffer.toString()
                        if (buf.contains("<end_of_turn>") ||
                            buf.contains("<start_of_turn>model") ||
                            buf.contains("</s>")) {
                            close()
                            return
                        }
                        if (!buf.contains("<")) {
                            if (buf.isNotEmpty()) trySend(buf)
                            tokenBuffer.clear()
                            return
                        }
                        val ltIndex = buf.indexOf("<")
                        if (ltIndex > 0) {
                            trySend(buf.substring(0, ltIndex))
                            tokenBuffer.clear()
                            tokenBuffer.append(buf.substring(ltIndex))
                        }
                    }
                    override fun onDone() {
                        val remaining = tokenBuffer.toString()
                            .replace("<end_of_turn>", "")
                            .replace("<start_of_turn>", "")
                            .trim()
                        if (remaining.isNotEmpty()) trySend(remaining)
                        tokenBuffer.clear()
                        close()
                    }
                    override fun onError(e: Throwable) {
                        Log.e(TAG_AUDIO, "transcribeAudio error: ${e.message}", e)
                        close(e)
                    }
                }
            )

            awaitClose {
                tokenBuffer.clear()
                try { conversation.cancelProcess() } catch (e: Exception) {}
                try { conversation.close() } catch (e: Exception) {}
                Log.d(TAG_AUDIO, "Transcription conversation closed")
            }
        }.flowOn(Dispatchers.IO)

    fun shutdown() {
        engine?.close()
        engineScope.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Converts 16-bit PCM samples from AudioRecord to float32 bytes in [-1, 1]
     * as expected by the Gemma 4 E2B audio encoder.
     * int16 range [-32768, 32767] → divide by 32768f → float32 [-1, 1].
     */
    private fun floatArrayToBytes(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer
            .allocate(samples.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putFloat(sample)
        }
        return buffer.array()
    }

    private fun modelFilePath(): String {
        val f = File(context.filesDir, MODEL_ASSET)
        if (!f.exists()) error("Model not downloaded yet")
        return f.absolutePath
    }

    companion object {
        private const val TAG_AUDIO   = "Haq/GemmaAudio"
        private const val MODEL_ASSET = "gemma-4-E2B-it.litertlm"
        // prefill_1024 writes a 1024-slot chunk starting at the current KV cache
        // position. System prompt (~70 tokens) is prefilled first, so the user-turn
        // prefill runs from ~position 70 and needs slots [70…1094]. The KV cache must
        // be larger than 1094 — 2048 gives comfortable headroom for longer queries too.
        private const val MAX_TOKENS  = 2048

        // Minimal system prompt for the transcription-only conversation.
        // Kept separate from SYSTEM_PROMPT so the welfare persona doesn't
        // bleed into pure transcription output.
        private const val TRANSCRIPTION_SYSTEM_PROMPT =
            "You are a transcription assistant. When given audio, output only " +
            "the exact words spoken. Never translate, summarise, or add commentary."

        private const val SYSTEM_PROMPT =
            "You are Haq, a trusted guide helping marginalised Indian citizens " +
            "understand their government entitlements and legal rights. Always " +
            "respond in the same language the user speaks. Every answer must be " +
            "grounded in the person's specific profile and question — never give " +
            "a generic response. Write in flowing prose, never use bullet points " +
            "or numbered lists. When an entitlement or legal remedy has a specific " +
            "value (rupees, ration quantity, days of relief, etc.), state it " +
            "precisely. Name all documents the person needs and explain briefly " +
            "why each matters. When nearby government offices are provided, name " +
            "the office and its phone number early in your response — do not save " +
            "it for the end. If you do not know something, say so plainly — never " +
            "invent a scheme or law, and never suggest the user needs a middleman " +
            "or paid agent. If critical demographic information is missing (such " +
            "as state, caste category, income, or occupation) and you need it to " +
            "give an accurate answer, ask the user for that specific detail before " +
            "proceeding. Draw on your knowledge of Indian government schemes and " +
            "laws when answering."
    }
}
