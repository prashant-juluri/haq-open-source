package com.haq.app.inference

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the Gemma model file as a WorkManager foreground job.
 *
 * Running as a foreground worker keeps the process alive when the user
 * switches apps — the OS will not kill a process with an active foreground
 * service. Progress is reported via [setProgress] and observed by
 * [ModelDownloadManager], which maps it back to the [DownloadState] flow
 * that the UI already watches.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // no timeout — file is ~2.4 GB
        .build()

    override suspend fun doWork(): Result {
        Log.d("Haq/Download", "ModelDownloadWorker started")

        // Promote to foreground immediately so the OS keeps us alive.
        setForeground(buildForegroundInfo(0, indeterminate = true))

        val dest = File(applicationContext.filesDir, MODEL_FILENAME)

        // Already fully downloaded — nothing to do.
        if (dest.exists() && dest.length() > MIN_MODEL_SIZE) {
            Log.d("Haq/Download", "Model already present, worker exiting")
            return Result.success()
        }

        // Clean up any previous partial download before starting.
        val temp = File(applicationContext.filesDir, "$MODEL_FILENAME.tmp")
        temp.delete()

        return try {
            val request = Request.Builder().url(MODEL_DOWNLOAD_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("Haq/Download", "Server error ${response.code}")
                    return Result.failure(workDataOf(KEY_ERROR to "Server error ${response.code}"))
                }

                val body = response.body ?: run {
                    Log.e("Haq/Download", "Empty response body")
                    return Result.failure(workDataOf(KEY_ERROR to "Empty response body"))
                }

                val totalBytes = body.contentLength()   // -1 if unknown
                var downloadedBytes = 0L
                var lastReportedPct = -1

                temp.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8 * 1024 * 1024)    // 8 MB read buffer
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            // WorkManager sets isStopped when the job is cancelled.
                            if (isStopped) {
                                Log.d("Haq/Download", "Worker stopped — deleting temp file")
                                temp.delete()
                                return Result.failure(workDataOf(KEY_ERROR to "Cancelled"))
                            }

                            out.write(buf, 0, n)
                            downloadedBytes += n

                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100L) / totalBytes)
                                    .toInt().coerceIn(0, 99)
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    setProgress(workDataOf(KEY_PROGRESS to pct))
                                    setForeground(buildForegroundInfo(pct, indeterminate = false))
                                }
                            }
                        }
                    }
                }

                // Atomic rename: prevents a partial file from being mistaken for complete.
                if (!temp.renameTo(dest)) {
                    temp.delete()
                    Log.e("Haq/Download", "Rename failed — storage full?")
                    return Result.failure(
                        workDataOf(KEY_ERROR to "Failed to save model — check storage space")
                    )
                }

                Log.d("Haq/Download", "Model download complete: ${dest.length()} bytes")
                Result.success()
            }
        } catch (e: Exception) {
            temp.delete()
            Log.e("Haq/Download", "Download error", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    private fun buildForegroundInfo(progress: Int, indeterminate: Boolean): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Haq — Downloading AI Model")
            .setContentText(if (indeterminate) "Starting…" else "$progress% complete")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val CHANNEL_ID     = "haq_model_download"
        const val WORK_NAME      = "model_download"
        const val KEY_PROGRESS   = "progress"
        const val KEY_ERROR      = "error"

        private const val NOTIFICATION_ID = 1001

        private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val MIN_MODEL_SIZE = 1_000_000_000L   // 1 GB
        private const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }
}
