package com.haq.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only access to law.db shipped in assets.
 *
 * law.db contains rag_chunks extracted from Central Acts on indiacode.nic.in —
 * 780 acts, ~28,000 sections.  Each row's rag_chunk is pre-formatted as:
 *   "Section 3 of The Minimum Wages Act, 1948 — Definitions: …"
 *
 * Copy semantics are identical to SchemeRepository: on first call [ensureDatabase]
 * copies the asset to filesDir; subsequent calls skip the copy if the file is
 * non-empty AND matches the expected version stored in SharedPreferences.
 *
 * Scoring per section (higher = more relevant):
 *   +2  section_title contains a query keyword
 *   +1  rag_chunk contains a query keyword
 *
 * Minimum score of 2 required so only genuinely matching sections are returned.
 */
object LawRepository {

    private const val DB_ASSET  = "law.db"
    private const val TAG       = "Haq/LawRAG"
    private const val MAX_RESULTS = 3
    private const val PREFS_NAME  = "haq_law_prefs"
    private const val PREF_VERSION = "law_db_version"

    /**
     * Copies law.db from assets to filesDir eagerly so the first [search]
     * call incurs no copy or version-check latency.
     */
    suspend fun preload(context: Context) = withContext(Dispatchers.IO) {
        try { ensureDatabase(context) } catch (e: Exception) {
            Log.w(TAG, "preload failed: ${e.message}")
        }
    }

    /**
     * Returns rag_chunk strings for the top [MAX_RESULTS] law sections that
     * match the user's query.  Returns an empty list on any error so callers
     * always proceed — law context is additive, not required.
     */
    suspend fun search(
        context: Context,
        query: String,
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val dbFile = ensureDatabase(context)
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                querySections(db, query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "search() failed: ${e.message}", e)
            emptyList()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Copies law.db from assets to filesDir if:
     *   (a) the file does not exist or is empty, OR
     *   (b) the stored version in SharedPreferences is older than [BuildConfig.LAW_DB_VERSION].
     *
     * Bumping LAW_DB_VERSION in build.gradle.kts triggers a re-copy on next
     * launch — no uninstall required.
     */
    private fun ensureDatabase(context: Context): File {
        val dbFile = File(context.filesDir, DB_ASSET)
        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt(PREF_VERSION, 0)

        val needsCopy = !dbFile.exists()
            || dbFile.length() < 1_000_000L
            || stored < com.haq.app.BuildConfig.LAW_DB_VERSION

        if (!needsCopy) return dbFile

        Log.d(TAG, "Copying $DB_ASSET from assets (stored=$stored, current=${com.haq.app.BuildConfig.LAW_DB_VERSION})")
        context.assets.open(DB_ASSET).use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
        prefs.edit().putInt(PREF_VERSION, com.haq.app.BuildConfig.LAW_DB_VERSION).apply()
        Log.d(TAG, "Copy complete: ${dbFile.length() / 1024} KB")
        return dbFile
    }

    /**
     * Scores every section in [db] against the user's [query] keywords and
     * returns the top [MAX_RESULTS] rag_chunks.
     *
     * Score per section:
     *   +2 per keyword found in section_title
     *   +1 per keyword found in rag_chunk
     *
     * Only ASCII words longer than 4 characters are used as keywords — the same
     * filter as SchemeRepository — so non-English queries gracefully degrade to
     * returning nothing rather than producing noise.
     */
    private fun querySections(
        db: SQLiteDatabase,
        query: String,
    ): List<String> {
        val args = mutableListOf<String>()

        val keywords = query
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^A-Za-z]"), "").trim() }
            .filter { it.length > 4 && it.all { c -> c.code < 0x0080 } }
            .distinct()
            .take(6)

        if (keywords.isEmpty()) {
            Log.d(TAG, "No usable ASCII keywords in query — skipping law search")
            return emptyList()
        }

        Log.d(TAG, "Law search keywords: $keywords")

        val scoreParts = keywords.map { word ->
            args.add("%$word%")
            args.add("%$word%")
            "CASE WHEN section_title LIKE ? THEN 2 ELSE 0 END + " +
            "CASE WHEN rag_chunk     LIKE ? THEN 1 ELSE 0 END"
        }
        val scoreSql = scoreParts.joinToString(" + ")

        val sql = """
            SELECT rag_chunk
            FROM (
                SELECT rag_chunk, ($scoreSql) AS score
                FROM law_sections
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
            Log.d(TAG, "${results.size} law sections retrieved")
            return results
        }
    }
}
