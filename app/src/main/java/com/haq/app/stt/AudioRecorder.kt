package com.haq.app.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRecorder(private val context: Context) {

    /**
     * Records audio for [durationMs] milliseconds and returns normalised
     * float samples in [-1.0, 1.0] ready for Whisper input.
     *
     * Caller must ensure RECORD_AUDIO permission is granted before calling.
     * Throws [SecurityException] if permission is missing.
     * Throws [IllegalStateException] if AudioRecord fails to initialise.
     */
    suspend fun record(durationMs: Int): FloatArray = withContext(Dispatchers.IO) {
        val sampleRate   = WhisperConfig.SAMPLE_RATE
        val totalSamples = (sampleRate * durationMs) / 1000
        val minBufSize   = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBufSize, 4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("AudioRecord failed to initialise — check RECORD_AUDIO permission")
        }

        val shortBuf    = ShortArray(bufSize / 2)
        val samples     = ShortArray(totalSamples)
        var totalRead   = 0

        try {
            recorder.startRecording()
            while (totalRead < totalSamples) {
                val toRead  = minOf(shortBuf.size, totalSamples - totalRead)
                val nRead   = recorder.read(shortBuf, 0, toRead)
                if (nRead <= 0) break
                shortBuf.copyInto(samples, totalRead, 0, nRead)
                totalRead += nRead
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        // Normalise 16-bit PCM → float [-1, 1]
        FloatArray(totalRead) { i -> samples[i] / 32768f }
    }
}
