package com.yourteam.guard.harness

import android.app.Application
import com.yourteam.guard.harness.log.EventLog

class HarnessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        com.yourteam.guard.harness.rules.Prefs.init(this)
    }
}
