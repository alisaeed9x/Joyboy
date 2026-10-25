package me.prince.launcher

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

private const val TAG = "Launcher"
// Xiaomi TVs have a picture side panel (mode, backlight, OLED settings); everything else gets display settings
private const val XIAOMI_PICTURE_SETTINGS = "com.mitv.settings.PICTURE_SETTINGS"

class MainActivity : ComponentActivity() {
    private var reloadKey by mutableIntStateOf(0)
    private var homePresses by mutableIntStateOf(0)
    private var resumes by mutableIntStateOf(0)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reloadKey++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvLink.start(applicationContext)   // phone remote + push update server
        // Back on the home screen should do nothing, not exit the launcher
        onBackPressedDispatcher.addCallback(this) {}

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })

        setContent {
            HomeScreen(
                reloadKey = reloadKey,
                homePresses = homePresses,
                resumes = resumes,
                onLaunch = { if (it.isInput) launchFresh(it.launchIntent) else launch(it.launchIntent) },
                onAppInfo = { app ->
                    launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                },
                onWifi = { launch(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                onSettings = { launch(Intent(Settings.ACTION_SETTINGS)) },
                onPicture = { launch(pictureSettings()) },
                onSound = { launch(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                onVideos = { startActivity(Intent(this, VideosActivity::class.java)) },
                onYouTube = { startActivity(Intent(this, YouTubeActivity::class.java)) },
                // Stremio title links only work on a cold start; plain app launches don't need that
                onOpenTrending = { if (it.data != null) launchFresh(it) else launch(it) },
            )
        }
    }

    // Home pressed while already on the home screen: jump back to the first app
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses++
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    private fun pictureSettings(): Intent {
        val xiaomi = Intent(XIAOMI_PICTURE_SETTINGS)
        return if (packageManager.resolveActivity(xiaomi, 0) != null) xiaomi else Intent(Settings.ACTION_DISPLAY_SETTINGS)
    }

    // Live TV apps (HDMI inputs) and Stremio (title links) can ignore the link if they're already running,
    // so close the target's background process first. It's always in the background while we're Home.
    private fun launchFresh(intent: Intent) {
        packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName?.let {
            getSystemService(ActivityManager::class.java).killBackgroundProcesses(it)
        }
        launch(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $intent", e)
        }
    }
}
