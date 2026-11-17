@file:OptIn(UnstableApi::class)

package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.widget.Toast
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.newtube.mobile.ui.playback.JoyboyPlayerHost
import java.lang.ref.WeakReference

/**
 * (v946) سلاسة التشغيل من غير AI ومن غير تكلفة على المعالج:
 *  0) فتح الفريمات: كل شاشات البرنامج بتطلب أعلى معدل تحديث بيدعمه الجهاز (120 / 144 … حسب الشاشة) بدل ما أندرويد ينزل لـ60.
 *     طلب مش قفل — أندرويد لسه ممكن ينزّله لو الجهاز سخن أو موفّر البطارية شغّال. (مش على التلفزيون.)
 *  1) مطابقة معدل الشاشة مع فريمات الفيديو: 24/30 على 120Hz = ×5/×4 بالظبط، فمفيش رعشة (judder). لو مفيش معدل مضاعف بالظبط
 *     (25fps على 60/90/120 مثلًا) بيفضل على أعلى معدل.
 *  2) (تخفيف الزجاج اتشال في v988: في v987 الفيديو على SurfaceView فالزجاج لون بس أصلًا، ومنعًا لأي مساس بشكل الأزرار.)
 *  3) فك الترميز: اسم الـdecoder بيتسجّل في اللوج (MtSmooth)، ولو اتفك سوفتوير لفيديو تقيل بيظهر تنبيه واحد بالسبب.
 *     (Media3 أصلًا بيرتّب الهاردوير الأول؛ السوفتوير بيتختار بس لو الهاردوير مش بيدعم الصيغة أو فشل.)
 * Cfg: mt_hz (0 أعلى · -1 النظام · رقم ثابت) · mt_hz_match (افتراضي 1) · mt_hw_warn (افتراضي 1).
 */
object MtSmooth {
    const val K_HZ = "mt_hz"
    const val K_MATCH = "mt_hz_match"
    const val K_HW_WARN = "mt_hw_warn"

    private val ui = Handler(Looper.getMainLooper())
    private var installed = false
    private var cur: WeakReference<Activity>? = null

    // حالة مشغّل MeeTube الحالي
    private var act: Activity? = null
    private var player: ExoPlayer? = null
    private var matchRate = 0f
    private var matchMode = 0
    private var matchKey = ""
    private var playing = false
    private var warned = ""

    // ---------- شاشات العرض ----------
    private fun display(ctx: Context): Display? = try {
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    } catch (_: Throwable) { null }

    /** أوضاع الشاشة بنفس الدقة الحالية (من غير تغيير دقة) */
    private fun modes(ctx: Context): List<Display.Mode> {
        val d = display(ctx) ?: return emptyList()
        return try {
            val c = d.mode
            d.supportedModes.filter { it.physicalWidth == c.physicalWidth && it.physicalHeight == c.physicalHeight }
        } catch (_: Throwable) { emptyList() }
    }

    fun rates(ctx: Context): List<Int> {
        val r = modes(ctx).map { Math.round(it.refreshRate) }.distinct().sorted()
        if (r.isNotEmpty()) return r
        val f = try { display(ctx)?.refreshRate ?: 60f } catch (_: Throwable) { 60f }
        return listOf(Math.round(f))
    }

    fun topRate(ctx: Context): Int = rates(ctx).maxOrNull() ?: 60

    // ---------- الإعدادات ----------
    fun hzChoices(ctx: Context): List<String> {
        val r = rates(ctx); val top = r.maxOrNull() ?: 60
        return listOf("أعلى ($top)") + r.filter { it < top && it >= 50 }.sortedDescending().map { it.toString() } + listOf("النظام")
    }

    fun hzLabel(ctx: Context): String {
        val hz = Cfg.int(K_HZ, 0)
        return when {
            hz < 0 -> "النظام"
            hz == 0 -> "أعلى (${topRate(ctx)})"
            else -> hz.toString()
        }
    }

    fun setHz(ctx: Context, label: String) {
        val v = when {
            label.startsWith("أعلى") -> 0
            label == "النظام" -> -1
            else -> label.trim().toIntOrNull() ?: 0
        }
        Cfg.put(K_HZ, v.toString())
        try { (cur?.get() ?: act)?.let { refresh(it) } } catch (_: Throwable) {}
        try { evalMatch() } catch (_: Throwable) {}
    }

    fun matchOn(): Boolean = Cfg.bool(K_MATCH, true)
    fun hwWarnOn(): Boolean = Cfg.bool(K_HW_WARN, true)

    // ---------- 0) فتح الفريمات في كل الشاشات ----------
    /** المعدل المطلوب للبرنامج عمومًا: 0f = سيب القرار لأندرويد */
    private fun appRate(ctx: Context): Float {
        val hz = Cfg.int(K_HZ, 0)
        val rs = rates(ctx)
        return when {
            hz < 0 -> 0f
            hz == 0 -> (rs.maxOrNull() ?: 60).toFloat()
            else -> (rs.minByOrNull { Math.abs(it - hz) } ?: 60).toFloat()
        }
    }

    private fun setWindow(a: Activity, rate: Float, modeId: Int) {
        val w = a.window ?: return
        val lp = w.attributes
        var changed = false
        if (lp.preferredRefreshRate != rate) { lp.preferredRefreshRate = rate; changed = true }
        if (lp.preferredDisplayModeId != modeId) { lp.preferredDisplayModeId = modeId; changed = true }
        if (changed) w.attributes = lp
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                w.setFrameRatePowerSavingsBalanced(rate <= 0f)
                w.decorView.setRequestedFrameRate(if (rate > 0f) rate else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
            } catch (_: Throwable) {}
        }
    }

    /** بيطبّق المعدل على نافذة شاشة (رخيص — بيتنده كل resume) */
    fun refresh(a: Activity) {
        try {
            if (DeviceUi.isTv(a)) return
            if (a === act && matchMode != 0) { setWindow(a, matchRate, matchMode); return }
            if (Cfg.int(K_HZ, 0) < 0) return   // «النظام»: مانلمسش (إعداد MeeTube نفسه ممكن يكون ماسك الشاشة)
            setWindow(a, appRate(a), 0)
        } catch (_: Throwable) {}
    }

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                cur = WeakReference(a)
                // post: onResume بتاع MeeTube (HighRefresh.apply) بيشتغل قبلنا، فنطبّق بعده
                ui.post { try { Cfg.ensure(a.applicationContext); refresh(a) } catch (_: Throwable) {} }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // ---------- الربط بمشغّل MeeTube ----------
    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { evalMatch() }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { evalMatch() }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { evalMatch() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            try { checkDecoder(decoderName) } catch (e: Throwable) { LogStore.err("MtSmooth:decoder", e) }
        }
    }

    fun attach(activity: Activity, p: ExoPlayer, info: JoyboyPlayerHost.VideoInfo?) {
        try {
            Cfg.ensure(activity.applicationContext)
            if (player !== p) {
                detachListeners()
                p.addListener(listener)
                p.addAnalyticsListener(analytics)
                warned = ""
            }
            act = activity; player = p
            playing = try { p.isPlaying } catch (_: Throwable) { false }
            evalMatch()
        } catch (e: Throwable) { LogStore.err("MtSmooth:attach", e) }
    }

    fun detach(activity: Activity) {
        try {
            if (act !== activity) return
            detachListeners()
            matchRate = 0f; matchMode = 0; matchKey = ""
            player = null; act = null
            refresh(activity)   // رجّع المعدل العام للبرنامج
        } catch (_: Throwable) {}
    }

    private fun detachListeners() {
        val p = player ?: return
        try { p.removeListener(listener) } catch (_: Throwable) {}
        try { p.removeAnalyticsListener(analytics) } catch (_: Throwable) {}
    }

    private fun videoFormat(p: ExoPlayer): Format? {
        try {
            for (g in p.currentTracks.groups) {
                if (g.type != androidx.media3.common.C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
            }
        } catch (_: Throwable) {}
        return try { p.videoFormat } catch (_: Throwable) { null }
    }

    // ---------- 1) مطابقة معدل الشاشة مع فريمات الفيديو ----------
    private fun evalMatch() {
        try {
            val a = act ?: return
            val p = player ?: return
            if (DeviceUi.isTv(a)) return
            if (!matchOn() || Cfg.int(K_HZ, 0) != 0) { clearMatch(a); return }   // مطابقة بس لما المعدل «أعلى»
            val f = videoFormat(p) ?: return
            val fps = f.frameRate
            if (!(fps > 1f)) { clearMatch(a); return }
            var best: Display.Mode? = null
            var bestK = 0
            for (m in modes(a)) {
                val r = m.refreshRate
                val k = Math.round(r / fps)
                if (k < 1) continue
                if (Math.abs(r - k * fps) <= 0.5f && (best == null || r > best.refreshRate)) { best = m; bestK = k }
            }
            if (best == null) {
                if (matchMode != 0) LogStore.add("MtSmooth: ${fmt(fps)}fps — مفيش معدل شاشة مضاعف بالظبط، الشاشة على أعلى معدل")
                clearMatch(a); return
            }
            matchRate = best.refreshRate; matchMode = best.modeId
            val key = "${fmt(fps)}/${best.modeId}"
            if (key != matchKey) {
                matchKey = key
                LogStore.add("MtSmooth: فيديو ${fmt(fps)}fps ← شاشة ${fmt(best.refreshRate)}Hz (×$bestK)")
            }
            setWindow(a, matchRate, matchMode)
        } catch (e: Throwable) { LogStore.err("MtSmooth:match", e) }
    }

    private fun clearMatch(a: Activity) {
        if (matchMode == 0 && matchRate == 0f) { return }
        matchRate = 0f; matchMode = 0; matchKey = ""
        refresh(a)
    }

    private fun fmt(v: Float): String = if (Math.abs(v - Math.round(v)) < 0.01f) Math.round(v).toString() else String.format(java.util.Locale.US, "%.2f", v)

    // ---------- 2) فك الترميز: هاردوير ولا سوفتوير ----------
    private fun isSoftware(ci: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return ci.isSoftwareOnly || !ci.isHardwareAccelerated
        return softName(ci.name)
    }

    private fun softName(name: String): Boolean {
        val n = name.lowercase(java.util.Locale.ROOT)
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") ||
            n.contains(".sw.") || n.endsWith(".sw") || n.contains("ffmpeg") || n.contains("dav1d") || n.contains("libgav1")
    }

    private fun codecLabel(mime: String?): String = when {
        mime == null -> "?"
        mime.equals("video/avc", true) -> "H.264"
        mime.equals("video/hevc", true) -> "HEVC"
        mime.equals("video/av01", true) -> "AV1"
        mime.equals("video/x-vnd.on2.vp9", true) -> "VP9"
        mime.equals("video/x-vnd.on2.vp8", true) -> "VP8"
        else -> mime.removePrefix("video/")
    }

    /** فيه decoder هاردوير في الجهاز بيدعم الصيغة والمقاس دول؟ */
    private fun hasHardware(mime: String, w: Int, h: Int, fps: Float): Boolean {
        return try {
            for (ci in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                if (ci.isEncoder || isSoftware(ci)) continue
                if (ci.supportedTypes.none { it.equals(mime, true) }) continue
                val vc = ci.getCapabilitiesForType(mime).videoCapabilities ?: continue
                val ok = if (w > 0 && h > 0) (if (fps > 1f) vc.areSizeAndRateSupported(w, h, fps.toDouble()) else vc.isSizeSupported(w, h)) else true
                if (ok) return true
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun checkDecoder(name: String) {
        val a = act ?: return
        val p = player ?: return
        val f = videoFormat(p)
        val mime = f?.sampleMimeType
        val w = f?.width ?: 0
        val h = f?.height ?: 0
        val fps = f?.frameRate ?: 0f
        val sw = try {
            if (Build.VERSION.SDK_INT >= 29) {
                val ci = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name.equals(name, true) }
                if (ci != null) isSoftware(ci) else softName(name)
            } else softName(name)
        } catch (_: Throwable) { softName(name) }
        val desc = "${codecLabel(mime)} ${w}×${h}" + (if (fps > 1f) " ${fmt(fps)}fps" else "")
        LogStore.add("MtSmooth: decoder=$name (${if (sw) "سوفتوير" else "هاردوير"}) — $desc")
        if (!sw || !hwWarnOn()) return
        val heavyVideo = w.toLong() * h >= 1_100_000L || fps > 31f
        if (!heavyVideo) return
        val key = "$name/$mime/${w}x$h"
        if (key == warned) return
        warned = key
        val hw = mime != null && hasHardware(mime, w, h, fps)
        val why = if (hw) "فيه هاردوير في جهازك للصيغة دي بس ماشتغلش معاها (اتفك سوفتوير بدله)."
                  else "جهازك مفيهوش فك هاردوير للصيغة دي (${codecLabel(mime)})."
        LogStore.add("MtSmooth: تنبيه — فك سوفتوير لفيديو تقيل: $why")
        ui.post {
            try { Toast.makeText(a, "⚠ الفيديو بيتفك بالمعالج (سوفتوير) — ممكن يقطّع ويسخّن الجهاز.\n$why", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        }
    }
}
