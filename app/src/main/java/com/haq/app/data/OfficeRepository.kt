package com.haq.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only access to offices.db shipped in assets.
 *
 * offices.db contains government offices relevant to Haq's use cases:
 *   - DLSA  (District Legal Services Authority) — free legal aid
 *   - Labour (district labour enforcement offices)
 *
 * Schema:
 *   offices(id, state, district, office_type, name, address, phone, rag_chunk)
 *
 * Search returns rag_chunks for offices in the user's state/district that
 * match the query's office types.  Falls back to state-level results when
 * no district match is found (e.g. existing users whose profile predates
 * the district field).
 *
 * Version-gated via OFFICES_DB_VERSION in BuildConfig — bump to trigger
 * re-copy on next launch without requiring an uninstall.
 */
object OfficeRepository {

    private const val DB_ASSET   = "offices.db"
    private const val TAG        = "Haq/OfficeRAG"
    private const val MAX_RESULTS = 2
    private const val PREFS_NAME  = "haq_office_prefs"
    private const val PREF_VERSION = "offices_db_version"

    suspend fun preload(context: Context) = withContext(Dispatchers.IO) {
        try { ensureDatabase(context) } catch (e: Exception) {
            Log.w(TAG, "preload failed: ${e.message}")
        }
    }

    /**
     * Returns rag_chunks for offices in [state]/[district] relevant to [officeTypes].
     * Tries district-exact match first; falls back to state-level if no rows found.
     */
    suspend fun search(
        context: Context,
        state: String,
        district: String,
        officeTypes: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val dbFile = ensureDatabase(context)
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                queryOffices(db, state, district, officeTypes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "search() failed: ${e.message}", e)
            emptyList()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun ensureDatabase(context: Context): File {
        val dbFile = File(context.filesDir, DB_ASSET)
        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt(PREF_VERSION, 0)

        val needsCopy = !dbFile.exists()
            || dbFile.length() < 1_000L
            || stored < com.haq.app.BuildConfig.OFFICES_DB_VERSION

        if (!needsCopy) return dbFile

        Log.d(TAG, "Copying $DB_ASSET from assets (stored=$stored, current=${com.haq.app.BuildConfig.OFFICES_DB_VERSION})")
        context.assets.open(DB_ASSET).use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
        prefs.edit().putInt(PREF_VERSION, com.haq.app.BuildConfig.OFFICES_DB_VERSION).apply()
        Log.d(TAG, "Copy complete: ${dbFile.length() / 1024} KB")
        return dbFile
    }

    private fun queryOffices(
        db: SQLiteDatabase,
        state: String,
        district: String,
        officeTypes: List<String>,
    ): List<String> {
        if (officeTypes.isEmpty() || state.isBlank()) return emptyList()

        val typePlaceholders = officeTypes.joinToString(",") { "?" }

        // Try district-exact match first
        if (district.isNotBlank()) {
            val districtArgs = (officeTypes + state + district).toTypedArray()
            val districtSql = """
                SELECT rag_chunk FROM offices
                WHERE office_type IN ($typePlaceholders)
                  AND state LIKE ?
                  AND district LIKE ?
                  AND rag_chunk IS NOT NULL AND rag_chunk != ''
                ORDER BY office_type
                LIMIT $MAX_RESULTS
            """.trimIndent()

            db.rawQuery(districtSql, districtArgs).use { cursor ->
                val results = mutableListOf<String>()
                while (cursor.moveToNext()) results.add(cursor.getString(0))
                if (results.isNotEmpty()) {
                    Log.d(TAG, "${results.size} offices found for district='$district'")
                    return results
                }
            }
        }

        // Fall back to state-level (nearest state capital office)
        val stateArgs = (officeTypes + state).toTypedArray()
        val stateSql = """
            SELECT rag_chunk FROM offices
            WHERE office_type IN ($typePlaceholders)
              AND state LIKE ?
              AND rag_chunk IS NOT NULL AND rag_chunk != ''
            ORDER BY office_type
            LIMIT $MAX_RESULTS
        """.trimIndent()

        db.rawQuery(stateSql, stateArgs).use { cursor ->
            val results = mutableListOf<String>()
            while (cursor.moveToNext()) results.add(cursor.getString(0))
            Log.d(TAG, "${results.size} offices found at state level for state='$state'")
            return results
        }
    }
}
