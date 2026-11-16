package com.decio.skipii

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * (v1080) The Shorts feed, players and bank: one per process, outliving any window.
 * Held here, not in SkipiiApplication: Joyboy's application class is JoyApp, so
 * SkipiiApplication is never instantiated and every `as SkipiiApplication` cast
 * in Shorts.kt / MainActivity.kt crashed with a ClassCastException the moment
 * Shorts opened. JoyApp installs this in onCreate, before any window exists.
 */
object SkipiiShorts {
    private var _state: ShortsState? = null
    val state: ShortsState
        get() = checkNotNull(_state) { "SkipiiShorts not installed (JoyApp.onCreate)" }
    val isInstalled: Boolean get() = _state != null
    fun install(app: Application) {
        if (_state == null) {
            _state = ShortsState(app, MainScope())
        }
    }
}

/**
 * (v1080) Embedding seam between Joyboy's MainActivity (plain Views + BottomNav)
 * and the vendored SkipiiApp composable. Joyboy drives the tab, opens videos /
 * channels / searches and asks about Back through here; SkipiiApp collects the
 * requests and owns all player / tab state. Nothing here touches Engine.kt,
 * Player.kt or the Joyboy player: those are Phase 3.
 */
object SkipiiHost {
    private val _tabRequests = MutableSharedFlow<Tab>(extraBufferCapacity = 1)
    val tabRequests: SharedFlow<Tab> = _tabRequests.asSharedFlow()
    fun requestTab(tab: Tab) {
        _tabRequests.tryEmit(tab)
    }

    private val _videoRequests = MutableSharedFlow<VideoItem>(extraBufferCapacity = 1)
    val videoRequests: SharedFlow<VideoItem> = _videoRequests.asSharedFlow()
    fun openVideo(item: VideoItem) {
        _videoRequests.tryEmit(item)
    }

    private val _urlRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val urlRequests: SharedFlow<String> = _urlRequests.asSharedFlow()
    fun openUrl(url: String) {
        _urlRequests.tryEmit(url)
    }

    private val _channelRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val channelRequests: SharedFlow<String> = _channelRequests.asSharedFlow()
    fun openChannel(url: String) {
        _channelRequests.tryEmit(url)
    }

    private val _searchRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val searchRequests: SharedFlow<String> = _searchRequests.asSharedFlow()
    fun search(query: String) {
        _searchRequests.tryEmit(query)
    }

    /** Back inside the embedded YouTube tab. Installed by SkipiiApp; true = consumed. */
    var backHandler: (() -> Boolean)? = null
    fun back(): Boolean {
        return try {
            backHandler?.invoke() == true
        } catch (_: Throwable) {
            false
        }
    }

    /** (v1080 Phase 3D) The live player, published by SkipiiApp while it is composed. */
    var playerState: PlayerState? = null

    /** (v1080 Phase 3D) Whether Joyboy shows a bottom bar under the embedded YouTube tab. */
    var embeddedBottomBar: Boolean = true

    /** (v1080 Phase 3D) Poke for Joyboy's floating video bubble, set by Joyboy MainActivity. */
    var playbackListener: (() -> Unit)? = null
    fun notifyPlaybackChanged() {
        try {
            playbackListener?.invoke()
        } catch (_: Throwable) {
        }
    }

    /** (v1073) A Shorts link: youtube.com/shorts/<id> (or youtu.be with /shorts/). */
    fun isShortUrl(url: String): Boolean {
        return url.contains("/shorts/")
    }

    /** (v1073) A Shorts item: a known duration of 1..60 seconds. */
    fun isShort(item: VideoItem): Boolean {
        return item.id.length == 11 && item.durationSec in 1L..60L
    }
}
