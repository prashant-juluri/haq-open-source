package com.haq.app.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class AudioRecorder {

    companion object {
        private const val TAG = "Haq/AudioRecorder"

        // VAD parameters
        // SPEECH_THRESHOLD: RMS above this → active speech. Must be well above typical room noise
        // (~300–600 RMS on most Android microphones). Set conservatively high so background hum
        // never trips the "speech has started" gate — Whisper handles leading silence fine.
        private const val SPEECH_THRESHOLD_RMS  = 1500f // 16-bit units (~-26 dBFS); normal speech 2000–8000
        // SILENCE_THRESHOLD: RMS below this → silence. Slightly below SPEECH_THRESHOLD so there
        // is hysteresis — a brief dip mid-sentence does not prematurely end recording.
        private const val SILENCE_THRESHOLD_RMS = 800f  // 16-bit units (~-32 dBFS)
        private const val VAD_CHUNK_MS          = 80    // process 80 ms chunks
        private const val MIN_SPEECH_MS         = 300   // require ≥300 ms of speech before stopping
        private const val SILENCE_AFTER_MS      = 1500  // stop after 1.5 s of silence post-speech
        private const val MAX_DURATION_MS       = 10_000
    }

    /**
     * Records 16 kHz mono PCM using energy-based VAD. Stops when the user
     * has been silent for [SILENCE_AFTER_MS] ms after at least [MIN_SPEECH_MS]
     * ms of speech, or when [MAX_DURATION_MS] elapses (hard cap).
     *
     * Returns normalised float samples in [-1.0, 1.0] ready for Whisper.
     */
    suspend fun recordWithVad(maxDurationMs: Int = MAX_DURATION_MS): FloatArray =
        withContext(Dispatchers.IO) {
            val sampleRate  = WhisperConfig.SAMPLE_RATE
            val chunkSize   = sampleRate * VAD_CHUNK_MS / 1000
            val minBufSize  = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = maxOf(minBufSize, chunkSize * 4)

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

            val maxSamples = sampleRate * maxDurationMs / 1000
            val allSamples = ArrayList<Short>(maxSamples)
            val chunk      = ShortArray(chunkSize)
            var speechMs   = 0
            var silenceMs  = 0

            try {
                recorder.startRecording()
                Log.d(TAG, "Recording started (VAD)")

                while (allSamples.size < maxSamples) {
                    val n = recorder.read(chunk, 0, chunkSize)
                    if (n <= 0) break

                    // RMS energy of this 80 ms chunk
                    var sumSq = 0L
                    for (i in 0 until n) sumSq += chunk[i].toLong() * chunk[i].toLong()
                    val rms = sqrt(sumSq.toDouble() / n).toFloat()

                    // Log every ~640 ms (every 8th chunk) so we can tune thresholds
                    if (allSamples.size % (chunkSize * 8) < chunkSize) {
                        Log.d(TAG, "VAD rms=%.0f speechMs=$speechMs silenceMs=$silenceMs".format(rms))
                    }

                    if (rms >= SPEECH_THRESHOLD_RMS) {
                        // Clearly above the speech floor — active speech
                        speechMs  += VAD_CHUNK_MS
                        silenceMs  = 0
                    } else if (speechMs >= MIN_SPEECH_MS && rms < SILENCE_THRESHOLD_RMS) {
                        // Below silence floor after enough speech has been seen
                        silenceMs += VAD_CHUNK_MS
                    }
                    // Gap between SILENCE_THRESHOLD and SPEECH_THRESHOLD = hysteresis zone:
                    // neither counter moves, preventing mid-sentence breath pauses from
                    // triggering the stop condition.

                    for (i in 0 until n) allSamples.add(chunk[i])

                    if (speechMs >= MIN_SPEECH_MS && silenceMs >= SILENCE_AFTER_MS) {
                        Log.d(TAG, "VAD stop: speechMs=$speechMs silenceMs=$silenceMs")
                        break
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                Log.d(TAG, "Recorded ${allSamples.size} samples " +
                    "(${allSamples.size * 1000 / sampleRate} ms)")
            }

            FloatArray(allSamples.size) { i -> allSamples[i] / 32768f }
        }

    /**
     * Records a fixed [durationMs] of audio (no VAD). Kept for reference;
     * the Whisper path always uses [recordWithVad].
     */
    suspend fun record(durationMs: Int): FloatArray = withContext(Dispatchers.IO) {
        val sampleRate   = WhisperConfig.SAMPLE_RATE
        val totalSamples = sampleRate * durationMs / 1000
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

        val shortBuf  = ShortArray(bufSize / 2)
        val samples   = ShortArray(totalSamples)
        var totalRead = 0

        try {
            recorder.startRecording()
            while (totalRead < totalSamples) {
                val toRead = minOf(shortBuf.size, totalSamples - totalRead)
                val n      = recorder.read(shortBuf, 0, toRead)
                if (n <= 0) break
                shortBuf.copyInto(samples, totalRead, 0, n)
                totalRead += n
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        FloatArray(totalRead) { i -> samples[i] / 32768f }
    }
}
