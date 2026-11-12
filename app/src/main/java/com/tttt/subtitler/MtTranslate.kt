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
    @Volatile private var sideSubs: List<Sub>? = null   // (v935) سبتايتل جانبي (ملف .srt/.vtt) معروض من غير محرك
    private var engKey = ""
    private var dubPlay: DubPlay? = null
    @Volatile private var dubBusy = false
    @Volatile private var dubCancel = false
    private var ctlVisible = true   // (v934) أزرار المشغّل ظاهرة؟ — السبتايتل بيطلع فوقها

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
    /** (v936) فيديوهات متخفية/موقع محجوب (مفتاح = curId): الترجمة المحفوظة بتتمسح أول ما المحرك يقف (زي مشغّل Joyboy v124) */
    val privIds: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    /** (v936) هوية قديمة لفيديو (forceid من «إصلاح اللينك»): مفتاح = «L:<لينك>» ← هوية الترجمة/التقدم القديمة */
    val idOverride: MutableMap<String, String> = java.util.Collections.synchronizedMap(HashMap<String, String>())
    private var listenedOn: ExoPlayer? = null
    private val trans = object : androidx.media3.common.Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { onVideoChanged() }
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    override fun attach(activity: Activity, player: ExoPlayer, overlay: ViewGroup, options: ViewGroup?, info: JoyboyPlayerHost.VideoInfo) {
        try {
            if (act !== activity) { stopEngine(); offsetMs = 0L }   // أكتفيتي جديدة = جلسة جديدة
            this.act = activity; this.player = player; this.info = info; this.overlay = overlay
            try { MtTvPlayer.set(activity, player, try { info.title() ?: "" } catch (_: Throwable) { "" }) } catch (_: Throwable) {}   // (v928) ريموت الموبايل يتحكم في مشغّل MeeTube على التلفزيون
            Cfg.ensure(activity.applicationContext)
            // شيل أي نسخة قديمة من الزرار/السبتايتل (المشغّل بيتبني تاني مع كل فيديو/تغيير سطح)
            for (t in listOf(TAG_SUB)) overlay.findViewWithTag<View>(t)?.let { overlay.removeView(it) }
            options?.findViewWithTag<View>(TAG_BTN)?.let { options.removeView(it) }
            options?.findViewWithTag<View>(TAG_BTN2)?.let { options.removeView(it) }
            val s = SubtitleView(activity).apply { tag = TAG_SUB; layoutDirection = View.LAYOUT_DIRECTION_RTL; style = SubStyle.load { k, d -> Cfg.str(k, d) } }
            overlay.addView(s, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = subMargin(activity) })
            sv = s; cached = null
            btn = null; btn2 = null
            // (v934 م2) زرار 🌐 (ترجم / نسبة %) + ⚙ (قايمة الترجمة) في صف أزرار الأعلى، بنفس شكل زجاج MeeTube
            if (options != null) {
                val b1 = pill(activity, TAG_BTN, "🌐 ترجم") { toggle() }
                val b2 = pill(activity, TAG_BTN2, "⚙") { showMenu() }
                options.addView(b2, 0); options.addView(b1, 0)
                btn = b1; btn2 = b2
            }
            // فيديو تاني (التالي/قايمة): المحرك القديم يتقفل، والترجمة تفضل شغّالة لو كانت شغّالة
            try { listenedOn?.removeListener(trans) } catch (_: Throwable) {}
            player.addListener(trans); listenedOn = player
            refreshBtn(); maybeAuto()
            MtMedia.attach(activity, player, options, info)   // (v937 م3) A-B · تكرار · حلقات · استكمال
            if (!tickOn) { tickOn = true; ui.post(tick) }
        } catch (e: Throwable) { LogStore.err("MtTranslate:attach", e) }
    }

    override fun detach(activity: Activity) {
        try {
            try { MtTvPlayer.clear(activity) } catch (_: Throwable) {}
            try { MtMedia.detach(activity) } catch (_: Throwable) {}   // (v937 م3)
            if (act !== activity) return
            ui.removeCallbacks(tick); tickOn = false
            overlay?.let { o -> sv?.let { v -> o.removeView(v) } }
            (btn?.parent as? ViewGroup)?.removeView(btn)
            (btn2?.parent as? ViewGroup)?.removeView(btn2)
            try { listenedOn?.removeListener(trans) } catch (_: Throwable) {}; listenedOn = null
            sv = null; btn = null; btn2 = null; overlay = null; player = null
            dubCancel = true; try { dubPlay?.stop() } catch (_: Throwable) {}; dubPlay = null; sideSubs = null
            stopEngine()
            act = null
        } catch (e: Throwable) { LogStore.err("MtTranslate:detach", e) }
    }

    /** (v934) هامش السبتايتل من تحت: أعلى لما أزرار المشغّل ظاهرة */
    private fun subMargin(a: Activity) = dp(a, if (ctlVisible) 96 else 40)

    /** (v937 م3) ⏮ ⏭ في MeeTube ← حلقات فولدر المكتبة */
    override fun onStep(activity: Activity, dir: Int): Boolean = try { MtMedia.onStep(activity, dir) } catch (_: Throwable) { false }

    /** JOYBOY(v939): Browser-origin streams minimize to Android PiP so the Joyboy Browser remains underneath. */
    override fun preferSystemPip(activity: Activity): Boolean = try {
        MtWeb.preferSystemPip(try { info?.localUri() } catch (_: Throwable) { null }, try { info?.videoId() } catch (_: Throwable) { null })
    } catch (_: Throwable) { false }

    /** (v937 م3) هوية الفيديو للاستكمال/«آخر المشغّلة» (نفس مفتاح مشغّل Joyboy، مع forceid) */
    internal fun joyVid(a: Activity, lu: String): String = idOverride["L:$lu"] ?: localVid(a, lu)

    /** (v937 م3) (عدد الجمل، الثواني المغطّاة) من محرك الترجمة الشغّال، أو null */
    internal fun coverStats(): Pair<Int, Double>? = try { eng?.let { Pair(it.subs.size, it.coveredSec()) } } catch (_: Throwable) { null }

    override fun onControlsVisibility(activity: Activity, visible: Boolean) {
        try {
            ctlVisible = visible
            val v = sv ?: return
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
            val m = subMargin(activity)
            if (lp.bottomMargin != m) { lp.bottomMargin = m; v.layoutParams = lp }
        } catch (_: Throwable) {}
    }

    private fun pill(a: Activity, t: String, label: String, click: () -> Unit): TextView = TextView(a).apply {
        tag = t; text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        setPadding(dp(a, 12), 0, dp(a, 12), 0)
        background = GradientDrawable().apply { cornerRadius = dp(a, 18).toFloat(); setColor(0x66000000) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 36)).apply { marginEnd = dp(a, 6) }
        setOnClickListener { click() }
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
        sideSubs = null; try { dubPlay?.stop() } catch (_: Throwable) {}   // (v935) السبتايتل الجانبي والدبلجة مخصوصين للفيديو اللي كان شغّال
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
        val e = eng; val wasId: String? = engVid.ifEmpty { null }; val wasKey = engKey; val ctx0 = act?.applicationContext; eng = null; engVid = ""
        if (e != null) Thread {
            try { e.saveNow() } catch (_: Throwable) {}; try { e.stop() } catch (_: Throwable) {}
            // (v936) متخفي: امسح الترجمة المحفوظة بعد الحفظ النهائي
            if (wasId != null && privIds.contains(wasId) && wasKey.isNotEmpty()) try { ctx0?.let { c -> File(File(c.filesDir, "progress"), Store.keyFor(wasKey) + ".json").delete() } } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
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
                try { info?.headers()?.let { hdr.putAll(it) } } catch (_: Throwable) {}   // (v934 م2) Referer/UA/Cookie بتاع اللينك
                vid = idOverride[id] ?: localId ?: "f:$lu"   // (v936) forceid
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
            engKey = vid
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

    /** (v924) يبعت الفيديو الشغّال للتلفزيون (من نفس المكان) */
    private fun sendToTv() {
        val a = act ?: return
        val lu = localUri()
        val pos = try { player?.currentPosition ?: 0L } catch (_: Throwable) { 0L }
        val ttl = try { info?.title() ?: "" } catch (_: Throwable) { "" }
        val spec = when {
            lu != null && (lu.startsWith("http://") || lu.startsWith("https://")) -> MediaSpec(url = lu, title = ttl, startMs = pos)
            lu != null -> MediaSpec(uri = lu, title = ttl, startMs = pos)
            else -> MediaSpec(ytid = curId(), title = ttl, startMs = pos)
        }
        if (!PlayTarget.force(a, spec)) Notice.show(a, "📺 ما اتبعتش للتلفزيون — اقترن بالتلفزيون الأول", 2600L)
    }

    private val dualCycle = listOf(0, 3, 1, 4)
    private val dualShort = mapOf(0 to "فردي", 3 to "أصلي", 1 to "مزدوج", 4 to "مزدوج (أصلي فوق)", 2 to "إنجليزي")
    private fun curDual(): Int = try { Cfg.int("sub_dual", 0) } catch (_: Throwable) { 0 }

    private fun cycleDual() {
        val a = act ?: return
        val n = dualCycle[(dualCycle.indexOf(curDual()) + 1) % dualCycle.size]
        Cfg.put("sub_dual", n.toString())
        sv?.style = SubStyle.load { k, d -> Cfg.str(k, d) }
        Notice.show(a, "💬 وضع الترجمة: " + (dualShort[n] ?: "فردي"), 1600L)
    }

    private fun fmtT(sec: Double): String { val t = sec.toInt().coerceAtLeast(0); return String.format("%d:%02d", t / 60, t % 60) }

    /** (v934) قايمة الجمل: اختار جملة ← المشغّل يروح لوقتها */
    private fun showSentences() {
        val a = act ?: return
        val list = eng?.subs
        if (list == null || list.isEmpty()) { Notice.show(a, "مفيش جمل مترجمة لسه", 2000L); return }
        val cur = list.indexOfFirst { it.end * 1000 >= (player?.currentPosition ?: 0L) }.coerceAtLeast(0)
        val from = (cur - 20).coerceAtLeast(0); val to = (from + 300).coerceAtMost(list.size)
        val names = Array(to - from) { k -> val q = list[from + k]; "${from + k + 1}. [" + fmtT(q.start) + "] " + q.translated.ifBlank { q.original }.replace('\n', ' ').take(90) }
        GAlert(a).setTitle("📝 الجمل (${list.size})").setItems(names) { _, i -> try { player?.seekTo((list[from + i].start * 1000).toLong()) } catch (_: Throwable) {} }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun setOffset(a: Activity, v: Long) {
        offsetMs = v.coerceIn(-10000L, 10000L)
        Notice.show(a, "إزاحة الترجمة: " + String.format("%+.2f", offsetMs / 1000.0) + "ث", 1400L)
    }

    // ===================== (v935 م2) باقي أدوات Joyboy فوق مشغّل MeeTube =====================

    /** 🗂 النسخ المحفوظة (لهجات الترجمة): احفظ الحالية / بدّل لنسخة */
    private fun showVersions() {
        val a = act ?: return
        val e = eng
        if (e == null) { Notice.show(a, "ابدأ الترجمة الأول", 1800L); return }
        val vs = e.versions.toList()
        val names = ArrayList<String>()
        names.add("💾 احفظ النسخة الحالية")
        vs.forEach { v -> names.add(v.name + " — " + v.dialect.ifBlank { "—" } + " — " + v.subs.size + " جملة") }
        GAlert(a).setTitle("🗂 ترجمات الفيديو").setItems(names.toTypedArray()) { _, i ->
            if (i == 0) { e.saveVersion("نسخة " + fmtT(posSec)); Notice.show(a, "اتحفظت النسخة الحالية", 1600L) }
            else restoreVersion(a, e, i - 1)
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun restoreVersion(a: Activity, e: Engine, i: Int) {
        val v = e.versions.getOrNull(i) ?: return
        val n = e.applyVersion(i)
        val d = if (v.dialect == "فصحى") "" else v.dialect
        try { Cfg.put("conv_dialect", d); TrWizard.syncSaved("conv_dialect", d) } catch (_: Throwable) {}
        e.adoptDialect(d); cached = null
        Notice.show(a, "اتطبّقت نسخة «" + v.name + "» (اتغيّرت $n جملة)", 2400L)
    }

    /** 🎨 ستايل السبتايتل: حجم · خلفية · خط · حركة دخول · لون موحّد (نفس مفاتيح Cfg بتاعة مشغّل Joyboy) */
    private fun putStyle(k: String, v: String) { Cfg.put(k, v); sv?.style = SubStyle.load { kk, d -> Cfg.str(kk, d) } }

    private fun showStyle() {
        val a = act ?: return
        val st = sv?.style ?: return
        val bgLabel = if (st.noBg) "من غير خلفية" else st.bgOpa.toString() + "%"
        val names = arrayOf(
            "🔠 كبّر الخط +10  (الحالي ${st.scale}%)",
            "🔡 صغّر الخط −10",
            "▭ شفافية الخلفية: $bgLabel  (اضغط للتبديل)",
            "✍ الخط: " + (SubStyle.fonts.firstOrNull { it.id == st.font }?.label ?: st.font),
            "🎬 حركة الدخول: " + (SubStyle.entrances.firstOrNull { it.id == st.anim }?.label ?: st.anim),
            "🎨 لون موحّد: " + (if (st.uniOn) st.uniColor else "مقفول")
        )
        GAlert(a).setTitle("🎨 ستايل السبتايتل").setItems(names) { _, i ->
            when (i) {
                0 -> { putStyle("sub_scale", (st.scale + 10).coerceAtMost(200).toString()); showStyle() }
                1 -> { putStyle("sub_scale", (st.scale - 10).coerceAtLeast(60).toString()); showStyle() }
                2 -> {
                    val steps = listOf(25, 45, 70, 100)
                    if (st.noBg) { putStyle("sub_nobg", "0"); putStyle("sub_bgopa", "25") }
                    else { val nx = steps.firstOrNull { it > st.bgOpa }; if (nx == null) putStyle("sub_nobg", "1") else putStyle("sub_bgopa", nx.toString()) }
                    showStyle()
                }
                3 -> GAlert(a).setTitle("✍ الخط").setItems(SubStyle.fonts.map { it.label }.toTypedArray()) { _, k -> putStyle("sub_font", SubStyle.fonts[k].id); showStyle() }.setNegativeButton("رجوع", null).show()
                4 -> GAlert(a).setTitle("🎬 حركة الدخول").setItems(SubStyle.entrances.map { it.label }.toTypedArray()) { _, k -> putStyle("sub_anim", SubStyle.entrances[k].id); showStyle() }.setNegativeButton("رجوع", null).show()
                5 -> {
                    val opts = listOf("مقفول") + SubStyle.unifiedPalette
                    GAlert(a).setTitle("🎨 لون موحّد").setItems(opts.toTypedArray()) { _, k ->
                        if (k == 0) putStyle("sub_uni_on", "0") else { putStyle("sub_uni_color", opts[k]); putStyle("sub_uni_on", "1") }
                        showStyle()
                    }.setNegativeButton("رجوع", null).show()
                }
            }
        }.setNegativeButton("إغلاق", null).show()
    }

    /** 📄 سبتايتل جانبي: ملف .srt/.vtt — عربي يتعرض علطول، غير كده يتترجم (جيميناي لو في مفتاح وإلا جوجل) */
    private fun sideSubMenu() {
        val a = act ?: return
        if (sideSubs != null) { sideSubs = null; sv?.show(null); cached = null; Notice.show(a, "اتشال السبتايتل الجانبي", 1600L); return }
        SubPick.pick(a) { uri ->
            if (uri == null) return@pick
            Thread {
                val pk = SubPick.read(a.applicationContext, uri)
                if (pk == null) { ui.post { Notice.show(a, "الملف مش ملف ترجمة مقروء", 2400L) }; return@Thread }
                val cues = SubFileTr.parse(pk.text)
                fun toSubs(texts: List<String>?) = cues.mapIndexed { i, c ->
                    val tr = texts?.getOrNull(i) ?: ""
                    Sub(c.start, c.end, c.text, if (tr.isBlank()) c.text else tr, "male", "unknown", "none", emptyList(), emptyList(), false, false)
                }
                if (SubFileTr.mostlyArabic(cues)) { ui.post { applySide(a, toSubs(null), pk.name) }; return@Thread }
                val engName = if (SubFileTr.hasGemKeys()) "gemini" else "google"
                ui.post { Notice.show(a, "📄 بترجم «" + pk.name + "» (" + cues.size + " جملة) بـ" + (if (engName == "gemini") "جيميناي" else "جوجل") + "…", 2600L) }
                var last = 0L
                val out = try { SubFileTr.translate(cues, engName, { pr -> val n = System.currentTimeMillis(); if (n - last > 2500L) { last = n; ui.post { act?.let { Notice.show(it, pr, 1500L) } } } }, { act == null }) } catch (t: Throwable) { LogStore.err("MtTranslate:side", t); null }
                ui.post { if (out == null) Notice.show(a, "❌ ترجمة الملف فشلت", 2600L) else applySide(a, toSubs(out.texts), pk.name) }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun applySide(a: Activity, subs: List<Sub>, name: String) {
        if (act !== a) return
        stopEngine(); sv?.show(null); cached = null
        sideSubs = subs
        Notice.show(a, "✅ السبتايتل الجانبي «$name» شغّال (${subs.size} جملة)", 2600L)
    }

    /** 🎙 دبلجة بصوت Edge (سلمى/شاكر) من الجمل المترجمة */
    private fun dubToggle() {
        val a = act ?: return
        val e = eng; val p = player ?: return
        val dp = dubPlay
        if (dp != null && dp.active) { dp.stop(); Notice.show(a, "🎙 الدبلجة اتقفلت — صوت الفيديو الأصلي رجع", 2000L); return }
        if (dubBusy) { dubCancel = true; Notice.show(a, "⏹ بلغي الدبلجة…", 1600L); return }
        val subs = e?.subs ?: sideSubs
        val segs = if (subs == null) emptyList() else Dub.segsOf(subs)
        if (segs.isEmpty()) { Notice.show(a, "مفيش جمل مترجمة لسه — ترجم الأول وبعدين دبّلج", 2600L); return }
        val dur = try { p.duration } catch (_: Throwable) { -1L }
        val total = if (dur > 0) dur / 1000.0 else 0.0
        val key = Store.keyFor(if (engKey.isNotEmpty()) engKey else (curId() ?: "x"))
        val app = a.applicationContext
        dubBusy = true; dubCancel = false
        Notice.show(a, "🎙 بجهّز الدبلجة (" + segs.size + " جملة) بصوت " + Dub.voiceLabel() + "…", 2600L)
        Thread {
            var last = 0L
            val f = try { Dub.build(app, key, segs, total, { d, t -> val n = System.currentTimeMillis(); if (n - last > 4000L) { last = n; ui.post { act?.let { Notice.show(it, "🎙 دبلجة $d/$t", 1500L) } } } }, { dubCancel || act == null }) } catch (t: Throwable) { LogStore.err("MtTranslate:dub", t); null }
            dubBusy = false
            ui.post {
                val aa = act ?: return@post
                val pp = player ?: return@post
                if (f == null) { Notice.show(aa, if (dubCancel) "اتلغت الدبلجة" else "❌ الدبلجة فشلت — اتأكد إن النت شغّال وجرّب تاني", 2600L); return@post }
                val d2 = dubPlay ?: DubPlay(pp).also { dubPlay = it }
                Notice.show(aa, if (d2.start(f)) "✅ الدبلجة شغّالة — صوت الفيديو الأصلي اتوطّى" else "❌ مقدرتش أشغّل ملف الدبلجة", 2600L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun dubVoiceSwap() {
        val a = act ?: return
        val v = Dub.toggleVoice(); dubPlay?.stop()
        Notice.show(a, "🗣 الصوت دلوقتي: " + Dub.voiceLabel(v) + " — دوس دبلجة تاني عشان تتبنى بالصوت الجديد", 3000L)
    }

    /** ⬇ تحميل اللينك المباشر الشغّال (بنفس هيدرزه وصوته المنفصل) كمهمة في تبويب «المهام» */
    private fun downloadCur() {
        val a = act ?: return
        val lu = localUri()
        if (lu == null || !(lu.startsWith("http://", true) || lu.startsWith("https://", true))) {
            Notice.show(a, "التحميل من هنا للينكات المباشرة بس — يوتيوب من زرار ⬇ بتاع المشغّل، والملف المحلي موجود أصلاً", 3200L); return
        }
        val name = (try { info?.title() } catch (_: Throwable) { null } ?: "").ifBlank { "video" }
        val hm = HashMap<String, String>()
        try { info?.headers()?.let { hm.putAll(it) } } catch (_: Throwable) {}
        val au = try { info?.audioUrl()?.takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }
        val job = Downloader.Job(name, lu, au, lu.contains(".m3u8", true), 0, hm, false)
        val app = a.applicationContext
        TaskCenter.add(app, "download", "⬇ " + job.name) { t ->
            job.ctl.cancelled = false; job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { p -> job.ctl.paused = p }
            val res = Downloader.run(app, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""; t.outPath = res.second
        }
        Notice.show(a, "⬇ اتضاف للتحميل — تابعه من تبويب «المهام»", 3000L)
    }

    /** (v935 م2) قايمة أدوات الترجمة فوق مشغّل MeeTube (حوار زجاجي GAlert) */
    private fun showMenu() {
        val a = act ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun m(label: String, f: () -> Unit) { items.add(Pair(label, f)) }
        m((if (on) "⏹ وقّف الترجمة" else "🌐 ابدأ الترجمة")) { toggle() }
        m(("💬 وضع الترجمة: " + (dualShort[curDual()] ?: "فردي") + "  (اضغط للتبديل)")) { cycleDual() }
        m("📝 قايمة الجمل") { showSentences() }
        m("🗂 النسخ المحفوظة (اللهجات)") { showVersions() }
        m("🎨 ستايل السبتايتل") { showStyle() }
        m((if (sideSubs != null) "✖ شيل السبتايتل الجانبي" else "📄 سبتايتل جانبي (.srt / .vtt)")) { sideSubMenu() }
        m(("⏩ قدّم الترجمة ربع ثانية" + (if (offsetMs != 0L) "  (الحالي ${String.format("%+.2f", offsetMs / 1000.0)}ث)" else ""))) { setOffset(a, offsetMs + 250L) }
        m("⏪ أخّر الترجمة ربع ثانية") { setOffset(a, offsetMs - 250L) }
        m("↺ صفّر إزاحة الترجمة") { setOffset(a, 0L) }
        m((if (dubPlay?.active == true) "🎙 وقّف الدبلجة" else "🎙 دبلجة بصوت " + Dub.voiceLabel())) { dubToggle() }
        m("🗣 بدّل صوت الدبلجة (سلمى/شاكر)") { dubVoiceSwap() }
        m("⬇ حمّل الفيديو") { downloadCur() }
        m("📺 ابعت للتلفزيون") { sendToTv() }
        if (localUri() != null) {   // (v937 م3) أدوات الميديا للملفات/اللينكات (مش يوتيوب)
            m(MtMedia.abLabel()) { MtMedia.abNext() }
            m(("🔁 وضع التكرار: " + MtMedia.loopLabel() + "  (اضغط للتبديل)")) { MtMedia.loopCycle() }
            m("⏮ الحلقة اللي قبلها") { MtMedia.step(-1) }
            m("⏭ الحلقة اللي بعدها") { MtMedia.step(1) }
            m("📃 الليستة (طابور التشغيل)") { PlayQueueUi.show(a) }
        }
        m("⚙ إعدادات محرك الترجمة") { MeeTubeLink.openSettings(a, "tr_engine") }
        GAlert(a).setTitle("🌐 أدوات الترجمة والميديا").setItems(items.map { it.first }.toTypedArray()) { _, i -> items.getOrNull(i)?.second?.invoke() }.setNegativeButton("إغلاق", null).show()
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
                    val side = sideSubs
                    if (v != null && ((on && e != null) || side != null)) {
                        val list: List<Sub> = if (on && e != null) e.subs else side!!
                        if (list !== cached) {
                            cached = list
                            starts = LongArray(list.size) { (list[it].start * 1000).toLong() }
                            ends = LongArray(list.size) { (list[it].end * 1000).toLong() }
                        }
                        val idx = PlayerLogic.activeIndices(starts, ends, p.currentPosition + offsetMs, 0L)
                        val shown = PlayerLogic.combine(idx.mapNotNull { list.getOrNull(it) })
                        v.show(shown, if (idx.size > 1) idx.mapNotNull { list.getOrNull(it) } else emptyList())
                        if (on && ++tickN % 8 == 0) refreshBtn()
                    }
                }
            } catch (t: Throwable) { LogStore.err("MtTranslate:tick", t) }
            ui.postDelayed(this, 120L)
        }
    }
}
