package com.tttt.subtitler

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.newtube.mobile.ui.playback.JoyboyPlayerHost
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * (v890) محرك الترجمة بتاع Joyboy فوق مشغّل MeeTube — المشغّل نفسه (media3 + SABR) بتاع MeeTube من غير أي تعديل في التشغيل.
 * بيتسجّل من MeeTubeLink.bindBar عبر JoyboyPlayerHost (مودول :mobile مايعرفش app).
 *  • زرار «🌐» في صف أزرار الأعلى (جنب الكاست/CC): دوسة = ابدأ/وقّف الترجمة.
 *  • السبتايتل = SubtitleView بتاع Joyboy (نفس الستايل المحفوظ) فوق الفيديو، على مكان تشغيل ExoPlayer بتاع MeeTube.
 *  • مصدر الصوت للمحرك: روابط MtYt (MeeTube MediaItemService) — مش من مشغّل MeeTube نفسه (SABR مالوش رابط مباشر).
 *  • (v923) الملفات المحلية واللينكات المباشرة بتتفتح في مشغّل MeeTube (MtPlay) وبتتترجم برضه: مصدر الصوت = الملف/اللينك نفسه، ومفتاح الترجمة المحفوظة = نفس هوية مشغّل Joyboy (f:الاسم:الحجم).
 *  • (v923) زرار ⚙ جنب 🌐: إزاحة توقيت الترجمة · إبعت للتلفزيون · إعدادات محرك الترجمة.
 *  • (v942) قوايم bottom-sheet بـ Ui.sheet (HtmlUi.kt:313) بدل GAlert لقايمة الأدوات وقايمة النظام؛ GAlert لسه مستخدمة لقوايم الجمل/النسخ/الستايل.
 *  • (v942) شريط أزرار Joyboy الكامل (MtBar.kt) — بيتوصل عبر Provider.extraControls؛ دوال مساعدة للريموت: jumpSentence/shiftOffset/restyle/stopTranslate/exportSrt/applyPhoneSubs.
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
    @Volatile private var offsetMs = 0L
    private var sv: SubtitleView? = null
    @Volatile private var sideSubs: List<Sub>? = null
    private var engKey = ""
    private var dubPlay: DubPlay? = null
    @Volatile private var dubBusy = false
    @Volatile private var dubCancel = false
    private var ctlVisible = true
    @Volatile private var nativeCcOn = false
    private var lastEngFeed = 0L
    @Volatile private var netBusy = ""
    private var netSess: SubOnline.Session? = null

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
            if (act !== activity) { stopEngine(); offsetMs = 0L; nativeCcOn = false }
            this.act = activity; this.player = player; this.info = info; this.overlay = overlay
            try { MtTvPlayer.set(activity, player, try { info.title() ?: "" } catch (_: Throwable) { "" }) } catch (_: Throwable) {}
            Cfg.ensure(activity.applicationContext)
            for (t in listOf(TAG_SUB)) overlay.findViewWithTag<View>(t)?.let { overlay.removeView(it) }
            options?.findViewWithTag<View>(TAG_BTN)?.let { options.removeView(it) }
            options?.findViewWithTag<View>(TAG_BTN2)?.let { options.removeView(it) }
            val s = SubtitleView(activity).apply { tag = TAG_SUB; layoutDirection = View.LAYOUT_DIRECTION_RTL; style = SubStyle.load { k, d -> Cfg.str(k, d) } }
            overlay.addView(s, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = subMargin(activity) })
            sv = s; cached = null
            btn = null; btn2 = null
            if (options != null) {
                val b1 = pill(activity, TAG_BTN, "🌐 ترجم") { toggle() }
                val b2 = pill(activity, TAG_BTN2, "⚙") { showMenu() }
                options.addView(b2, 0); options.addView(b1, 0)
                btn = b1; btn2 = b2
            }
            try { listenedOn?.removeListener(trans) } catch (_: Throwable) {}
            player.addListener(trans); listenedOn = player
            refreshBtn(); maybeAuto()
            MtMedia.attach(activity, player, options, info)
            try { MtEnhance.attach(activity, player, info) } catch (_: Throwable) {}   // (v945) تحسين صورة الملفات المحلية
            try {
                MtBar.attachCircles(activity, player, overlay, info)
            } catch (_: Throwable) {}
            if (!tickOn) { tickOn = true; ui.post(tick) }
        } catch (e: Throwable) { LogStore.err("MtTranslate:attach", e) }
    }

    override fun detach(activity: Activity) {
        try {
            try { MtTvPlayer.clear(activity) } catch (_: Throwable) {}
            try { MtMedia.detach(activity) } catch (_: Throwable) {}
            try { MtEnhance.detach(activity) } catch (_: Throwable) {}
            try {
                MtBar.detach(activity)
            } catch (_: Throwable) {}
            if (act !== activity) return
            ui.removeCallbacks(tick); tickOn = false
            overlay?.let { o -> sv?.let { v -> o.removeView(v) } }
            (btn?.parent as? ViewGroup)?.removeView(btn)
            (btn2?.parent as? ViewGroup)?.removeView(btn2)
            try { listenedOn?.removeListener(trans) } catch (_: Throwable) {}; listenedOn = null
            sv = null; btn = null; btn2 = null; overlay = null; player = null
            nativeCcOn = false; netSess = null; netBusy = ""
            dubCancel = true; try { dubPlay?.stop() } catch (_: Throwable) {}; dubPlay = null; sideSubs = null
            stopEngine()
            act = null
        } catch (e: Throwable) { LogStore.err("MtTranslate:detach", e) }
    }

    /** (v934) هامش السبتايتل من تحت: أعلى لما أزرار المشغّل ظاهرة */
    private fun subMargin(a: Activity) = dp(a, if (ctlVisible) 96 else 40)

    /** (v937 م3) ⏮ ⏭ في MeeTube ← حلقات فولدر المكتبة */
    override fun onStep(activity: Activity, dir: Int): Boolean = try { MtMedia.onStep(activity, dir) } catch (_: Throwable) { false }

    /** (v939) قفز بالأرقام على التلفزيون (نفس سلوك Player.tvDigit): آخر رقمين = ثواني، واللي قبلهم دقايق — دوسة OK أو استنى 2.2ث */
    private var tvDigits = ""
    private var tvDigitRun: Runnable? = null

    /** (v942) مفاتيح ميديا على التلفزيون بس: تشغيل/إيقاف · تقديم/ترجيع 10ث · التالي/السابق · MENU يفتح قايمة التلفزيون · BACK يقفل أعلى طبقة */
    override fun onKey(activity: Activity, event: KeyEvent): Boolean {
        return try {
            if (!DeviceUi.isTv(activity)) return false
            if (event.action != KeyEvent.ACTION_DOWN) return false
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val p = player ?: return false
                    if (act !== activity) return false
                    try {
                        if (p.isPlaying) p.pause() else p.play()
                    } catch (_: Throwable) {}
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    val p = player ?: return false
                    if (act !== activity) return false
                    try { p.play() } catch (_: Throwable) {}
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val p = player ?: return false
                    if (act !== activity) return false
                    try { p.pause() } catch (_: Throwable) {}
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val p = player ?: return false
                    if (act !== activity) return false
                    try {
                        val dur = try { p.duration } catch (_: Throwable) { 0L }
                        val pos = try { p.currentPosition } catch (_: Throwable) { 0L }
                        p.seekTo((pos + 10000L).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE))
                    } catch (_: Throwable) {}
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val p = player ?: return false
                    if (act !== activity) return false
                    try {
                        val dur = try { p.duration } catch (_: Throwable) { 0L }
                        val pos = try { p.currentPosition } catch (_: Throwable) { 0L }
                        p.seekTo((pos - 10000L).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE))
                    } catch (_: Throwable) {}
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (act !== activity) return false
                    return MtMedia.onStep(activity, 1)
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (act !== activity) return false
                    return MtMedia.onStep(activity, -1)
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (act !== activity) return false
                    MtBar.showTvMenu(activity)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (act !== activity) return false
                    MtBar.closeTopLayer(activity)
                }
            }
            val d = when (event.keyCode) {
                KeyEvent.KEYCODE_0 -> 0
                KeyEvent.KEYCODE_1 -> 1
                KeyEvent.KEYCODE_2 -> 2
                KeyEvent.KEYCODE_3 -> 3
                KeyEvent.KEYCODE_4 -> 4
                KeyEvent.KEYCODE_5 -> 5
                KeyEvent.KEYCODE_6 -> 6
                KeyEvent.KEYCODE_7 -> 7
                KeyEvent.KEYCODE_8 -> 8
                KeyEvent.KEYCODE_9 -> 9
                KeyEvent.KEYCODE_NUMPAD_0 -> 0
                KeyEvent.KEYCODE_NUMPAD_1 -> 1
                KeyEvent.KEYCODE_NUMPAD_2 -> 2
                KeyEvent.KEYCODE_NUMPAD_3 -> 3
                KeyEvent.KEYCODE_NUMPAD_4 -> 4
                KeyEvent.KEYCODE_NUMPAD_5 -> 5
                KeyEvent.KEYCODE_NUMPAD_6 -> 6
                KeyEvent.KEYCODE_NUMPAD_7 -> 7
                KeyEvent.KEYCODE_NUMPAD_8 -> 8
                KeyEvent.KEYCODE_NUMPAD_9 -> 9
                else -> return false
            }
            if (act !== activity || player == null) return false
            if (tvDigits.length >= 4) tvDigits = ""
            tvDigits += d.toString()
            Notice.show(activity, "⏱ روح لوقت: $tvDigits (آخر رقمين = ثواني)", 1500L)
            tvDigitRun?.let { ui.removeCallbacks(it) }
            val r = Runnable {
                val buf = tvDigits
                tvDigits = ""
                tvDigitRun = null
                if (buf.isEmpty()) return@Runnable
                val sec = if (buf.length <= 2) buf.toIntOrNull() ?: 0 else (buf.dropLast(2).toIntOrNull() ?: 0) * 60 + (buf.takeLast(2).toIntOrNull() ?: 0)
                try {
                    val p = player ?: return@Runnable
                    val dur = try { p.duration } catch (_: Throwable) { 0L }
                    p.seekTo((sec * 1000L).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE))
                    Notice.show(activity, "⏱ " + PlayerLogic.clock(sec * 1000L), 1100L)
                } catch (e: Throwable) {
                    LogStore.err("MtTranslate:tvJump", e)
                }
            }
            tvDigitRun = r
            ui.postDelayed(r, 2200L)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** (v939) تصغير المشغّل (سحب لتحت/قرص/زرار): نسجّل وجهة الاستعادة من المصدر الأصلي في اللوج ونسيب MeeTube يصغّر زي ما هو — false = الإيماءة مش مستهلكة عندنا */
    override fun onMinimize(activity: Activity): Boolean {
        return try {
            if (act !== activity) return false
            val kind = MtOrigin.current()?.kind
            LogStore.add("MtOrigin: minimize → " + (MtOrigin.minimizeTarget()?.name ?: "none"))
            kind != null && kind != MtOrigin.Kind.YOUTUBE && kind != MtOrigin.Kind.REMOTE_TV
        } catch (_: Throwable) {
            false
        }
    }

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

    /** (v942) شريط أزرار Joyboy الكامل (MtBar) — بيتحط في شريط تحكم MeeTube */
    override fun extraControls(activity: Activity, bar: ViewGroup) {
        try {
            val p = player ?: return
            val i = info ?: return
            if (act !== activity) return
            MtBar.attachBar(activity, p, bar, i)
        } catch (e: Throwable) {
            LogStore.err("MtTranslate:extraControls", e)
        }
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
        sideSubs = null; nativeCcOn = false
        try { dubPlay?.stop() } catch (_: Throwable) {}
        if (autoFor != null && id == autoFor) { stopEngine(); sv?.show(null); maybeAuto(); return }
        if (id != engVid) { val was = on; stopEngine(); sv?.show(null); if (was && id != null) start() else refreshBtn() }
    }

    fun toggle() {
        val a = act ?: return
        if (on) { stopEngine(); sv?.show(null); refreshBtn(); Notice.show(a, "الترجمة اتقفلت", 1600L); return }
        start()
    }

    /** (v942) نفس اسم toggle بوضوح — MtBar بيناديها */
    fun toggleTranslate() { toggle() }

    /** (v942) إيقاف مؤقت للترجمة (زي Player.pauseTranslate): المحرك مايبعتش مقاطع جديدة لحد ما يتكمّل */
    fun pauseTranslate(): Boolean {
        val e = eng
        if (!on || e == null) return false
        e.userPaused = true
        ui.post { refreshBtn() }
        return true
    }

    /** (v942) كمّل الترجمة بعد إيقاف مؤقت */
    fun resumeTranslate(): Boolean {
        val e = eng
        if (!on || e == null) return false
        if (!e.userPaused) return false
        e.userPaused = false
        ui.post { refreshBtn() }
        return true
    }

    /** (v942) الترجمة شغّالة دلوقتي؟ */
    fun isTranslating(): Boolean = on

    /** (v942) الترجمة واقفة مؤقتًا؟ — لزرار ▮▮/▶ في MtBar */
    fun isPaused(): Boolean = on && eng?.userPaused == true

    /** (v942) إيقاف الترجمة نهائي (زرار ✕ في شريط MtBar) — غير toggle اللي بيبدأ لو كانت واقفة */
    fun stopTranslate() {
        val a = act
        stopEngine()
        try {
            sv?.show(null)
        } catch (_: Throwable) {}
        ui.post { refreshBtn() }
        if (a != null) Notice.show(a, "الترجمة اتوقفت", 1600L)
    }

    /** (v942) قفز لجملة الترجمة اللي قبلها/اللي بعدها — زي ⏮ ⏭ في مشغّل Joyboy */
    fun jumpSentence(dir: Int): Boolean {
        val p = player ?: return false
        val list = eng?.subs ?: sideSubs ?: return false
        if (list.isEmpty()) return false
        val pos = try {
            p.currentPosition
        } catch (_: Throwable) {
            return false
        }
        val cur = list.indexOfFirst { it.end * 1000 > pos }.coerceAtLeast(0)
        val target = if (dir > 0) (cur + 1).coerceAtMost(list.size - 1) else (cur - 1).coerceAtLeast(0)
        if (target == cur) return false
        return try {
            p.seekTo((list[target].start * 1000).toLong())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** (v942) إزاحة الترجمة بمقدار — للريموت (shift) وشريط التزامن */
    fun shiftOffset(d: Long) {
        val a = act ?: return
        setOffset(a, offsetMs + d)
    }

    /** (v942) صفّر إزاحة الترجمة */
    fun resetOffset() {
        val a = act ?: return
        setOffset(a, 0L)
    }

    /** (v942) إزاحة الترجمة الحالية (مللي ثانية) — لشريط التزامن في MtBar */
    fun offsetMs(): Long = offsetMs

    /** (v942) أعد تحميل ستايل السبتايتل فورًا (بعد أوامر ريموت بتغيّر Cfg) */
    fun restyle() {
        try {
            sv?.style = SubStyle.load { k, d -> Cfg.str(k, d) }
        } catch (_: Throwable) {}
    }

    /** (v942) تصدير الترجمة الحالية .srt في «تنزيلات/Subtitler» */
    fun exportSrt(): Boolean {
        val a = act ?: return false
        val list = eng?.subs ?: sideSubs ?: return false
        if (list.isEmpty()) return false
        val sb = StringBuilder()
        fun fmtSrt(sec: Double): String {
            val ms = (sec * 1000).toLong().coerceAtLeast(0L)
            val h = ms / 3600000L
            val m = (ms % 3600000L) / 60000L
            val s = (ms % 60000L) / 1000L
            val r = ms % 1000L
            return String.format("%02d:%02d:%02d,%03d", h, m, s, r)
        }
        for ((i, q) in list.withIndex()) {
            sb.append(i + 1).append('\n')
            sb.append(fmtSrt(q.start)).append(" --> ").append(fmtSrt(q.end)).append('\n')
            sb.append(q.translated.ifBlank { q.original }).append("\n\n")
        }
        val name = "joyboy_" + System.currentTimeMillis() + ".srt"
        return try {
            val uriOut = if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Subtitler")
                }
                a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            } else {
                null
            }
            if (uriOut != null) {
                a.contentResolver.openOutputStream(uriOut)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
                Notice.show(a, "📄 اتصدّرت الترجمة: تنزيلات/Subtitler/" + name, 3000L)
                true
            } else {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Subtitler")
                dir.mkdirs()
                val f = java.io.File(dir, name)
                f.writeText(sb.toString(), Charsets.UTF_8)
                Notice.show(a, "📄 اتصدّرت الترجمة: تنزيلات/Subtitler/" + name, 3000L)
                true
            }
        } catch (t: Throwable) {
            LogStore.err("MtTranslate:exportSrt", t)
            Notice.show(a, "❌ التصدير فشل", 2400L)
            false
        }
    }

    /** (v942) نتيجة ترجمة جايّة من الموبايل (SfJob) — زي Player.subFromPhone: 0 اتطبّقت · 1 مفيش مشغّل · 2 مرفوضة · 3 خسر السباق */
    fun applyPhoneSubs(jid: String, texts: List<String>, failed: Int, eng: String): Int {
        val a = act ?: return 1
        val cs = SfJob.cues
        if (SfJob.id != jid || cs == null || texts.size != cs.size) return 2
        if (failed * 2 > cs.size) {
            LogStore.add("💬 سوفت صب: رفضت نتيجة الموبايل — فشل " + failed + "/" + cs.size)
            return 2
        }
        if (!SfJob.claim(jid, "phone")) return 3
        val name = "الموبايل (" + (if (eng == "gemini") "جيميناي" else "جوجل") + ")"
        val subs = subsFromCues(cs, texts)
        ui.post { applySide(a, subs, name) }
        return 0
    }

    private fun stopEngine() {
        on = false; token++
        val e = eng; val wasId: String? = engVid.ifEmpty { null }; val wasKey = engKey; val ctx0 = act?.applicationContext; eng = null; engVid = ""
        if (e != null) Thread {
            try { e.saveNow() } catch (_: Throwable) {}; try { e.stop() } catch (_: Throwable) {}
            if (wasId != null && privIds.contains(wasId) && wasKey.isNotEmpty()) try { ctx0?.let { c -> File(File(c.filesDir, "progress"), Store.keyFor(wasKey) + ".json").delete() } } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
        cached = null
    }

    /** (v942) وضع محرك الترجمة المحفوظ: gemini | google | whisper | transcribe — مفتاح Cfg جديد mt_tr_mode (مفيش مفتاح موجود بيخزّن القيم دي؛ tr_mode_id بتاع Player بيخزّن معرّفات بريست) */
    fun curEngineMode(): String {
        val m = try { Cfg.str("mt_tr_mode", "gemini") } catch (_: Throwable) { "gemini" }
        return if (m == "google" || m == "whisper" || m == "transcribe") m else "gemini"
    }

    /** (v942) غيّر وضع محرك الترجمة — بيتطبّق على الترجمة الجاية (أو بعد إيقاف/تشغيل) */
    fun setEngineMode(mode: String) {
        val m = mode.trim().lowercase()
        if (m != "gemini" && m != "google" && m != "whisper" && m != "transcribe") {
            act?.let { Notice.show(it, "وضع ترجمة مش معروف: $mode", 2000L) }
            return
        }
        try { Cfg.put("mt_tr_mode", m) } catch (t: Throwable) { LogStore.err("MtTranslate:engMode", t) }
        if (m == "google") act?.let { Notice.show(it, "جوجل للملفات بس — الترجمة الحية هنا بجيميناي", 2600L) }
    }

    private fun start() {
        val a = act ?: return
        val id = curId()
        if (id == null) { Notice.show(a, "مش قادر أعرف الفيديو ده للترجمة", 2600L); return }
        val conf0 = try { Cfg.snapshot() } catch (e: Throwable) { LogStore.err("MtTranslate:conf", e); return }
        if (conf0.keys.isEmpty()) { Notice.show(a, "ضيف مفتاح جيميناي من إعدادات Joyboy الأول", 3000L); return }
        val mode = curEngineMode()
        if (mode == "google") {
            Notice.show(a, "جوجل للملفات بس — الترجمة الحية هنا بجيميناي", 3000L)
        }
        if (mode == "whisper") {
            if (Offline.configured()) {
                try { Cfg.put("stt_offline", "1") } catch (_: Throwable) {}
            } else {
                Notice.show(a, "وشبر مش متظبط على الجهاز — الترجمة الحية بجيميناي", 3000L)
            }
        }
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
            if (lu != null) {
                try { info?.headers()?.let { hdr.putAll(it) } } catch (_: Throwable) {}
                vid = idOverride[id] ?: localId ?: "f:$lu"
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
                    if (mode == "transcribe") it.transOnly = true
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
                try { e.translateFrom(posSec) } catch (t: Throwable) { LogStore.err("MtTranslate:from", t) }
            }
            try { e.run() } catch (t: Throwable) { LogStore.err("MtTranslate:run", t) }
        }.apply { isDaemon = true }.start()
    }

    /** (v924) يبعت الفيديو الشغّال للتلفزيون (من نفس المكان) */
    fun sendToTv() {
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

    fun cycleDual() {
        val a = act ?: return
        val n = dualCycle[(dualCycle.indexOf(curDual()) + 1) % dualCycle.size]
        Cfg.put("sub_dual", n.toString())
        sv?.style = SubStyle.load { k, d -> Cfg.str(k, d) }
        Notice.show(a, "💬 وضع الترجمة: " + (dualShort[n] ?: "فردي"), 1600L)
    }

    private fun fmtT(sec: Double): String { val t = sec.toInt().coerceAtLeast(0); return String.format("%d:%02d", t / 60, t % 60) }

    /** (v934) قايمة الجمل: اختار جملة ← المشغّل يروح لوقتها */
    fun showSentences() {
        val a = act ?: return
        val list = eng?.subs
        if (list == null || list.isEmpty()) { Notice.show(a, "مفيش جمل مترجمة لسه", 2000L); return }
        val cur = list.indexOfFirst { it.end * 1000 >= (player?.currentPosition ?: 0L) }.coerceAtLeast(0)
        val from = (cur - 20).coerceAtLeast(0); val to = (from + 300).coerceAtMost(list.size)
        val names = Array(to - from) { k -> val q = list[from + k]; "${from + k + 1}. [" + fmtT(q.start) + "] " + q.translated.ifBlank { q.original }.replace('\n', ' ').take(90) }
        GAlert(a).setTitle("📝 الجمل (${list.size})").setItems(names) { _, i -> try { player?.seekTo((list[from + i].start * 1000).toLong()) } catch (_: Throwable) {} }
            .setNegativeButton("إغلاق", null).show()
    }

    fun setOffset(a: Activity, v: Long) {
        offsetMs = v.coerceIn(-10000L, 10000L)
        Notice.show(a, "إزاحة الترجمة: " + String.format("%+.2f", offsetMs / 1000.0) + "ث", 1400L)
    }

    // ===================== (v935 م2) باقي أدوات Joyboy فوق مشغّل MeeTube =====================

    /** 🗂 النسخ المحفوظة (لهجات الترجمة): احفظ الحالية / بدّل لنسخة */
    fun showVersions() {
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

    fun showStyle() {
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

    /** (v942) Sub من cues ملف ترجمة + ترجمات اختيارية (مشترك بين sideSubMenu وsearchOnlineSubs وimportSub) */
    private fun subsFromCues(cues: List<SubFileTr.Cue>, texts: List<String>?): List<Sub> {
        return cues.mapIndexed { i, c ->
            val tr = texts?.getOrNull(i) ?: ""
            Sub(c.start, c.end, c.text, if (tr.isBlank()) c.text else tr, "male", "unknown", "none", emptyList(), emptyList(), false, false)
        }
    }

    /** 📄 سبتايتل جانبي: ملف .srt/.vtt — عربي يتعرض علطول، غير كده يتترجم (جيميناي لو في مفتاح وإلا جوجل) */
    fun sideSubMenu() {
        val a = act ?: return
        if (sideSubs != null) { sideSubs = null; sv?.show(null); cached = null; Notice.show(a, "اتشال السبتايتل الجانبي", 1600L); return }
        SubPick.pick(a) { uri ->
            if (uri == null) return@pick
            Thread {
                val pk = SubPick.read(a.applicationContext, uri)
                if (pk == null) { ui.post { Notice.show(a, "الملف مش ملف ترجمة مقروء", 2400L) }; return@Thread }
                val cues = SubFileTr.parse(pk.text)
                if (SubFileTr.mostlyArabic(cues)) { ui.post { applySide(a, subsFromCues(cues, null), pk.name) }; return@Thread }
                val engName = if (SubFileTr.hasGemKeys()) "gemini" else "google"
                val tvJob = try { DeviceUi.isTv(a) } catch (_: Throwable) { false }
                val jid = if (tvJob) UUID.randomUUID().toString() else ""
                if (tvJob) SfJob.begin(jid)
                if (tvJob) SfJob.cues = cues
                ui.post { Notice.show(a, "📄 بترجم «" + pk.name + "» (" + cues.size + " جملة) بـ" + (if (engName == "gemini") "جيميناي" else "جوجل") + "…", 2600L) }
                var last = 0L
                val out = try { SubFileTr.translate(cues, engName, { pr -> val n = System.currentTimeMillis(); if (n - last > 2500L) { last = n; ui.post { act?.let { Notice.show(it, pr, 1500L) } } } }, { act == null || (tvJob && SfJob.winner == "phone") }) } catch (t: Throwable) { LogStore.err("MtTranslate:side", t); null }
                if (tvJob && (out == null || out.failed * 2 > cues.size) && SfJob.phoneAlive()) {
                    SfJob.tvFailed = true
                    var wl = 0
                    while (SfJob.winner.isEmpty() && SfJob.phoneAlive() && act != null && wl++ < 3600) {
                        try { Thread.sleep(500L) } catch (_: InterruptedException) {}
                    }
                }
                ui.post {
                    if (tvJob && SfJob.winner == "phone") { Notice.show(a, "📱 الموبايل خلّص الترجمة الأول", 2600L); return@post }
                    if (out == null) { Notice.show(a, "❌ ترجمة الملف فشلت", 2600L); return@post }
                    if (out.failed * 2 > cues.size) { Notice.show(a, "⚠ أغلب الجمل فشلت — ما طبّقتش الترجمة", 2600L); return@post }
                    if (tvJob && !SfJob.claim(jid, "tv")) return@post
                    applySide(a, subsFromCues(cues, out.texts), pk.name)
                }
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
    fun dubToggle() {
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

    fun dubVoiceSwap() {
        val a = act ?: return
        val v = Dub.toggleVoice(); dubPlay?.stop()
        Notice.show(a, "🗣 الصوت دلوقتي: " + Dub.voiceLabel(v) + " — دوس دبلجة تاني عشان تتبنى بالصوت الجديد", 3000L)
    }

    /** ⬇ تحميل اللينك المباشر الشغّال (بنفس هيدرزه وصوته المنفصل) كمهمة في تبويب «المهام» */
    fun downloadCur() {
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

    // ===================== (v942) قوايم bottom-sheet =====================

    /** (v942) صفوف شيت: Pair(العنوان، الفعل) — فعل null = عنوان قسم مش clickable */
    private fun openSheet(a: Activity, title: String, items: List<Pair<String, (() -> Unit)?>>) {
        val views = ArrayList<View>()
        val u = Ui(a, Themes.current())
        var d: android.app.Dialog? = null
        for ((label, f) in items) {
            if (f == null) {
                views.add(u.text(label, 15f, u.th.primary, true).apply { setPadding(u.dp(4), u.dp(12), u.dp(4), u.dp(2)) })
            } else {
                views.add(u.button(label) { d?.dismiss(); f() })
            }
        }
        d = u.sheet(a, title, views)
        d.show()
    }

    /** (v942) شيت من Views جاهزة (نصوص/أزرار) */
    private fun openSheetViews(a: Activity, title: String, views: List<View>) {
        val u = Ui(a, Themes.current())
        val d = u.sheet(a, title, views)
        d.show()
    }

    /** (v935 م2) قايمة أدوات الترجمة فوق مشغّل MeeTube — (v942) بقت bottom-sheet */
    fun showMenu() {
        val a = act ?: return
        val items = ArrayList<Pair<String, (() -> Unit)?>>()
        fun m(label: String, f: () -> Unit) { items.add(Pair(label, f)) }
        m((if (on) "⏹ وقّف الترجمة" else "🌐 ابدأ الترجمة")) { toggle() }
        m("⚙ نظام الترجمة (جيميناي / وشبر / تفريغ…)") { translateMenu() }
        m(("💬 وضع الترجمة: " + (dualShort[curDual()] ?: "فردي") + "  (اضغط للتبديل)")) { cycleDual() }
        m("📝 قايمة الجمل") { showSentences() }
        m("🔎 ابحث في الجمل") { searchSentences() }
        m("🗂 النسخ المحفوظة (اللهجات)") { showVersions() }
        m("🎨 ستايل السبتايتل") { showStyle() }
        m((if (sideSubs != null) "✖ شيل السبتايتل الجانبي" else "📄 سبتايتل جانبي (.srt / .vtt)")) { sideSubMenu() }
        m(("⏩ قدّم الترجمة ربع ثانية" + (if (offsetMs != 0L) "  (الحالي ${String.format("%+.2f", offsetMs / 1000.0)}ث)" else ""))) { setOffset(a, offsetMs + 250L) }
        m("⏪ أخّر الترجمة ربع ثانية") { setOffset(a, offsetMs - 250L) }
        m("↺ صفّر إزاحة الترجمة") { setOffset(a, 0L) }
        m((if (dubPlay?.active == true) "🎙 وقّف الدبلجة" else "🎙 دبلجة بصوت " + Dub.voiceLabel())) { dubToggle() }
        m("🗣 بدّل صوت الدبلجة (سلمى/شاكر)") { dubVoiceSwap() }
        m("🔊 مسارات الصوت") { showAudioTracks() }
        m("💬 سوفت سب أصلي (جوه الفيديو)") { showSoftSubs() }
        m("🌍 ترجمة أونلاين") { searchOnlineSubs() }
        m("⬇ حمّل الفيديو") { downloadCur() }
        m("📺 ابعت للتلفزيون") { sendToTv() }
        if (localUri() != null) {
            m(MtMedia.abLabel()) { MtMedia.abNext() }
            m(("🔁 وضع التكرار: " + MtMedia.loopLabel() + "  (اضغط للتبديل)")) { MtMedia.loopCycle() }
            m("⏮ الحلقة اللي قبلها") { MtMedia.step(-1) }
            m("⏭ الحلقة اللي بعدها") { MtMedia.step(1) }
            m("📃 الليستة (طابور التشغيل)") { PlayQueueUi.show(a) }
        }
        m("⚙ إعدادات محرك الترجمة") { MeeTubeLink.openSettings(a, "tr_engine") }
        openSheet(a, "🌐 أدوات الترجمة والميديا", items)
    }

    /** (v942) قايمة نظام الترجمة — نفس بنود Player.translateMenu بس bottom-sheet: جيميناي ٧٥/٢٥ مقابل الأدق، وشبر أوفلاين، تفريغ بس، إيقاف/كمّل، إعادة ترجمة، تقدم الباتشات، تغيير النظام */
    fun translateMenu() {
        val a = act ?: return
        val items = ArrayList<Pair<String, (() -> Unit)?>>()
        fun m(label: String, f: () -> Unit) { items.add(Pair(label, f)) }
        fun h(label: String) { items.add(Pair(label, null)) }
        val e = eng
        val pct = if (on && e != null && durSec > 0) PlayerLogic.percent(e.coveredSec(), durSec).toString() + "%" else "—"
        val paused = e?.userPaused == true
        h("الترجمة: " + (if (!on) "واقفة" else pct + (if (paused) " (واقفة مؤقتًا)" else "")) + " · الوضع: " + curEngineMode())
        h("🔤 نظام الترجمة")
        val dm = try { Cfg.int("dual_listen", 1) } catch (_: Throwable) { 1 }
        m("☁ جيميناي — ٧٥/٢٥ (سماع واحد)" + (if (dm == 0) " ✓ الحالي" else "")) {
            Cfg.put("dual_listen", "0")
            Notice.show(a, "☁ جيميناي ٧٥/٢٥ — هيتطبق على الباتشات الجاية", 2200L)
        }
        m("🎯 جيميناي — السماع الأدق (مفتاحين + حكم)" + (if (dm == 2) " ✓ الحالي" else "")) {
            Cfg.put("dual_listen", "2")
            Notice.show(a, "🎯 السماع الأدق — هيتطبق على الباتشات الجاية", 2200L)
        }
        m("📴 وشبر + ترجمة أوفلاين" + (if (curEngineMode() == "whisper") " ✓ الحالي" else "")) {
            if (!Offline.configured()) { Notice.show(a, "📴 وشبر مش متظبط على الجهاز — ظبّطه من إعدادات الترجمة الأول", 3000L); return@m }
            setEngineMode("whisper")
            Notice.show(a, "📴 وشبر — هيتطبق لما تعيد الترجمة (أو دوس «🔀 غيّر النظام»)", 3000L)
        }
        m("✍ تفريغ بس — جيميناي (من غير ترجمة)" + (if (curEngineMode() == "transcribe") " ✓ الحالي" else "")) {
            setEngineMode("transcribe")
            Notice.show(a, "✍ تفريغ بس — هيتطبق لما تعيد الترجمة", 2600L)
        }
        m("🌐 جوجل — من غير مفتاح (للملفات بس)") {
            setEngineMode("google")
        }
        if (on && e != null) {
            h("🛠 أدوات")
            if (paused) m("▶ كمّل الترجمة") { resumeTranslate() } else m("⏸ إيقاف مؤقت") { pauseTranslate() }
            m("🔁 إعادة ترجمة الجملة الحالية") {
                try { e.redoFrom(e.chunkOfSec(posSec)) } catch (t: Throwable) { LogStore.err("MtTranslate:redoCur", t) }
            }
            m("🔁 إعادة ترجمة الفيديو كله") {
                try { e.redoAll(true) } catch (t: Throwable) { LogStore.err("MtTranslate:redoAll", t) }
                Notice.show(a, "🔁 بيعيد ترجمة الفيديو كله", 2000L)
            }
            m("📊 تقدم الباتشات") { showBatches() }
        }
        h("⚙ الإعدادات")
        m("🔀 غيّر نظام الترجمة (إيقاف + اختيار)") {
            stopEngine(); sv?.show(null); refreshBtn()
            translateMenu()
        }
        m("⚙ إعدادات الترجمة") { MeeTubeLink.openSettings(a, "tr_engine") }
        openSheet(a, "⚙ نظام الترجمة", items)
    }

    /** (v942) تقدم الباتشات — نفس بيانات Player.batchDialog بس شيت نصوص */
    private fun showBatches() {
        val a = act ?: return
        val e = eng
        if (e == null) { Notice.show(a, "ابدأ الترجمة الأول", 1800L); return }
        val u = Ui(a, Themes.current())
        val views = ArrayList<View>()
        val rows = try { e.batches() } catch (_: Throwable) { emptyList() }
        if (rows.isEmpty()) {
            views.add(u.text("مفيش باتشات لسه", 14f, u.th.muted))
        } else {
            for (b in rows) {
                views.add(u.text("باتش ${b.idx + 1}  ${fmtT(b.start)}–${fmtT(b.end)}  ${b.mark}  (${b.count} جملة)", 14f, u.th.text))
            }
        }
        openSheetViews(a, "📊 تقدم الباتشات", views)
    }

    // ===================== (v942) مسارات الصوت والسوفت سب الأصلية =====================

    /** (v942) مسارات الصوت بتاعة ExoPlayer (ملفات/لينكات متعددة المسارات) — parseATracks/switchAudio بتوع Player مش قابلين للنقل هنا: مفيش qlist على مشغّل MeeTube */
    fun showAudioTracks() {
        val a = act ?: return
        val p = player ?: return
        val items = ArrayList<Triple<androidx.media3.common.Tracks.Group, Int, String>>()
        try {
            for (g in p.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val lg = f.language ?: ""
                    val ln = if (lg.isBlank() || lg == "und") "" else try { java.util.Locale(lg).getDisplayLanguage(java.util.Locale("ar")).ifBlank { lg } } catch (_: Throwable) { lg }
                    val ch = when (f.channelCount) { 1 -> "مونو"; 2 -> "ستيريو"; 6 -> "5.1"; 8 -> "7.1"; else -> "" }
                    val nm = listOf(f.label?.trim().orEmpty().ifBlank { ln }, ch).filter { it.isNotBlank() }.joinToString(" · ")
                    items.add(Triple(g, i, nm.ifBlank { "مسار صوت " + (items.size + 1) }))
                }
            }
        } catch (t: Throwable) { LogStore.err("MtTranslate:audTracks", t) }
        if (items.isEmpty()) { Notice.show(a, "مفيش غير مسار صوت واحد في الفيديو ده", 2200L); return }
        val rows = ArrayList<Pair<String, (() -> Unit)?>>()
        rows.add(Pair("🔊 مسارات الصوت (${items.size})", null))
        for ((g, i, nm) in items) {
            rows.add(Pair(nm, {
                try {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    Notice.show(a, "🔊 لغة الصوت: $nm", 2000L)
                } catch (t: Throwable) { LogStore.err("MtTranslate:audPick", t) }
            }))
        }
        rows.add(Pair("🔊 تلقائي (اختيار المشغّل)", {
            try {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            } catch (t: Throwable) { LogStore.err("MtTranslate:audAuto", t) }
        }))
        openSheet(a, "🔊 مسارات الصوت", rows)
    }

    /** (v942) مسارات السوفت سب الأصلية جوه الفيديو (ExoPlayer text tracks) — العرض في SubtitleView بتاعة MeeTube نفسها (Media3SubtitleManager)، وسبتايتل Joyboy بيتكتم وهي شغّالة */
    fun showSoftSubs() {
        val a = act ?: return
        val p = player ?: return
        val items = ArrayList<Triple<androidx.media3.common.Tracks.Group, Int, String>>()
        try {
            for (g in p.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val lg = f.language ?: ""
                    val ln = if (lg.isBlank() || lg == "und") "" else try { java.util.Locale(lg).getDisplayLanguage(java.util.Locale("ar")).ifBlank { lg } } catch (_: Throwable) { lg }
                    val nm = (f.label?.trim().orEmpty().ifBlank { ln }).ifBlank { "مسار ترجمة " + (items.size + 1) }
                    items.add(Triple(g, i, nm))
                }
            }
        } catch (t: Throwable) { LogStore.err("MtTranslate:softTracks", t) }
        if (items.isEmpty() && !nativeCcOn) { Notice.show(a, "مفيش سوفت سب جوه الفيديو ده", 2200L); return }
        val rows = ArrayList<Pair<String, (() -> Unit)?>>()
        rows.add(Pair("💬 سوفت سب أصلي (${items.size})", null))
        for ((g, i, nm) in items) {
            rows.add(Pair(nm, {
                try {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    nativeCcOn = true
                    sv?.show(null)
                    Notice.show(a, "💬 سوفت سب: $nm", 2000L)
                } catch (t: Throwable) { LogStore.err("MtTranslate:softPick", t) }
            }))
        }
        rows.add(Pair("✖ اقفل السوفت سب الأصلي", {
            try {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            } catch (t: Throwable) { LogStore.err("MtTranslate:softOff", t) }
            nativeCcOn = false
        }))
        openSheet(a, "💬 سوفت سب أصلي", rows)
    }

    // ===================== (v942) ترجمة أونلاين + بحث الجمل =====================

    private fun vidFolder(): String {
        return try {
            val u = localUri() ?: return ""
            val uri = android.net.Uri.parse(u)
            if (uri.scheme == "file") File(uri.path ?: "").parentFile?.name ?: "" else ""
        } catch (_: Throwable) { "" }
    }

    /** (v942) ترجمة أونلاين: بحث تلقائي باسم الفيديو + نتيجة تانية + صيد من المتصفح (زي Player.subNet/subHunt) */
    fun searchOnlineSubs() {
        val a = act ?: return
        val title = try { info?.title() ?: "" } catch (_: Throwable) { "" }
        if (title.isBlank() && localUri() == null) { Notice.show(a, "مش قادر أعرف اسم الفيديو للبحث", 2400L); return }
        val rows = ArrayList<Pair<String, (() -> Unit)?>>()
        rows.add(Pair("🌍 جيب ترجمة أونلاين تلقائي (باسم الفيديو)", {
            subNet(a, "auto")
        }))
        if ((netSess?.remaining() ?: 0) > 0) {
            rows.add(Pair("⏭ جرّب نتيجة تانية (" + netSess!!.remaining() + " متبقية)", {
                subNet(a, "next")
            }))
        }
        rows.add(Pair("🌐 افتح المتصفح على موقع ترجمة", null))
        SubHunt.siteNames.forEachIndexed { w, name ->
            rows.add(Pair(name, {
                try {
                    val info2 = SubName.build(title.ifBlank { localUri() ?: "" }, vidFolder())
                    SubHunt.start(a, info2, w) { g -> applyGot(a, g) }
                } catch (t: Throwable) { LogStore.err("MtTranslate:subHunt", t) }
            }))
        }
        openSheet(a, "🌍 ترجمة أونلاين", rows)
    }

    private fun subNet(a: Activity, mode: String) {
        if (netBusy.isNotEmpty()) { Notice.show(a, "⏳ $netBusy", 2000L); return }
        val title = try { info?.title() ?: "" } catch (_: Throwable) { "" }
        val info2 = SubName.build(title.ifBlank { localUri() ?: "" }, vidFolder())
        val langs = SubOnline.prefLangs()
        netBusy = "بدوّر على ترجمة…"
        val appCtx = a.applicationContext
        Thread {
            try {
                val rep: (String) -> Unit = { m -> netBusy = m }
                var got: SubOnline.Got? = null
                if (mode == "next") {
                    val s = netSess
                    if (s == null || s.remaining() <= 0) { ui.post { Notice.show(a, "مفيش نتايج تانية", 3000L) }; return@Thread }
                    got = SubOnline.fetchNext(appCtx, s, rep)
                } else {
                    val cached = SubOnline.cacheFind(appCtx, info2, langs)
                    if (cached != null && cached.lang == langs[0]) got = cached
                    else {
                        val hits = SubOnline.search(info2, langs, rep)
                        val s = SubOnline.Session(info2, langs, hits); netSess = s
                        if (hits.isNotEmpty()) got = SubOnline.fetchNext(appCtx, s, rep)
                        if (got == null) got = cached
                        if (hits.isEmpty() && got == null) { ui.post { Notice.show(a, "🌍 مفيش نتايج لـ «" + info2.label() + "» — جرّب المتصفح (🌐)", 5000L) }; return@Thread }
                    }
                }
                if (got == null) { ui.post { Notice.show(a, "⚠ ما قدرتش أنزّل ترجمة مناسبة — جرّب «⏭ نتيجة تانية» أو المتصفح", 5000L) }; return@Thread }
                applyGot(a, got)
            } catch (t: Throwable) {
                LogStore.err("MtTranslate:subNet", t)
                ui.post { Notice.show(a, "⚠ فشل جلب الترجمة", 3000L) }
            } finally {
                netBusy = ""
            }
        }.apply { isDaemon = true }.start()
    }

    /** (v942) ترجمة جاهزة (أونلاين/متصفح) ← تتحط كسبتايتل جانبي؛ عربي يتعرض علطول وإلا تتترجم زي sideSubMenu */
    private fun applyGot(a: Activity, g: SubOnline.Got): Boolean {
        val cues = g.cues
        if (cues.isEmpty()) return false
        if (SubFileTr.mostlyArabic(cues)) {
            ui.post { applySide(a, subsFromCues(cues, null), g.fileName) }
            return true
        }
        val engName = if (SubFileTr.hasGemKeys()) "gemini" else "google"
        Thread {
            val out = try { SubFileTr.translate(cues, engName, {}, { act == null }) } catch (t: Throwable) { LogStore.err("MtTranslate:netTr", t); null }
            ui.post {
                if (out == null) { Notice.show(a, "❌ ترجمة الملف فشلت", 2600L); return@post }
                applySide(a, subsFromCues(cues, out.texts), g.fileName)
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    /** (v942) بحث في الجمل المترجمة/الجانبية: اكتب ← النتايج تحت، دوسة = قفز لوقت الجملة */
    fun searchSentences() {
        val a = act ?: return
        val list = eng?.subs ?: sideSubs
        if (list == null || list.isEmpty()) { Notice.show(a, "مفيش جمل مترجمة لسه", 2000L); return }
        val u = Ui(a, Themes.current())
        val box = u.input("ابحث في الجمل…", "")
        val results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        var d: android.app.Dialog? = null
        fun rebuild(q: String) {
            results.removeAllViews()
            val qq = q.trim()
            val hits = list.withIndex().filter { qq.isEmpty() || it.value.translated.contains(qq, true) || it.value.original.contains(qq, true) }.take(60)
            if (hits.isEmpty()) {
                results.addView(u.text("مفيش نتايج", 14f, u.th.muted))
                return
            }
            for ((i, s) in hits) {
                val label = "${i + 1}. [" + fmtT(s.start) + "] " + s.translated.ifBlank { s.original }.replace('\n', ' ').take(90)
                results.addView(u.button(label) {
                    d?.dismiss()
                    try { player?.seekTo((s.start * 1000).toLong()) } catch (_: Throwable) {}
                })
            }
        }
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuild(s?.toString() ?: "") }
        })
        rebuild("")
        d = u.sheet(a, "🔎 ابحث في الجمل (${list.size})", listOf(box, results))
        d.show()
    }

    // ===================== (v942) حالة المحرك كـ JSON + جسر RPC =====================

    /** (v942) حالة المحرك كـ JSON — نفس الحقول اللي BrainRpc «eng» بيحطها في /sync (BrainRpc.kt:100) */
    fun engineJson(): String {
        return try {
            val e = eng
            val subs = e?.subs ?: sideSubs ?: emptyList()
            val cur = subs.firstOrNull { posSec >= it.start && posSec <= it.end }
            val curTxt = cur?.translated?.ifBlank { cur.original } ?: ""
            val pct = if (durSec > 0 && e != null) PlayerLogic.percent(e.coveredSec(), durSec) else 0
            JSONObject()
                .put("on", on)
                .put("vid", engVid.ifEmpty { curId() ?: "" })
                .put("pct", pct)
                .put("covered", e?.coveredSec() ?: 0.0)
                .put("dur", durSec)
                .put("subs", subs.size)
                .put("cur", curTxt)
                .put("dialect", e?.convDialect ?: Cfg.str("conv_dialect", ""))
                .put("userPaused", e?.userPaused ?: false)
                .put("paused", e?.paused ?: false)
                .put("mode", curEngineMode())
                .put("title", try { info?.title() ?: "" } catch (_: Throwable) { "" })
                .toString()
        } catch (_: Throwable) { "{}" }
    }

    /** (v942) جسر RPC للمحرك — بيناديه MtBar/TvLink لما الريموت يبعت engine.* (BrainRpc.exec بيوصل engine.* لـ PlayerActivity.brain؛ التوصيلة للمشغّل Mt لسه على منسّق v942) */
    fun rpc(op: String, args: JSONObject): JSONObject {
        return try {
            when (op) {
                "engine.get" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    JSONObject().put("ok", true)
                        .put("on", on)
                        .put("userPaused", e.userPaused)
                        .put("paused", e.paused)
                        .put("transOnly", e.transOnly)
                        .put("convDialect", e.convDialect)
                        .put("toneStyle", e.toneStyle)
                        .put("toneStrength", e.toneStrength)
                        .put("covered", e.coveredSec())
                        .put("pending", e.pendingCount())
                        .put("failed", e.failedCount())
                        .put("fullyDone", e.fullyDone())
                        .put("subs", e.subs.size)
                        .put("vid", engVid)
                        .put("pct", if (durSec > 0) PlayerLogic.percent(e.coveredSec(), durSec) else 0)
                }
                "engine.versions" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    val arr = JSONArray()
                    for (v in e.versions) arr.put(JSONObject().put("name", v.name).put("dialect", v.dialect).put("count", v.subs.size))
                    JSONObject().put("ok", true).put("versions", arr)
                }
                "engine.pause" -> JSONObject().put("ok", pauseTranslate())
                "engine.resume" -> JSONObject().put("ok", resumeTranslate())
                "engine.cancel" -> {
                    stopTranslate()
                    JSONObject().put("ok", true)
                }
                "engine.retranslate" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    e.redoAll(true)
                    JSONObject().put("ok", true)
                }
                "engine.translateFrom" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    e.translateFrom(args.optDouble("sec", posSec))
                    JSONObject().put("ok", true)
                }
                "engine.dialect" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    val d = args.optString("d", "")
                    e.setConvDialect(d, true)
                    try { Cfg.put("conv_dialect", d) } catch (_: Throwable) {}
                    JSONObject().put("ok", true)
                }
                "engine.tone" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    if (args.has("style")) e.toneStyle = args.optString("style")
                    if (args.has("strength")) e.toneStrength = args.optString("strength")
                    JSONObject().put("ok", true)
                }
                "engine.mode" -> {
                    setEngineMode(args.optString("mode", "gemini"))
                    JSONObject().put("ok", true).put("mode", curEngineMode())
                }
                "engine.batches" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    val arr = JSONArray()
                    for (b in e.batches()) arr.put(JSONObject().put("i", b.idx).put("s", b.start).put("e", b.end).put("c", b.count).put("m", b.mark))
                    JSONObject().put("ok", true).put("batches", arr)
                }
                "engine.chars" -> {
                    val e = eng ?: return JSONObject().put("ok", false).put("err", "noengine")
                    JSONObject().put("ok", true).put("chars", BrainChars.encode(e.charactersNow()))
                }
                else -> JSONObject().put("ok", false).put("err", "unknown")
            }
        } catch (t: Throwable) {
            try { JSONObject().put("ok", false).put("err", t.message ?: "err") } catch (_: Throwable) { JSONObject() }
        }
    }

    /** (v942) استيراد ملف ترجمة (.srt/.vtt) كسبتايتل جانبي — بيتطبّق على خيط الواجهة، فـ true معناها الملف اتقري والجمل اتبعتت للتطبيق */
    fun importSub(uri: android.net.Uri): Boolean {
        val a = act ?: return false
        return try {
            val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            val cues = SubFileTr.parse(SubFileTr.decode(bytes))
            if (cues.isEmpty()) return false
            val name = try { uri.lastPathSegment?.substringAfterLast('/') ?: "sub.srt" } catch (_: Throwable) { "sub.srt" }
            val subs = subsFromCues(cues, null)
            ui.post { applySide(a, subs, name) }
            true
        } catch (t: Throwable) {
            LogStore.err("MtTranslate:importSub", t)
            false
        }
    }

    private fun refreshBtn(state: String? = null) {
        val b = btn ?: return
        val e = eng
        b.text = when {
            state != null -> "🌐 $state"
            !on -> "🌐 ترجم"
            e?.userPaused == true -> "🌐 ⏸"
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
                    if (v != null && !nativeCcOn && ((on && e != null) || side != null)) {
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
                    if (on) {
                        val now = System.currentTimeMillis()
                        if (now - lastEngFeed >= 2000L) {
                            lastEngFeed = now
                            try {
                                if (BrainBus.active()) BrainBus.setEngine(engineJson())
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (t: Throwable) { LogStore.err("MtTranslate:tick", t) }
            ui.postDelayed(this, 120L)
        }
    }
}
