@file:OptIn(UnstableApi::class)

package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import org.schabi.newpipe.extractor.Page
import java.util.Locale

/**
 * (v402) صفحة المشاهدة (زي يوتيوب): دوسة على فيديو → المربع بتاعه بيكبر بأنيميشن من مكان الكارت لمشغّل مصغّر فوق،
 * وتحته العنوان واللايكات والقناة والاشتراك والوصف والتعليقات والمقترحات. زرار التكبير (الركن) بيفتح مشغّلك الكامل من نفس الثانية.
 * الإعداد: يوتيوب ← «لما تدوس على فيديو»: صفحة المشاهدة (الافتراضي) أو المشغّل مباشرة.
 */
object YtOpen {
    const val PAGE = "page"
    const val PLAYER = "player"
    fun mode(): String = if (Cfg.str("yt_open_mode", PAGE) == PLAYER) PLAYER else PAGE
    fun autoPlay(): Boolean = Cfg.str("yt_mini_auto", "1") != "0"
}

interface YtWatchHost {
    fun expand(id: String, title: String, posMs: Long, translate: Boolean, pick: YtPick?)
    fun openChannel(url: String, name: String)
    fun openVideo(v: NpItem)
    fun audio(v: NpItem)
    fun download(anchor: View, v: NpItem)
    fun state(open: Boolean)
    fun dock(item: NpItem, kept: YtKept) {}   // (v417) تصغير لشريط تحت: المشغّل بيكمّل شغّال
}

/** أيقونات مرسومة (من غير خطوط): 0 تشغيل · 1 إيقاف · 2 تكبير · 3 سهم لتحت */
private class YtGlyph(ctx: Context, var kind: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h)
        if (s <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        p.color = Color.WHITE
        when (kind) {
            0 -> {
                p.style = Paint.Style.FILL
                path.reset(); path.moveTo(cx - s * 0.14f, cy - s * 0.22f); path.lineTo(cx - s * 0.14f, cy + s * 0.22f); path.lineTo(cx + s * 0.22f, cy); path.close()
                c.drawPath(path, p)
            }
            1 -> {
                p.style = Paint.Style.FILL
                c.drawRect(cx - s * 0.18f, cy - s * 0.2f, cx - s * 0.06f, cy + s * 0.2f, p)
                c.drawRect(cx + s * 0.06f, cy - s * 0.2f, cx + s * 0.18f, cy + s * 0.2f, p)
            }
            2 -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f; p.strokeCap = Paint.Cap.ROUND
                val a = s * 0.27f; val l = s * 0.15f
                c.drawLine(cx - a, cy - a, cx - a + l, cy - a, p); c.drawLine(cx - a, cy - a, cx - a, cy - a + l, p)
                c.drawLine(cx + a, cy - a, cx + a - l, cy - a, p); c.drawLine(cx + a, cy - a, cx + a, cy - a + l, p)
                c.drawLine(cx - a, cy + a, cx - a + l, cy + a, p); c.drawLine(cx - a, cy + a, cx - a, cy + a - l, p)
                c.drawLine(cx + a, cy + a, cx + a - l, cy + a, p); c.drawLine(cx + a, cy + a, cx + a, cy + a - l, p)
            }
            4 -> {
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.2f, s * 0.07f, p); c.drawCircle(cx, cy, s * 0.07f, p); c.drawCircle(cx, cy + s * 0.2f, s * 0.07f, p)
            }
            else -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                path.reset(); path.moveTo(cx - s * 0.2f, cy - s * 0.08f); path.lineTo(cx, cy + s * 0.12f); path.lineTo(cx + s * 0.2f, cy - s * 0.08f)
                c.drawPath(path, p)
            }
        }
    }
}

/** (v429) رابط صوت اتجاب بالفعل في صفحة المشاهدة — Main.playYtAudio بيستعمله بدل جلب جديد (صالح 20 ثانية) */
object YtAudioHint {
    class H(val id: String, val url: String, val ua: String, val pos: Long, val at: Long)
    @Volatile private var h: H? = null
    fun put(id: String, url: String, ua: String, pos: Long) { h = H(id, url, ua, pos, System.currentTimeMillis()) }
    fun take(id: String): H? { val x = h; h = null; return if (x != null && x.id == id && System.currentTimeMillis() - x.at < 20_000L) x else null }
}

/** (v406) تسليم المشغّل: زرار التكبير بيدّي نفس الـ ExoPlayer (شغّال ومحمّل) للمشغّل الكامل من غير ما يعيد التحميل. بيتنسى بعد 12 ثانية لو محدّش أخده */
object YtHandoff {
    /** (v606) التسليم ده بسبب الخروج من التطبيق (Home) والفيديو شغّال: المشغّل الكامل يدخل PiP أول ما يفتح */
    @Volatile var pipNext = false
    @Volatile private var p: ExoPlayer? = null
    private val hh = Handler(Looper.getMainLooper())
    private val expire = Runnable { val x = p; p = null; try { x?.release() } catch (_: Throwable) {} }
    fun put(x: ExoPlayer) {
        val old = p; p = x
        hh.removeCallbacks(expire); hh.postDelayed(expire, 12000L)
        if (old != null && old !== x) try { old.release() } catch (_: Throwable) {}
    }
    fun take(): ExoPlayer? { hh.removeCallbacks(expire); val x = p; p = null; return x }
}

/** (v406) علامة «مدبلج» على كروت القوايم: بنفحص لغات الصوت للفيديوهات الظاهرة (خيطين خلفيين، بعد ما الكارت يفضل ظاهر لحظة) ونكاشّي النتيجة */
object YtDub {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val queued = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(List<String>) -> Unit>>()
    private val pool = java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingDeque<Runnable>(40), { r -> Thread(r).apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    fun enabled(): Boolean = Cfg.str("yt_dub_scan", "1") != "0"
    fun get(id: String): List<String>? = cache[id]
    fun set(id: String, langs: List<String>) { cache[id] = langs }
    /** cb بتتنادى على خيط خلفي — الـ UI لازم يعمل runOnUiThread */
    fun request(id: String, cb: (List<String>) -> Unit) {
        if (!enabled() || id.length != 11) return
        cache[id]?.let { cb(it); return }
        waiters.getOrPut(id) { java.util.concurrent.CopyOnWriteArrayList() }.add(cb)
        val now = System.currentTimeMillis()
        val t = queued[id]
        if (t != null && now - t < 45_000L) return
        queued[id] = now
        pool.execute {
            val l = try { YtNp.init(); YtNp.audioLangs(id) } catch (_: Throwable) { null }
            if (l != null) cache[id] = l
            val ws = waiters.remove(id)
            if (l != null && ws != null) for (w in ws) try { w(l) } catch (_: Throwable) {}
        }
    }
    fun isDubbed(l: List<String>?): Boolean = l != null && l.size > 1
}

/** مشغّل مصغّر (نسخة صغيرة من المشغّل): ExoPlayer + تحكم (تشغيل/إيقاف · شريط · زرار تكبير في الركن · سهم تصغير) */
class YtMiniPlayer(private val act: Activity, private val ui: Ui) : FrameLayout(act) {
    var onExpand: (Long, YtPick?) -> Unit = { _, _ -> }
    var onCollapse: () -> Unit = {}
    var onEnded: (() -> Unit)? = null   // (v469) C9: خلص الفيديو — صفحة المشاهدة بتشغّل اللي بعده من قائمة الانتظار
    private class Ent(val label: String, val url: String, val audio: String, val h: Int)
    private class Lg(val name: String, val code: String, val url: String, val q: List<Pair<String, String>>)
    private val ents = ArrayList<Ent>()
    private val lgs = ArrayList<Lg>()
    private val aq = ArrayList<Pair<String, String>>()
    private var curEnt = 0
    private var lgIdx = -1
    private var audOvr: String? = null
    private var qRaw = ""
    private var curPick: YtPick? = null
    private var exo: ExoPlayer? = null
    private var lastPick: YtPick? = null
    private var resumeMs = 0L
    /** (v586) اتسلّم للمشغّل الكامل: مكان الاستكمال بعد الرجوع بييجي من تسجيل المشغّل الكامل مش من لحظة التكبير */
    private var handed = false
    var idOf: (() -> String?)? = null
    private var vw = 0
    private var vh = 0
    private var dragging = false
    private var ctlOn = true
    private val hh = Handler(Looper.getMainLooper())
    private val tex = TextureView(act)
    private val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
    private val spin = WinSpinnerDrawable()
    private val spinV = View(act).apply { foreground = spin; visibility = View.GONE }
    private val shade = View(act).apply { setBackgroundColor(0x55000000) }
    private val playG = YtGlyph(act, 0)
    private val playB = FrameLayout(act).apply { background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0x99000000.toInt()) } }
    private val msgTv = TextView(act).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE; setPadding(ui.dp(28), 0, ui.dp(28), 0) }
    private val timeTv = TextView(act).apply { textSize = 11f; setTextColor(Color.WHITE); text = "0:00 / 0:00" }
    private val seek = SeekBar(act)
    private val expandG = YtGlyph(act, 2)
    private val closeG = YtGlyph(act, 3)
    private val gearG = YtGlyph(act, 4)
    private val dubTv = TextView(act).apply {
        text = "🌐 مدبلج"; textSize = 11f; setTextColor(Color.WHITE); visibility = View.GONE; includeFontPadding = false
        setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5)); background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = ui.dp(6).toFloat() }
    }
    private val bar = LinearLayout(act)

    private val hideR = Runnable { if (exo?.isPlaying == true && !dragging) showCtl(false, false) }
    private val ticker = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val d = p.duration; val pos = p.currentPosition
            if (d > 0 && !dragging) seek.progress = (pos * 1000L / d).toInt()
            timeTv.text = fmt(pos) + " / " + fmt(if (d > 0) d else 0L)
            hh.postDelayed(this, 500L)
        }
    }
    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                androidx.media3.common.Player.STATE_BUFFERING -> loading(true)
                androidx.media3.common.Player.STATE_READY -> loading(false)
                androidx.media3.common.Player.STATE_ENDED -> { loading(false); showCtl(true, false); try { onEnded?.invoke() } catch (_: Throwable) {} }
                else -> {}
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playG.kind = if (isPlaying) 1 else 0; playG.invalidate()
            keepScreenOn = isPlaying && !audioOnly   // (v469) C4: وضع الصوت بس بيخلي الشاشة تنام عادي
            if (isPlaying) scheduleHide() else showCtl(true, false)
        }
        override fun onRenderedFirstFrame() { thumb.visibility = View.GONE }
        override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { vw = (v.width * v.pixelWidthHeightRatio).toInt(); vh = v.height; fit() }
        override fun onPlayerError(e: PlaybackException) {
            LogStore.err("YtMini", e)
            this@YtMiniPlayer.error("ما قدرتش أشغّله هنا — دوس زرار التكبير يفتحه في مشغّلك")
        }
    }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setBackgroundColor(Color.BLACK)
        addView(tex, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        addView(thumb, FrameLayout.LayoutParams(-1, -1))
        addView(shade, FrameLayout.LayoutParams(-1, -1))
        addView(spinV, FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.CENTER))
        addView(msgTv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        playB.addView(playG, FrameLayout.LayoutParams(-1, -1))
        addView(playB, FrameLayout.LayoutParams(ui.dp(56), ui.dp(56), Gravity.CENTER))
        addView(closeG, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP or Gravity.START))
        addView(gearG, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP or Gravity.END))
        addView(dubTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(10), ui.dp(48), 0) })
        bar.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), 0, ui.dp(2), 0) }
        seek.max = 1000
        try {
            seek.progressTintList = ColorStateList.valueOf(YtSkin.accent)
            seek.thumbTintList = ColorStateList.valueOf(YtSkin.accent)
            seek.progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
        } catch (_: Throwable) {}
        bar.addView(timeTv, LinearLayout.LayoutParams(-2, -2))
        bar.addView(seek, LinearLayout.LayoutParams(0, ui.dp(36), 1f))
        bar.addView(expandG, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        addView(bar, FrameLayout.LayoutParams(-1, ui.dp(44), Gravity.BOTTOM))

        setOnClickListener { showCtl(!ctlOn, true) }
        closeG.setOnClickListener { onCollapse() }
        gearG.setOnClickListener { showMenu(it) }
        dubTv.setOnClickListener { langMenu(it) }
        expandG.setOnClickListener {
            // (v406) تكبير فوري: بنسلّم نفس المشغّل (شغّال ومحمّل) للمشغّل الكامل من غير إعادة تحميل
            val ho = handoff()
            if (ho != null) { showCtl(true, false); onExpand(ho.second, ho.first) }
            else {
                val pos = exo?.currentPosition ?: resumeMs
                resumeMs = pos; handed = true
                releasePlayer(true)
                showCtl(true, false)
                onExpand(pos, null)
            }
        }
        playB.setOnClickListener {
            val p = exo
            if (p == null) {
                val lp = lastPick
                if (lp != null) {
                    // (v586) رجعنا من المشغّل الكامل: كمّل من اللي سجّله هناك (الأحدث)، مش من لحظة ما كبّرت
                    val id = try { idOf?.invoke() } catch (_: Throwable) { null }
                    val rs = if (handed && id != null) (try { YtProg.resumeNow(act.applicationContext, id) } catch (_: Throwable) { 0L }) else resumeMs
                    handed = false
                    playPick(lp, true, rs)
                } else onExpand(0L, null)
            } else if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) { p.seekTo(0L); p.playWhenReady = true }
            else p.playWhenReady = !p.playWhenReady
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { val d = exo?.duration ?: 0L; if (d > 0) timeTv.text = fmt(d * progress / 1000L) + " / " + fmt(d) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true; hh.removeCallbacks(hideR) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                exo?.let { val d = it.duration; if (d > 0) it.seekTo(d * seek.progress / 1000L) }
                scheduleHide()
            }
        })
    }

    // (v479 م5) تلفزيون: المشغّل المصغّر هدف تركيز واحد — OK = تشغيل/إيقاف · ←/→ = ±10ث (والضغط المستمر بيزوّد الخطوة) · Menu = قايمة الجودة/الصوت
    init {
        if (TvFocus.onCtx(act)) {
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isFocusable = true
            setOnKeyListener { _, k, e -> tvKey(k, e) }
        }
    }

    private fun tvKey(k: Int, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount == 0) { togglePlay(); showCtl(true, true) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val p = exo ?: return true
                val step = if (e.repeatCount > 12) 60_000L else if (e.repeatCount > 4) 30_000L else 10_000L
                val d = p.duration
                var t = p.currentPosition + (if (k == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step)
                t = if (d > 0L) t.coerceIn(0L, d) else t.coerceAtLeast(0L)
                p.seekTo(t); showCtl(true, true)
                return true
            }
            KeyEvent.KEYCODE_MENU -> { showMenu(this); return true }
        }
        return false
    }

    fun togglePlay() { if (exo == null && lastPick == null) return; playB.performClick() }   // لسه بيحمّل: OK ماتفتحش المشغّل الكامل بالغلط
    fun hasLangs(): Boolean = lgs.size > 1
    fun qualityLabel(): String = ents.getOrNull(curEnt)?.label ?: ""
    fun langLabel(): String = lgs.getOrNull(lgIdx)?.name ?: ""
    fun qualityMenu(anchor: View) {
        if (ents.size < 2) { Notice.show(act, "مفيش جودات تانية للفيديو ده", 1800L); return }
        val m = PopupMenu(act, anchor)
        ents.forEachIndexed { i, e -> m.menu.add(0, 100 + i, i, (if (i == curEnt) "✓ " else "") + e.label) }
        m.setOnMenuItemClickListener { mi -> pickQuality(mi.itemId - 100); true }
        m.show()
    }
    fun audioLangMenu(anchor: View) {
        if (lgs.size < 2) { Notice.show(act, "الفيديو ده مفيهوش لغات صوت تانية", 1800L); return }
        langMenu(anchor)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun fit() {
        val bw = width; val bh = height
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return
        val sc = minOf(bw.toFloat() / vw, bh.toFloat() / vh)
        val lp = tex.layoutParams as? FrameLayout.LayoutParams ?: return
        val nw = (vw * sc).toInt(); val nh = (vh * sc).toInt()
        if (lp.width != nw || lp.height != nh) { lp.width = nw; lp.height = nh; lp.gravity = Gravity.CENTER; tex.layoutParams = lp }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun scheduleHide() { hh.removeCallbacks(hideR); hh.postDelayed(hideR, 3200L) }

    private fun showCtl(on: Boolean, auto: Boolean) {
        ctlOn = on
        hh.removeCallbacks(hideR)
        for (v in listOf<View>(shade, playB, bar, closeG, gearG)) {
            if (on) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(160L).start() }
            else v.animate().alpha(0f).setDuration(160L).withEndAction { if (!ctlOn) v.visibility = View.INVISIBLE }.start()
        }
        if (on && auto && exo?.isPlaying == true) scheduleHide()
    }

    fun setThumb(url: String) {
        thumb.visibility = View.VISIBLE
        try { if (url.isNotBlank()) Glide.with(act).load(url).centerCrop().into(thumb) else Glide.with(act).clear(thumb) } catch (_: Throwable) {}
    }

    fun loading(on: Boolean) {
        spinV.visibility = if (on) View.VISIBLE else View.GONE
        if (on) spin.start() else spin.stop()
        if (on) { playB.visibility = View.INVISIBLE; msgTv.visibility = View.GONE } else if (ctlOn) playB.visibility = View.VISIBLE
    }

    fun error(msg: String) {
        loading(false); msgTv.text = msg; msgTv.visibility = View.VISIBLE
        showCtl(true, false)
    }

    fun reset() {
        releasePlayer(false)
        lastPick = null; curPick = null; resumeMs = 0L; handed = false; vw = 0; vh = 0
        ents.clear(); lgs.clear(); aq.clear(); audOvr = null; lgIdx = -1; curEnt = 0; qRaw = ""; dubTv.visibility = View.GONE
        msgTv.visibility = View.GONE; seek.progress = 0; timeTv.text = "0:00 / 0:00"
        playG.kind = 0; playG.invalidate()
        thumb.visibility = View.VISIBLE
        showCtl(true, false)
    }

    fun start(pick: YtPick, autoplay: Boolean, seekMs: Long = 0L) { parse(pick); playPick(pick, autoplay, seekMs) }

    private fun parse(pick: YtPick) {
        ents.clear(); lgs.clear(); aq.clear(); audOvr = null; lgIdx = -1; curEnt = 0
        qRaw = pick.qlist
        try {
            val a = org.json.JSONArray(pick.qlist)
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); ents.add(Ent(o.optString("l"), o.optString("u"), o.optString("a"), o.optInt("h"))) }
            val at = a.optJSONObject(0)?.optJSONArray("at")
            if (at != null) for (i in 0 until at.length()) {
                val o = at.getJSONObject(i); val q = ArrayList<Pair<String, String>>(); val qa = o.optJSONArray("q")
                if (qa != null) for (j in 0 until qa.length()) q.add(Pair(qa.getJSONObject(j).optString("n"), qa.getJSONObject(j).optString("u")))
                lgs.add(Lg(o.optString("n"), o.optString("l"), o.optString("u"), q)); if (o.optInt("c") == 1) lgIdx = i
            }
            val aqa = a.optJSONObject(0)?.optJSONArray("aq")
            if (aqa != null) for (j in 0 until aqa.length()) aq.add(Pair(aqa.getJSONObject(j).optString("n"), aqa.getJSONObject(j).optString("u")))
        } catch (_: Throwable) {}
        curEnt = ents.indexOfFirst { it.url == pick.url }.let { if (it >= 0) it else ents.indexOfFirst { e -> e.h == pick.h } }
        dubTv.visibility = if (lgs.size > 1) View.VISIBLE else View.GONE
    }

    // ───────── (v406) جودة الفيديو · لغة الصوت · جودة الصوت ─────────
    private fun audioVariants(): List<Pair<String, String>> = if (lgIdx >= 0 && lgIdx < lgs.size && lgs[lgIdx].q.isNotEmpty()) lgs[lgIdx].q else aq

    private fun showMenu(anchor: View) {
        val m = PopupMenu(act, anchor)
        if (ents.size > 1) {
            val sm = m.menu.addSubMenu("🎞 الجودة" + (ents.getOrNull(curEnt)?.let { " (" + it.label + ")" } ?: ""))
            ents.forEachIndexed { i, e -> sm.add(0, 100 + i, i, (if (i == curEnt) "✓ " else "") + e.label) }
        }
        if (lgs.size > 1) {
            val sm = m.menu.addSubMenu("🌐 لغة الصوت" + (lgs.getOrNull(lgIdx)?.let { " (" + it.name + ")" } ?: ""))
            lgs.forEachIndexed { i, l -> sm.add(0, 200 + i, i, (if (i == lgIdx) "✓ " else "") + l.name) }
        }
        val av = audioVariants()
        if (av.size > 1) {
            val sm = m.menu.addSubMenu("🔊 جودة الصوت")
            av.forEachIndexed { i, v -> sm.add(0, 300 + i, i, (if (audOvr == v.second) "✓ " else "") + v.first) }
        }
        m.menu.add(0, 400, 9, if (isAudioOnly()) "🎬 إلغاء وضع الصوت بس" else "🎧 وضع الصوت بس (أخف صوت)")   // (v469) C4
        m.setOnMenuItemClickListener { mi ->
            val id = mi.itemId
            if (id == 400) toggleAudioOnly()
            else if (id in 100..199) pickQuality(id - 100) else if (id in 200..299) pickLang(id - 200) else if (id in 300..399) pickAudioQ(id - 300)
            true
        }
        m.show()
    }

    private fun langMenu(anchor: View) {
        if (lgs.size < 2) return
        val m = PopupMenu(act, anchor)
        lgs.forEachIndexed { i, l -> m.menu.add(0, 200 + i, i, (if (i == lgIdx) "✓ " else "") + "🌐 " + l.name) }
        m.setOnMenuItemClickListener { mi -> pickLang(mi.itemId - 200); true }
        m.show()
    }

    /** لو الجودة الحالية فيديو بصوت مدمج: ننقل لأقرب جودة فيديو-بس (عشان الصوت يتبدّل) */
    private fun splitEnt(): Ent? {
        val e = ents.getOrNull(curEnt)
        if (e != null && e.audio.isNotEmpty()) return e
        val curH = e?.h ?: 0
        val cand = ents.filter { it.audio.isNotEmpty() }
        val n = cand.filter { it.h <= curH }.maxByOrNull { it.h } ?: cand.firstOrNull() ?: return null
        curEnt = ents.indexOf(n)
        return n
    }

    private fun pickQuality(i: Int) {
        val e = ents.getOrNull(i) ?: return
        curEnt = i
        reload(e.url, if (e.audio.isEmpty()) null else (audOvr ?: e.audio), e.h, "🎞 الجودة: " + e.label)
    }
    private fun pickLang(i: Int) {
        val l = lgs.getOrNull(i) ?: return
        lgIdx = i; audOvr = l.url
        val e = splitEnt()
        if (e == null) { Notice.show(act, "اللغة دي مش متاحة مع الجودات الحالية", 2200L); return }
        reload(e.url, audOvr, e.h, "🌐 الصوت: " + l.name)
    }
    private fun pickAudioQ(i: Int) {
        val v = audioVariants().getOrNull(i) ?: return
        audOvr = v.second
        val e = splitEnt()
        if (e == null) { Notice.show(act, "جودة الصوت دي مش متاحة مع الجودات الحالية", 2200L); return }
        reload(e.url, audOvr, e.h, "🔊 جودة الصوت: " + v.first)
    }
    private fun reload(url: String, audio: String?, h: Int, msg: String) {
        val old = curPick
        val pos = position(); val play = exo?.playWhenReady ?: true
        playPick(YtPick(url, audio, old?.ua ?: "", old?.title ?: "", h, qRaw), play, pos)
        Notice.show(act, msg, 1600L)
    }

    /** qlist للمشغّل الكامل بعد ما تغيّر لغة/جودة صوت: كل الجودات بصوت اللغة المختارة */
    private fun qlistForHandoff(): String {
        val ov = audOvr ?: return qRaw
        return try {
            val a = org.json.JSONArray(qRaw)
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("a").isNotEmpty()) o.put("a", ov) }
            val at = a.optJSONObject(0)?.optJSONArray("at")
            if (at != null) for (i in 0 until at.length()) at.getJSONObject(i).put("c", if (i == lgIdx) 1 else 0)
            a.toString()
        } catch (_: Throwable) { qRaw }
    }

    /** بيسلّم المشغّل الشغّال للـ YtHandoff ويرجّع (pick الحالي, الموضع) عشان نفتح المشغّل الكامل من غير تحميل */
    fun handoff(): Pair<YtPick, Long>? {
        val p = exo ?: return null; val cp = curPick ?: return null
        if (p.playbackState == androidx.media3.common.Player.STATE_IDLE) return null
        val pos = p.currentPosition
        try { p.removeListener(listener) } catch (_: Throwable) {}
        hh.removeCallbacks(ticker); exo = null; keepScreenOn = false
        p.playWhenReady = true
        YtHandoff.put(p)
        thumb.visibility = View.VISIBLE
        playG.kind = 0; playG.invalidate()
        resumeMs = pos; handed = true
        return Pair(YtPick(cp.url, cp.audio, cp.ua, cp.title, cp.h, qlistForHandoff()), pos)
    }

    /** (v417) تصغير لشريط يوتيوب السفلي (YtDock): بنسلّم نفس المشغّل شغّال من غير ما يقف أو يتحمّل تاني. null لو مفيش حاجة شغّالة تتسلّم */
    fun detachForDock(): YtKept? {
        val p = exo ?: return null; val cp = curPick ?: return null
        val st = p.playbackState
        if (st == androidx.media3.common.Player.STATE_IDLE || st == androidx.media3.common.Player.STATE_ENDED) return null
        val pos = p.currentPosition
        try { p.removeListener(listener) } catch (_: Throwable) {}
        hh.removeCallbacks(ticker); exo = null; keepScreenOn = false
        thumb.visibility = View.VISIBLE
        playG.kind = 0; playG.invalidate()
        resumeMs = pos
        return YtKept(p, YtPick(cp.url, cp.audio, cp.ua, cp.title, cp.h, qlistForHandoff()), pos)
    }

    private fun playPick(pick: YtPick, autoplay: Boolean, seekMs: Long) {
        lastPick = pick; curPick = pick
        releasePlayer(false)
        try {
            val f = DefaultHttpDataSource.Factory().setUserAgent(pick.ua)
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.youtube.com/")).setAllowCrossProtocolRedirects(true)
            val p = ExoPlayer.Builder(act, DefaultRenderersFactory(act).setEnableDecoderFallback(true)).build()
            try { p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true) } catch (_: Throwable) {}
            p.setVideoTextureView(tex)
            val au = pick.audio
            val src = if (!au.isNullOrBlank()) {
                val pf = ProgressiveMediaSource.Factory(f)
                MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(Uri.parse(pick.url))), pf.createMediaSource(MediaItem.fromUri(Uri.parse(au))))
            } else DefaultMediaSourceFactory(f).createMediaSource(MediaItem.fromUri(Uri.parse(pick.url)))
            p.addListener(listener)
            if (seekMs > 0L) p.setMediaSource(src, seekMs) else p.setMediaSource(src)
            p.prepare(); p.playWhenReady = autoplay
            exo = p
            loading(true)
            hh.removeCallbacks(ticker); hh.post(ticker)
        } catch (e: Throwable) {
            LogStore.err("YtMini:start", e)
            error("ما قدرتش أشغّله هنا — دوس زرار التكبير يفتحه في مشغّلك")
        }
    }

    private fun releasePlayer(showThumb: Boolean) {
        hh.removeCallbacks(ticker)
        exo?.let { try { it.release() } catch (_: Throwable) {} }
        exo = null
        keepScreenOn = false
        if (showThumb) { thumb.visibility = View.VISIBLE; playG.kind = 0; playG.invalidate() }
    }

    fun position(): Long = exo?.currentPosition ?: resumeMs
    /** (v606) شغّال فعلًا دلوقتي؟ (لقرار الدخول في PiP عند الخروج من التطبيق) */
    fun isPlayingNow(): Boolean = try { exo?.isPlaying == true } catch (_: Throwable) { false }
    fun seekToMs(ms: Long) { try { exo?.seekTo(ms.coerceAtLeast(0L)) } catch (_: Throwable) {} }   // (v469) C1/C6
    fun durationMs(): Long = try { exo?.duration?.takeIf { it > 0L } ?: 0L } catch (_: Throwable) { 0L }

    // ───────── (v469) C4 وضع «صوت بس»: أخف ستريم صوتي + إخفاء الفيديو ─────────
    private var audioOnly = false
    private var preAudioEnt = -1
    fun isAudioOnly() = audioOnly
    fun toggleAudioOnly() {
        if (!audioOnly) {
            val light = audioVariants().lastOrNull()?.second ?: curPick?.audio?.takeIf { it.isNotBlank() }
            if (light == null) { Notice.show(act, "الوضع ده محتاج ستريم صوت منفصل — جرّب زرار «🎧 صوت» تحت (بيشغّل في مشغّل الموسيقى وبيكمّل مع قفل الشاشة)", 3600L); return }
            preAudioEnt = curEnt; audioOnly = true
            tex.visibility = View.INVISIBLE; thumb.visibility = View.VISIBLE; keepScreenOn = false
            reload(light, null, 0, "🎧 وضع الصوت بس: أخف جودة صوت")
        } else {
            audioOnly = false; tex.visibility = View.VISIBLE
            if (preAudioEnt >= 0) pickQuality(preAudioEnt)
            Notice.show(act, "🎬 رجعنا لوضع الفيديو", 1500L)
        }
    }
    /** (v429) رابط صوت جاهز من اللي شغّال دلوقتي (لتحويل «🎧 صوت» فوري من غير جلب جديد): url, ua, الموضع — null لو HLS/مفيش */
    fun audioHint(): Triple<String, String, Long>? {
        val cp = curPick ?: return null
        val u = cp.audio?.takeIf { it.isNotBlank() } ?: cp.url
        if (u.isBlank() || u.contains(".m3u8", true) || u.contains("/manifest/", true)) return null
        return Triple(u, cp.ua, position())
    }
    fun pause() { exo?.playWhenReady = false }
    fun release() { releasePlayer(false); hh.removeCallbacks(hideR) }
}

/** (v469) C9 قائمة انتظار يوتيوب: بتتملى من مقترحات/قايمة الفيديو الحالي، والتالي بيتشغّل تلقائيًا، وبتتعدّل بالسحب للحذف من شريط المشغّل المصغّر */
object YtQueue {
    private val l = ArrayList<NpItem>()
    @Volatile var curId = ""
    @Synchronized fun reset(cur: NpItem, rest: List<NpItem>) { curId = cur.id; l.clear(); l.addAll(rest.filter { it.kind == 0 && it.id != cur.id }.distinctBy { it.id }.take(60)) }
    @Synchronized fun add(v: NpItem) { if (v.kind == 0 && v.id != curId && l.none { it.id == v.id }) l.add(v) }
    @Synchronized fun addNext(v: NpItem) { if (v.kind == 0 && v.id != curId) { l.removeAll { it.id == v.id }; l.add(0, v) } }
    @Synchronized fun remove(id: String) { l.removeAll { it.id == id } }
    @Synchronized fun list(): List<NpItem> = ArrayList(l)
    @Synchronized fun next(): NpItem? = if (l.isEmpty()) null else l.removeAt(0)
    @Synchronized fun size() = l.size
}

/** الصفحة نفسها: مشغّل مصغّر فوق + (عنوان · قناة · اشتراك · وصف · مقترحات/تعليقات) بتتحرك بالسحب تحته */
class YtWatchPage(private val act: Activity, private val ui: Ui, private val host: YtWatchHost) : FrameLayout(act) {
    private val sw = act.resources.displayMetrics.widthPixels
    private val medium: Typeface = YtSkin.medium
    private val mini = YtMiniPlayer(act, ui)
    private val scroll = ScrollView(act)
    private val body = LinearLayout(act)
    private var gen = 0
    private var item: NpItem? = null
    private var det: YtDetail? = null
    private var from: Rect? = null
    private var closing = false
    private var comments = false
    private var cLoaded = false
    private var cNext: Page? = null
    private var cMore: ((Page) -> NpComments)? = null
    private var cItems = ArrayList<NpComment>()
    private var cTotal = -1
    private var liked = false
    private var likeCount = -1L
    private var relShown = 20
    // (v479 م5) تلفزيون: تخطيط SmartTube — فيديو + معلومات فوق، صف أزرار، وتحت صف مقترحات أفقي (أو التعليقات)
    private val tv = DeviceUi.isTv(act)
    private val rail = LinearLayout(act)
    private val railScroll = HorizontalScrollView(act)
    private val comScroll = ScrollView(act)
    private var tail: View = scroll
    private var qChip: TextView? = null
    private var lChip: TextView? = null
    private var chanChip: TextView? = null

    private val titleTv = TextView(act).apply { textSize = 17f; setTextColor(YtSkin.text); typeface = medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0) }
    private val crumbTv = TextView(act).apply { textSize = 13f; setTextColor(YtSkin.sub); setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(2)) }   // (v527) تلفزيون: «إنت فين»
    private val metaTv = TextView(act).apply { textSize = 12f; setTextColor(YtSkin.sub); setPadding(ui.dp(14), ui.dp(4), ui.dp(14), 0) }
    private val chipsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, ui.dp(10), 0) }
    private val chanBox = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) }
    private val chanAvHost = FrameLayout(act)
    private val chanName = TextView(act).apply { textSize = 14f; setTextColor(YtSkin.text); typeface = medium; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val subB = TextView(act).apply { textSize = 13f; typeface = medium; gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
    private val descTv = TextView(act).apply { textSize = 13f; setTextColor(YtSkin.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
    private val tabRel = TextView(act)
    private val tabCom = TextView(act)
    private val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val bellB = YtIcon(act, "bell")
    private val cEdit = EditText(act)
    private val composer = LinearLayout(act)
    private val likeChip: TextView
    private val saveChip: TextView
    // (v469) C1/C6/C8: شريط تخطي الرعاة + حفظ التقدم + الديسلايك + استكمال عند الفتح
    private var sbBar: LinearLayout? = null
    private var sbTxt: TextView? = null
    private var lastSkipFrom = -1L
    private var skipCoolUntil = 0L
    private var progSaveAt = 0L
    private var dislikeN = -1L
    private var metaBase = ""
    var seekOnOpenMs = 0L
    private val hh2 = Handler(Looper.getMainLooper())
    private val sbHide = Runnable { sbBar?.visibility = View.GONE }
    private val pageTick: Runnable = object : Runnable {
        override fun run() {
            hh2.postDelayed(this, 500L)
            if (!isOpen) return
            val v = item ?: return
            if (tv) tvPaint()
            val pos = mini.position(); val dur = mini.durationMs()
            if (dur > 0 && pos > 0) {
                val now = System.currentTimeMillis()
                if (now - progSaveAt > 4000L) { progSaveAt = now; try { YtProg.save(v.id, pos, dur, titleNow(), v.thumb) } catch (_: Throwable) {} }
            }
            // (v469) C1: تخطي مقاطع SponsorBlock تلقائيًا + شريط تراجع 5 ثواني
            if (YtSponsor.on() && pos > 0 && System.currentTimeMillis() > skipCoolUntil) {
                val tgt = YtSponsor.skipTarget(v.id, pos)
                if (tgt > 0) {
                    lastSkipFrom = pos; skipCoolUntil = System.currentTimeMillis() + 6000L
                    mini.seekToMs(tgt)
                    val cat = YtSponsor.cached(v.id)?.firstOrNull { it.endMs == tgt }?.cat ?: "sponsor"
                    sbTxt?.text = "⏭ اتخطى مقطع " + YtSponsor.label(cat)
                    sbBar?.visibility = View.VISIBLE
                    hh2.removeCallbacks(sbHide); hh2.postDelayed(sbHide, 5000L)
                }
            }
        }
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE && !closing

    /** (v479 م5) تلفزيون: التركيز بالأسهم يفضل جوه صفحة المشاهدة (مايهربش للشبكة اللي تحتها) */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!tv || visibility != View.VISIBLE) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    /** (v479 م5) Back على التلفزيون: لو التركيز تحت الفيديو (أزرار/مقترحات/تعليقات) يرجع للفيديو الأول، وبعدها يقفل الصفحة */
    fun tvBack(): Boolean {
        if (!tv || !isOpen) return false
        val f = findFocus() ?: return false
        if (f === mini) return false
        mini.requestFocus(); return true
    }

    // ───────── (v428) سحب لتحت = تصغير الصفحة (زي يوتيوب): الصفحة تتبع صباعك، وبعد حد معيّن/سرعة تنزل للشريط الصغير (YtDock) ─────────
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private var dragDownY = 0f
    private var dragDownX = 0f
    private var dragStartedOnVideo = false
    private var dragging = false
    private var vt: VelocityTracker? = null

    private fun canStartDrag(): Boolean = dragStartedOnVideo || !scroll.canScrollVertically(-1)   // فوق الفيديو دايمًا، وتحته لو المحتوى واقف في أوله

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragDownY = e.rawY; dragDownX = e.rawX; dragging = false
                dragStartedOnVideo = e.y < mini.bottom
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - dragDownY; val dx = e.rawX - dragDownX
                if (!dragging && dy > slop * 2 && dy > Math.abs(dx) * 1.5f && canStartDrag()) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = (e.rawY - dragDownY).coerceAtLeast(0f)
                translationY = dy
                alpha = (1f - dy / (height * 0.9f)).coerceIn(0.25f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val dy = translationY
                if (e.actionMasked == MotionEvent.ACTION_UP && (dy > ui.dp(110) || vy > 1400f)) {
                    animate().translationY(height * 0.55f).alpha(0f).setDuration(160L)
                        .withEndAction { close(false); translationY = 0f; alpha = 1f }.start()
                } else {
                    animate().translationY(0f).alpha(1f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
                }
            }
        }
        return true
    }

    init {
        visibility = View.GONE; isClickable = true; setBackgroundColor(YtSkin.bgSolid); layoutDirection = View.LAYOUT_DIRECTION_RTL
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(mini, LinearLayout.LayoutParams(-1, sw * 9 / 16))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, FrameLayout.LayoutParams(-1, -1))
        body.orientation = LinearLayout.VERTICAL; body.layoutDirection = View.LAYOUT_DIRECTION_RTL
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        scroll.isFillViewport = false

        likeChip = chip("👍") { toggleLike() }
        saveChip = chip("🔖 احفظ") { toggleSave() }
        for (c in listOf(likeChip, saveChip,
            chip("🌐 ترجمة وفرجة") { val v = item; if (v != null) { val ho = mini.handoff(); host.expand(v.id, titleNow(), ho?.second ?: mini.position(), true, ho?.first) } },
            chip("🎧 صوت") { val v = item; if (v != null) { try { mini.audioHint()?.let { YtAudioHint.put(v.id, it.first, it.second, it.third) } } catch (_: Throwable) {}; mini.pause(); host.audio(saveItem()) } },
            chip("⬇ تحميل") { a -> val v = item; if (v != null) host.download(a, saveItem()) },
            chip("📋 نسخ") { val v = item; if (v != null) copy(YtNp.watchUrl(v.id)) },
            chip("↗ مشاركة") { val v = item; if (v != null) { val sec = mini.position() / 1000L; share(YtNp.watchUrl(v.id) + if (sec > 5) "&t=" + sec + "s" else "") } })) {   // (v469) C11: مشاركة من الموضع الحالي
            chipsRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        }

        chanBox.addView(chanAvHost, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) })
        chanBox.addView(chanName, LinearLayout.LayoutParams(0, -2, 1f))
        chanBox.addView(subB, LinearLayout.LayoutParams(-2, -2))
        bellB.apply { glyph = 0.62f; contentDescription = "إشعارات القناة"; foreground = YtSkin.ripple(true); visibility = View.GONE
            setOnClickListener { v -> YtSubscribe.bellMenu(act, v, chanUrl(), chanNameNow(), "") { paintSub() } } }
        chanBox.addView(bellB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(6) })
        chanBox.isClickable = true
        chanBox.setOnClickListener { openChan() }
        subB.setOnClickListener { toggleSub() }

        descTv.background = GradientDrawable().apply { setColor(YtSkin.surface); cornerRadius = ui.dp(12).toFloat() }
        descTv.setOnClickListener { descTv.maxLines = if (descTv.maxLines == 2) 60 else 2 }

        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(8)) }
        for (t in listOf(tabRel, tabCom)) {
            t.apply { textSize = 13f; typeface = medium; gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)) }
            tabs.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        }
        tabRel.text = "📑 مقترحات"; tabCom.text = "💬 تعليقات"
        tabRel.setOnClickListener { setTab(false) }
        tabCom.setOnClickListener { setTab(true) }

        body.addView(titleTv, LinearLayout.LayoutParams(-1, -2))
        body.addView(metaTv, LinearLayout.LayoutParams(-1, -2))
        body.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        body.addView(chanBox, LinearLayout.LayoutParams(-1, -2))
        body.addView(descTv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), 0, ui.dp(12), 0) })
        // (v403) مربع كتابة تعليق (بيظهر في تاب التعليقات؛ النشر على حسابك لو مسجّل الدخول)
        cEdit.apply { hint = "اكتب تعليق…"; textSize = 14f; setTextColor(YtSkin.text); setHintTextColor(YtSkin.sub); maxLines = 4; background = GradientDrawable().apply { setColor(YtSkin.surface); cornerRadius = ui.dp(20).toFloat() }
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)) }
        val send = TextView(act).apply { text = "نشر"; textSize = 13f; typeface = medium; gravity = Gravity.CENTER; setTextColor(YtSkin.chipOnText)
            background = GradientDrawable().apply { setColor(YtSkin.chipOnBg); cornerRadius = ui.dp(18).toFloat() }; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); setOnClickListener { sendComment() } }
        composer.apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(6)) }
        composer.addView(cEdit, LinearLayout.LayoutParams(0, -2, 1f)); composer.addView(send, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        body.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        body.addView(composer, LinearLayout.LayoutParams(-1, -2))
        body.addView(listBox, LinearLayout.LayoutParams(-1, -2))

        mini.idOf = { item?.id }   // (v586)
        mini.onCollapse = { close(true) }
        mini.onExpand = { pos, pk -> val v = item; if (v != null) host.expand(v.id, titleNow(), pos, false, pk) }
        mini.onEnded = { val nx = YtQueue.next(); if (nx != null) host.openVideo(nx) }   // (v469) C9: التالي من قائمة الانتظار تلقائيًا
        // (v469) C1: شريط «اتخطى مقطع» + تراجع — تحت المشغّل المصغّر على طول
        val sb = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); setBackgroundColor(YtSkin.surface) }
        val st = TextView(act).apply { textSize = 12.5f; setTextColor(YtSkin.text); typeface = medium }
        val un = TextView(act).apply { text = "↩ تراجع"; textSize = 12.5f; typeface = medium; gravity = Gravity.CENTER; setTextColor(YtSkin.chipOnText); setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            background = GradientDrawable().apply { setColor(YtSkin.chipOnBg); cornerRadius = ui.dp(14).toFloat() }
            setOnClickListener { if (lastSkipFrom >= 0) { mini.seekToMs(lastSkipFrom); skipCoolUntil = System.currentTimeMillis() + 12000L }; sbBar?.visibility = View.GONE } }
        sb.addView(st, LinearLayout.LayoutParams(0, -2, 1f)); sb.addView(un, LinearLayout.LayoutParams(-2, -2))
        sbTxt = st; sbBar = sb
        (mini.parent as? LinearLayout)?.addView(sb, 1, LinearLayout.LayoutParams(-1, -2))
        if (tv) tvLayout()
    }

    // ───────── (v479 م5) تخطيط التلفزيون (SmartTube): فيديو + معلومات · صف أزرار · صف مقترحات أفقي / تعليقات ─────────
    private fun detach(v: View?) { (v?.parent as? ViewGroup)?.removeView(v) }

    private fun setIf(t: TextView?, s: String) { if (t != null && t.text.toString() != s) t.text = s }

    private fun tvLayout() {
        val dm = act.resources.displayMetrics
        val old = (0 until chipsRow.childCount).map { chipsRow.getChildAt(it) }   // 0 لايك · 1 حفظ · 2 ترجمة · 3 صوت · 4 تحميل · 5 نسخ · 6 مشاركة
        chipsRow.removeAllViews()
        removeAllViews()
        for (v in listOf<View?>(mini, sbBar, listBox, tabRel, tabCom, subB, bellB, crumbTv, titleTv, metaTv, descTv)) detach(v)

        val miniW = minOf((dm.widthPixels * 0.56f).toInt(), (dm.heightPixels * 0.52f * 16f / 9f).toInt())
        val miniH = miniW * 9 / 16
        mini.id = View.generateViewId()
        val vcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        vcol.addView(mini, LinearLayout.LayoutParams(miniW, miniH))
        sbBar?.let { vcol.addView(it, LinearLayout.LayoutParams(miniW, -2)) }

        titleTv.apply { textSize = 22f; maxLines = 3 }
        metaTv.textSize = 15f
        descTv.apply { textSize = 15f; maxLines = 6; setOnClickListener(null); isClickable = false; isFocusable = false }
        val info = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        info.addView(crumbTv, LinearLayout.LayoutParams(-1, -2))
        info.addView(titleTv, LinearLayout.LayoutParams(-1, -2))
        info.addView(metaTv, LinearLayout.LayoutParams(-1, -2))
        info.addView(descTv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(12), ui.dp(12), 0) })
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(24), ui.dp(16), ui.dp(24), 0) }
        top.addView(vcol, LinearLayout.LayoutParams(-2, -2))
        top.addView(info, LinearLayout.LayoutParams(0, miniH, 1f).apply { marginStart = ui.dp(20) })

        // صف الأزرار: اشتراك · جرس · لايك · القناة · الجودة · لغة الصوت · مقترحات · تعليقات · حفظ · ترجمة · صوت · تحميل · مشاركة
        val qc = chip("🎞 الجودة") { a -> mini.qualityMenu(a) }
        val lc = chip("🌐 لغة الصوت") { a -> mini.audioLangMenu(a) }.apply { visibility = View.GONE }
        val cc = chip("📺 القناة") { openChan() }
        qChip = qc; lChip = lc; chanChip = cc
        subB.id = View.generateViewId()
        mini.nextFocusDownId = subB.id
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)) }
        val btns = listOf<View?>(subB, bellB, likeChip, cc, qc, lc, tabRel, tabCom, saveChip, old.getOrNull(2), old.getOrNull(3), old.getOrNull(4), old.getOrNull(6))
        for (v in btns) {
            if (v == null) continue
            detach(v)
            if (v === bellB) { acts.addView(v, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { setMargins(ui.dp(5), 0, ui.dp(5), 0) }); v.nextFocusUpId = mini.id; continue }
            (v as? TextView)?.apply { textSize = 16f; setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)) }
            v.nextFocusUpId = mini.id
            acts.addView(v, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(5), 0, ui.dp(5), 0) })
        }
        val actsHsv = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(acts, FrameLayout.LayoutParams(-2, -2)) }

        // الجزء السفلي: صف مقترحات أفقي (الافتراضي) أو التعليقات (قايمة بتتسكرول بالأسهم)
        rail.apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14)); clipChildren = false; clipToPadding = false }
        railScroll.apply { isHorizontalScrollBarEnabled = false; addView(rail, FrameLayout.LayoutParams(-2, -2)) }
        comScroll.apply {
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS; isFocusable = true; visibility = View.GONE
            setOnFocusChangeListener { v, f -> v.foreground = if (f) GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(ui.dp(2), YtSkin.accent); cornerRadius = ui.dp(8).toFloat() } else null }
            addView(listBox, FrameLayout.LayoutParams(-1, -2))
        }
        val lower = FrameLayout(act)
        lower.addView(railScroll, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        lower.addView(comScroll, FrameLayout.LayoutParams(-1, -1))

        val tl = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        tl.addView(actsHsv, LinearLayout.LayoutParams(-1, -2))
        tl.addView(lower, LinearLayout.LayoutParams(-1, 0, 1f))
        tail = tl
        val colTv = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        colTv.addView(top, LinearLayout.LayoutParams(-1, -2))
        colTv.addView(tl, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(colTv, FrameLayout.LayoutParams(-1, -1))
        tvTabVis()
    }

    private fun tvTabVis() {
        railScroll.visibility = if (comments) View.GONE else View.VISIBLE
        comScroll.visibility = if (comments) View.VISIBLE else View.GONE
    }

    /** نصوص الأزرار اللي بتتغيّر بعد التحميل (الجودة الحالية · لغة الصوت لو الفيديو متعدد اللغات) */
    private fun tvPaint() {
        try {
            setIf(qChip, "🎞 " + mini.qualityLabel().ifBlank { "الجودة" })
            val hl = mini.hasLangs()
            val l = lChip
            if (l != null) {
                val vis = if (hl) View.VISIBLE else View.GONE
                if (l.visibility != vis) l.visibility = vis
                if (hl) setIf(l, "🌐 " + mini.langLabel().ifBlank { "لغة الصوت" })
            }
        } catch (_: Throwable) {}
    }

    private fun renderRail() {
        val rel = det?.related?.takeIf { it.isNotEmpty() } ?: liteRel
        if (rel.isEmpty()) { status(if (det == null) "⏳ …" else "مفيش مقترحات"); return }
        val fi = (0 until rail.childCount).firstOrNull { rail.getChildAt(it).hasFocus() } ?: -1   // التركيز مايضيعش لو القايمة اتحدّثت (وصلت التفاصيل)
        rail.removeAllViews()
        for (r in rel.take(relShown)) rail.addView(relCardTv(r), LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(7), 0, ui.dp(7), 0) })
        if (rel.size > relShown) rail.addView(chip("المزيد ▸") { relShown += 20; renderRail() }.apply { textSize = 16f; setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); isFocusable = true },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL; setMargins(ui.dp(12), 0, ui.dp(12), 0) })
        if (fi >= 0 && rail.childCount > 0) rail.getChildAt(minOf(fi, rail.childCount - 1))?.requestFocus()
    }

    private fun relCardTv(v: NpItem): View {
        val cw = (act.resources.displayMetrics.widthPixels * 0.205f).toInt()
        val ch = cw * 9 / 16
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface) }
        val box = FrameLayout(act)
        box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        if (v.dur > 0) box.addView(TextView(act).apply {
            text = fmtDur(v.dur); textSize = 13f; setTextColor(Color.WHITE); typeface = medium; includeFontPadding = false
            setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3)); background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        try { if (v.thumb.isNotBlank()) Glide.with(act).load(v.thumb).centerCrop().override(cw, ch).into(thumb) } catch (_: Throwable) {}
        val title = TextView(act).apply { text = v.title; textSize = 15f; setTextColor(YtSkin.text); typeface = medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(2), ui.dp(8), ui.dp(2), 0) }
        val meta = TextView(act).apply {
            text = listOf(v.channel, if (v.count > 0) fmtN(v.count) + " مشاهدة" else "", v.age).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 12.5f; setTextColor(YtSkin.sub); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(2), ui.dp(2), ui.dp(2), 0)
        }
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; isFocusable = true; isClickable = true }
        root.addView(box, LinearLayout.LayoutParams(cw, ch))
        root.addView(title, LinearLayout.LayoutParams(cw, -2))
        root.addView(meta, LinearLayout.LayoutParams(cw, -2))
        root.setOnClickListener { host.openVideo(v) }
        root.setOnLongClickListener { tvRelMenu(root, v); true }   // زر Menu على الريموت = نفس الدوسة الطويلة (TvFocus.handleKey)
        root.setOnFocusChangeListener { _, f -> if (f && v.kind == 0 && v.id.length == 11) YtFast.warm(v.id) }
        return root
    }

    private fun tvRelMenu(anchor: View, v: NpItem) {
        val m = PopupMenu(act, anchor)
        val vid = v.kind == 0 && v.id.length == 11
        m.menu.add(0, 1, 0, "▶ شغّل")
        if (vid) { m.menu.add(0, 2, 1, "🎧 صوت بس"); m.menu.add(0, 3, 2, "⬇ تحميل") }
        if (v.channelUrl.isNotBlank()) m.menu.add(0, 4, 3, "📺 القناة")
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> host.openVideo(v)
                2 -> host.audio(v)
                3 -> host.download(anchor, v)
                4 -> host.openChannel(v.channelUrl, v.channel)
            }
            true
        }
        m.show()
    }

    // ───────── عناصر مساعدة ─────────
    private fun chip(label: String, onClick: (View) -> Unit): TextView = TextView(act).apply {
        text = label; textSize = 13f; setTextColor(YtSkin.text); gravity = Gravity.CENTER; setSingleLine(); typeface = medium
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        background = GradientDrawable().apply { setColor(YtSkin.chip); cornerRadius = ui.dp(18).toFloat() }
        setOnClickListener { onClick(it) }
    }

    private fun fmtN(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", n / 1e9)
        n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 1_000L -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }
    private fun fmtDur(s: Long): String = if (s <= 0) "" else if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)

    private fun avatar(name: String, sizeDp: Int, url: String): View {
        val colors = intArrayOf(0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFF1E88E5.toInt(), 0xFF5E35B1.toInt())
        val c = colors[(name.hashCode() and 0x7fffffff) % colors.size]
        val f = FrameLayout(act)
        f.addView(TextView(act).apply {
            text = name.trim().take(1).uppercase(); gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = if (sizeDp >= 40) 17f else 14f; typeface = medium
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(c) }
        }, FrameLayout.LayoutParams(-1, -1))
        if (url.isNotBlank()) {
            val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            f.addView(iv, FrameLayout.LayoutParams(-1, -1))
            try { Glide.with(act).load(url).circleCrop().override(ui.dp(sizeDp), ui.dp(sizeDp)).into(iv) } catch (_: Throwable) {}
        }
        return f
    }

    private fun titleNow(): String { val v = item ?: return ""; return det?.title?.ifBlank { v.title } ?: v.title }
    private fun chanUrl(): String { val v = item ?: return ""; return (det?.channelUrl ?: "").ifBlank { v.channelUrl } }
    private fun chanNameNow(): String { val v = item ?: return ""; return (det?.channel ?: "").ifBlank { v.channel } }
    private fun saveItem(): NpItem {
        val v = item!!; val d = det
        return NpItem(v.id, titleNow(), chanNameNow(), chanUrl(), (d?.thumb ?: "").ifBlank { v.thumb }, d?.dur ?: v.dur, d?.views ?: v.count, (d?.age ?: "").ifBlank { v.age }, 0, YtNp.watchUrl(v.id))
    }

    private fun copy(t: String) {
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", t))
        Notice.show(act, "📋 اتنسخ", 1500L)
    }
    private fun share(t: String) {
        try { act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "مشاركة")) } catch (_: Throwable) {}
    }

    private fun paintSub() {
        val u = chanUrl()
        val on = YtSubscribe.isSub(u)
        subB.text = if (on) "✓ مشترك" else "اشترك"
        subB.setTextColor(if (on) YtSkin.text else YtSkin.chipOnText)
        subB.background = GradientDrawable().apply { setColor(if (on) YtSkin.chip else YtSkin.chipOnBg); cornerRadius = ui.dp(18).toFloat() }
        bellB.visibility = if (on) View.VISIBLE else View.GONE
        bellB.filled = on && YtNotify.chanOn(u)
        bellB.background = GradientDrawable().apply { setColor(YtSkin.chip); cornerRadius = ui.dp(20).toFloat() }
        if (tv) setIf(chanChip, "📺 " + chanNameNow().ifBlank { "القناة" })
    }
    private fun toggleSub() { YtSubscribe.toggle(act, chanUrl(), chanNameNow(), "") { paintSub() } }

    // ───────── (v403) لايك على حسابك ─────────
    private fun likedSet(): HashSet<String> = HashSet(Cfg.str("ytp_liked", "").split("\n").filter { it.isNotBlank() })
    private fun paintLike() {
        val c = if (likeCount >= 0) " " + fmtN(likeCount) else ""
        likeChip.text = (if (liked) "👍✓" else "👍") + c
        likeChip.background = GradientDrawable().apply { setColor(if (liked) YtSkin.chipOnBg else YtSkin.chip); cornerRadius = ui.dp(18).toFloat() }
        likeChip.setTextColor(if (liked) YtSkin.chipOnText else YtSkin.text)
    }
    private fun toggleLike() {
        val v = item ?: return
        if (!YtAccount.loggedIn()) { Notice.show(act, "سجّل الدخول بحساب جوجل من تبويب «أنت» عشان اللايك يتسجّل على حسابك", 3600L); return }
        val now = !liked
        liked = now; if (likeCount >= 0) likeCount += if (now) 1 else -1
        paintLike()
        val ls = likedSet(); if (now) ls.add(v.id) else ls.remove(v.id); Cfg.put("ytp_liked", ls.toList().takeLast(500).joinToString("\n"))
        Thread {
            try { YtAccount.setLiked(v.id, now) }
            catch (e: Throwable) {
                act.runOnUiThread {
                    liked = !now; if (likeCount >= 0) likeCount += if (now) -1 else 1; paintLike()
                    val l2 = likedSet(); if (liked) l2.add(v.id) else l2.remove(v.id); Cfg.put("ytp_liked", l2.joinToString("\n"))
                    Notice.show(act, "اللايك ما اتسجّلش: " + (e.message ?: e.javaClass.simpleName).take(90), 3200L)
                }
            }
        }.apply { isDaemon = true }.start()
    }
    private fun openChan() {
        val u = chanUrl()
        if (u.isBlank()) { Notice.show(act, "القناة دي مالهاش لينك", 2200L); return }
        host.openChannel(u, chanNameNow())
    }
    private fun paintSave() {
        if (item == null) return
        saveChip.text = if (YtStore.contains(YtStore.SAVED, saveItem())) "❌ محفوظ" else "🔖 احفظ"
    }
    private fun toggleSave() {
        if (item == null) return
        val on = YtStore.toggle(YtStore.SAVED, saveItem())
        Notice.show(act, if (on) "🔖 اتحفظ" else "اتشال من المحفوظات", 1600L); paintSave()
    }

    private fun paintTabs() {
        for ((t, on) in listOf(tabRel to !comments, tabCom to comments)) {
            t.setTextColor(if (on) YtSkin.chipOnText else YtSkin.text)
            t.background = GradientDrawable().apply { setColor(if (on) YtSkin.chipOnBg else YtSkin.chip); cornerRadius = ui.dp(10).toFloat() }
        }
    }

    // ───────── فتح/قفل ─────────
    fun open(v: NpItem, src: Rect?) {
        val wasOpen = visibility == View.VISIBLE && !closing
        closing = false
        animate().cancel(); mini.animate().cancel(); tail.animate().cancel()
        gen++; val my = gen
        item = v; det = null; comments = false; cLoaded = false; cNext = null; cMore = null; cItems = ArrayList(); cTotal = -1; relShown = 20
        liked = likedSet().contains(v.id); likeCount = -1L; dislikeN = -1L; metaBase = ""; lastSkipFrom = -1L; sbBar?.visibility = View.GONE; cEdit.setText(""); composer.visibility = View.GONE; tabCom.text = "💬 تعليقات"
        if (YtSponsor.on()) YtSponsor.fetch(v.id) {}   // (v469) C1
        hh2.removeCallbacks(pageTick); hh2.postDelayed(pageTick, 600L)
        mini.reset(); mini.setThumb(v.thumb); mini.loading(true)
        bindItem(v)
        scroll.scrollTo(0, 0)
        if (tv) post { try { mini.requestFocus() } catch (_: Throwable) {} }   // (v479 م5) التركيز على الفيديو أول ما الصفحة تفتح (ومن مقترح كمان)
        if (!wasOpen) { from = src; host.state(true); animateIn(src) }
        else { body.alpha = 0f; body.animate().alpha(1f).setDuration(220L).start() }
        // (v471) الفيديو والتفاصيل بقوا على خيطين منفصلين: قبل كده المشغّل كان بيستنى NewPipe يخلّص كل التفاصيل (3-8 ثواني) قبل ما يبدأ يجيب الرابط،
        // وكمان كان بيمشي في الطريق البطيء بس. دلوقتي الرابط بيتجاب من نفس السباق السريع بتاع المشغّل الكامل (YtExtract + NewPipe + كاش) والتفاصيل تظهر لما تجهز.
        Thread {
            val cap = Cfg.int("yt_maxh", 0)   // (v406) نفس جودة المشغّل الكامل (المصغّر هو نفس المشغّل)
            var pk: YtPick? = null
            try { val g = YtFast.video(v.id, cap); if (g != null) pk = YtPick(g.url, g.audio, g.ua, g.title, 0, g.qlist) } catch (_: Throwable) {}
            val pick = pk
            act.runOnUiThread {
                if (my != gen || act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (pick == null) mini.error("ما قدرتش أجيب الفيديو هنا — دوس زرار التكبير يجرّب في مشغّلك")
                else { val sk = seekOnOpenMs; seekOnOpenMs = 0L; mini.start(pick, YtOpen.autoPlay(), sk) }   // (v469) C6: استكمال من نفس المكان
            }
        }.apply { isDaemon = true }.start()
        // (v472) المقترحات بتطلع من طلب next الخفيف (~ثانية، وبحسابك لو مسجّل) من غير ما تستنى تفاصيل NewPipe
        liteRel = emptyList()
        Thread {
            try {
                val l = YtAccount.relatedLite(v.id)
                if (l.isNotEmpty()) act.runOnUiThread { if (my == gen && !act.isFinishing && !act.isDestroyed && det == null) { liteRel = l; if (!comments) renderList() } }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
        Thread {
            try {
                val d = YtNp.details(v.id)
                act.runOnUiThread { if (my == gen && !act.isFinishing && !act.isDestroyed) bindDetail(d) }
            } catch (e: Throwable) {
                LogStore.err("YtWatch:details", e)
                act.runOnUiThread { if (my == gen) status("⚠ ما قدرتش أجيب التفاصيل: " + (e.message ?: e.javaClass.simpleName).take(120)) }
            }
        }.apply { isDaemon = true }.start()
    }

    fun close(animated: Boolean, dock: Boolean = true) {
        if (visibility != View.VISIBLE || closing) return
        closing = true; gen++
        hh2.removeCallbacks(pageTick); hh2.removeCallbacks(sbHide)   // (v469) C6: حفظ أخير قبل القفل
        try { val v = item; val d = mini.durationMs(); if (v != null && d > 0) YtProg.save(v.id, mini.position(), d, titleNow(), v.thumb) } catch (_: Throwable) {}
        // (v417) النزول من صفحة المشاهدة: الفيديو بيكمّل في شريط صغير تحت (YtDock) بدل ما يقف ويختفي
        val kept = if (dock && YtDockCfg.on()) mini.detachForDock() else null
        if (kept == null) mini.pause()
        host.state(false)
        val dockItem = item
        if (kept != null) { if (dockItem != null) host.dock(dockItem, kept) else { try { kept.p.release() } catch (_: Throwable) {} } }
        fun finish() {
            visibility = View.GONE; closing = false
            mini.release(); mini.animate().cancel(); tail.animate().cancel()
            mini.scaleX = 1f; mini.scaleY = 1f; mini.translationX = 0f; mini.translationY = 0f; mini.alpha = 1f
            tail.translationY = 0f; tail.alpha = 1f; alpha = 1f
        }
        if (!animated) { finish(); return }
        val src = from
        mini.pivotX = 0f; mini.pivotY = 0f
        val loc = IntArray(2); mini.getLocationOnScreen(loc)
        val pw = mini.width.toFloat().coerceAtLeast(1f); val ph = mini.height.toFloat().coerceAtLeast(1f)
        tail.animate().translationY(ui.dp(90).toFloat()).alpha(0f).setDuration(180L).start()
        if (src != null && src.width() > 0) {
            mini.animate().scaleX(src.width() / pw).scaleY(src.height() / ph).translationX((src.left - loc[0]).toFloat()).translationY((src.top - loc[1]).toFloat())
                .setDuration(260L).setInterpolator(DecelerateInterpolator(1.4f)).start()
            animate().alpha(0f).setStartDelay(120L).setDuration(160L).withEndAction { finish() }.start()
        } else {
            mini.animate().translationY(ui.dp(60).toFloat()).alpha(0f).setDuration(200L).start()
            animate().alpha(0f).setDuration(220L).withEndAction { finish() }.start()
        }
    }

    private fun animateIn(src: Rect?) {
        visibility = View.VISIBLE; alpha = 0f
        tail.alpha = 0f; tail.translationY = ui.dp(90).toFloat()
        post {
            val pw = (if (mini.width > 0) mini.width else sw).toFloat(); val ph = (if (mini.height > 0) mini.height else sw * 9 / 16).toFloat()
            val loc = IntArray(2); mini.getLocationOnScreen(loc)
            mini.pivotX = 0f; mini.pivotY = 0f
            if (src != null && src.width() > 0) {
                mini.scaleX = src.width() / pw; mini.scaleY = src.height() / ph
                mini.translationX = (src.left - loc[0]).toFloat(); mini.translationY = (src.top - loc[1]).toFloat()
            } else { mini.translationY = ui.dp(60).toFloat(); mini.alpha = 0f }
            animate().alpha(1f).setStartDelay(0L).setDuration(140L).start()
            mini.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f).setDuration(360L).setInterpolator(DecelerateInterpolator(1.6f)).start()
            tail.animate().translationY(0f).alpha(1f).setStartDelay(140L).setDuration(320L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ───────── المحتوى ─────────
    private fun bindItem(v: NpItem) {
        titleTv.text = v.title
        if (tv) crumbTv.text = "يوتيوب › مشاهدة › " + v.channel.ifBlank { "فيديو" }   // (v527)
        metaTv.text = listOf(if (v.count > 0) fmtN(v.count) + " مشاهدة" else "", v.age).filter { it.isNotBlank() }.joinToString(" · ")
        paintLike()
        chanName.text = v.channel
        chanAvHost.removeAllViews(); chanAvHost.addView(avatar(v.channel.ifBlank { "؟" }, 40, v.avatar), FrameLayout.LayoutParams(-1, -1))
        descTv.text = "⏳ …"; descTv.maxLines = if (tv) 6 else 2
        paintSub(); paintSave(); paintTabs()
        listBox.removeAllViews()
        if (tv) { rail.removeAllViews(); tvTabVis() }
        status("⏳ …")
    }

    private fun bindDetail(d: YtDetail) {
        det = d
        val v = item ?: return
        titleTv.text = d.title.ifBlank { v.title }
        metaBase = listOf(if (d.views >= 0) fmtN(d.views) + " مشاهدة" else "", d.age.ifBlank { v.age }).filter { it.isNotBlank() }.joinToString(" · ")
        metaTv.text = metaBase
        YtQueue.reset(v, d.related)   // (v469) C9
        val myG = gen
        YtDislike.fetch(v.id) { dd -> act.runOnUiThread { if (myG == gen && dd >= 0) { dislikeN = dd; metaTv.text = metaBase + " · 👎 " + fmtN(dd) } } }   // (v469) C8
        likeCount = d.likes; paintLike()
        val nm = chanNameNow()
        chanName.text = nm
        chanAvHost.removeAllViews(); chanAvHost.addView(avatar(nm.ifBlank { "؟" }, 40, v.avatar), FrameLayout.LayoutParams(-1, -1))
        descTv.text = d.desc.trim().ifBlank { "(مفيش وصف)" }
        paintSub(); paintSave()
        renderList()
    }

    private fun status(t: String) {
        val box = if (tv && !comments) rail else listBox   // (v479 م5) تلفزيون: حالة المقترحات بتظهر في صف المقترحات
        box.removeAllViews()
        box.addView(TextView(act).apply { text = t; textSize = if (tv) 16f else 13f; setTextColor(YtSkin.sub); gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(30), ui.dp(20), ui.dp(30)) }, if (box === rail) LinearLayout.LayoutParams(-2, -2) else LinearLayout.LayoutParams(-1, -2))
    }

    private fun setTab(c: Boolean) {
        if (comments == c) return
        comments = c; paintTabs()
        composer.visibility = if (c && !tv) View.VISIBLE else View.GONE   // (v479 م5) كتابة تعليق مش على التلفزيون
        if (tv) tvTabVis()
        if (c && !cLoaded) loadComments() else renderList()
    }

    private var liteRel: List<NpItem> = emptyList()   // (v472) مقترحات سريعة لحد ما تفاصيل NewPipe تجهز
    private fun renderList() {
        if (comments) { renderComments(); return }
        if (tv) { renderRail(); return }
        listBox.removeAllViews()
        val rel = det?.related?.takeIf { it.isNotEmpty() } ?: liteRel
        if (rel.isEmpty()) { status(if (det == null) "⏳ …" else "مفيش مقترحات"); return }
        for (r in rel.take(relShown)) listBox.addView(relCard(r), LinearLayout.LayoutParams(-1, -2))
        if (rel.size > relShown) listBox.addView(chip("عرض المزيد") { relShown += 20; renderList() }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(16)) })
    }

    private fun relCard(v: NpItem): View {
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface) }
        val box = FrameLayout(act)
        box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        if (v.dur > 0) box.addView(TextView(act).apply {
            text = fmtDur(v.dur); textSize = 12f; setTextColor(Color.WHITE); typeface = medium; includeFontPadding = false
            setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3)); background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        try { if (v.thumb.isNotBlank()) Glide.with(act).load(v.thumb).centerCrop().override(sw, sw * 9 / 16).into(thumb) } catch (_: Throwable) {}
        if (v.kind == 0 && v.id.length == 11) { val tb = YtThumbBtns.attach(act, ui, box); YtThumbBtns.bind(tb.aud, tb.dl, v.id, { host.audio(v) }, { host.download(box, v) }) }   // (v472) 🎧 + ⬇ فوق صورة المقترح
        val title = TextView(act).apply { text = v.title; textSize = 14f; setTextColor(YtSkin.text); typeface = medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = TextView(act).apply {
            text = listOf(v.channel, if (v.count > 0) fmtN(v.count) + " مشاهدة" else "", v.age).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 12f; setTextColor(YtSkin.sub); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title, LinearLayout.LayoutParams(-1, -2)); col.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        val info = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.TOP; setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(16)) }
        info.addView(avatar(v.channel.ifBlank { "؟" }, 36, v.avatar), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(12) })
        info.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; foreground = YtSkin.ripple(); setOnClickListener { host.openVideo(v) }; YtFast.warmOnTouch(this) { v.id } }
        root.addView(box, LinearLayout.LayoutParams(-1, sw * 9 / 16)); root.addView(info, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun loadComments() {
        val v = item ?: return
        val my = gen
        status("⏳ …")
        Thread {
            try {
                val (r, more) = YtNp.commentsX(v.id)
                act.runOnUiThread {
                    if (my != gen) return@runOnUiThread
                    cItems.addAll(r.items); cNext = r.next; cMore = more; cLoaded = true; cTotal = r.total
                    if (r.total > 0) tabCom.text = "💬 تعليقات " + fmtN(r.total.toLong())
                    if (comments) renderComments()
                }
            } catch (e: Throwable) {
                LogStore.err("YtWatch:comments", e)
                act.runOnUiThread { if (my == gen && comments) status("⚠ التعليقات مش متاحة: " + (e.message ?: e.javaClass.simpleName).take(100)) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun renderComments() {
        listBox.removeAllViews()
        if (cItems.isEmpty()) { status(if (cLoaded) "مفيش تعليقات" else "⏳ …"); return }
        for (c in cItems) listBox.addView(commentView(c, false), LinearLayout.LayoutParams(-1, -2))
        val nx = cNext; val mf = cMore
        if (nx != null && mf != null) listBox.addView(chip("عرض المزيد") { loadMoreComments(nx, mf) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(16)) })
    }

    /** تعليق (أو رد): مثبّت 📌 · قلب ❤ من صاحب القناة · علامة صاحب القناة · وزرار «ردود» بيفتحها تحته */
    private fun commentView(c: NpComment, isReply: Boolean): View {
        val sz = if (isReply) 28 else 36
        val tags = ArrayList<String>()
        if (c.pinned) tags.add("📌 مثبّت")
        if (c.owner) tags.add("✓ صاحب القناة")
        val head = (tags.joinToString(" ") + " " + listOf(c.author, c.age).filter { it.isNotBlank() }.joinToString(" · ")).trim()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(act).apply { text = head; textSize = 12f; setTextColor(if (c.owner) YtSkin.text else YtSkin.sub); typeface = medium; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(act).apply { text = c.text; textSize = 14f; setTextColor(YtSkin.text) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        val foot = ArrayList<String>()
        if (c.likes > 0) foot.add("👍 " + fmtN(c.likes))
        if (c.hearted) foot.add("❤ من صاحب القناة")
        if (foot.isNotEmpty()) col.addView(TextView(act).apply { text = foot.joinToString("   "); textSize = 12f; setTextColor(YtSkin.sub) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) })
        val repBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val rp = c.replies
        if (!isReply && c.replyCount > 0 && rp != null) {
            val tv = TextView(act).apply { text = "↩ " + c.replyCount + " ردود"; textSize = 13f; setTextColor(YtSkin.text); typeface = medium; setPadding(0, ui.dp(8), 0, ui.dp(2)) }
            tv.setOnClickListener { toggleReplies(tv, repBox, rp, c.replyCount) }
            col.addView(tv, LinearLayout.LayoutParams(-2, -2))
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.TOP; setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(6)) }
        row.addView(avatar(c.author.ifBlank { "؟" }, sz, c.avatar), LinearLayout.LayoutParams(ui.dp(sz), ui.dp(sz)).apply { marginEnd = ui.dp(12) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnLongClickListener { copy(c.text); true }
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
        repBox.setPadding(ui.dp(44), 0, 0, 0)
        wrap.addView(repBox, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun toggleReplies(tv: TextView, box: LinearLayout, first: Page, n: Int) {
        if (box.visibility == View.VISIBLE) { box.visibility = View.GONE; tv.text = "↩ $n ردود"; return }
        box.visibility = View.VISIBLE; tv.text = "⏳ …"
        if (box.childCount > 0) { tv.text = "▲ إخفاء الردود"; return }
        val v = item ?: return; val my = gen
        fun fill(page: Page) {
            Thread {
                try {
                    val r = YtNp.repliesOf(v.id, page)
                    act.runOnUiThread {
                        if (my != gen) return@runOnUiThread
                        if (box.childCount > 0 && box.getChildAt(box.childCount - 1).tag == "more") box.removeViewAt(box.childCount - 1)
                        for (c in r.items) box.addView(commentView(c, true), LinearLayout.LayoutParams(-1, -2))
                        val nx = r.next
                        if (nx != null) box.addView(chip("المزيد من الردود") { fill(nx) }.also { it.tag = "more" }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(8)) })
                        tv.text = "▲ إخفاء الردود"
                    }
                } catch (e: Throwable) {
                    act.runOnUiThread { if (my == gen) { tv.text = "↩ $n ردود"; box.visibility = View.GONE; Notice.show(act, "ما قدرتش أجيب الردود", 2000L) } }
                }
            }.apply { isDaemon = true }.start()
        }
        fill(first)
    }

    private fun loadMoreComments(nx: Page, mf: (Page) -> NpComments) {
        val my = gen
        Thread {
            try {
                val r = mf(nx)
                act.runOnUiThread { if (my == gen) { cItems.addAll(r.items); cNext = r.next; if (comments) renderComments() } }
            } catch (e: Throwable) {
                act.runOnUiThread { if (my == gen) Notice.show(act, "ما قدرتش أجيب تعليقات أكتر", 2000L) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** نشر تعليق على حسابك (best effort) */
    private fun sendComment() {
        val v = item ?: return
        val t = cEdit.text.toString().trim()
        if (t.isEmpty()) return
        if (!YtAccount.loggedIn()) { Notice.show(act, "سجّل الدخول بحساب جوجل من تبويب «أنت» عشان تكتب تعليق", 3600L); return }
        val my = gen
        Notice.show(act, "⏳ بنشر التعليق…", 1500L)
        Thread {
            try {
                YtAccount.postComment(v.id, t)
                act.runOnUiThread {
                    if (my != gen) return@runOnUiThread
                    cEdit.setText("")
                    cItems.add(0, NpComment(t, "أنت", "", 0L, "دلوقتي", false, false, false, 0, null))
                    cLoaded = true; if (comments) renderComments()
                    Notice.show(act, "✅ التعليق اتنشر على حسابك", 2200L)
                }
            } catch (e: Throwable) {
                LogStore.err("YtWatch:postComment", e)
                act.runOnUiThread { Notice.show(act, "ما قدرتش أنشر التعليق: " + (e.message ?: e.javaClass.simpleName).take(110), 4000L) }
            }
        }.apply { isDaemon = true }.start()
    }

    fun pause() { mini.pause() }
    fun release() { mini.release() }

    /** (v606) خرجت من التطبيق (Home) والفيديو شغّال في صفحة المشاهدة (بورتريه، من غير ما تدخل شاشة كاملة): سلّمه للمشغّل الكامل ومعاه علامة «ادخل PiP» */
    fun pipHandoff(): Boolean {
        val v = item ?: return false
        if (!isOpen || closing || !mini.isPlayingNow()) return false
        val ho = mini.handoff() ?: return false
        YtHandoff.pipNext = true
        host.expand(v.id, titleNow(), ho.second, false, ho.first)
        return true
    }
}

/** (v417) مشغّل شغّال + الـ pick بتاعه + الموضع — بيتسلّم من المشغّل المصغّر لشريط يوتيوب السفلي */
class YtKept(val p: ExoPlayer, val pick: YtPick, val pos: Long)

object YtDockCfg { fun on(): Boolean = Cfg.str("yt_dock", "1") != "0" }

/**
 * (v417) الشريط الصغير السفلي زي يوتيوب: لما تنزّل من صفحة المشاهدة الفيديو بيكمّل في مربع صغير فوق شريط يوتيوب السفلي
 * (مربع فيديو · العنوان · القناة · ⏸/▶ · ✕ · خط تقدم أحمر). دوسة عليه = يتكبّر لمشغّلك الكامل من نفس الثانية (نفس المشغّل من غير تحميل).
 * بيقف لما الصفحة تتخبّى (تبويب تاني / خروج) وبيتقفل لما تفتح فيديو تاني.
 */
class YtDock(private val act: Activity, private val ui: Ui, private val onExpand: (String, String, Long, YtPick?) -> Unit) : FrameLayout(act) {
    private var exo: ExoPlayer? = null
    private var pick: YtPick? = null
    private var vid = ""
    private var ttl = ""
    private var vw = 0
    private var vh = 0
    private val hh = Handler(Looper.getMainLooper())
    private val tex = TextureView(act)
    private val titleTv = TextView(act).apply { textSize = 13f; setTextColor(YtSkin.text); typeface = YtSkin.medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val chanTv = TextView(act).apply { textSize = 11f; setTextColor(YtSkin.sub); setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val playTv = TextView(act).apply { textSize = 20f; gravity = Gravity.CENTER; setTextColor(YtSkin.text); text = "▮▮" }
    private val queueTv = TextView(act).apply { textSize = 17f; gravity = Gravity.CENTER; setTextColor(YtSkin.text); text = "☰"; contentDescription = "قائمة الانتظار" }   // (v469) C9
    private val closeTv = TextView(act).apply { textSize = 18f; gravity = Gravity.CENTER; setTextColor(YtSkin.text); text = "✕" }
    private val prog = View(act).apply { setBackgroundColor(YtSkin.accent); pivotX = 0f; scaleX = 0f }
    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { paintPlay() }
        override fun onPlaybackStateChanged(state: Int) { paintPlay() }
        override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { vw = (v.width * v.pixelWidthHeightRatio).toInt(); vh = v.height; fit() }
        override fun onPlayerError(e: PlaybackException) { LogStore.err("YtDock", e); Notice.show(act, "⚠ الفيديو وقف — دوس عليه يفتحه في المشغّل", 2600L) }
    }
    private val tick = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val d = p.duration
            if (d > 0) prog.scaleX = (p.currentPosition.toFloat() / d.toFloat()).coerceIn(0f, 1f)
            hh.postDelayed(this, 500L)
        }
    }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        visibility = View.GONE
        setBackgroundColor(YtSkin.surface)
        isClickable = true
        elevation = ui.dp(6).toFloat()
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val vbox = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        vbox.addView(tex, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        row.addView(vbox, LinearLayout.LayoutParams(ui.dp(104), -1))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), 0, ui.dp(6), 0) }
        col.addView(titleTv, LinearLayout.LayoutParams(-1, -2))
        col.addView(chanTv, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(queueTv, LinearLayout.LayoutParams(ui.dp(40), -1))
        row.addView(playTv, LinearLayout.LayoutParams(ui.dp(44), -1))
        row.addView(closeTv, LinearLayout.LayoutParams(ui.dp(40), -1))
        addView(row, FrameLayout.LayoutParams(-1, -1))
        addView(prog, FrameLayout.LayoutParams(-1, ui.dp(2), Gravity.TOP))
        setOnClickListener { expand() }
        queueTv.setOnClickListener { showQueue() }
        playTv.setOnClickListener { toggle() }
        closeTv.setOnClickListener { dismiss() }
    }

    private fun fit() {
        val bw = ui.dp(104); val bh = ui.dp(58)
        if (vw <= 0 || vh <= 0) return
        val sc = minOf(bw.toFloat() / vw, bh.toFloat() / vh)
        val lp = tex.layoutParams as? FrameLayout.LayoutParams ?: return
        val nw = (vw * sc).toInt(); val nh = (vh * sc).toInt()
        if (lp.width != nw || lp.height != nh) { lp.width = nw; lp.height = nh; lp.gravity = Gravity.CENTER; tex.layoutParams = lp }
    }

    private fun paintPlay() {
        val p = exo
        playTv.text = if (p != null && p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_ENDED) "▮▮" else "▶"
    }

    private fun toggle() {
        val p = exo ?: return
        if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) { p.seekTo(0L); p.playWhenReady = true } else p.playWhenReady = !p.playWhenReady
        paintPlay()
    }

    fun show(item: NpItem, k: YtKept) {
        dismissInner()
        vid = item.id; ttl = item.title; pick = k.pick
        titleTv.text = item.title; chanTv.text = item.channel
        val p = k.p; exo = p
        try { p.setVideoTextureView(tex) } catch (_: Throwable) {}
        p.addListener(listener)
        val v = p.videoSize
        if (v.width > 0 && v.height > 0) { vw = (v.width * v.pixelWidthHeightRatio).toInt(); vh = v.height; fit() }
        paintPlay()
        alpha = 0f; translationY = ui.dp(24).toFloat(); visibility = View.VISIBLE
        animate().alpha(1f).translationY(0f).setDuration(200L).start()
        hh.removeCallbacks(tick); hh.post(tick)
    }

    /** (v469) C9 قائمة الانتظار: عرض + سحب أفقي لحذف عنصر + دوسة لتشغيله في المشغّل الكامل */
    private fun showQueue() {
        val items = YtQueue.list()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        val dlg: android.app.Dialog = ui.sheet(act, "📃 قائمة الانتظار (" + items.size + ")", listOf<View>(col))
        if (items.isEmpty()) {
            col.addView(TextView(act).apply { text = "القائمة فاضية — بتتملى من مقترحات الفيديو الحالي، أو من ضغطة طويلة على أي فيديو ← «⏭ شغّل بعده»"; textSize = 13f; setTextColor(YtSkin.sub); gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(22), ui.dp(16), ui.dp(22)) }, LinearLayout.LayoutParams(-1, -2))
        }
        for (v in items) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7)) }
            val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(android.graphics.Color.BLACK) }
            try { if (v.thumb.isNotBlank()) Glide.with(act).load(v.thumb).into(img) } catch (_: Throwable) {}
            row.addView(img, LinearLayout.LayoutParams(ui.dp(96), ui.dp(54)))
            val tc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, 0, 0) }
            tc.addView(TextView(act).apply { text = v.title; textSize = 13f; setTextColor(YtSkin.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2))
            tc.addView(TextView(act).apply { text = v.channel; textSize = 11f; setTextColor(YtSkin.sub); setSingleLine(); ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2))
            row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
            var dx0 = 0f; var moved = false
            row.setOnTouchListener { rv, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; moved = false; true }
                    MotionEvent.ACTION_MOVE -> { val dx = e.rawX - dx0; if (Math.abs(dx) > ui.dp(8)) moved = true; rv.translationX = dx; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = rv.translationX
                        if (Math.abs(dx) > rv.width * 0.32f) {
                            YtQueue.remove(v.id)
                            rv.animate().translationX(if (dx > 0) rv.width.toFloat() else -rv.width.toFloat()).alpha(0f).setDuration(160L).withEndAction { (rv.parent as? LinearLayout)?.removeView(rv); queueTv.text = "☰" }.start()
                            Notice.show(act, "🗑 اتشال من قائمة الانتظار", 1400L)
                        } else if (!moved) { dlg.dismiss(); expandItem(v) }
                        else rv.animate().translationX(0f).setDuration(160L).start()
                        true
                    }
                    else -> false
                }
            }
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        dlg.show()
    }

    /** (v469) C9 تشغيل عنصر من قائمة الانتظار في المشغّل الكامل (من موضعه المحفوظ لو ليه) */
    private fun expandItem(v: NpItem) {
        dismiss()
        val e = try { YtProg.get(v.id) } catch (_: Throwable) { null }
        val pos = if (e != null && !e.done) e.posMs else 0L
        onExpand(v.id, v.title, pos, null)
    }

    /** دوسة على الشريط: نفس المشغّل (شغّال ومحمّل) بيتسلّم للمشغّل الكامل من نفس الثانية */
    private fun expand() {
        val p = exo ?: return; val pk = pick ?: return
        val pos = p.currentPosition
        try { p.removeListener(listener) } catch (_: Throwable) {}
        hh.removeCallbacks(tick); exo = null
        p.playWhenReady = true
        YtHandoff.put(p)
        visibility = View.GONE
        onExpand(vid, ttl, pos, pk)
    }

    /** (v606) خرجت من التطبيق والفيديو شغّال في الشريط السفلي: نفس التسليم لمشغّل الكامل + دخول PiP */
    fun pipHandoff(): Boolean {
        val p = exo ?: return false
        if (visibility != View.VISIBLE || !p.isPlaying) return false
        YtHandoff.pipNext = true
        expand()
        return true
    }

    fun pause() { try { exo?.playWhenReady = false } catch (_: Throwable) {}; paintPlay() }

    fun dismiss() { dismissInner(); visibility = View.GONE }

    private fun dismissInner() {
        hh.removeCallbacks(tick)
        val p = exo; exo = null
        if (p != null) {
            try { p.removeListener(listener) } catch (_: Throwable) {}
            try { p.release() } catch (_: Throwable) {}
        }
        prog.scaleX = 0f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) { try { exo?.playWhenReady = false } catch (_: Throwable) {} }
    }
}
