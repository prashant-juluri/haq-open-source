package com.haq.app.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// ── Download state ────────────────────────────────────────────────────────────

sealed class DownloadState {
    object Idle : DownloadState()
    object Checking : DownloadState()
    object WifiRequired : DownloadState()
    data class Downloading(val progressPercent: Int) : DownloadState()
    object Complete : DownloadState()
    data class Error(val message: String) : DownloadState()
}

// ── ModelDownloadManager ──────────────────────────────────────────────────────

class ModelDownloadManager(private val context: Context) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)      // no timeout — file is ~2.4 GB
        .build()

    /**
     * Checks whether the model exists; downloads it if not.
     * Safe to call multiple times — exits immediately if already [DownloadState.Complete].
     * All file I/O and network checks run on [Dispatchers.IO].
     */
    suspend fun startDownload() {
        // Already complete — nothing to do
        if (_state.value is DownloadState.Complete) return

        _state.value = DownloadState.Checking

        // File existence and size checks are blocking syscalls — keep them off Main.
        val (modelFile, alreadyComplete) = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, MODEL_FILENAME)
            val fileSize = if (file.exists()) file.length() else 0L
            Log.d("Haq/Download",
                "checkAndDownload() called, exists=${file.exists()}, size=$fileSize")
            if (file.exists() && fileSize > MIN_MODEL_SIZE) {
                file to true
            } else {
                if (file.exists()) {
                    Log.w("Haq/Download",
                        "Model file is too small ($fileSize bytes) — incomplete download, re-downloading")
                    file.delete()
                }
                file to false
            }
        }

        if (alreadyComplete) {
            _state.value = DownloadState.Complete
            return
        }

        if (!isOnWifi()) {
            _state.value = DownloadState.WifiRequired
            return
        }

        downloadModel(modelFile)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun downloadModel(dest: File) = withContext(Dispatchers.IO) {
        val temp = File(context.filesDir, "$MODEL_FILENAME.tmp")
        temp.delete()   // clean up any previous partial download

        try {
            _state.value = DownloadState.Downloading(0)

            val request = Request.Builder().url(MODEL_DOWNLOAD_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = DownloadState.Error("Server error ${response.code}")
                    return@withContext
                }

                val body = response.body ?: run {
                    _state.value = DownloadState.Error("Empty response body")
                    return@withContext
                }

                val totalBytes = body.contentLength()   // -1 if unknown
                var downloadedBytes = 0L

                temp.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8 * 1024 * 1024)   // 8 MB read buffer
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloadedBytes += n
                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100L) / totalBytes).toInt()
                                _state.value = DownloadState.Downloading(pct.coerceIn(0, 99))
                            }
                        }
                    }
                }

                // Atomic rename to final path
                if (!temp.renameTo(dest)) {
                    temp.delete()
                    _state.value = DownloadState.Error("Failed to save model — check storage space")
                    return@withContext
                }

                _state.value = DownloadState.Complete
            }
        } catch (e: Exception) {
            temp.delete()
            _state.value = DownloadState.Error(e.message ?: "Download failed")
        }
    }

    private suspend fun isOnWifi(): Boolean = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@withContext false)
            ?: return@withContext false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MIN_MODEL_SIZE = 1_000_000_000L  // 1GB — incomplete downloads are smaller

        // Placeholder — replace with final CDN URL before shipping
        private const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }
}
