package com.haq.app.stt

import android.content.Context
import org.json.JSONObject

/**
 * Minimal Whisper tokenizer backed by the tokenizer.json vocab from HuggingFace.
 * Decodes output token-ID sequences to UTF-8 text using byte-level BPE.
 */
class WhisperTokenizer(context: Context) {

    // id → token string (may be a byte-level BPE piece like "Ġhello" or "Ä±")
    private val idToToken: Map<Int, String>

    init {
        val json   = context.assets.open(WhisperConfig.TOKENIZER).bufferedReader().readText()
        val root   = JSONObject(json)
        val vocab  = root.getJSONObject("model").getJSONObject("vocab")
        val map    = mutableMapOf<Int, String>()
        val keys   = vocab.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            map[vocab.getInt(token)] = token
        }
        idToToken = map
    }

    /**
     * Converts a list of token IDs to a human-readable string.
     * Skips all special tokens (id >= 50257).
     */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id >= WhisperConfig.TOKEN_EOT) continue   // skip special tokens
            val token = idToToken[id] ?: continue
            sb.append(bpeToString(token))
        }
        return sb.toString().trim()
    }

    // ── Byte-level BPE decoding ───────────────────────────────────────────────

    /**
     * Whisper's BPE vocab uses GPT-2's byte-to-unicode mapping.
     * We invert it to recover raw UTF-8 bytes from the token string.
     */
    private fun bpeToString(token: String): String {
        val bytes = ByteArray(token.length)
        var i = 0
        for (ch in token) {
            val b = UNICODE_TO_BYTE[ch]
            if (b != null) {
                bytes[i++] = b
            } else {
                // Character is literal — encode directly
                val encoded = ch.toString().toByteArray(Charsets.UTF_8)
                if (i + encoded.size > bytes.size) break
                encoded.copyInto(bytes, i)
                i += encoded.size
            }
        }
        return String(bytes, 0, i, Charsets.UTF_8)
    }

    companion object {
        /**
         * GPT-2 / Whisper byte-to-unicode mapping (inverted here: unicode→byte).
         * Built once at class load time.
         */
        private val UNICODE_TO_BYTE: Map<Char, Byte> by lazy {
            buildUnicodeToByteMap()
        }

        private fun buildUnicodeToByteMap(): Map<Char, Byte> {
            val map = mutableMapOf<Char, Byte>()
            // Printable ASCII that maps to itself
            val bs = mutableListOf<Int>()
            bs += ('!' .code..'~' .code).toList()
            bs += ('¡' .code..'¬' .code).toList()
            bs += ('®' .code..'ÿ' .code).toList()

            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n++
                }
            }
            for (i in bs.indices) {
                map[cs[i].toChar()] = bs[i].toByte()
            }
            return map
        }
    }
}
