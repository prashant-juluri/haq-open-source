package com.haq.app

import android.app.Application
import com.haq.app.data.ProfileManager
import com.haq.app.tts.TTSManager

class HaqApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialise singletons once per process. Application.onCreate() survives
        // Activity restarts (e.g. Samsung permission-grant restart) so these are
        // guaranteed to run exactly once regardless of how many times the Activity
        // or its ViewModels are created.
        TTSManager.init(this)
        ProfileManager.init(this)
    }
}
