package com.haq.app.stt

object WhisperConfig {
    const val SAMPLE_RATE       = 16000
    const val CHUNK_DURATION_MS = 10000  // 10 second max recording

    const val ENCODER_MODEL = "whisper/encoder_model.onnx"
    const val DECODER_MODEL = "whisper/decoder_model_merged.onnx"
    const val TOKENIZER     = "whisper/tokenizer.json"

    // Whisper language token IDs
    const val LANG_HINDI   = 9688   // <|hi|>
    const val LANG_TELUGU  = 9995   // <|te|>
    const val LANG_TAMIL   = 9990   // <|ta|>

    // Whisper special token IDs
    const val TOKEN_TRANSCRIBE    = 50359
    const val TOKEN_NO_TIMESTAMPS = 50363
    const val TOKEN_SOT           = 50258   // <|startoftranscript|>
    const val TOKEN_EOT           = 50257   // <|endoftext|>

    // Mel spectrogram parameters
    const val N_MELS       = 80
    const val N_FFT        = 400
    const val HOP_LENGTH   = 160
    const val N_FRAMES     = 3000   // encoder expects exactly 3000 time frames
}
