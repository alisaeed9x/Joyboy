package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
import com.newtube.mobile.ui.playback.JoyboyPlayerHost
import java.io.File
import kotlin.math.roundToInt

/**
 * (v937، المرحلة 3 من دمج المشغّل) أدوات الميديا فوق مشغّل MeeTube للملفات/اللينكات المحلية:
 *  - حلقة A-B (+ كبسولة في صف الأزرار بتظهر لما تكون شغّالة)
 *  - وضع التكرار (عادي / نفس الفيديو / كل فولدر المكتبة) — بنفس مفاتيح Cfg بتاعة مشغّل Joyboy (loop / autoplay_wrap)
 *  - الحلقات: ⏮ ⏭ والتالي عند النهاية من فولدر المكتبة (VideoScan.cache) + طابور التشغيل (PlayQueue)
 *  - الاستكمال: lastpos + «آخر المشغّلة» (recent.json) بنفس صيغة مشغّل Joyboy
 * (v942) تضخيم الصوت فوق 100% (LoudnessEnhancer على جلسة الصوت، Cfg vol_boost) ومؤقت نوم Joyboy
 * بخفوت 10 ثوانٍ (MeeTube عنده SleepTimer من غير خفوت) والاستكمال بيتكتب كمان في VideoStateService
 * بتاع MeeTube بنفس مفتاح joy_local_ — وlastpos وrecent.json لسه المصدر الاحتياطي.
 * الأزرار والاستكمال خاملة لو الفيديو يوتيوب (localUri == null) ومابتعملش حاجة للمتخفي (تاريخ/استكمال) — بس الصوت والتضخيم ومؤقت النوم بيشتغلوا على أي مشغّل.
 * بيتنده من MtTranslate.attach/detach (نفس دورة حياة المشغّل) و Provider.onStep.
 */
object MtMedia {
    private const val TAG_AB = "joy_ab_pill"
    private val ui = Handler(Looper.getMainLooper())
    private var act: Activity? = null
    private var player: ExoPlayer? = null
    private var info: JoyboyPlayerHost.VideoInfo? = null
    private var options: ViewGroup? = null
    private var pill: TextView? = null
    private var key: String? = null
    private var abA = -1L
    private var abB = -1L
    private var resumed = false
    private var ended = false
    private var ticking = false
    private var lastPosSave = 0L
    private var lastRecentSave = 0L
    private var loud: LoudnessEnhancer? = null
    private var sleepAtMs = 0L
    private var sleepEndOfVideo = false
    private var fading = false
    private var fadeStartVol = 1f
    private var fadeStartMs = 0L
    private var preFadeVol = 1f
    @Volatile private var scanning = false

    /** «L:<لينك>» لفيديوهات اتفتحت بـstartms صريح (ما نكمّلش من الاستكمال بتاعنا) */
    val noResume: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun lu(): String? = try { info?.localUri()?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
    private fun priv(): Boolean = lu()?.let { MtTranslate.privIds.contains("L:$it") } == true
    private fun isWeb(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)
    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    // ===================== دورة الحياة =====================

    fun attach(a: Activity, p: ExoPlayer, opts: ViewGroup?, i: JoyboyPlayerHost.VideoInfo) {
        try {
            val newKey = try { i.localUri()?.takeIf { it.isNotBlank() } ?: i.videoId() } catch (_: Throwable) { null }
            if (act !== a || key != newKey) { abA = -1L; abB = -1L; resumed = false; ended = false }
            act = a; info = i; options = opts; key = newKey
            if (player !== p) { try { player?.removeListener(listener) } catch (_: Throwable) {}; p.addListener(listener); player = p }
            opts?.findViewWithTag<View>(TAG_AB)?.let { opts.removeView(it) }
            pill = null
            refreshPill()
            applyLoop(false)
            applyBoost()
            if (!ticking) { ticking = true; ui.post(tick) }
            if (p.playbackState == Player.STATE_READY) tryResume()
            try { if (!priv()) MtWeb.attach(a, p, lu()) } catch (_: Throwable) {}   // (v938) لقطة مصغّرة للمصطادة
        } catch (e: Throwable) { LogStore.err("MtMedia:attach", e) }
    }

    fun detach(a: Activity) {
        try {
            if (act !== a) return
            saveNow(true)
            val wasLu = lu()
            val wasVid = try { info?.videoId() } catch (_: Throwable) { null }   // (v979)
            ui.removeCallbacks(tick); ticking = false
            try { player?.removeListener(listener) } catch (_: Throwable) {}
            releaseBoost()
            // (v942) مؤقت النوم بيفضل شغال وبيكمّل على المشغّل الجديد — التضخيم بس اللي بيتفك لأنه مربوط بجلسة الصوت القديمة
            (pill?.parent as? ViewGroup)?.removeView(pill)
            pill = null; player = null; options = null; info = null; act = null; key = null
            abA = -1L; abB = -1L
            // (v938) extra ret: رجوع للمتصفح/يوتيوب عند الإغلاق الفعلي
            // (v939) لو مفيش ret للمصطادة، MtOrigin بيرجّع حسب المصدر (واتساب/محلي ← ميديا Joyboy)
            try {
                val webRet = MtWeb.onDetach(a, wasLu)
                if (!webRet) MtOrigin.onDetach(a, wasLu, wasVid)
            } catch (_: Throwable) {}
        } catch (e: Throwable) { LogStore.err("MtMedia:detach", e) }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) ended = false
            if (state == Player.STATE_READY) tryResume()
            if (state == Player.STATE_ENDED) {
                // (v942) نوم «آخر الفيديو» لفيديو مش محلي: onEnded بيرجع بدري لما localUri فاضي فبنوقّف هنا
                if (sleepEndOfVideo && lu() == null) {
                    sleepEndOfVideo = false
                    sleepAtMs = 0L
                    try { player?.pause() } catch (_: Throwable) {}
                    act?.let { Notice.show(it, "😴 مؤقت النوم: الفيديو وقف", 2200L) }
                }
                onEnded()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveNow(false) }
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            try { loud?.release() } catch (_: Throwable) {}
            loud = null
            applyBoost()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try {
                val p = player
                if (p != null) {
                    if (abA >= 0L && abB > abA && p.isPlaying && p.currentPosition >= abB) p.seekTo(abA)
                    if (sleepAtMs > 0L || fading) sleepStep(p)
                    if (p.isPlaying) {
                        val now = System.currentTimeMillis()
                        if (now - lastPosSave >= 3000L) { lastPosSave = now; saveNow(false) }
                        if (now - lastRecentSave >= 20000L) { lastRecentSave = now; saveNow(true) }
                    }
                }
            } catch (t: Throwable) { LogStore.err("MtMedia:tick", t) }
            ui.postDelayed(this, 250L)
        }
    }

    // ===================== حلقة A-B =====================

    /** نص بند القايمة: بيوصف الخطوة الجاية (أ ← ب ← إلغاء) */
    fun abLabel(): String = when {
        abA < 0L -> "🅰 حلقة A-B: نقطة أ هنا"
        abB <= abA -> "🅱 نقطة ب هنا (أ = " + PlayerLogic.clock(abA) + ")"
        else -> "✖ إلغاء حلقة A-B (" + PlayerLogic.clock(abA) + " ← " + PlayerLogic.clock(abB) + ")"
    }

    /** (v939) حالة A-B بصيغة حقل rState بتاع الريموت: "" أو "A 01:23" أو "A 01:23 ← B 02:34" */
    fun abState(): String = when {
        abA < 0L -> ""
        abB <= abA -> "A " + PlayerLogic.clock(abA)
        else -> "A " + PlayerLogic.clock(abA) + " ← B " + PlayerLogic.clock(abB)
    }

    fun abNext() {
        val a = act ?: return
        val p = player ?: return
        val pos = try { p.currentPosition } catch (_: Throwable) { return }
        when {
            abA < 0L -> { abA = pos; abB = -1L; Notice.show(a, "🅰 نقطة أ: " + PlayerLogic.clock(abA) + " — عيّن نقطة ب من نفس القايمة", 2400L) }
            abB <= abA -> {
                if (pos <= abA) { Notice.show(a, "🅱 نقطة ب لازم تكون بعد أ", 2200L); return }
                abB = pos; Notice.show(a, "🔁 حلقة A-B شغالة: " + PlayerLogic.clock(abA) + " ← " + PlayerLogic.clock(abB), 2400L)
            }
            else -> { abClear(); return }
        }
        refreshPill()
    }

    fun abClear() {
        val had = abA >= 0L || abB >= 0L
        abA = -1L; abB = -1L
        if (had) act?.let { Notice.show(it, "✖ حلقة A-B اتلغت", 1600L) }
        refreshPill()
    }

    private fun refreshPill() {
        val a = act ?: return
        val o = options ?: return
        if (abA < 0L) { (pill?.parent as? ViewGroup)?.removeView(pill); pill = null; return }
        val label = if (abB > abA) "🔁 A-B ✖" else "🅰 " + PlayerLogic.clock(abA)
        val existing = pill
        if (existing != null) { existing.text = label; return }
        val t = TextView(a).apply {
            tag = TAG_AB; text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(a, 12), 0, dp(a, 12), 0)
            background = GradientDrawable().apply { cornerRadius = dp(a, 18).toFloat(); setColor(0xCCE53935.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 36)).apply { marginEnd = dp(a, 6) }
            setOnClickListener { if (abB > abA) abClear() else abNext() }
        }
        o.addView(t, 0)
        pill = t
    }

    // ===================== وضع التكرار =====================

    /** 0 عادي (التالي لو فيه وبعدها يقف) · 1 يعيد نفس الفيديو · 2 يكمّل الفولدر ولما يخلص يرجع لأول فيديو — نفس Player.loopMode */
    fun loopMode(): Int = try { if (Cfg.str("loop", "0") == "1") 1 else if (Cfg.str("autoplay_wrap", "0") == "1") 2 else 0 } catch (_: Throwable) { 0 }
    fun loopLabel(): String = when (loopMode()) { 1 -> "🔂 نفس الفيديو"; 2 -> "🔁 كل الفولدر"; else -> "➡ عادي" }

    fun loopCycle() {
        val a = act ?: return
        val n = (loopMode() + 1) % 3
        Cfg.put("loop", if (n == 1) "1" else "0"); Cfg.put("autoplay_wrap", if (n == 2) "1" else "0")
        applyLoop(true)
        Notice.show(a, when (n) { 1 -> "🔂 تكرار نفس الفيديو"; 2 -> "🔁 تكرار كل فيديوهات الفولدر"; else -> "➡ تشغيل عادي" }, 1800L)
    }

    /** «نفس الفيديو» = REPEAT_MODE_ONE على ExoPlayer؛ ما بنلمسش وضع MeeTube نفسه إلا لما إحنا اللي ضبطناه */
    private fun applyLoop(force: Boolean) {
        if (lu() == null) return
        val p = player ?: return
        try {
            if (loopMode() == 1) p.repeatMode = Player.REPEAT_MODE_ONE
            else if (force && p.repeatMode == Player.REPEAT_MODE_ONE) p.repeatMode = Player.REPEAT_MODE_OFF
        } catch (_: Throwable) {}
    }

    // ===================== الصوت: تضخيم + سلايدر 0..200 =====================

    /** التضخيم الحالي من Cfg vol_boost (0..100) — نفس مفتاح مشغّل Joyboy */
    fun boostPct(): Int = try { (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0).coerceIn(0, 100) } catch (_: Throwable) { 0 }

    fun setBoostPct(b: Int) {
        try { Cfg.put("vol_boost", b.coerceIn(0, 100).toString()) } catch (_: Throwable) {}
        applyBoost()
    }

    /** بيعيد تطبيق التضخيم من Cfg على جلسة الصوت الحالية */
    fun applyBoost() {
        val pct = boostPct()
        try {
            val p = player ?: return
            val sid = try { p.audioSessionId } catch (_: Throwable) { return }
            if (sid == C.AUDIO_SESSION_ID_UNSET || sid == 0) return
            if (loud == null) loud = LoudnessEnhancer(sid)
            loud?.setTargetGain(pct * 15)
            loud?.enabled = pct > 0
        } catch (e: Throwable) { LogStore.err("MtMedia:boost", e) }
    }

    private fun releaseBoost() {
        try { loud?.release() } catch (_: Throwable) {}
        loud = null
    }

    /** سلايدر موحّد 0..200: لحد 100 صوت عادي، وفوق 100 التضخيم (والصوت على الآخر) — نفس فكرة Player.volTotal */
    fun setVolumePct(v: Int) {
        val p = player ?: return
        val vv = v.coerceIn(0, 200)
        try {
            if (vv <= 100) {
                p.volume = vv / 100f
                if (boostPct() != 0) setBoostPct(0)
            } else {
                p.volume = 1f
                setBoostPct(vv - 100)
            }
        } catch (e: Throwable) { LogStore.err("MtMedia:volume", e) }
    }

    /** القيمة الحالية المجمّعة: صوت المشغّل × 100 + التضخيم */
    fun volumePct(): Int {
        val vol = try { player?.volume } catch (_: Throwable) { null } ?: return boostPct()
        return (vol * 100f).roundToInt().coerceIn(0, 100) + boostPct()
    }

    // ===================== مؤقت النوم =====================

    /** min > 0 = بعد كذا دقيقة (5/10/15/30/45/60) · -1 = لما الفيديو يخلص · 0 = إلغاء — نفس واجهة Player.setSleepTimer */
    fun setSleep(min: Int) {
        val a = act
        val hadSleep = sleepAtMs > 0L || sleepEndOfVideo || fading
        sleepAtMs = 0L
        sleepEndOfVideo = false
        if (fading) {
            fading = false
            try { player?.volume = preFadeVol } catch (_: Throwable) {}
        }
        if (min == 0) {
            if (hadSleep) a?.let { Notice.show(it, "😴 مؤقت النوم اتلغى", 1800L) }
            return
        }
        if (min < 0) {
            sleepEndOfVideo = true
            a?.let { Notice.show(it, "😴 الفيديو هيقف لما يخلص (من غير ما يكمّل اللي بعده)", 2200L) }
            return
        }
        sleepAtMs = System.currentTimeMillis() + min * 60_000L
        a?.let { Notice.show(it, "😴 الفيديو هيقف بعد $min دقيقة (والصوت بينزل بهدوء)", 2200L) }
    }

    fun cancelSleep() { setSleep(0) }

    fun sleepLabel(): String = when {
        sleepEndOfVideo -> "آخر الفيديو"
        sleepAtMs > 0L -> {
            val left = ((sleepAtMs - System.currentTimeMillis() + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            "$left دقيقة"
        }
        else -> "مقفول"
    }

    /** بيتنده من الـtick كل 250ms: لما المعاد ييجي، الصوت بينزل من قيمته الحالية لصفر في 10 ثوانٍ وبعدها إيقاف */
    private fun sleepStep(p: ExoPlayer) {
        try {
            if (!fading) {
                val left = sleepAtMs - System.currentTimeMillis()
                if (left > 0L) return
                fading = true
                fadeStartMs = System.currentTimeMillis()
                fadeStartVol = try { p.volume } catch (_: Throwable) { 1f }
                preFadeVol = fadeStartVol
            }
            val el = System.currentTimeMillis() - fadeStartMs
            if (el >= 10_000L) {
                fading = false
                sleepAtMs = 0L
                try { p.volume = 0f } catch (_: Throwable) {}
                try { p.pause() } catch (_: Throwable) {}
                try { p.volume = preFadeVol } catch (_: Throwable) {}
                act?.let { Notice.show(it, "😴 مؤقت النوم: الفيديو وقف", 2200L) }
                return
            }
            val frac = 1f - el / 10_000f
            try { p.volume = (fadeStartVol * frac).coerceIn(0f, 1f) } catch (_: Throwable) {}
        } catch (t: Throwable) { LogStore.err("MtMedia:sleep", t) }
    }

    // ===================== الحلقات =====================

    private fun sibs(): Pair<List<VideoItem>, Int>? {
        val u = lu() ?: return null
        val all = VideoScan.cache ?: return null
        val me = all.firstOrNull { it.uri == u } ?: return null
        val sib = VideoLib.sortVideos(all.filter { it.folderKey == me.folderKey }, VideoLib.SORT_NAME)
        return Pair(sib, sib.indexOfFirst { it.uri == u })
    }

    private fun targetOf(d: Int): VideoItem? {
        val s = sibs() ?: return null
        s.first.getOrNull(s.second + d)?.let { return it }
        if (loopMode() == 2 && s.first.size > 1) return if (d > 0) s.first.first() else s.first.last()
        return null
    }

    private fun ensureScan(a: Activity, then: (() -> Unit)? = null) {
        if (VideoScan.cache != null || scanning || !VideoScan.hasPermission(a)) return
        scanning = true
        val app = a.applicationContext
        Thread {
            try { VideoScan.scan(app) } catch (_: Throwable) {}
            scanning = false
            if (then != null) ui.post { then() }
        }.apply { isDaemon = true }.start()
    }

    private fun openItem(a: Activity, t: VideoItem) {
        try {
            val wasPriv = priv()
            saveNow(true)
            Cfg.put("lib_last", t.uri); Cfg.put("lib_last_f:" + t.folderKey, t.uri)
            Notice.show(a, "▶ " + t.title, 1800L)
            val v = Video()
            v.videoId = "joy_local_" + Integer.toHexString(t.uri.hashCode())
            v.title = t.title
            v.localUri = t.uri
            if (wasPriv) { MtTranslate.privIds.add("L:" + t.uri); v.incognito = true }
            PlaybackPresenter.instance(a.applicationContext).openVideo(v)
        } catch (e: Throwable) { LogStore.err("MtMedia:open", e) }
    }

    /** بند القايمة: ⏮ (-1) / ⏭ (+1) — بيقول للمستخدم لو مفيش */
    fun step(d: Int) { onStep(act ?: return, d, true) }

    /** من زراري ⏮ ⏭ في MeeTube (Provider.onStep). true = اتعاملنا معاها */
    fun onStep(a: Activity, d: Int, notify: Boolean = false): Boolean {
        if (act !== a || lu() == null) return false
        if (VideoScan.cache == null) {   // المكتبة لسه ما اتفحصتش: نفحصها وبعدين ننفّذ (لو مفيش صلاحية ← MeeTube يتصرّف عادي)
            if (!VideoScan.hasPermission(a)) return false
            ensureScan(a) { onStep(a, d, true) }; return true
        }
        if (sibs() == null) { if (notify) Notice.show(a, "الفيديو ده مش من مكتبة الجهاز", 2000L); return notify }
        val t = targetOf(d)
        if (t == null) { Notice.show(a, if (d > 0) "دي آخر حلقة في الفولدر" else "دي أول حلقة في الفولدر", 1800L); return true }
        openItem(a, t)
        return true
    }

    private fun onEnded() {
        val a = act ?: return
        if (lu() == null || ended) return
        ended = true
        saveNow(true)
        // (v942) نوم «آخر الفيديو» بتاع Joyboy: إيقاف من غير ما يكمّل اللي بعده — زي فحص endOfVideoSleep بتاع MeeTube اللي تحته
        if (sleepEndOfVideo) {
            sleepEndOfVideo = false
            sleepAtMs = 0L
            try { player?.pause() } catch (_: Throwable) {}
            Notice.show(a, "😴 مؤقت النوم: الفيديو وقف", 2200L)
            return
        }
        try { if (info?.endOfVideoSleep() == true) return } catch (_: Throwable) {}   // مؤقت النوم «آخر الفيديو» — MeeTube بيوقف لوحده
        try {
            val pm = info?.playbackMode() ?: 2
            if (pm == 0 || pm == 1) return
            if (pm == 3) return
        } catch (_: Throwable) {}
        if (loopMode() == 1) { try { player?.seekTo(0L); player?.play() } catch (_: Throwable) {}; return }
        try { if (PlayQueue.hasNext(a)) { PlayQueue.playNext(a); return } } catch (e: Throwable) { LogStore.err("MtMedia:queue", e) }   // طابور التشغيل له الأولوية (زي Player v694)
        val go: () -> Unit = {
            val t = targetOf(1)
            if (t != null && act === a) {
                val goNow = Runnable { openItem(a, t) }
                if (Cfg.str("autoplay_next", "1") != "1") goNow.run()
                else {
                    val nv = Video(); nv.title = t.title
                    val shown = try { (a as? com.newtube.mobile.ui.playback.MobilePlaybackActivity)?.showNextCountdown(nv, goNow) ?: false } catch (_: Throwable) { false }
                    if (!shown) goNow.run()
                }
            }
        }
        if (VideoScan.cache == null) ensureScan(a, go) else go()
    }

    // ===================== الاستكمال + «آخر المشغّلة» =====================

    private fun tryResume() {
        if (resumed) return
        val a = act ?: return
        val p = player ?: return
        val u = lu() ?: return
        resumed = true
        if (priv() || noResume.remove("L:$u")) return
        val dur = try { p.duration } catch (_: Throwable) { -1L }
        if (dur <= 0L || p.currentPosition > 3000L) return
        val k = key
        Thread {
            val pos = savedPosMs(a, u)
            if (pos > 5000L && pos < dur - 4000L) ui.post {
                if (act === a && key == k) try {
                    if (p.currentPosition <= 3000L) { p.seekTo(pos); Notice.show(a, "⏩ كملت من " + PlayerLogic.clock(pos), 2000L) }
                } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true }.start()
    }

    private fun savedPosMs(a: Activity, u: String): Long {
        val id = MtTranslate.joyVid(a, u)
        try {
            val s = Cfg.p.getString("lastpos", "") ?: ""
            val i = s.indexOf('\t')
            if (i > 0 && s.substring(0, i) == id) { val v = s.substring(i + 1).toLongOrNull() ?: 0L; if (v > 5000L) return v }
        } catch (_: Throwable) {}
        try {
            val r = Recents.parse(File(a.filesDir, "recent.json").readText()).firstOrNull { it.id == id }
            if (r != null && r.posSec > 5.0) return (r.posSec * 1000).toLong()
        } catch (_: Throwable) {}
        return 0L
    }

    /** (v942) write-through للاستكمال في VideoStateService بتاع MeeTube (مخزنه المحلي اللي VideoStateController بيقراه في restorePosition) */
    private fun writeMtState(a: Activity, id: String, pos: Long, dur: Long) {
        try {
            if (pos <= 1000L || pos >= dur - 4000L) return
            val v = Video()
            v.videoId = id
            v.title = (try { info?.title() } catch (_: Throwable) { null })?.takeIf { it.isNotBlank() } ?: Recents.titleOf(id)
            val lu = lu()
            if (lu != null) v.localUri = lu
            val speed = try { player?.playbackParameters?.speed ?: 1f } catch (_: Throwable) { 1f }
            VideoStateService.instance(a.applicationContext)?.save(VideoStateService.State(v, pos, dur, speed))
        } catch (t: Throwable) { LogStore.err("MtMedia:mtstate", t) }
    }

    /** على الـUI thread بس. recent=true بيكتب كمان «آخر المشغّلة» (recent.json) بنفس صيغة Player.saveRecentForce */
    private fun saveNow(recent: Boolean) {
        val a = act ?: return
        val p = player ?: return
        val u = lu() ?: return
        if (priv()) return
        val pos = try { p.currentPosition } catch (_: Throwable) { return }
        val dur = try { p.duration } catch (_: Throwable) { return }
        if (dur <= 0L) return
        val id = MtTranslate.joyVid(a, u)
        try {
            if (pos > 1000L && pos < dur - 4000L) Cfg.p.edit().putString("lastpos", id + "\t" + pos).apply()
            else if (pos >= dur - 4000L && (Cfg.p.getString("lastpos", "") ?: "").startsWith(id + "\t")) Cfg.p.edit().remove("lastpos").apply()
        } catch (_: Throwable) {}
        // (v942) write-through للاستكمال في مخزن MeeTube المحلي بنفس المفتاح — وlastpos لسه المصدر الاحتياطي
        try { writeMtState(a, id, pos, dur) } catch (_: Throwable) {}
        if (!recent || pos <= 1000L) return
        val title = (try { info?.title() } catch (_: Throwable) { null })?.takeIf { it.isNotBlank() } ?: Recents.titleOf(id)
        val st = MtTranslate.coverStats()
        val dir = a.applicationContext.filesDir
        val web = isWeb(u)
        Thread {
            synchronized(Recents.ioLock) {
                try {
                    val f = File(dir, "recent.json")
                    val old = Recents.parse(try { f.readText() } catch (_: Exception) { "" })
                    val prev = old.firstOrNull { it.id == id }
                    val r = Recent(id, title, if (web) u else "", if (web) "" else u, pos / 1000.0, dur / 1000.0,
                        st?.first ?: (prev?.subs ?: 0), st?.second ?: (prev?.coverSec ?: 0.0), System.currentTimeMillis())
                    f.writeText(Recents.toJson(Recents.upsert(old, r, 300)))
                } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true }.start()
    }
}
