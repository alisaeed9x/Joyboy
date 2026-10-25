package com.tttt.subtitler

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

/**
 * (v546 م0-ب) واجهة التشغيل اللي PlayerActivity بتكلّمها بدل ExoPlayer مباشرة.
 * محلي = LocalPlayback (فوق ExoPlayer، من غير أي منطق). بعيد = RemotePlayback (mirror للتلفزيون، من غير شبكة على الـUI).
 * الاستدعاءات الخاصة بـExoPlayer (trackSelectionParameters / currentTracks / setVideoSurfaceView / setMediaSource …) بتعدّي من exo()
 * اللي بترجّع null في الوضع البعيد.
 */
interface PlaybackBackend {
    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean
    var playWhenReady: Boolean
    var volume: Float
    val playbackState: Int
    val bufferedPosition: Long
    /** true = وضع المتحكم: التشغيل الحقيقي على التلفزيون */
    val isRemote: Boolean

    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setPlaybackSpeed(s: Float)
    /** تحميل فيديو. محلي: setMediaItem + prepare بسيطة. بعيد: أمر load للتلفزيون */
    fun load(spec: MediaSpec)
    fun release()

    fun audioTracks(): List<String>
    fun currentAudio(): Int
    fun selectAudio(i: Int)
    /** (عرض، ارتفاع) أو (0، 0) لو لسه مش معروف */
    fun videoSize(): Pair<Int, Int>

    /** ExoPlayer الحقيقي، أو null في الوضع البعيد */
    fun exo(): ExoPlayer?
}

/** تفويض كامل لـExoPlayer — مفيش أي منطق هنا (سلوك المشغّل المحلي زي ما كان بالظبط) */
class LocalPlayback(val p: ExoPlayer) : PlaybackBackend {
    override val currentPosition: Long get() = p.currentPosition
    override val duration: Long get() = p.duration
    override val isPlaying: Boolean get() = p.isPlaying
    override var playWhenReady: Boolean
        get() = p.playWhenReady
        set(v) { p.playWhenReady = v }
    override var volume: Float
        get() = p.volume
        set(v) { p.volume = v }
    override val playbackState: Int get() = p.playbackState
    override val bufferedPosition: Long get() = p.bufferedPosition
    override val isRemote: Boolean get() = false

    override fun play() { p.play() }
    override fun pause() { p.pause() }
    override fun seekTo(ms: Long) { p.seekTo(ms) }
    override fun setPlaybackSpeed(s: Float) { p.setPlaybackSpeed(s) }
    override fun load(spec: MediaSpec) {
        val u = spec.uri ?: spec.url ?: return
        p.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(u)))
        p.prepare()
        if (spec.startMs > 0L) p.seekTo(spec.startMs)
        p.playWhenReady = true
    }
    override fun release() { p.release() }

    override fun audioTracks(): List<String> {
        val out = ArrayList<String>()
        for (g in p.currentTracks.groups) {
            if (g.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) out.add(g.getTrackFormat(i).language ?: g.getTrackFormat(i).label ?: "?")
        }
        return out
    }
    override fun currentAudio(): Int {
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) { if (g.isTrackSelected(i)) return n; n += 1 }
        }
        return 0
    }
    override fun selectAudio(i: Int) {
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
            for (k in 0 until g.length) {
                if (n == i) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, k)).build()
                    return
                }
                n += 1
            }
        }
    }
    override fun videoSize(): Pair<Int, Int> { val v = p.videoSize; return Pair(v.width, v.height) }
    override fun exo(): ExoPlayer? = p
}

/**
 * (v546 م2-ج) مشغّل بعيد: الـgetters من LinkSession.mirror (ذاكرة بس). الأوامر async للتلفزيون مع قيمة تفاؤلية قصيرة.
 * الصوت على الموبايل مكتوم: volume هنا مجرد قيمة محلية ومابتتبعتش.
 */
class RemotePlayback : PlaybackBackend {
    private val m get() = LinkSession.mirror

    @Volatile private var posOv = -1L
    @Volatile private var posOvAt = 0L
    @Volatile private var playOv: Boolean? = null
    @Volatile private var playOvAt = 0L
    @Volatile private var vol = 0f

    private fun fresh(at: Long, ms: Long) = System.currentTimeMillis() - at < ms

    override val currentPosition: Long
        get() {
            val now = System.currentTimeMillis()
            val real = m.position(now)
            if (posOv >= 0L && fresh(posOvAt, 1500L)) {
                val pl = isPlaying
                val sp = PosExtrap.speedOf(m.st.optString("speed", "1"))
                return if (pl) posOv + ((now - posOvAt) * sp).toLong() else posOv
            }
            return real
        }
    override val duration: Long get() { val d = m.st.optLong("dur", 0L); return if (d > 0L) d else androidx.media3.common.C.TIME_UNSET }
    override val isPlaying: Boolean
        get() { val o = playOv; return if (o != null && fresh(playOvAt, 2000L)) o else m.st.optBoolean("playing", false) }
    override var playWhenReady: Boolean
        get() = isPlaying
        set(v) { if (v) play() else pause() }
    override var volume: Float
        get() = vol
        set(v) { vol = v }
    override val playbackState: Int
        get() {
            if (!m.gotFirst || !m.live) return Player.STATE_BUFFERING
            val d = m.st.optLong("dur", 0L)
            if (d <= 0L) return Player.STATE_BUFFERING
            return if (!isPlaying && m.position(System.currentTimeMillis()) >= d - 300L) Player.STATE_ENDED else Player.STATE_READY
        }
    override val bufferedPosition: Long get() = 0L
    override val isRemote: Boolean get() = true

    override fun play() { playOv = true; playOvAt = System.currentTimeMillis(); LinkSession.send("play") }
    override fun pause() { playOv = false; playOvAt = System.currentTimeMillis(); LinkSession.send("pause") }
    private val hd = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var seekPend = -1L
    private var seekSched = false

    /** السحب على الشريط بيبعت seekTo كل ~90ms: بنحدّث الموضع التفاؤلي فورًا وبنبعت للتلفزيون آخر قيمة كل 120ms بس */
    override fun seekTo(ms: Long) {
        val t = if (ms < 0L) 0L else ms
        posOv = t; posOvAt = System.currentTimeMillis()
        seekPend = t
        if (seekSched) return
        seekSched = true
        hd.postDelayed({
            seekSched = false
            val v = seekPend; seekPend = -1L
            if (v >= 0L) LinkSession.send("seekto", JSONObject().put("ms", v))
        }, 120L)
    }
    override fun setPlaybackSpeed(s: Float) { LinkSession.send("speed.set", JSONObject().put("v", s.toDouble())) }
    override fun load(spec: MediaSpec) {
        val u = PlayTarget.targetUrl(spec) ?: return
        val a = JSONObject().put("url", u).put("title", spec.title).put("how", spec.how).put("startms", spec.startMs)
        a.put("ref", spec.headers["ref"] ?: "").put("ua", spec.headers["ua"] ?: "").put("cookie", spec.headers["cookie"] ?: "")
        LinkSession.send("load", a)
    }
    /** مفيش حاجة تتحرر محليًا: الجلسة (LinkSession) بتفضل شغّالة لحد ما PlayerActivity تقفلها بنفسها */
    override fun release() {}

    override fun audioTracks(): List<String> {
        val a = m.st.optJSONArray("al") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }
    override fun currentAudio(): Int = m.st.optInt("ac", 0)
    override fun selectAudio(i: Int) { LinkSession.send("track.audio", JSONObject().put("i", i)) }
    override fun videoSize(): Pair<Int, Int> = Pair(m.st.optInt("vw", 0), m.st.optInt("vh", 0))
    override fun exo(): ExoPlayer? = null
}
