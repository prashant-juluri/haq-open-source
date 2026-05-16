package com.haq.app.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads Whisper-small float16 ONNX model files from HuggingFace on first
 * use of a Whisper-capable language (te/ml/kn/ta/bn/gu/mr/ne).
 *
 * Uses decoder_model_merged_fp16.onnx for all decode steps:
 *   Step 0  — use_cache_branch=false, empty past_key_values (seq=0); reads real encoder KV
 *             (seq=1500) via OnnxTensor.floatBuffer to bypass ORT Java nested-array seq=0 bug.
 *   Steps 1+ — use_cache_branch=true; reuses fixed encoder KV from step 0 and grows decoder KV.
 *
 * Files are written atomically (temp → rename) to [Context.filesDir]/whisper/.
 * Already-complete files are skipped on subsequent calls.
 *
 * Source: onnx-community/whisper-small on HuggingFace (fp16 export).
 * Encoder ~177 MB, decoder ~309 MB, tokenizer <1 MB — total ~486 MB.
 * Float16 weights are more accurate than int8-quantized at 2× the download size.
 * ORT upcasts to float32 on devices without native fp16 ALU (all Snapdragon 6/7 have it).
 */
object WhisperDownloadManager {

    private const val TAG      = "Haq/WhisperDL"
    private const val BASE_URL = "https://huggingface.co/onnx-community/whisper-small/resolve/main"

    private data class ModelFile(
        val url:      String,
        val relPath:  String,
        val minBytes: Long,
    )

    private val FILES = listOf(
        ModelFile(
            url      = "$BASE_URL/onnx/encoder_model_quantized.onnx",
            relPath  = "whisper/encoder_model_quantized.onnx",
            minBytes = 80_000_000L,   // ~92 MB
        ),
        ModelFile(
            url      = "$BASE_URL/onnx/decoder_model_merged_quantized.onnx",
            relPath  = "whisper/decoder_model_merged_quantized.onnx",
            minBytes = 130_000_000L,  // ~157 MB
        ),
        ModelFile(
            url      = "$BASE_URL/tokenizer.json",
            relPath  = "whisper/tokenizer.json",
            minBytes = 100L,
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun isAvailable(context: Context): Boolean = WhisperEngine.isAvailable(context)

    suspend fun download(context: Context, onProgress: (Int) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            File(context.filesDir, "whisper").mkdirs()

            val pending = FILES.filter { f ->
                val dest = File(context.filesDir, f.relPath)
                val skip = dest.exists() && dest.length() >= f.minBytes
                if (skip) Log.d(TAG, "Skipping ${f.relPath} (${dest.length()} bytes)")
                !skip
            }

            if (pending.isEmpty()) {
                Log.d(TAG, "All Whisper files already on disk")
                onProgress(100)
                return@withContext
            }

            onProgress(0)

            val estimatedTotal = pending.sumOf { it.minBytes }
            var downloadedSoFar = 0L

            for (file in pending) {
                val dest = File(context.filesDir, file.relPath)
                val temp = File(context.filesDir, "${file.relPath}.tmp")
                temp.delete()

                Log.d(TAG, "Downloading ${file.relPath}")
                val req = Request.Builder().url(file.url).build()

                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful)
                        throw IllegalStateException("HTTP ${response.code} for ${file.relPath}")
                    val body = response.body
                        ?: throw IllegalStateException("Empty body for ${file.relPath}")

                    temp.outputStream().buffered(4 * 1024 * 1024).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(256 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                downloadedSoFar += n
                                val pct = if (estimatedTotal > 0)
                                    (downloadedSoFar * 99L / estimatedTotal).toInt().coerceIn(0, 99)
                                else 50
                                onProgress(pct)
                            }
                        }
                    }

                    if (!temp.renameTo(dest)) {
                        temp.delete()
                        throw IllegalStateException("Failed to save ${file.relPath}")
                    }
                    Log.d(TAG, "Saved ${file.relPath}: ${dest.length()} bytes")
                }
            }

            onProgress(100)
            Log.d(TAG, "Whisper download complete")
        }
}
