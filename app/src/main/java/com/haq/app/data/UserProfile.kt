package com.haq.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String = "",
    val preferredLanguage: String = "hi", // hi, te, ml
    val state: String = "",
    val casteCategory: String = "",       // SC, ST, OBC, General
    val occupation: String = "",
    val isOnboarded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis()
)
