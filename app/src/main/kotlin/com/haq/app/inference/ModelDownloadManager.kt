package com.haq.app.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Download state ────────────────────────────────────────────────────────────

sealed class DownloadState {
    object Idle        : DownloadState()
    object Checking    : DownloadState()
    object WifiRequired: DownloadState()
    data class Downloading(val progressPercent: Int) : DownloadState()
    object Complete    : DownloadState()
    data class Error(val message: String) : DownloadState()
}

// ── ModelDownloadManager ──────────────────────────────────────────────────────

/**
 * Manages the one-time Gemma model download via WorkManager.
 *
 * The actual download runs in [ModelDownloadWorker], a foreground CoroutineWorker
 * that survives the user switching apps or the OS killing the foreground Activity.
 * This class enqueues the worker, observes its [WorkInfo], and translates state
 * changes into the [downloadState] flow that the UI already watches.
 *
 * On process restart (e.g. app reopened mid-download), [observeWorkInfo] is called
 * from [init] immediately, so the in-progress worker is picked up without re-enqueueing.
 */
class ModelDownloadManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workManager = WorkManager.getInstance(context)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _state.asStateFlow()

    init {
        // Begin observing immediately so that if a worker is already running
        // (app reopened mid-download), we pick up its current progress.
        observeWorkInfo()
    }

    /**
     * Checks whether the model is already on disk; if not, enqueues the download
     * worker. Safe to call multiple times — [ExistingWorkPolicy.KEEP] means a
     * running or enqueued job is never interrupted.
     */
    fun startDownload() {
        scope.launch { checkAndEnqueue(replace = false) }
    }

    /**
     * Cancels any failed or stuck job and starts a fresh download.
     * Called when the user taps "Retry" on the error screen.
     */
    fun retryDownload() {
        scope.launch { checkAndEnqueue(replace = true) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun checkAndEnqueue(replace: Boolean) {
        _state.value = DownloadState.Checking

        val modelFile = File(context.filesDir, MODEL_FILENAME)
        val alreadyOnDisk = withContext(Dispatchers.IO) {
            modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE
        }

        if (alreadyOnDisk) {
            Log.d("Haq/Download", "Model already on disk — skipping download")
            _state.value = DownloadState.Complete
            return
        }

        if (!isOnWifi()) {
            Log.d("Haq/Download", "No WiFi — showing WifiRequired")
            _state.value = DownloadState.WifiRequired
            return
        }

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    // UNMETERED = WiFi. Safety net: WorkManager pauses the job
                    // automatically if WiFi drops mid-download and resumes when
                    // it returns — no manual retry needed.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()

        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(ModelDownloadWorker.WORK_NAME, policy, request)
        Log.d("Haq/Download", "Worker enqueued (policy=$policy)")
        // WorkInfo changes are delivered to observeWorkInfo() which updates _state.
    }

    /**
     * Collects the WorkInfo flow for the unique download job and maps each
     * [WorkInfo.State] to the corresponding [DownloadState]. Runs for the
     * lifetime of the [scope] (process lifetime).
     */
    private fun observeWorkInfo() {
        scope.launch {
            workManager
                .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK_NAME)
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED  -> {
                            // Enqueued = waiting to start (constraints not yet met).
                            // Keep showing Downloading(0) so the UI doesn't flicker
                            // between Checking and Downloading.
                            if (_state.value !is DownloadState.Downloading) {
                                _state.value = DownloadState.Downloading(0)
                            }
                        }
                        WorkInfo.State.RUNNING  -> {
                            val pct = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                            _state.value = DownloadState.Downloading(pct)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            Log.d("Haq/Download", "Worker succeeded")
                            _state.value = DownloadState.Complete
                        }
                        WorkInfo.State.FAILED    -> {
                            val msg = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                ?: "Download failed"
                            Log.e("Haq/Download", "Worker failed: $msg")
                            _state.value = DownloadState.Error(msg)
                        }
                        WorkInfo.State.CANCELLED -> {
                            Log.w("Haq/Download", "Worker cancelled")
                            _state.value = DownloadState.Error("Download cancelled")
                        }
                    }
                }
        }
    }

    private suspend fun isOnWifi(): Boolean = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@withContext false)
            ?: return@withContext false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        const val MODEL_FILENAME  = "gemma-4-E2B-it.litertlm"
        const val MIN_MODEL_SIZE  = 1_000_000_000L  // 1 GB
    }
}
