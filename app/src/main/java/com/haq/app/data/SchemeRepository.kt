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
 * Retrieval uses scored SQL LIKE matching on structured columns (state,
 * scheme_tags, eligibility) rather than FTS full-text search. This avoids
 * the FTS4 false-positive problem where "OBC" in passing eligibility text
 * (e.g. "not available for OBC") scores as a caste match.
 *
 * Scoring per scheme (higher = more relevant):
 *   +4  scheme_tags contains the caste label ("Scheduled Caste" etc.)
 *   +3  state column matches the user's state
 *   +1  level = 'Central' (relevant to all states)
 *   +2  scheme_tags or eligibility contains each occupation keyword
 */
object SchemeRepository {

    private const val DB_ASSET    = "schemes.db"
    private const val TAG         = "Haq/RAG"
    private const val MAX_RESULTS = 4

    /**
     * Copies schemes.db from assets to filesDir eagerly so the first [search]
     * call incurs no copy latency.  Safe to call multiple times — a no-op if the
     * file already exists at the expected version.
     */
    suspend fun preload(context: Context) = withContext(Dispatchers.IO) {
        try { ensureDatabase(context) } catch (e: Exception) {
            Log.w(TAG, "preload failed: ${e.message}")
        }
    }

    /**
     * Returns rag_chunk strings for the top [MAX_RESULTS] schemes ranked by
     * how closely they match the user's profile. Returns an empty list on any
     * error so callers can always proceed with a Gemma prompt that lacks context.
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

    private fun ensureDatabase(context: Context): File {
        val dbFile = File(context.filesDir, DB_ASSET)
        if (dbFile.exists() && dbFile.length() > 1_000_000L) return dbFile

        Log.d(TAG, "Copying $DB_ASSET from assets")
        context.assets.open(DB_ASSET).use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "Copy complete: ${dbFile.length() / 1024} KB")
        return dbFile
    }

    /**
     * Scores every scheme in [db] against the user's profile and returns the
     * top [MAX_RESULTS] rag_chunks. All matching is LIKE-based on structured
     * columns so caste/state/occupation terms never accidentally match
     * incidental text in unrelated schemes.
     *
     * Score components:
     *   Caste  — scheme_tags LIKE '%Scheduled Caste%'  (+4, exact label match)
     *   State  — state LIKE '%Rajasthan%'               (+3, state-specific)
     *            level = 'Central'                      (+1, central schemes apply everywhere)
     *   Occ.   — occupation words in scheme_tags or eligibility (+2 per word)
     *
     * Minimum score of 2 required to appear in results (avoids returning
     * completely unrelated schemes when profile is sparse).
     */
    private fun querySchemes(
        db: SQLiteDatabase,
        query: String,
        state: String,
        casteCategory: String,
        occupation: String,
    ): List<String> {
        val args = mutableListOf<String>()

        // ── Caste score (+3) ──────────────────────────────────────────────────
        // Deliberately lower than state (+5) so a Rajasthan-specific scheme
        // beats a generic Central OBC scheme when the user is in Rajasthan.
        val casteLabel = casteToTag(casteCategory)
        val casteSql = if (casteLabel != null) {
            args.add("%$casteLabel%")
            "CASE WHEN scheme_tags LIKE ? THEN 3 ELSE 0 END"
        } else "0"

        // ── State score (+5 state match, +2 central) ──────────────────────────
        // State is the strongest signal — a Rajasthan scheme for any category
        // is more relevant than a Central OBC scheme.
        val stateSql = if (state.isNotBlank()) {
            args.add("%${state.trim()}%")
            "CASE WHEN state LIKE ? THEN 5 WHEN level = 'Central' THEN 2 ELSE 0 END"
        } else "CASE WHEN level = 'Central' THEN 2 ELSE 0 END"

        // ── Occupation score (+2 per keyword in scheme_tags or eligibility) ─────
        // Only use ASCII words — occupation may be stored in the user's language
        // (Hindi, Telugu etc.) which won't match English scheme data.
        val occWords = occupation
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 4 && it.all { c -> c.code < 0x0080 } }
            .distinct()

        val occParts = occWords.map { word ->
            args.add("%$word%")
            args.add("%$word%")
            "CASE WHEN scheme_tags LIKE ? OR eligibility LIKE ? THEN 2 ELSE 0 END"
        }
        val occSql = if (occParts.isNotEmpty()) occParts.joinToString(" + ") else "0"

        // ── Query keyword score (+1 per intent word from the user's question) ──
        // Catches words like "pension", "widow", "loan" directly from what the
        // user asked — useful when occupation is stored in a non-English script.
        val queryWords = query
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^A-Za-z]"), "").trim() }
            .filter { it.length > 5 && it.all { c -> c.code < 0x0080 } }
            .distinct()
            .take(5)

        val queryParts = queryWords.map { word ->
            args.add("%$word%")
            args.add("%$word%")
            "CASE WHEN scheme_tags LIKE ? OR eligibility LIKE ? THEN 1 ELSE 0 END"
        }
        val querySql = if (queryParts.isNotEmpty()) queryParts.joinToString(" + ") else "0"

        val scoreSql = "($casteSql + $stateSql + $occSql + $querySql)"

        Log.d(TAG, "Scoring: caste=$casteLabel state=$state occWords=$occWords queryWords=$queryWords")

        val sql = """
            SELECT rag_chunk
            FROM (
                SELECT rag_chunk, $scoreSql AS score
                FROM schemes
                WHERE rag_chunk IS NOT NULL AND rag_chunk != ''
            )
            WHERE score >= 2
            ORDER BY score DESC
            LIMIT $MAX_RESULTS
        """.trimIndent()

        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val results = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val chunk = cursor.getString(0)
                if (!chunk.isNullOrBlank()) results.add(chunk)
            }
            Log.d(TAG, "${results.size} rag_chunks retrieved (score >= 2)")
            return results
        }
    }

    /**
     * Maps user-profile caste codes to the full labels used in scheme_tags.
     * Returns null for "General" — no caste filter applied.
     */
    private fun casteToTag(code: String): String? = when (code.trim().uppercase()) {
        "SC"  -> "Scheduled Caste"
        "ST"  -> "Scheduled Tribe"
        "OBC" -> "OBC"
        else  -> null
    }
}
