package com.haq.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Lightweight foreground service that keeps the process alive while Gemma is
 * generating a response and TTS is speaking it.
 *
 * This service does NO work itself — it exists purely to raise the process
 * priority to "foreground service" level so Android does not kill the process
 * when the user switches apps. Gemma generation and TTS continue running in
 * the ViewModel's coroutine scope and the system TTS service respectively.
 *
 * Lifecycle:
 *   START  — called from HaqViewModel.submitQuery() just before generation begins
 *   STOP   — called from HaqViewModel.submitQuery() finally block when done/paused
 *
 * The notification shows a tap target that brings the app back to the foreground,
 * so users know they can return to see the streamed text response.
 */
class HaqResponseService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("Haq/Service", "HaqResponseService started")
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // START_NOT_STICKY: if the process is killed anyway (extreme memory pressure),
        // do not restart the service automatically — the ViewModel will handle recovery.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Haq/Service", "HaqResponseService stopped")
    }

    private fun buildNotification(): Notification {
        // Tapping the notification returns the user to MainActivity.
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Haq is responding")
            .setContentText("Tap to return to the app")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID      = "haq_response"
        const val NOTIFICATION_ID = 1002
    }
}
