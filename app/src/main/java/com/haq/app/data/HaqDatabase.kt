package com.haq.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UserProfile::class], version = 3)
abstract class HaqDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile private var INSTANCE: HaqDatabase? = null

        /** Adds lastQuery, lastResponse, lastQueryAt columns introduced in v2. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN lastQuery TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN lastResponse TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN lastQueryAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Adds district column introduced in v3. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN district TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): HaqDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    HaqDatabase::class.java,
                    "haq_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
            }
    }
}
