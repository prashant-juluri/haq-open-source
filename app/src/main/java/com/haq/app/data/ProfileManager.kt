package com.haq.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

object ProfileManager {
    private lateinit var db: HaqDatabase

    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = HaqDatabase.getInstance(context)
        }
    }

    fun getAllProfiles(): Flow<List<UserProfile>> =
        db.userProfileDao().getAllProfiles()

    suspend fun getActiveProfile(id: Int): UserProfile? =
        db.userProfileDao().getProfile(id)

    suspend fun createProfile(
        name: String,
        language: String,
        state: String,
        casteCategory: String,
        occupation: String
    ): Int {
        val profile = UserProfile(
            name = name,
            preferredLanguage = language,
            state = state,
            casteCategory = casteCategory,
            occupation = occupation,
            isOnboarded = true
        )
        return db.userProfileDao().insertProfile(profile).toInt()
    }

    suspend fun updateLastActive(id: Int) {
        val profile = db.userProfileDao().getProfile(id) ?: return
        db.userProfileDao().updateProfile(
            profile.copy(lastActiveAt = System.currentTimeMillis())
        )
    }
}
