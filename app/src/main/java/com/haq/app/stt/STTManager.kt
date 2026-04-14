package com.haq.app.stt

import android.content.Context
import java.io.File

object STTManager {

    private lateinit var recorder: AudioRecorder

    fun init(context: Context) {
        recorder = AudioRecorder(context)
    }

    /** Records 10 s of audio and returns a WAV [File] in cacheDir. */
    suspend fun recordAndGetFile(): File = recorder.recordToFile()
}
