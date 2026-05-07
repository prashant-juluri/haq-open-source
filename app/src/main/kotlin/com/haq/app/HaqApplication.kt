package com.haq.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.haq.app.data.ProfileManager
import com.haq.app.inference.ModelDownloadWorker
import com.haq.app.tts.TTSManager

class HaqApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialise singletons once per process. Application.onCreate() survives
        // Activity restarts (e.g. Samsung permission-grant restart) so these are
        // guaranteed to run exactly once regardless of how many times the Activity
        // or its ViewModels are created.
        TTSManager.init(this)
        ProfileManager.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Low importance: no sound, no heads-up — just a persistent progress bar.
        nm.createNotificationChannel(
            NotificationChannel(
                ModelDownloadWorker.CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while the AI model downloads in the background"
            }
        )

        // Low importance: silent persistent notification while Gemma speaks a response.
        nm.createNotificationChannel(
            NotificationChannel(
                HaqResponseService.CHANNEL_ID,
                "Voice Response",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the AI response alive when you switch apps"
            }
        )
    }
}
