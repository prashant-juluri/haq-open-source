package com.haq.app.stt

object WhisperConfig {
    const val SAMPLE_RATE       = 16000
    const val CHUNK_DURATION_MS = 10_000

    const val ENCODER_MODEL = "whisper/encoder_model_quantized.onnx"
    const val DECODER_MODEL = "whisper/decoder_model_merged_quantized.onnx"
    const val TOKENIZER     = "whisper/tokenizer.json"

    // Whisper special token IDs (common to all model sizes)
    const val TOKEN_EOT           = 50257   // <|endoftext|>
    const val TOKEN_SOT           = 50258   // <|startoftranscript|>
    const val TOKEN_TRANSCRIBE    = 50359
    const val TOKEN_NO_TIMESTAMPS = 50363

    // Language token range — tokens 50259..50357 are <|af|>..<|zh|>
    const val LANG_TOKEN_FIRST = 50259
    const val LANG_TOKEN_LAST  = 50357

    // Per-language token IDs (offset = index in Whisper's sorted language list + 50259)
    // Sorted order matches openai/whisper tokenizer exactly.
    const val LANG_BENGALI   = 50267   // bn  (index  8)
    const val LANG_ENGLISH   = 50277   // en  (index 18)
    const val LANG_GUJARATI  = 50286   // gu  (index 27)
    const val LANG_HINDI     = 50290   // hi  (index 31)
    const val LANG_KANNADA   = 50303   // kn  (index 44)
    const val LANG_MALAYALAM = 50314   // ml  (index 55)
    const val LANG_MARATHI   = 50316   // mr  (index 57)
    const val LANG_NEPALI    = 50320   // ne  (index 61)
    const val LANG_TAMIL     = 50343   // ta  (index 84)
    const val LANG_TELUGU    = 50344   // te  (index 85)

    /** Returns the Whisper language token ID for a two-letter language code. */
    fun languageToken(code: String): Int = when (code) {
        "bn" -> LANG_BENGALI
        "en" -> LANG_ENGLISH
        "gu" -> LANG_GUJARATI
        "hi" -> LANG_HINDI
        "kn" -> LANG_KANNADA
        "ml" -> LANG_MALAYALAM
        "mr" -> LANG_MARATHI
        "ne" -> LANG_NEPALI
        "ta" -> LANG_TAMIL
        "te" -> LANG_TELUGU
        else -> LANG_ENGLISH   // safe fallback
    }

    // Mel spectrogram parameters
    const val N_MELS     = 80
    const val N_FFT      = 400
    const val HOP_LENGTH = 160
    const val N_FRAMES   = 3000

    // Whisper-small decoder architecture
    const val NUM_LAYERS    = 12
    const val NUM_HEADS     = 12
    const val HEAD_DIM      = 64
    const val ENCODER_STEPS = 1500
    const val D_MODEL       = 768
    const val VOCAB_SIZE    = 51864
    const val MAX_TOKENS    = 448
}
