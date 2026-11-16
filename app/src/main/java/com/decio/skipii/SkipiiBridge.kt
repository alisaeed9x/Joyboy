package com.decio.skipii

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.tttt.subtitler.AudioSource
import com.tttt.subtitler.AudioSources
import com.tttt.subtitler.Cfg
import com.tttt.subtitler.Conf
import com.tttt.subtitler.DlQuality
import com.tttt.subtitler.Downloader
import com.tttt.subtitler.Engine
import com.tttt.subtitler.Host
import com.tttt.subtitler.PromptBuilder
import com.tttt.subtitler.Store
import com.tttt.subtitler.Sub
import com.tttt.subtitler.TaskCenter
import com.tttt.subtitler.TrProfile
import java.io.File
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * (v1080 Phase 3C) Joyboy Engine translation grafted onto the Skipii player.
 * The bridge owns one Engine per video, exactly like Player.initEngine does:
 * same video id ("yt:" + 11-char id, so saved translations are shared with the
 * old player), same Store file, same Conf snapshot, same audio source built by
 * AudioSources from the StreamCache audio URL. Translation stays one model
 * (gemini-flash-lite-latest, from Cfg); no new engine, no ML Kit.
 * Engine subs are mapped to CaptionCue lines and pushed into the open video's
 * CaptionsState, so the existing CaptionLayer draws them on the video.
 * TrProfile.restore runs here before the snapshot: this is the Skipii path's
 * init point, the equivalent of Player.initEngine. TrSlider needs no wiring:
 * Engine reads it live (direct path, slow-audio, holes, hi-timing).
 */
object SkipiiTranslate {
    enum class State { Idle, Starting, Running, Paused }

    private const val TAG = "SkipiiTranslate"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var vid = ""

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var runThread: Thread? = null

    @Volatile
    private var gen = 0

    private fun postMain(f: () -> Unit) {
        main.post {
            try {
                f()
            } catch (_: Throwable) {
            }
        }
    }

    private fun fail(state: PlayerState, msg: String) {
        postMain {
            state.actionMessage = msg
            state.setTranslateState(State.Idle)
        }
    }

    /**
     * The translate pill: start the Engine for this video, or pause / resume it.
     * Taps while starting are ignored; a second tap while running pauses.
     */
    fun toggle(ctx: Context, state: PlayerState) {
        val item = state.current ?: return
        val id = item.id
        if (id.length != 11) {
            state.actionMessage = str(R.string.player_translate_no_video)
            return
        }
        if (state.translateState == State.Starting) return
        val eng = engine
        if (eng != null && vid == "yt:$id") {
            when (state.translateState) {
                State.Paused -> {
                    eng.userPaused = false
                    state.setTranslateState(State.Running)
                    ensureRunThread(eng, state)
                    postMain {
                        if (engine === eng && state.current?.id == id) {
                            state.captions.useJoyboyCues(toCues(eng.subs))
                        }
                    }
                    return
                }
                State.Running -> {
                    eng.userPaused = true
                    state.setTranslateState(State.Paused)
                    return
                }
                else -> {
                    // Idle with an engine left over (its run loop already
                    // returned, e.g. no keys): retire it and start fresh.
                }
            }
        }
        start(ctx.applicationContext, state, item)
    }

    private fun ensureRunThread(eng: Engine, state: PlayerState) {
        if (runThread?.isAlive == true) return
        if (!eng.reopen()) return
        launchRun(eng, state)
    }

    private fun launchRun(eng: Engine, state: PlayerState) {
        val t = Thread {
            try {
                eng.run()
            } catch (_: Throwable) {
            } finally {
                postMain {
                    if (engine === eng) {
                        state.setTranslateState(State.Idle)
                    }
                }
            }
        }
        t.isDaemon = true
        runThread = t
        t.start()
    }

    private fun start(appCtx: Context, state: PlayerState, item: VideoItem) {
        stop()
        val g = ++gen
        state.setTranslateState(State.Starting)
        Thread {
            try {
                val info = try {
                    StreamCache.get(item.id) ?: StreamCache.getOrLoad(item.id)
                } catch (_: Throwable) {
                    null
                }
                val pick = info?.let { StreamSelection.pick(it) }
                val audioUrl = pick?.audio?.content ?: pick?.muxed?.content
                if (audioUrl.isNullOrBlank()) {
                    fail(state, str(R.string.player_translate_no_audio))
                    return@Thread
                }
                try {
                    TrProfile.restore()
                } catch (_: Throwable) {
                }
                val conf = try {
                    Cfg.snapshot()
                } catch (_: Throwable) {
                    null
                }
                if (conf == null) {
                    fail(state, str(R.string.player_translate_failed))
                    return@Thread
                }
                val v = "yt:" + item.id
                val store = Store(File(appCtx.filesDir, "progress"), Store.keyFor(v))
                val pb = PromptBuilder { p ->
                    appCtx.assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val host = BridgeHost(state, item.id)
                val eng = Engine(conf, { makeAudio(appCtx, audioUrl, conf) }, store, host, pb)
                eng.persistBlocked = true
                eng.convDialect = readCfg("conv_dialect", "")
                eng.toneStyle = readCfg("tone_style", "")
                eng.toneStrength = readCfg("tone_strength", "متوسطة")
                eng.titleHint = item.title
                eng.baseLangCode = readCfg("base_lang", "ar")
                host.engine = eng
                if (g != gen) {
                    eng.stop()
                    return@Thread
                }
                vid = v
                engine = eng
                try {
                    eng.load()
                } catch (_: Throwable) {
                }
                eng.persistBlocked = false
                if (g != gen) {
                    eng.stop()
                    return@Thread
                }
                val posSec = state.positionMs / 1000.0
                if (eng.subs.isNotEmpty() && !eng.fullyDone()) {
                    eng.translateFrom(posSec)
                } else {
                    eng.translateFromStart()
                }
                postMain {
                    if (engine !== eng) return@postMain
                    state.captions.useJoyboyCues(toCues(eng.subs))
                    state.setTranslateState(State.Running)
                }
                launchRun(eng, state)
            } catch (e: Throwable) {
                Log.w(TAG, "start failed for ${item.id}", e)
                fail(state, str(R.string.player_translate_failed))
            }
        }.apply { isDaemon = true }.start()
    }

    private fun readCfg(key: String, def: String): String {
        return try {
            Cfg.str(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    /**
     * Audio for the Engine: the best StreamCache audio stream (m4a first, original
     * track preferred), fed to the same AudioSources the old player uses, with the
     * Skipii media User-Agent and a YouTube referer.
     */
    private fun makeAudio(ctx: Context, audioUrl: String, conf: Conf): AudioSource {
        val hdr = mapOf(
            "User-Agent" to Net.USER_AGENT,
            "Referer" to "https://www.youtube.com/",
        )
        return AudioSources.make(ctx, null, audioUrl, hdr, conf.audioTrack) { m ->
            Log.i(TAG, m)
        }
    }

    /** Engine subs to caption lines: translated text, original when translating only. */
    private fun toCues(subs: List<Sub>): List<CaptionCue> {
        val out = ArrayList<CaptionCue>(subs.size)
        for (s in subs) {
            val text = s.withSpeaker(s.translated.ifBlank { s.original }).trim()
            if (text.isEmpty()) continue
            val shown = if (s.isSound && !text.startsWith("[")) "[$text]" else text
            val startMs = (s.start * 1000.0).toLong().coerceAtLeast(0L)
            val endMs = (s.end * 1000.0).toLong().coerceAtLeast(startMs + 1L)
            out.add(CaptionCue(startMs, endMs, shown))
        }
        out.sortBy { it.startMs }
        return out
    }

    /**
     * Retire the current Engine: cut its Host callbacks, then stop it. Called when
     * the video changes, when the player closes, and before starting a new video.
     */
    @Synchronized
    fun stop() {
        gen++
        val eng = engine
        engine = null
        vid = ""
        try {
            eng?.stop()
        } catch (_: Throwable) {
        }
    }

    /**
     * The Engine's Host, bound to one PlayerState. Position comes from the state's
     * 250 ms poll (never the controller: ExoPlayer calls from a background thread
     * throw). Changed lines are pushed only while the captions are still driven
     * by this Engine for the same video.
     */
    private inner class BridgeHost(
        private val state: PlayerState,
        private val id: String,
    ) : Host {
        @Volatile
        var engine: Engine? = null

        override fun log(s: String) {
            Log.i(TAG, s)
        }

        override fun status(s: String) {
            Log.i(TAG, s)
        }

        override fun changed() {
            val eng = engine ?: return
            if (this@SkipiiTranslate.engine !== eng) return
            val cues = toCues(eng.subs)
            postMain {
                if (this@SkipiiTranslate.engine !== eng) return@postMain
                if (state.current?.id != id) return@postMain
                if (!state.captions.joyboyDriven) return@postMain
                state.captions.useJoyboyCues(cues)
            }
        }

        override fun notice(s: String) {
            postMain {
                state.actionMessage = s
            }
        }

        override fun position(): Double {
            return state.positionMs / 1000.0
        }

        override fun playerDuration(): Double {
            return state.durationMs / 1000.0
        }
    }
}

/**
 * (v1080 Phase 3C) Joyboy download grafted onto the Skipii player.
 * No second downloader: the quality list comes from StreamCache via
 * StreamSelection.heights (capped at 1080p), each height mapped to its best
 * progressive video stream (mp4 first, highest bitrate) plus the best audio
 * stream, and the pick goes through DlQuality + Downloader.Job + TaskCenter,
 * exactly like Main.ytDownload. Stream URLs carry Net.USER_AGENT.
 */
object SkipiiDownload {
    private val headers = mapOf(
        "User-Agent" to Net.USER_AGENT,
        "Referer" to "https://www.youtube.com/",
    )

    /** Quality picker for the open video; one quality only downloads straight away. */
    fun ask(activity: Activity, item: VideoItem) {
        Thread {
            val opts = try {
                options(item.id)
            } catch (_: Throwable) {
                emptyList()
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (opts.isEmpty()) {
                    Toast.makeText(activity, "⚠ معرفتش أجيب جودات التحميل", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (opts.size == 1) {
                    start(activity.applicationContext, item, opts.first(), false)
                    return@runOnUiThread
                }
                DlQuality.show(activity, "⬇ اختار جودة التحميل", opts, {
                    start(activity.applicationContext, item, opts.first(), true)
                }) { o ->
                    start(activity.applicationContext, item, o, false)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun options(id: String): List<DlQuality.Opt> {
        val info = StreamCache.get(id) ?: StreamCache.getOrLoad(id)
        val audio = StreamSelection.pick(info).audio?.content
        val out = ArrayList<DlQuality.Opt>()
        val heights = StreamSelection.heights(info).filter { it <= 1080 }
        for (h in heights) {
            val v = info.videoOnlyStreams
                .filter {
                    it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height == h
                }
                .sortedWith(
                    compareByDescending<VideoStream> { if (it.format == MediaFormat.MPEG_4) 0 else 1 }
                        .thenByDescending { it.bitrate },
                )
                .firstOrNull() ?: continue
            out.add(DlQuality.Opt("${h}p", h, v.content, audio))
        }
        if (out.isEmpty()) {
            val muxed = info.videoStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .maxByOrNull { it.height } ?: return emptyList()
            out.add(DlQuality.Opt("${muxed.height}p", muxed.height, muxed.content, null))
        }
        return out.sortedByDescending { it.h }
    }

    private fun start(appCtx: Context, item: VideoItem, opt: DlQuality.Opt, audioOnly: Boolean) {
        val url = if (audioOnly) opt.audio ?: return else opt.url
        val name = item.title.ifBlank { item.id } +
            if (!audioOnly && opt.h > 0) " [${opt.h}p]" else ""
        TaskCenter.add(appCtx, "download", (if (audioOnly) "🎧 " else "⬇ ") + name) { t ->
            t.msg = "بجيب الرابط…"
            TaskCenter.changed()
            val job = if (audioOnly) {
                Downloader.Job(name, url, null, false, 1, headers, true)
            } else {
                Downloader.Job(name, url, opt.audio, false, opt.h, headers)
            }
            job.ctl.cancelled = false
            job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { pz -> job.ctl.paused = pz }
            val res = Downloader.run(appCtx, job) { what, pct ->
                t.pct = pct.coerceIn(0, 99)
                t.msg = what
                TaskCenter.changed()
            }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""
            t.outPath = res.second
            Downloader.takeLastOut()?.let {
                t.outUri = it.first
                t.mime = it.second
            }
        }
        Toast.makeText(appCtx, "⬇ اتضاف للتحميل — تابعه من «مركز التحميل»", Toast.LENGTH_SHORT).show()
    }
}
