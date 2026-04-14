package com.haq.app.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SAMPLE_RATE    = 16000
private const val DURATION_MS    = 10_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING       = AudioFormat.ENCODING_PCM_16BIT

class AudioRecorder(private val context: Context) {

    /**
     * Records [DURATION_MS] ms of 16kHz mono 16-bit PCM audio and writes a
     * standard WAV file to the app's cacheDir.
     *
     * Returns the [File] — caller is responsible for deleting it after use.
     * Throws [SecurityException] if RECORD_AUDIO permission is missing.
     * Throws [IllegalStateException] if AudioRecord fails to initialise.
     */
    suspend fun recordToFile(): File = withContext(Dispatchers.IO) {
        val totalSamples = SAMPLE_RATE * DURATION_MS / 1000
        val minBufSize   = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        val bufSize      = maxOf(minBufSize, 4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING,
            bufSize,
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("AudioRecord failed to initialise — check RECORD_AUDIO permission")
        }

        val pcmData  = ShortArray(totalSamples)
        var totalRead = 0
        val readBuf  = ShortArray(bufSize / 2)

        try {
            recorder.startRecording()
            while (totalRead < totalSamples) {
                val toRead = minOf(readBuf.size, totalSamples - totalRead)
                val n      = recorder.read(readBuf, 0, toRead)
                if (n <= 0) break
                readBuf.copyInto(pcmData, totalRead, 0, n)
                totalRead += n
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        val outFile = File(context.cacheDir, "haq_recording_${System.currentTimeMillis()}.wav")
        writeWav(outFile, pcmData, totalRead, SAMPLE_RATE)
        outFile
    }

    // ── WAV writer ────────────────────────────────────────────────────────────

    private fun writeWav(file: File, pcm: ShortArray, numSamples: Int, sampleRate: Int) {
        val numChannels   = 1
        val bitsPerSample = 16
        val byteRate      = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign    = numChannels * bitsPerSample / 8
        val dataBytes     = numSamples * blockAlign

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(int32LE(36 + dataBytes))
            fos.write("WAVE".toByteArray())
            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(int32LE(16))               // chunk size
            fos.write(int16LE(1))                // PCM format
            fos.write(int16LE(numChannels))
            fos.write(int32LE(sampleRate))
            fos.write(int32LE(byteRate))
            fos.write(int16LE(blockAlign))
            fos.write(int16LE(bitsPerSample))
            // data chunk
            fos.write("data".toByteArray())
            fos.write(int32LE(dataBytes))
            // PCM samples (little-endian)
            val buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) buf.putShort(pcm[i])
            fos.write(buf.array())
        }
    }

    private fun int32LE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun int16LE(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
