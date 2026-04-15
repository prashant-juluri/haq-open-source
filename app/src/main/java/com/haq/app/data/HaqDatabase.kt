package com.haq.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UserProfile::class], version = 1)
abstract class HaqDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile private var INSTANCE: HaqDatabase? = null

        fun getInstance(context: Context): HaqDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    HaqDatabase::class.java,
                    "haq_database"
                ).build().also { INSTANCE = it }
            }
    }
}
