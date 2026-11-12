package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import com.newtube.mobile.ui.playback.JoyboyPlayerHost
import java.io.File

/**
 * (v890) محرك الترجمة بتاع Joyboy فوق مشغّل MeeTube — المشغّل نفسه (media3 + SABR) بتاع MeeTube من غير أي تعديل في التشغيل.
 * بيتسجّل من MeeTubeLink.bindBar عبر JoyboyPlayerHost (مودول :mobile مايعرفش app).
 *  • زرار «🌐» في صف أزرار الأعلى (جنب الكاست/CC): دوسة = ابدأ/وقّف الترجمة.
 *  • السبتايتل = SubtitleView بتاع Joyboy (نفس الستايل المحفوظ) فوق الفيديو، على مكان تشغيل ExoPlayer بتاع MeeTube.
 *  • مصدر الصوت للمحرك: روابط MtYt (MeeTube MediaItemService) — مش من مشغّل MeeTube نفسه (SABR مالوش رابط مباشر).
 *  • (v923) الملفات المحلية واللينكات المباشرة بتتفتح في مشغّل MeeTube (MtPlay) وبتتترجم برضه: مصدر الصوت = الملف/اللينك نفسه، ومفتاح الترجمة المحفوظة = نفس هوية مشغّل Joyboy (f:الاسم:الحجم).
 *  • (v923) زرار ⚙ جنب 🌐: إزاحة توقيت الترجمة · إبعت للتلفزيون · إعدادات محرك الترجمة.
 */
object MtTranslate : JoyboyPlayerHost.Provider {
    private const val TAG_BTN = "joy_tr_btn"
    private const val TAG_SUB = "joy_tr_sub"
    private const val TAG_BTN2 = "joy_tr_menu"
    private val ui = Handler(Looper.getMainLooper())

    private var act: Activity? = null
    private var player: ExoPlayer? = null
    private var info: JoyboyPlayerHost.VideoInfo? = null
    private var overlay: ViewGroup? = null
    private var btn: TextView? = null
    private var btn2: TextView? = null
    @Volatile private var offsetMs = 0L   // (v923) إزاحة توقيت الترجمة (+ = تظهر بدري)
    private var sv: SubtitleView? = null

    private var eng: Engine? = null
    private var engVid = ""
    @Volatile private var posSec = 0.0
    @Volatile private var durSec = 0.0
    @Volatile private var on = false
    private var starts = LongArray(0)
    private var ends = LongArray(0)
    private var cached: List<Sub>? = null
    private var tickOn = false
    private var token = 0
    /** (v890) فيديو مطلوب له ترجمة تلقائية (من Main.playYt) — بيتستهلك مرة واحدة أول ما الفيديو ده يبقى هو الشغّال */
    @Volatile var autoFor: String? = null
    private var listenedOn: ExoPlayer? = null
    private val trans = object : androidx.media3.common.Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { onVideoChanged() }
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    override fun attach(activity: Activity, player: ExoPlayer, overlay: ViewGroup, options: ViewGroup?, info: JoyboyPlayerHost.VideoInfo) {
        try {
            if (act !== activity) { stopEngine(); offsetMs = 0L }   // أكتفيتي جديدة = جلسة جديدة
            this.act = activity; this.player = player; this.info = info; this.overlay = overlay
            Cfg.ensure(activity.applicationContext)
            // شيل أي نسخة قديمة من الزرار/السبتايتل (المشغّل بيتبني تاني مع كل فيديو/تغيير سطح)
            for (t in listOf(TAG_SUB)) overlay.findViewWithTag<View>(t)?.let { overlay.removeView(it) }
            options?.findViewWithTag<View>(TAG_BTN)?.let { options.removeView(it) }
            options?.findViewWithTag<View>(TAG_BTN2)?.let { options.removeView(it) }
            val s = SubtitleView(activity).apply { tag = TAG_SUB; layoutDirection = View.LAYOUT_DIRECTION_RTL; style = SubStyle.load { k, d -> Cfg.str(k, d) } }
            overlay.addView(s, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(activity, 40) })
            sv = s; cached = null
            if (options != null) {
                val b = TextView(activity).apply {
                    tag = TAG_BTN; gravity = Gravity.CENTER; textSize = 13f; setTextColor(Color.WHITE)
                    setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
                    background = GradientDrawable().apply { cornerRadius = dp(activity, 18).toFloat(); setColor(0x66000000) }
                    setOnClickListener { toggle() }
                }
                options.addView(b, 0, LinearLayout.LayoutParams(-2, dp(activity, 36)).apply { marginEnd = dp(activity, 6) })
                btn = b
                val m = TextView(activity).apply {   // (v923) قايمة أدوات Joyboy
                    tag = TAG_BTN2; gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.WHITE); text = "⚙"
                    setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
                    background = GradientDrawable().apply { cornerRadius = dp(activity, 18).toFloat(); setColor(0x66000000) }
                    setOnClickListener { showMenu() }
                }
                options.addView(m, 1, LinearLayout.LayoutParams(-2, dp(activity, 36)).apply { marginEnd = dp(activity, 6) })
                btn2 = m
            } else { btn = null; btn2 = null }
            // فيديو تاني (التالي/قايمة): المحرك القديم يتقفل، والترجمة تفضل شغّالة لو كانت شغّالة
            try { listenedOn?.removeListener(trans) } catch (_: Throwable) {}
            player.addListener(trans); listenedOn = player
            refreshBtn(); maybeAuto()
            if (!tickOn) { tickOn = true; ui.post(tick) }
        } catch (e: Throwable) { LogStore.err("MtTranslate:attach", e) }
    }

    override fun detach(activity: Activity) {
        try {
            if (act !== activity) return
            ui.removeCallbacks(tick); tickOn = false
            overlay?.let { o -> sv?.let { v -> o.removeView(v) } }
            (btn?.parent as? ViewGroup)?.removeView(btn)
            (btn2?.parent as? ViewGroup)?.removeView(btn2)
            try { listenedOn?.removeListener(trans) } catch (_: Throwable) {}; listenedOn = null
            sv = null; btn = null; btn2 = null; overlay = null; player = null
            stopEngine()
            act = null
        } catch (e: Throwable) { LogStore.err("MtTranslate:detach", e) }
    }

    private fun localUri(): String? = try { info?.localUri()?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
    /** (v923) مفتاح الفيديو الشغّال: معرّف يوتيوب (11 حرف) أو «L:<uri>» لملف/لينك مباشر */
    private fun curId(): String? = localUri()?.let { "L:$it" } ?: try { info?.videoId()?.takeIf { it.length == 11 } } catch (_: Throwable) { null }
    /** (v923) نفس هوية مشغّل Joyboy للملفات: f:الاسم:الحجم (فالترجمة المحفوظة والتقدم بيتشاركوا بين المشغّلين) */
    private fun localVid(a: Activity, u: String): String {
        try {
            val uri = android.net.Uri.parse(u)
            if (uri.scheme == "content") a.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) return "f:" + c.getString(0) + ":" + c.getLong(1) }
            if (uri.scheme == "file") { val f = File(uri.path ?: ""); if (f.isFile) return "f:" + f.name + ":" + f.length() }
        } catch (_: Throwable) {}
        return "f:$u"
    }

    private fun maybeAuto() {
        val want = autoFor ?: return
        if (curId() == want && !on) { autoFor = null; start() }
    }

    private fun onVideoChanged() {
        val id = curId()
        if (autoFor != null && id == autoFor) { stopEngine(); sv?.show(null); maybeAuto(); return }
        if (id != engVid) { val was = on; stopEngine(); sv?.show(null); if (was && id != null) start() else refreshBtn() }
    }

    private fun toggle() {
        val a = act ?: return
        if (on) { stopEngine(); sv?.show(null); refreshBtn(); Notice.show(a, "الترجمة اتقفلت", 1600L); return }
        start()
    }

    private fun stopEngine() {
        on = false; token++
        val e = eng; eng = null; engVid = ""
        if (e != null) Thread { try { e.saveNow() } catch (_: Throwable) {}; try { e.stop() } catch (_: Throwable) {} }.apply { isDaemon = true }.start()
        cached = null
    }

    private fun start() {
        val a = act ?: return
        val id = curId()
        if (id == null) { Notice.show(a, "مش قادر أعرف الفيديو ده للترجمة", 2600L); return }
        val conf0 = try { Cfg.snapshot() } catch (e: Throwable) { LogStore.err("MtTranslate:conf", e); return }
        if (conf0.keys.isEmpty()) { Notice.show(a, "ضيف مفتاح جيميناي من إعدادات Joyboy الأول", 3000L); return }
        stopEngine()
        on = true; engVid = id
        val my = ++token
        refreshBtn("⏳")
        val appCtx = a.applicationContext
        val lu = if (id.startsWith("L:")) id.substring(2) else null
        val localId = if (lu != null) localVid(a, lu) else null
        Thread {
            val hdr: HashMap<String, String> = HashMap()
            var src: String? = null
            val vid: String
            if (lu != null) {   // (v923) ملف محلي أو لينك مباشر: الصوت من نفس المصدر اللي المشغّل بيشغّله
                vid = localId ?: "f:$lu"
            } else {
                val got = try { MtYt.video(id, 0, false) } catch (_: Throwable) { null }
                src = got?.audio?.takeIf { it.isNotEmpty() } ?: got?.url
                if (got == null || src == null) {
                    ui.post {
                        if (my != token) return@post
                        on = false; engVid = ""; refreshBtn()
                        act?.let { Notice.show(it, "ما قدرتش أجيب صوت الفيديو للترجمة: " + MtYt.lastWhy.take(80), 3500L) }
                    }
                    return@Thread
                }
                hdr.put("User-Agent", got.ua); hdr.put("Referer", "https://www.youtube.com/")
                vid = "yt:$id"
            }
            val e = try {
                val pb = PromptBuilder { p -> appCtx.assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
                val store = Store(File(appCtx.filesDir, "progress"), Store.keyFor(vid))
                val host = object : Host {
                    override fun log(s: String) { LogStore.add(s) }
                    override fun status(s: String) {}
                    override fun changed() {}
                    override fun position(): Double = posSec
                    override fun playerDuration(): Double = durSec
                }
                val mk: () -> AudioSource
                if (lu != null) {
                    val uu = android.net.Uri.parse(lu)
                    val web = uu.scheme == "http" || uu.scheme == "https"
                    val f: () -> AudioSource = { AudioSources.make(appCtx, if (web) null else uu, if (web) lu else null, hdr, conf0.audioTrack, { m -> LogStore.add(m) }) }
                    mk = f
                } else {
                    val f: () -> AudioSource = { AudioSources.make(appCtx, null, src, hdr, conf0.audioTrack, { m -> LogStore.add(m) }) }
                    mk = f
                }
                Engine(conf0, mk, store, host, pb).also {
                    it.convDialect = Cfg.str("conv_dialect", "")
                    it.toneStyle = Cfg.str("tone_style", ""); it.toneStrength = Cfg.str("tone_strength", "متوسطة")
                    it.titleHint = (try { info?.title() } catch (_: Throwable) { null } ?: "")
                }
            } catch (t: Throwable) {
                LogStore.err("MtTranslate:engine", t)
                ui.post { if (my == token) { on = false; engVid = ""; refreshBtn() } }
                return@Thread
            }
            if (my != token) { try { e.stop() } catch (_: Throwable) {}; return@Thread }
            try { e.load() } catch (t: Throwable) { LogStore.err("MtTranslate:load", t) }
            if (my != token) { try { e.stop() } catch (_: Throwable) {}; return@Thread }
            eng = e
            ui.post {
                if (my != token) return@post
                refreshBtn()
                // الترجمة من مكان التشغيل الحالي (مش من الأول) — زي «ترجم» في مشغّل Joyboy
                try { e.translateFrom(posSec) } catch (t: Throwable) { LogStore.err("MtTranslate:from", t) }
            }
            try { e.run() } catch (t: Throwable) { LogStore.err("MtTranslate:run", t) }
        }.apply { isDaemon = true }.start()
    }

    /** (v923) قايمة أدوات Joyboy فوق مشغّل MeeTube */
    private fun showMenu() {
        val a = act ?: return
        val lu = localUri()
        val names = arrayOf(
            "⏩ قدّم الترجمة نص ثانية" + (if (offsetMs != 0L) "  (الحالي ${offsetMs / 1000.0}ث)" else ""),
            "⏪ أخّر الترجمة نص ثانية",
            "↺ صفّر إزاحة الترجمة",
            "📺 ابعت للتلفزيون",
            "⚙ إعدادات محرك الترجمة"
        )
        GAlert(a).setTitle("أدوات Joyboy").setItems(names) { _, i ->
            when (i) {
                0 -> { offsetMs += 500L; Notice.show(a, "إزاحة الترجمة: ${offsetMs / 1000.0}ث", 1400L) }
                1 -> { offsetMs -= 500L; Notice.show(a, "إزاحة الترجمة: ${offsetMs / 1000.0}ث", 1400L) }
                2 -> { offsetMs = 0L; Notice.show(a, "الإزاحة اتصفّرت", 1400L) }
                3 -> {
                    val pos = try { player?.currentPosition ?: 0L } catch (_: Throwable) { 0L }
                    val ttl = try { info?.title() ?: "" } catch (_: Throwable) { "" }
                    val spec = when {
                        lu != null && (lu.startsWith("http://") || lu.startsWith("https://")) -> MediaSpec(url = lu, title = ttl, startMs = pos)
                        lu != null -> MediaSpec(uri = lu, title = ttl, startMs = pos)
                        else -> MediaSpec(ytid = curId(), title = ttl, startMs = pos)
                    }
                    if (!PlayTarget.force(a, spec)) Notice.show(a, "📺 ما اتبعتش للتلفزيون", 2600L)
                }
                4 -> MeeTubeLink.openSettings(a, "tr_engine")
            }
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun refreshBtn(state: String? = null) {
        val b = btn ?: return
        val e = eng
        b.text = when {
            state != null -> "🌐 $state"
            !on -> "🌐 ترجم"
            e != null && durSec > 0 -> "🌐 " + PlayerLogic.percent(e.coveredSec(), durSec) + "%"
            else -> "🌐 …"
        }
        (b.background as? GradientDrawable)?.setColor(if (on) 0xCCE53935.toInt() else 0x66000000)
    }

    private var tickN = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!tickOn) return
            try {
                val p = player
                if (p != null) {
                    posSec = p.currentPosition / 1000.0
                    val d = p.duration; if (d > 0) durSec = d / 1000.0
                    val e = eng
                    val v = sv
                    if (on && e != null && v != null) {
                        val list = e.subs
                        if (list !== cached) {
                            cached = list
                            starts = LongArray(list.size) { (list[it].start * 1000).toLong() }
                            ends = LongArray(list.size) { (list[it].end * 1000).toLong() }
                        }
                        val idx = PlayerLogic.activeIndices(starts, ends, p.currentPosition + offsetMs, 0L)
                        val shown = PlayerLogic.combine(idx.mapNotNull { list.getOrNull(it) })
                        v.show(shown, if (idx.size > 1) idx.mapNotNull { list.getOrNull(it) } else emptyList())
                        if (++tickN % 8 == 0) refreshBtn()
                    }
                }
            } catch (t: Throwable) { LogStore.err("MtTranslate:tick", t) }
            ui.postDelayed(this, 120L)
        }
    }
}
