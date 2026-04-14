package com.haq.app.stt

import android.content.Context
import org.json.JSONObject

/**
 * Decodes Whisper output token IDs to UTF-8 text using byte-level BPE.
 * Backed by tokenizer.json from the HuggingFace model repo.
 */
class WhisperTokenizer(context: Context) {

    private val idToToken: Map<Int, String>

    init {
        val json  = context.assets.open(WhisperConfig.TOKENIZER).bufferedReader().readText()
        val vocab = JSONObject(json).getJSONObject("model").getJSONObject("vocab")
        val map   = mutableMapOf<Int, String>()
        val keys  = vocab.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            map[vocab.getInt(token)] = token
        }
        idToToken = map
    }

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id >= WhisperConfig.TOKEN_EOT) continue
            val token = idToToken[id] ?: continue
            sb.append(bpeToString(token))
        }
        return sb.toString().trim()
    }

    private fun bpeToString(token: String): String {
        val bytes = ByteArray(token.length * 4)
        var i = 0
        for (ch in token) {
            val b = UNICODE_TO_BYTE[ch]
            if (b != null) {
                bytes[i++] = b
            } else {
                val encoded = ch.toString().toByteArray(Charsets.UTF_8)
                if (i + encoded.size > bytes.size) break
                encoded.copyInto(bytes, i); i += encoded.size
            }
        }
        return String(bytes, 0, i, Charsets.UTF_8)
    }

    companion object {
        private val UNICODE_TO_BYTE: Map<Char, Byte> by lazy {
            val bs = mutableListOf<Int>()
            bs += ('!'.code..'~'.code).toList()
            bs += ('¡'.code..'¬'.code).toList()
            bs += ('®'.code..'ÿ'.code).toList()
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n++ }
            buildMap { for (i in bs.indices) put(cs[i].toChar(), bs[i].toByte()) }
        }
    }
}
