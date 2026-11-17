package com.decio.skipii

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import org.schabi.newpipe.extractor.NewPipe

/** Skipii inside Joyboy: one process-level init, and a ComposeView the host puts in its YouTube tab. */
object SkipiiEmbed {
    private var inited = false
    lateinit var shorts: ShortsState
        private set
    /** Class name of the host's main activity (set by the host); notification taps open it with tab=youtube. */
    var hostActivityClass: String = ""
    var hostTaskId: () -> Int? = { null }
    /** True while the YouTube tab is not the visible one. */
    var hidden by mutableStateOf(true)
    /** True while Skipii's player is fullscreen (the host hides its bar then). */
    var fullscreen by mutableStateOf(false)
    var onFullscreen: ((Boolean) -> Unit)? = null
    /** (id, title) of a tapped video; return true when the host plays it itself. */
    var onPlay: ((String, String) -> Boolean)? = null
    private var savedOrientation: Int? = null

    fun mainIntent(context: Context): Intent =
        Intent().setClassName(context.packageName, hostActivityClass).putExtra("tab", "youtube")

    fun openTaskId(): Int? = hostTaskId()

    fun init(app: Application) {
        if (inited) return
        inited = true
        AppLanguage.init(app)
        // Joyboy may have set up NewPipe already (YtNp); don't replace its downloader.
        try { NewPipe.getDownloader() } catch (_: Throwable) { null } ?: NewPipe.init(NewPipeDownloader.instance)
        try { org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(false) } catch (_: Throwable) {}
        ThemeSettings.init(app)
        QualityPreference.init(app)
        PlaybackSpeed.init(app)
        AudioOnly.init(app)
        PlayerControls.init(app)
        FullscreenZoom.init(app)
        FullscreenSwipes.init(app)
        SeekStep.init(app)
        ChaptersStayOpen.init(app)
        AutoPip.init(app)
        LiveControls.init(app)
        CaptionSettings.init(app)
        CaptionTranslator.refresh()
        SearchHistory.init(app)
        ResumeStore.init(app)
        SavedSession.init(app)
        Account.init(app)
        Bells.init(app)
        NewVideos.schedule(app)
        SponsorBlock.init(app)
        ShortsSetting.init(app)
        ShortsBank.init(app)
        AppUpdate.init(app)
        AppUpdate.enabled = false
        shorts = ShortsState(app, kotlinx.coroutines.MainScope())
        try {
            Coil.setImageLoader(ImageLoader.Builder(app).crossfade(180)
                .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.25).build() }.build())
        } catch (_: Throwable) {}
        app.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                private var last: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (last != null && last != network) StreamCache.invalidateAll()
                    last = network
                }
            },
        )
    }

    /** A notification / live-activity tap that carries a video for the player. */
    fun handleIntent(intent: Intent?) {
        if (!inited) return
        NewVideos.takeVideo(intent)?.let { NewVideos.opening = it }
        LiveControls.takeOpen(intent)
    }

    fun createView(activity: ComponentActivity): View = ComposeView(activity).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SkipiiTheme { SkipiiApp() }
            }
        }
    }

    /** Called by the host when the YouTube tab is shown / hidden. */
    fun setHidden(activity: ComponentActivity, hide: Boolean) {
        if (hide == hidden) return
        hidden = hide
        if (hide) {
            savedOrientation?.let { activity.requestedOrientation = it }
            savedOrientation = null
            fullscreen = false
            onFullscreen?.invoke(false)
        } else {
            if (savedOrientation == null) savedOrientation = activity.requestedOrientation
            if (!fullscreen) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
