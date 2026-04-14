package com.haq.app.stt

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Runs the Whisper Tiny encoder+decoder pipeline via ONNX Runtime.
 *
 * Memory strategy: sessions are created fresh on each [transcribe] call and
 * closed immediately after, so ~144 MB of native memory is released before
 * Gemma runs inference. The [OrtEnvironment] stays open (it is lightweight).
 */
class WhisperEngine(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private fun sessionOpts() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setMemoryPatternOptimization(true)
        addConfigEntry("session.use_env_allocators", "1")
    }

    /** Load encoder bytes once and cache — avoids re-reading 31 MB every call. */
    private val encoderBytes: ByteArray by lazy {
        context.assets.open(WhisperConfig.ENCODER_MODEL).readBytes()
    }
    private val decoderBytes: ByteArray by lazy {
        context.assets.open(WhisperConfig.DECODER_MODEL).readBytes()
    }

    private val tokenizer: WhisperTokenizer by lazy { WhisperTokenizer(context) }

    suspend fun transcribe(audioSamples: FloatArray, languageTokenId: Int): String =
        withContext(Dispatchers.IO) {

            // Open sessions for this transcription only
            val encoderSession = env.createSession(encoderBytes, sessionOpts())
            val decoderSession = env.createSession(decoderBytes, sessionOpts())

            try {
                // 1. Mel spectrogram [1, 80, 3000]
                val mel = MelSpectrogram.compute(audioSamples)
                val melTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(mel),
                    longArrayOf(1, WhisperConfig.N_MELS.toLong(), WhisperConfig.N_FRAMES.toLong()),
                )

                // 2. Encoder
                val encoderOut    = encoderSession.run(mapOf("input_features" to melTensor))
                val encoderHidden = encoderOut[0] as OnnxTensor
                melTensor.close()

                // 3. Greedy decoder
                val maxNewTokens = 224
                val outputIds    = mutableListOf<Int>()
                var inputIds     = intArrayOf(
                    WhisperConfig.TOKEN_SOT,
                    languageTokenId,
                    WhisperConfig.TOKEN_TRANSCRIBE,
                    WhisperConfig.TOKEN_NO_TIMESTAMPS,
                )

                for (step in 0 until maxNewTokens) {
                    val inputIdsTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()),
                        longArrayOf(1, inputIds.size.toLong()),
                    )

                    val decoderOut  = decoderSession.run(
                        mapOf("input_ids" to inputIdsTensor, "encoder_hidden_states" to encoderHidden)
                    )
                    val logits      = decoderOut[0] as OnnxTensor
                    val logitsBuf   = logits.floatBuffer
                    inputIdsTensor.close()

                    // Greedy: argmax over vocab at the last sequence position
                    val vocabSize = 51865
                    val lastPos   = (inputIds.size - 1) * vocabSize
                    var bestId    = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (v in 0 until vocabSize) {
                        val score = logitsBuf.get(lastPos + v)
                        if (score > bestScore) { bestScore = score; bestId = v }
                    }
                    logits.close()

                    if (bestId == WhisperConfig.TOKEN_EOT) break
                    outputIds += bestId
                    inputIds   = inputIds + bestId
                }

                encoderHidden.close()
                tokenizer.decode(outputIds)

            } finally {
                // Always close sessions to free ~144 MB before Gemma runs
                runCatching { encoderSession.close() }
                runCatching { decoderSession.close() }
            }
        }

    fun close() {
        runCatching { env.close() }
    }
}
