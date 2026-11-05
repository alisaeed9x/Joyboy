package com.tttt.joyboylauncher

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val p = getSharedPreferences("launcher", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(p.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        DynamicColors.applyToActivitiesIfAvailable(this) // Material You
    }
}
