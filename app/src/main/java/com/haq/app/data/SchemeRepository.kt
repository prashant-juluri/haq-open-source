package com.haq.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only access to the pre-built schemes.db shipped in assets.
 *
 * On first call [ensureDatabase] copies the asset to filesDir so Android's
 * SQLiteDatabase can open it (assets are not directly openable as a file path).
 * All subsequent calls skip the copy if the file already exists and is non-empty.
 *
 * The FTS5 query uses profile fields (occupation, state, caste) as search terms
 * and returns the top [MAX_RESULTS] rag_chunk strings ranked by BM25.
 */
object SchemeRepository {

    private const val DB_ASSET   = "schemes.db"
    private const val TAG        = "Haq/RAG"
    private const val MAX_RESULTS = 4

    /**
     * Returns rag_chunk strings for the top [MAX_RESULTS] schemes that match
     * the user's profile. Returns an empty list on any error so callers can
     * always proceed with a Gemma prompt that lacks RAG context.
     */
    suspend fun search(
        context: Context,
        query: String,
        state: String,
        casteCategory: String,
        occupation: String,
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val dbFile = ensureDatabase(context)
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                querySchemes(db, query, state, casteCategory, occupation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "search() failed: ${e.message}", e)
            emptyList()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Ensures schemes.db exists in filesDir (copies from assets once).
     * A size check guards against a previous incomplete copy.
     */
    private fun ensureDatabase(context: Context): File {
        val dbFile = File(context.filesDir, DB_ASSET)
        // 1 MB threshold — a valid schemes.db is always > 20 MB
        if (dbFile.exists() && dbFile.length() > 1_000_000L) return dbFile

        Log.d(TAG, "Copying $DB_ASSET from assets (${dbFile.absolutePath})")
        context.assets.open(DB_ASSET).use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "Copy complete: ${dbFile.length() / 1024} KB")
        return dbFile
    }

    /**
     * Runs an FTS5 MATCH query against schemes_fts and returns rag_chunks.
     *
     * Search terms are derived from the user's English profile fields because
     * the scheme data is in English and FTS5 can't match cross-language.
     *
     * Caste codes ("SC", "ST", "OBC") are mapped to their full tag labels
     * ("Scheduled Caste", "Scheduled Tribe", "OBC") and searched with a
     * column-restriction to scheme_tags so they don't match incidental
     * occurrences of "SC" inside other words or sentences.
     *
     * A simple keyword pass over the raw [query] string also extracts any
     * ASCII words longer than 3 chars (handles "pension", "ration", etc.).
     */
    private fun querySchemes(
        db: SQLiteDatabase,
        query: String,
        state: String,
        casteCategory: String,
        occupation: String,
    ): List<String> {
        val terms = mutableListOf<String>()

        // Occupation and state — general FTS across all columns
        if (occupation.isNotBlank()) terms.add(ftsQuote(occupation))
        if (state.isNotBlank())      terms.add(ftsQuote(state))

        // Caste — restrict to scheme_tags column to avoid false positives
        // (e.g. bare "SC" matches many unrelated sentences)
        val casteTag = casteToTag(casteCategory)
        if (casteTag != null) terms.add("scheme_tags:${ftsQuote(casteTag)}")

        // Extract ASCII keywords from the raw query (e.g. "pension", "ration")
        query.split(Regex("\\s+"))
            .filter { it.length > 3 && it.all { c -> c.code < 0x0080 } }
            .mapNotNull { w -> w.replace(Regex("[^A-Za-z0-9]"), "").takeIf { it.length > 3 } }
            .forEach { terms.add(it) }

        if (terms.isEmpty()) {
            Log.d(TAG, "No usable search terms — skipping RAG")
            return emptyList()
        }

        val ftsQuery = terms.distinct().joinToString(" OR ")
        Log.d(TAG, "FTS query: \"$ftsQuery\"")

        db.rawQuery(
            """
            SELECT s.rag_chunk
            FROM schemes_fts fts
            JOIN schemes s ON s.rowid = fts.rowid
            WHERE schemes_fts MATCH ?
              AND s.rag_chunk IS NOT NULL
              AND s.rag_chunk != ''
            ORDER BY rank
            LIMIT $MAX_RESULTS
            """.trimIndent(),
            arrayOf(ftsQuery),
        ).use { cursor ->
            val results = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val chunk = cursor.getString(0)
                if (!chunk.isNullOrBlank()) results.add(chunk)
            }
            Log.d(TAG, "FTS returned ${results.size} rag_chunks")
            return results
        }
    }

    /**
     * Maps user-profile caste codes to the full tag labels used in schemes_fts.
     * Returns null for "General" (no caste-based restriction needed).
     */
    private fun casteToTag(code: String): String? = when (code.trim().uppercase()) {
        "SC"  -> "Scheduled Caste"
        "ST"  -> "Scheduled Tribe"
        "OBC" -> "OBC"
        else  -> null   // General or blank — no caste filter
    }

    /**
     * Wraps a multi-word term in FTS5 double-quotes and strips characters
     * that would break the MATCH expression (`"`, `*`, `(`, `)`).
     */
    private fun ftsQuote(term: String): String {
        val clean = term.trim().replace(Regex("[\"*()]"), "")
        return if (clean.contains(' ')) "\"$clean\"" else clean
    }
}
