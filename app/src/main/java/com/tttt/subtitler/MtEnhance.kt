@file:OptIn(UnstableApi::class)

package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * (v945) تحسين صورة الفيديو المحلي في مشغّل MeeTube — من غير AI ومن غير عتاد قوي.
 * فلتر شادر واحد خفيف (CAS: Contrast Adaptive Sharpening — بيحدّ الحواف بس ومابيزودش الضوضا في المساحات الملساء)
 * + «ألوان أوضح» اختياري (تباين وتشبّع خفيف). مرّة واحدة على الـGPU لكل فريم، 5 قراءات texture بس.
 * (v992) بيشتغل على أي مصدر (حتى يوتيوب)، وبيتقفل لوحده لـ: HDR · أعلى من 1080p · أعلى من 60 فريم/ث.
 * حمايات (الجهاز مش متجرّب): لو أول فريم ماظهرش خلال 4.5ث بعد التفعيل، أو الـpipeline رمى خطأ، أو الفريمات بتقع كتير ← الفلتر بيتشال.
 * أول حالتين بيتخزّنوا (mt_enh_off) والفلتر بيفضل مقفول لحد ما تختار درجة من الإعدادات؛ الفريمات الواقعة مقفولة لحد ما تقفل البرنامج.
 * Cfg: mt_enh_level (0 إيقاف · 1 خفيف · 2 متوسط · 3 قوي — الافتراضي 1) · mt_enh_vivid · mt_enh_off.
 */
object MtEnhance {
    const val K_LEVEL = "mt_enh_level"
    const val K_VIVID = "mt_enh_vivid"
    const val K_OFF = "mt_enh_off"
    const val K_FAKE = "mt_enh_hdr"   // (v947) HDR وهمي: 0 إيقاف · 1 خفيف · 2 متوسط · 3 قوي (الافتراضي 0)

    private const val WATCH_MS = 4500L
    private const val MAX_PIXELS = 2_200_000   // ~1080p (1920×1088 = 2.09M)
    private const val MAX_FPS = 61f

    private val ui = Handler(Looper.getMainLooper())
    private var act: Activity? = null
    private var player: ExoPlayer? = null
    private var info: com.newtube.mobile.ui.playback.JoyboyPlayerHost.VideoInfo? = null
    private var applied = ""
    private var gotFrame = false
    private var memOff = false
    private var heavy = 0
    private var heavyAt = 0L
    private var watch: Runnable? = null
    private var reseek = false   // (v988) بعد دوسة الزرار: seek صغير عشان الـsink يسجّل المؤثرات فورًا
    private var primed = false   // (v992) الريندرر اتفعّل وقائمة المؤثرات مش null (يعني الـVideoSink موجود) — غير كده setVideoEffects ما بتعملش حاجة

    /** (v992) عدد الفريمات اللي الشادر رسمها فعلًا — بيثبت إن الفلتر شغّال مش مجرد «متفعّل» */
    @JvmField val drawn = java.util.concurrent.atomic.AtomicLong(0)

    // ---------- الإعدادات ----------
    private val names = listOf("إيقاف", "خفيف", "متوسط", "قوي")

    fun level(): Int = Cfg.int(K_LEVEL, 0).coerceIn(0, 3)   // (v988) الافتراضي إيقاف
    fun levelName(): String = names[level()]
    fun vivid(): Boolean = Cfg.bool(K_VIVID, false)

    /** اختيار من الإعدادات: بيفتح الفلتر من جديد لو كان اتقفل تلقائيًا */
    fun setLevel(name: String) {
        val i = names.indexOf(name).let { if (it < 0) 1 else it }
        Cfg.put(K_LEVEL, i.toString())
        Cfg.put(K_OFF, "0")
        memOff = false
    }

    private val fakeNames = listOf("إيقاف", "خفيف", "متوسط", "قوي")
    fun fake(): Int = Cfg.int(K_FAKE, 0).coerceIn(0, 3)
    fun fakeName(): String = fakeNames[fake()]
    fun setFake(name: String) {
        val i = fakeNames.indexOf(name).let { if (it < 0) 0 else it }
        Cfg.put(K_FAKE, i.toString())
        Cfg.put(K_OFF, "0")
        memOff = false
    }
    // ---------- (v949) أوضاع جاهزة — سويتش واحد بين كل أوضاع تحسين الصورة ----------
    class Mode(val name: String, val level: Int, val vivid: Boolean, val fake: Int)
    val modes: List<Mode> = listOf(
        Mode("إيقاف", 0, false, 0),
        Mode("وضوح", 2, false, 0),
        Mode("ألوان", 1, true, 0),
        Mode("HDR", 0, false, 2),
        Mode("كامل", 2, true, 2)
    )
    /** رقم الوضع الجاهز المطابق للإعدادات الحالية، أو -1 لو الإعدادات مخصّصة من الإعدادات */
    fun modeIndex(): Int = modes.indexOfFirst { it.level == level() && it.vivid == vivid() && it.fake == fake() }
    fun modeName(): String = modeIndex().let { if (it < 0) "مخصّص" else modes[it].name }
    /** بيروح للوضع اللي بعده (من «مخصّص» بيروح لأول وضع شغّال) ويطبّقه على الفيديو الشغّال فورًا */
    fun cycleMode(): String {
        val i = (modeIndex() + 1) % modes.size
        val m = modes[i]
        Cfg.put(K_LEVEL, m.level.toString())
        Cfg.put(K_VIVID, if (m.vivid) "1" else "0")
        Cfg.put(K_FAKE, m.fake.toString())
        Cfg.put(K_OFF, "0")
        memOff = false
        reseek = true
        evaluate()
        reseek = false
        return m.name
    }
    private fun hdrFor(l: Int): Float = when (l) { 1 -> 0.35f; 2 -> 0.65f; 3 -> 1.0f; else -> 0f }

    /** (v948) سبب إن الفلتر مش شغّال على الفيديو الحالي (للإشعار)، أو "" لو شغّال */
    @Volatile var lastWhy: String = ""
        private set

    /** (v948) تطبيق تغيير الدرجة على الفيديو الشغّال دلوقتي (من زرار HDR جوه المشغّل) */
    fun refresh() { evaluate() }

    private fun sharpFor(l: Int): Float = when (l) { 1 -> 0.20f; 2 -> 0.45f; else -> 0.75f }

    // ---------- الربط بالمشغّل ----------
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { evaluate() }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { evaluate() }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { evaluate() }
        override fun onRenderedFirstFrame() { gotFrame = true }
        override fun onPlayerError(error: PlaybackException) {
            try {
                if (applied.isEmpty()) return
                var t: Throwable? = error
                var inPipeline = false
                var guard = 0
                while (t != null && guard++ < 8) { if (t is VideoFrameProcessingException) { inPipeline = true; break }; t = t.cause }
                if (!inPipeline) return
                LogStore.add("MtEnhance: خطأ في pipeline الفلتر — اتشال")
                persistOff()
                val p = player ?: return
                clear()
                try { p.prepare() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            try {
                if (applied.isEmpty()) return
                if (droppedFrames < 30 || elapsedMs > 4000L) return
                val now = android.os.SystemClock.elapsedRealtime()
                heavy = if (now - heavyAt > 15000L) 1 else heavy + 1
                heavyAt = now
                if (heavy >= 2) {
                    memOff = true
                    LogStore.add("MtEnhance: فريمات واقعة كتير ($droppedFrames في ${elapsedMs}ms) — الفلتر اتقفل لحد ما تقفل البرنامج")
                    clear()
                }
            } catch (_: Throwable) {}
        }
    }

    fun attach(activity: Activity, p: ExoPlayer, i: com.newtube.mobile.ui.playback.JoyboyPlayerHost.VideoInfo?) {
        try {
            Cfg.ensure(activity.applicationContext)
            if (player !== p) {
                detachListeners()
                applied = ""
                primed = false
                p.addListener(listener)
                p.addAnalyticsListener(analytics)
            }
            act = activity; player = p; info = i
            try {
                val k = try { i?.sourceKind() ?: "youtube" } catch (_: Throwable) { "youtube" }
                if (k != "youtube" || level() > 0 || fake() > 0) {
                    p.setVideoEffects(emptyList<Effect>())   // (v988) الـVideoSink بيتعمل في onEnabled بس لو القايمة مش null
                    // (v992) لو الريندرر لسه ما اتفعّلش (المشغّل IDLE) فالـsink هيتعمل من أول فيديو؛ غير كده هنعمل دورة للريندرر أول ما نفعّل الفلتر
                    if (p.playbackState == Player.STATE_IDLE) primed = true
                }
            } catch (_: Throwable) {}
            evaluate()
        } catch (e: Throwable) { LogStore.err("MtEnhance:attach", e) }
    }

    fun detach(activity: Activity) {
        try {
            if (act !== activity) return
            cancelWatch()
            detachListeners()
            player = null; act = null; info = null; applied = ""
        } catch (_: Throwable) {}
    }

    private fun detachListeners() {
        val p = player ?: return
        try { p.removeListener(listener) } catch (_: Throwable) {}
        try { p.removeAnalyticsListener(analytics) } catch (_: Throwable) {}
    }

    // ---------- القرار ----------
    private fun wanted(): Boolean {
        // (v992) يوتيوب كمان (قبل كده كان بيتقفل معاه — ودي كانت سبب «دوست ومفيش أي تغيير» على فيديوهات المشغّل)
        return !(memOff || Cfg.bool(K_OFF, false) || (level() == 0 && fake() == 0))
    }

    /** الفورمات المختار للفيديو (من التراكس الأول، ولو لسه، من فورمات الريندرر) */
    private fun videoFormat(p: ExoPlayer): Format? {
        try {
            for (g in p.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
            }
        } catch (_: Throwable) {}
        return try { p.videoFormat } catch (_: Throwable) { null }
    }

    private fun blocked(f: Format): String? {
        val ct = f.colorInfo?.colorTransfer ?: Format.NO_VALUE
        if (ct == C.COLOR_TRANSFER_ST2084 || ct == C.COLOR_TRANSFER_HLG) return "HDR"
        if (f.width > 0 && f.height > 0 && f.width.toLong() * f.height > MAX_PIXELS) return "أعلى من 1080p"
        if (f.frameRate > MAX_FPS) return "فريمات عالية"
        return null
    }

    private fun evaluate() {
        try {
            val p = player ?: return
            if (!wanted()) {
                lastWhy = if (memOff || Cfg.bool(K_OFF, false)) "اتقفل تلقائي" else ""
                clear(); return
            }
            val f = videoFormat(p) ?: return   // نستنى الفورمات (لو لسه مفيش، مانغيّرش حاجة)
            val why = blocked(f)
            if (why != null) {
                lastWhy = why
                if (applied.isNotEmpty()) LogStore.add("MtEnhance: اتشال — $why")
                clear(); return
            }
            lastWhy = ""
            val l = level(); val v = vivid()
            val fk = fake()
            val key = "$l/${if (v) 1 else 0}/$fk"
            if (key == applied) return
            val eff = SharpenEffect(if (l == 0) -1f else sharpFor(l), if (v) 1.08f else 1f, if (v) 1.04f else 1f, hdrFor(fk))
            p.setVideoEffects(listOf<Effect>(eff))
            applied = key
            if (!primed) {
                // (v992) الريندرر شغّال من غير VideoSink: نقفل مسار الفيديو لحظة ونفتحه عشان onEnabled يبني الـsink بالمؤثر
                primed = true
                cycleVideoRenderer(p)
            } else if (reseek) { try { p.seekTo(p.currentPosition) } catch (_: Throwable) {} }
            gotFrame = false
            LogStore.add("MtEnhance: شغّال $key (${f.width}×${f.height})")
            armWatch()
        } catch (e: Throwable) {
            LogStore.err("MtEnhance:evaluate", e)
            persistOff()
            clear()
        }
    }

    /** (v992) disable/enable لمسار الفيديو (~250ms) — بيعيد onEnabled للريندرر فيتبني الـVideoSink بالمؤثرات الحالية */
    private fun cycleVideoRenderer(p: ExoPlayer) {
        try {
            val params = p.trackSelectionParameters
            if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) return   // مسار الفيديو مقفول أصلًا (صوت بس) — الـsink هيتعمل لما يتفتح
            p.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            LogStore.add("MtEnhance: دورة ريندرر الفيديو (عشان الـVideoSink يتبني بالمؤثر)")
            ui.postDelayed({
                try {
                    val pp = player ?: return@postDelayed
                    pp.trackSelectionParameters = pp.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                } catch (_: Throwable) {}
            }, 250L)
        } catch (e: Throwable) { LogStore.err("MtEnhance:cycle", e) }
    }

    /** (v992) بعد ~1.8ث من التفعيل: بيرجّع نص بيقول الفلتر رسم فريمات فعلًا ولا لأ (للإشعار) */
    fun reportLater(cb: (String) -> Unit) {
        val base = drawn.get()
        ui.postDelayed({
            try {
                val n = drawn.get() - base
                val playing = player?.isPlaying == true
                val msg = when {
                    applied.isEmpty() -> ""
                    n > 0 -> "✓ الفلتر بيرسم ($n فريم)"
                    !playing -> "الفيديو واقف — شغّله عشان الفلتر يتشاف"
                    else -> "⚠ الفلتر متفعّل بس ما رسمش ولا فريم (السطح/الـpipeline) — شوف اللوج MtEnhance"
                }
                if (msg.isNotEmpty()) LogStore.add("MtEnhance: $msg")
                cb(msg)
            } catch (_: Throwable) {}
        }, 1800L)
    }

    private fun clear() {
        cancelWatch()
        if (applied.isEmpty()) return
        applied = ""
        try { player?.setVideoEffects(emptyList()) } catch (_: Throwable) {}
        if (reseek) { try { player?.let { it.seekTo(it.currentPosition) } } catch (_: Throwable) {} }
    }

    private fun persistOff() {
        try { Cfg.put(K_OFF, "1") } catch (_: Throwable) {}
        memOff = true
    }

    // ---------- حارس «أول فريم» ----------
    private fun armWatch() {
        cancelWatch()
        val r = Runnable {
            try {
                val p = player ?: return@Runnable
                if (applied.isEmpty() || gotFrame) return@Runnable
                val st = p.playbackState
                if (st == Player.STATE_READY && p.playWhenReady && videoFormat(p) != null) {
                    LogStore.add("MtEnhance: مفيش فريم ظهر بعد ${WATCH_MS}ms من تفعيل الفلتر — اتشال وانقفل")
                    persistOff()
                    clear()
                    try { p.seekTo(p.currentPosition) } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        watch = r
        ui.postDelayed(r, WATCH_MS)
    }

    private fun cancelWatch() {
        watch?.let { ui.removeCallbacks(it) }
        watch = null
    }
}

/** تأثير فيديو: CAS + (تشبّع/تباين). sharp من 0 لـ1. */
class SharpenEffect(private val sharp: Float, private val sat: Float, private val con: Float, private val hdr: Float = 0f) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SharpenProgram(useHdr, sharp, sat, con, hdr)
}

private class SharpenProgram(
    useHdr: Boolean,
    private val sharp: Float,
    private val sat: Float,
    private val con: Float,
    private val hdr: Float
) : BaseGlShaderProgram(useHdr, 1) {

    private val prog = GlProgram(VERT, FRAG)
    private var w = 1
    private var h = 1

    init {
        prog.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = if (inputWidth > 0) inputWidth else 1
        h = if (inputHeight > 0) inputHeight else 1
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            prog.use()
            prog.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            prog.setFloatUniform("uTx", 1f / w)
            prog.setFloatUniform("uTy", 1f / h)
            prog.setFloatUniform("uSharp", if (sharp < 0f) 0f else sharp)
            prog.setFloatUniform("uSharpOn", if (sharp < 0f) 0f else 1f)
            prog.setFloatUniform("uHdr", hdr)
            prog.setFloatUniform("uSat", sat)
            prog.setFloatUniform("uCon", con)
            prog.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            MtEnhance.drawn.incrementAndGet()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { prog.delete() } catch (_: Throwable) {}
    }

    private companion object {
        const val VERT = "attribute vec4 aFramePosition;\n" +
            "varying vec2 vTexSamplingCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aFramePosition;\n" +
            "  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n" +
            "}\n"

        const val FRAG = "precision highp float;\n" +
            "uniform sampler2D uTexSampler;\n" +
            "uniform float uTx;\n" +
            "uniform float uTy;\n" +
            "uniform float uSharp;\n" +
            "uniform float uSharpOn;\n" +
            "uniform float uSat;\n" +
            "uniform float uCon;\n" +
            "uniform float uHdr;\n" +
            "varying vec2 vTexSamplingCoord;\n" +
            "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n" +
            "void main() {\n" +
            "  vec2 uv = vTexSamplingCoord;\n" +
            "  vec3 c = texture2D(uTexSampler, uv).rgb;\n" +
            "  vec3 col = c;\n" +
            "  if (uSharpOn > 0.5) {\n" +
            "    vec3 n = texture2D(uTexSampler, uv + vec2(0.0, -uTy)).rgb;\n" +
            "    vec3 s = texture2D(uTexSampler, uv + vec2(0.0, uTy)).rgb;\n" +
            "    vec3 w = texture2D(uTexSampler, uv + vec2(-uTx, 0.0)).rgb;\n" +
            "    vec3 e = texture2D(uTexSampler, uv + vec2(uTx, 0.0)).rgb;\n" +
            "    vec3 mn = min(c, min(min(n, s), min(w, e)));\n" +
            "    vec3 mx = max(c, max(max(n, s), max(w, e)));\n" +
            "    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 0.0001), 0.0, 1.0));\n" +
            "    float peak = -1.0 / mix(8.0, 5.0, uSharp);\n" +
            "    vec3 wgt = amp * peak;\n" +
            "    col = clamp(((n + s + w + e) * wgt + c) / (1.0 + 4.0 * wgt), 0.0, 1.0);\n" +
            "  }\n" +
            "  if (uHdr > 0.0) {\n" +
            "    vec3 m = (texture2D(uTexSampler, uv + vec2(6.0 * uTx, 0.0)).rgb + texture2D(uTexSampler, uv + vec2(-6.0 * uTx, 0.0)).rgb +\n" +
            "              texture2D(uTexSampler, uv + vec2(0.0, 6.0 * uTy)).rgb + texture2D(uTexSampler, uv + vec2(0.0, -6.0 * uTy)).rgb) * 0.25;\n" +
            "    float lc = dot(col, LUMA);\n" +
            "    col += (lc - dot(m, LUMA)) * 0.45 * uHdr;\n" +
            "    lc = dot(col, LUMA);\n" +
            "    col *= 1.0 + 0.20 * uHdr * smoothstep(0.5, 1.0, lc);\n" +
            "    col = clamp(col, 0.0, 1.0);\n" +
            "    col = mix(col, col * col * (3.0 - 2.0 * col), 0.35 * uHdr);\n" +
            "    float l2 = dot(col, LUMA);\n" +
            "    col = mix(vec3(l2), col, 1.0 + 0.25 * uHdr);\n" +
            "  }\n" +
            "  float l = dot(col, LUMA);\n" +
            "  col = mix(vec3(l), col, uSat);\n" +
            "  col = clamp((col - 0.5) * uCon + 0.5, 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n"
    }
}
