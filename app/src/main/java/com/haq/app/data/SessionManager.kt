package com.haq.app.data

import android.content.Context

object SessionManager {
    private const val PREFS_NAME = "haq_session"
    private const val KEY_ACTIVE_PROFILE = "active_profile_id"

    fun getActiveProfileId(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ACTIVE_PROFILE, -1)

    fun setActiveProfileId(context: Context, id: Int) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ACTIVE_PROFILE, id).apply()
}
