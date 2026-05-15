package com.haq.app.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device Whisper-tiny inference via ONNX Runtime.
 *
 * Hybrid two-model decoding strategy:
 *
 *   Step 0  — decoder_model_merged.onnx (use_cache_branch=false):
 *     Inputs : input_ids [1, 4], encoder_hidden_states [1, 1500, 384],
 *              use_cache_branch=false, past_key_values.* all empty (seq=0)
 *     Outputs: logits [1, 4, vocab] (read by name), present.N.{decoder|encoder}.{key|value}
 *     The false branch always outputs REAL encoder cross-attention KV (seq=1500) —
 *     read via OnnxTensor.floatBuffer to bypass the nested-array seq=0 bug in ORT Java API.
 *
 *   Steps 1+ — decoder_model_merged.onnx (use_cache_branch=true):
 *     Inputs : input_ids [1, 1], encoder_hidden_states, use_cache_branch=true,
 *              past_key_values.N.encoder.* (fixed from step 0, seq=1500),
 *              past_key_values.N.decoder.* (grows by 1 each step)
 *     Outputs: logits [1, 1, vocab], present.N.decoder.{key|value}
 *     The true branch does NOT recompute encoder KV; we always carry it from step 0.
 *     The present.* outputs feed back as past_key_values.* inputs.
 *
 * Files live in [Context.getFilesDir]/whisper/.
 */
class WhisperEngine(private val context: Context) {

    companion object {
        private const val TAG = "Haq/Whisper"

        fun isAvailable(context: Context): Boolean {
            val base = File(context.filesDir, "whisper")
            return File(base, "encoder_model.onnx").exists() &&
                   File(base, "decoder_model_merged.onnx").exists() &&
                   File(base, "tokenizer.json").exists()
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encSession: OrtSession? = null
    private var decMergedSession: OrtSession? = null    // all decode steps

    /**
     * KV name mapping for all decode steps.
     * decoder_model_merged.onnx outputs "present.*"
     * decoder_model_merged.onnx inputs "past_key_values.*"
     * Mapped by stripping prefix and matching on the "N.decoder/encoder.key/value" suffix.
     */
    private var kvMap: Map<String, String> = emptyMap()

    /** Names of the merged model's past_key_values inputs (used to create empty tensors at step 0). */
    private var mergedKvInputNames: List<String> = emptyList()

    private var idToToken: Map<Int, String> = emptyMap()
    private val byteDecoder: Map<Int, Char> = buildGpt2ByteDecoder()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun init() {
        val opts = OrtSession.SessionOptions().apply {
            setInterOpNumThreads(2)
            setIntraOpNumThreads(4)
        }
        val base = File(context.filesDir, "whisper")

        encSession       = env.createSession(File(base, "encoder_model.onnx").absolutePath, opts)
        decMergedSession = env.createSession(File(base, "decoder_model_merged.onnx").absolutePath, opts)

        mergedKvInputNames = decMergedSession!!.inputNames
            .filter { "past_key_values" in it }.sorted()

        // Map: merged model present.* output names → merged model past_key_values.* input names
        val mergedKvOutputs = decMergedSession!!.outputNames
            .filter { "present" in it || "past_key_values" in it }.sorted()
        kvMap = buildKvMap(mergedKvOutputs, mergedKvInputNames)

        Log.d(TAG, "Encoder inputs:        ${encSession!!.inputNames.joinToString()}")
        Log.d(TAG, "Merged decoder inputs: ${decMergedSession!!.inputNames.joinToString()}")
        Log.d(TAG, "Merged decoder outputs:${decMergedSession!!.outputNames.joinToString()}")
        Log.d(TAG, "KV map (${kvMap.size}): ${kvMap.entries.take(2)}…")

        loadTokenizer(File(base, "tokenizer.json"))
        Log.d(TAG, "WhisperEngine ready")
    }

    fun close() {
        encSession?.close();       encSession = null
        decMergedSession?.close(); decMergedSession = null
    }

    // ── Public API ───────────────────────────────────────────────────────────

    suspend fun transcribe(samples: FloatArray, langToken: Int): String =
        withContext(Dispatchers.Default) {
            try {
                val mel    = MelSpectrogram.compute(samples)
                val hidden = runEncoder(mel)
                val tokens = greedyDecode(hidden, langToken)
                detokenize(tokens).also { Log.d(TAG, "Transcript: '$it'") }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                ""
            }
        }

    // ── Encoder ──────────────────────────────────────────────────────────────

    private fun runEncoder(mel: FloatArray): FloatArray {
        val nMels   = WhisperConfig.N_MELS.toLong()
        val nFrames = WhisperConfig.N_FRAMES.toLong()
        val tensor  = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel),
                          longArrayOf(1L, nMels, nFrames))
        val result  = encSession!!.run(mapOf("input_features" to tensor))
        tensor.close()

        val steps  = WhisperConfig.ENCODER_STEPS
        val dModel = WhisperConfig.D_MODEL
        val out    = FloatArray(steps * dModel)

        @Suppress("UNCHECKED_CAST")
        val raw = result[0].value as Array<Array<FloatArray>>   // [1][steps][dModel]
        for (s in 0 until steps)
            for (d in 0 until dModel)
                out[s * dModel + d] = raw[0][s][d]

        result.close()
        return out
    }

    // ── Decoder ──────────────────────────────────────────────────────────────

    /**
     * Greedy decode using decoder_model_merged.onnx for all steps:
     *
     * Step 0  — use_cache_branch=false, full 4-token prompt, empty past_key_values (seq=0).
     *   Outputs logits [1, 4, vocab] (read by name), present.N.{decoder|encoder}.{key|value}.
     *   The false branch computes real encoder cross-attention KV (seq=1500) which we
     *   read via floatBuffer to bypass the nested-array seq=0 bug in ORT Java API.
     *
     * Steps 1+ — use_cache_branch=true, single new token.
     *   Encoder KV is always the fixed tensors from step 0 (it never changes per utterance).
     *   Decoder KV grows by 1 each step.
     *   The true branch does NOT recompute encoder KV; ORT returns null tensors for
     *   present.N.encoder.* which extractKv silently skips — we keep step 0's encoder KV.
     *
     * Max generated tokens: MAX_TOKENS − prompt.size (= 444) to stay within the decoder's
     * positional embedding limit of 448.
     */
    private fun greedyDecode(encoderHidden: FloatArray, langToken: Int): List<Int> {
        val encSteps = WhisperConfig.ENCODER_STEPS
        val dModel   = WhisperConfig.D_MODEL
        val encShape = longArrayOf(1L, encSteps.toLong(), dModel.toLong())

        val prompt = intArrayOf(
            WhisperConfig.TOKEN_SOT,
            langToken,
            WhisperConfig.TOKEN_TRANSCRIBE,
            WhisperConfig.TOKEN_NO_TIMESTAMPS,
        )

        val generated = mutableListOf<Int>()

        // ── Step 0: merged model, false branch, empty KV cache ────────────────
        val step0Inputs = mutableMapOf<String, OnnxTensor>()
        val idLongs0 = LongArray(prompt.size) { prompt[it].toLong() }
        step0Inputs["input_ids"] = OnnxTensor.createTensor(
            env, LongBuffer.wrap(idLongs0), longArrayOf(1L, prompt.size.toLong()))
        step0Inputs["encoder_hidden_states"] = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(encoderHidden), encShape)
        step0Inputs["use_cache_branch"] = OnnxTensor.createTensor(
            env, booleanArrayOf(false))
        // Provide empty (seq=0) past_key_values so the false branch ignores them.
        for (name in mergedKvInputNames) {
            step0Inputs[name] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(FloatArray(0)),
                longArrayOf(1L, WhisperConfig.NUM_HEADS.toLong(), 0L, WhisperConfig.HEAD_DIM.toLong()))
        }

        val step0Result = decMergedSession!!.run(step0Inputs)
        step0Inputs.values.forEach { it.close() }

        // Read logits by name — avoids relying on output index ordering.
        @Suppress("UNCHECKED_CAST")
        val logits0 = (step0Result.get("logits").get() as OnnxTensor).value as Array<Array<FloatArray>>
        var nextToken = logits0[0].last().indices.maxByOrNull { logits0[0].last()[it] }!!

        // Extract all KV from step 0 via raw floatBuffer (bypasses nested-array seq=0 bug).
        // Encoder KV is fixed for the entire utterance; decoder KV grows each step.
        val allStep0Kv = extractKv(step0Result, kvMap)
        step0Result.close()

        val encoderKv = allStep0Kv.filter { "encoder" in it.key }  // seq=1500, fixed
        var decoderKv = allStep0Kv.filter { "decoder" in it.key }  // seq=4, will grow

        Log.d(TAG, "Step 0: nextToken=$nextToken encoderKv=${encoderKv.size} decoderKv=${decoderKv.size}")

        if (nextToken == WhisperConfig.TOKEN_EOT || nextToken >= WhisperConfig.TOKEN_SOT)
            return generated
        generated.add(nextToken)

        // ── Steps 1+: merged model, true branch, growing decoder KV ──────────
        // Cap at MAX_TOKENS - prompt.size to stay within the 448-position limit.
        repeat(WhisperConfig.MAX_TOKENS - prompt.size) {
            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs["input_ids"] = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(nextToken.toLong())), longArrayOf(1L, 1L))
            inputs["encoder_hidden_states"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(encoderHidden), encShape)
            inputs["use_cache_branch"] = OnnxTensor.createTensor(
                env, booleanArrayOf(true))
            // Fixed encoder KV (step 0) + growing decoder KV
            for ((name, pair) in encoderKv + decoderKv) {
                val (data, shape) = pair
                inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            }

            val result = decMergedSession!!.run(inputs)
            inputs.values.forEach { it.close() }

            // Read logits by name.
            @Suppress("UNCHECKED_CAST")
            val logits = (result.get("logits").get() as OnnxTensor).value as Array<Array<FloatArray>>
            nextToken  = logits[0][0].indices.maxByOrNull { logits[0][0][it] }!!

            // Extract only decoder KV from steps 1+ — encoder KV stays from step 0.
            decoderKv = extractKv(result, kvMap).filter { "decoder" in it.key }
            result.close()

            if (nextToken == WhisperConfig.TOKEN_EOT || nextToken >= WhisperConfig.TOKEN_SOT)
                return generated
            generated.add(nextToken)
        }

        return generated
    }

    /**
     * Extracts KV tensors from a decoder result using [kvMap] to translate output
     * names to the input names expected by the next step's decoder.
     *
     * Uses OnnxTensor.floatBuffer + info.shape instead of value-as-nested-array.
     * ORT ≤ 1.17 returns seq=0 for encoder KV via the nested-array path even when
     * the underlying buffer has 1500 floats; reading the raw buffer bypasses that.
     */
    private fun extractKv(
        result: OrtSession.Result,
        kvMap:  Map<String, String>,
    ): Map<String, Pair<FloatArray, LongArray>> {
        val cache = mutableMapOf<String, Pair<FloatArray, LongArray>>()
        for ((outName, inName) in kvMap) {
            try {
                val v = result.get(outName)
                if (!v.isPresent) { Log.w(TAG, "KV '$outName' absent"); continue }

                val tensor = v.get() as OnnxTensor
                val shape  = tensor.info.shape   // [1, heads, seq, dim]
                val heads  = shape[1].toInt()
                val seq    = shape[2].toInt()
                val dim    = shape[3].toInt()

                if (seq == 0) {
                    Log.w(TAG, "KV '$outName' seq=0 — not output by this branch")
                    continue
                }

                val flat = FloatArray(heads * seq * dim)
                tensor.floatBuffer.get(flat)

                cache[inName] = Pair(flat, longArrayOf(1L, heads.toLong(), seq.toLong(), dim.toLong()))
                Log.d(TAG, "KV '$outName' → '$inName' [1,$heads,$seq,$dim]")
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract KV '$outName': ${e.message}")
            }
        }
        return cache
    }

    // ── KV name mapping ───────────────────────────────────────────────────────

    /**
     * Builds a map from each name in [outputs] to the corresponding name in [inputs]
     * by stripping the "present." or "past_key_values." prefix and matching on the
     * remaining suffix (e.g. "0.decoder.key"). Handles the present→past_key_values
     * renaming used between decode steps.
     */
    private fun buildKvMap(outputs: List<String>, inputs: List<String>): Map<String, String> {
        fun suffix(name: String) = when {
            name.startsWith("present.")         -> name.removePrefix("present.")
            name.startsWith("past_key_values.") -> name.removePrefix("past_key_values.")
            else                                -> name
        }
        val suffixToInput = inputs.associateBy { suffix(it) }
        return outputs.associateWith { outName ->
            suffixToInput[suffix(outName)] ?: outName
        }
    }

    // ── Tokenizer ────────────────────────────────────────────────────────────

    private fun loadTokenizer(file: File) {
        if (!file.exists()) { Log.w(TAG, "tokenizer.json not found"); return }
        try {
            val root  = JSONObject(file.readText())
            val vocab = root.optJSONObject("model")?.optJSONObject("vocab")
                ?: root.optJSONObject("vocab")
                ?: run { Log.w(TAG, "tokenizer.json: no vocab found"); return }

            val i2t  = mutableMapOf<Int, String>()
            val keys = vocab.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                i2t[vocab.getInt(token)] = token
            }
            idToToken = i2t
            Log.d(TAG, "Tokenizer loaded: ${i2t.size} tokens")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokenizer", e)
        }
    }

    private fun detokenize(tokens: List<Int>): String {
        val bytes = mutableListOf<Byte>()
        for (id in tokens) {
            val token = idToToken[id] ?: continue
            for (ch in token) {
                val b = byteDecoder[ch.code]
                if (b != null) bytes.add(b.code.toByte())
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    private fun buildGpt2ByteDecoder(): Map<Int, Char> {
        val bs = mutableListOf<Int>()
        val cs = mutableListOf<Int>()
        for (b in '!'.code..'~'.code)  { bs.add(b); cs.add(b) }
        for (b in '¡'.code..'¬'.code) { bs.add(b); cs.add(b) }
        for (b in '®'.code..'ÿ'.code) { bs.add(b); cs.add(b) }
        var n = 0
        for (b in 0..255) {
            if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        }
        return bs.zip(cs).associate { (b, c) -> c to b.toChar() }
    }
}
