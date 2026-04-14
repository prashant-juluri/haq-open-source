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
 * Runs the Whisper Small encoder+decoder pipeline via ONNX Runtime.
 * Models are loaded lazily on first [transcribe] call.
 */
class WhisperEngine(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val encoderSession: OrtSession by lazy {
        val bytes = context.assets.open(WhisperConfig.ENCODER_MODEL).readBytes()
        env.createSession(bytes, OrtSession.SessionOptions())
    }

    private val decoderSession: OrtSession by lazy {
        val bytes = context.assets.open(WhisperConfig.DECODER_MODEL).readBytes()
        env.createSession(bytes, OrtSession.SessionOptions())
    }

    private val tokenizer: WhisperTokenizer by lazy { WhisperTokenizer(context) }

    /**
     * Transcribes [audioSamples] (16kHz mono floats in [-1, 1]) using the
     * given Whisper [languageTokenId].  Returns the decoded text string.
     */
    suspend fun transcribe(audioSamples: FloatArray, languageTokenId: Int): String =
        withContext(Dispatchers.IO) {

            // 1. Mel spectrogram → [1, 80, 3000]
            val mel     = MelSpectrogram.compute(audioSamples)
            val melTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(mel),
                longArrayOf(1, WhisperConfig.N_MELS.toLong(), WhisperConfig.N_FRAMES.toLong()),
            )

            // 2. Encoder pass
            val encoderOut = encoderSession.run(
                mapOf("input_features" to melTensor)
            )
            val encoderHidden = encoderOut[0] as OnnxTensor

            // 3. Autoregressive decoder
            val maxTokens  = 448   // Whisper Small max output tokens
            val outputIds  = mutableListOf<Int>()

            // Initial decoder input: [SOT, LANG, TRANSCRIBE, NO_TIMESTAMPS]
            var inputIds = intArrayOf(
                WhisperConfig.TOKEN_SOT,
                languageTokenId,
                WhisperConfig.TOKEN_TRANSCRIBE,
                WhisperConfig.TOKEN_NO_TIMESTAMPS,
            )

            repeat(maxTokens) {
                val inputIdsTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()),
                    longArrayOf(1, inputIds.size.toLong()),
                )

                val decoderInputs = mapOf(
                    "input_ids"             to inputIdsTensor,
                    "encoder_hidden_states" to encoderHidden,
                )

                val decoderOut   = decoderSession.run(decoderInputs)
                val logits       = decoderOut[0] as OnnxTensor   // [1, seq, vocab]
                val logitsArray  = logits.floatBuffer

                // Greedy: pick token with highest logit at last position
                val vocabSize = 51865
                val lastPos   = (inputIds.size - 1) * vocabSize
                var bestId    = 0
                var bestScore = Float.NEGATIVE_INFINITY
                for (v in 0 until vocabSize) {
                    val score = logitsArray.get(lastPos + v)
                    if (score > bestScore) {
                        bestScore = score
                        bestId    = v
                    }
                }

                inputIdsTensor.close()
                logits.close()

                if (bestId == WhisperConfig.TOKEN_EOT) return@repeat
                outputIds += bestId
                inputIds   = inputIds + bestId
            }

            melTensor.close()
            encoderHidden.close()

            tokenizer.decode(outputIds)
        }

    fun close() {
        runCatching { encoderSession.close() }
        runCatching { decoderSession.close() }
        runCatching { env.close() }
    }
}
