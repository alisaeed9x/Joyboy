package com.tttt.subtitler

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v735) الجزء المشترك بين خدمة إمكانية الوصول (LinkWatchService) وخدمة «العرض فوق التطبيقات» الجديدة (ClipOverlayService):
 * قراءة الكليبورد بالفوكس · حفظ السجل · الدايرة العايمة · إشعار «اتحفظ».
 * نوع النافذة: TYPE_APPLICATION_OVERLAY لو إذن العرض فوق التطبيقات متاح (الأضمن)، وإلا TYPE_ACCESSIBILITY_OVERLAY (للخدمة بس).
 */
object LinkHub {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cur: LinkBubble? = null
    private var lastToastText = ""
    private var lastToastAt = 0L

    fun overlayOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && try { android.provider.Settings.canDrawOverlays(ctx) } catch (_: Throwable) { false }

    fun readClip(ctx: Context): String = try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
    } catch (_: Throwable) { "" }

    /** فتح البرنامج بالأمر اللي اتداس عليه في الدايرة (نفس extras بتاعة v729) */
    fun open(ctx: Context, act: String, c: ClipVideo?, url: String) {
        try {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("clip_act", act).putExtra("clip_url", c?.url ?: url).putExtra("clip_title", c?.title ?: "")
                .putExtra("clip_play", c?.playUrl ?: "").putExtra("clip_kind", c?.kind ?: "WEB")
                .putExtra("clip_ref", c?.ref ?: "").putExtra("clip_ua", c?.ua ?: "")
            ctx.startActivity(i)
        } catch (e: Throwable) { LogStore.err("LinkHub:open", e) }
    }

    fun show(ctx: Context, wm: WindowManager, type: Int, links: List<String>, red: Boolean) {
        main.post {
            try {
                cur?.close(false)
                val b = LinkBubble(ctx, wm, type, links, red) { act, c, u -> open(ctx, act, c, u) }
                cur = b
                b.onClosed = { if (cur === b) cur = null }
                b.start()
            } catch (e: Throwable) { LogStore.err("LinkHub:show", e) }
        }
    }

    fun closeCur() { main.post { try { cur?.close(false) } catch (_: Throwable) {}; cur = null } }

    /**
     * (v735) قراءة الكليبورد من الخلفية (أندرويد 10+): نافذة 1×1 شفافة بتاخد الفوكس لحظة.
     * القراءة بتحصل أول ما النافذة تاخد الفوكس فعلًا (onWindowFocusChanged) + محاولات احتياطية، بدل تأخير ثابت 150ms اللي كان بيفشل.
     */
    fun grab(ctx: Context, wm: WindowManager, type: Int, cb: (String) -> Unit) {
        main.post {
            var done = false
            var v: View? = null
            fun finish(t: String) {
                if (done) return
                done = true
                try { v?.let { wm.removeView(it) } } catch (_: Throwable) {}
                cb(t)
            }
            val view = object : View(ctx) {
                override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                    super.onWindowFocusChanged(hasWindowFocus)
                    if (hasWindowFocus) main.postDelayed({ val t = readClip(ctx); if (t.isNotBlank()) finish(t) }, 40L)
                }
            }
            v = view
            val lp = WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            try { wm.addView(view, lp) } catch (e: Throwable) { LogStore.err("LinkHub:grab", e); done = true; cb(""); return@post }
            for (ms in longArrayOf(150L, 350L, 700L, 1200L)) main.postDelayed({ if (!done) { val t = readClip(ctx); if (t.isNotBlank()) finish(t) } }, ms)
            main.postDelayed({ if (!done) { LogStore.add("⚠ مراقب اللينكات: ماقدرتش أقرا الكليبورد (النافذة ماخدتش فوكس)"); finish("") } }, 1800L)
        }
    }

    /** نسخة جديدة اتقرت: سجل + إشعار «اتحفظ» + دايرة لو فيها لينكات */
    fun gotClip(ctx: Context, wm: WindowManager, type: Int, text: String, src: String = "", srcLabel: String = "", page: String = "") {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!Cfg.bool("lw_hist_off", false)) {
            try { ClipHistory.add(ctx, t, src, srcLabel, page) } catch (_: Throwable) {}
            val now = System.currentTimeMillis()
            if (!Cfg.bool("lw_toast_off", false) && !(t == lastToastText && now - lastToastAt < 3000L)) {
                lastToastText = t; lastToastAt = now
                toast(ctx, wm, type, "📎 اتحفظ في سجل Joyboy")
            }
        }
        if (Cfg.bool("lw_clip_off", false)) return   // لقط اللينكات واقف (السجل بيفضل شغّال لوحده)
        if (ClipWatch.isSeen(ctx, t)) return
        val links = ClipWatch.extract(t)
        if (links.isEmpty()) return
        ClipWatch.markSeen(ctx, t)
        show(ctx, wm, type, links, false)
    }

    fun toast(ctx: Context, wm: WindowManager, type: Int, msg: String) {
        main.post {
            try {
                val d = ctx.resources.displayMetrics.density
                val tv = TextView(ctx).apply {
                    text = msg; setTextColor(Color.WHITE); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                    background = GradientDrawable().apply { setColor(0xE6101418.toInt()); cornerRadius = 20 * d }
                    alpha = 0f
                }
                val lp = WindowManager.LayoutParams(-2, -2, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT)
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = (96 * d).toInt()
                wm.addView(tv, lp)
                tv.animate().alpha(1f).setDuration(150).start()
                main.postDelayed({
                    try { tv.animate().alpha(0f).setDuration(220).withEndAction { try { wm.removeView(tv) } catch (_: Throwable) {} }.start() }
                    catch (_: Throwable) { try { wm.removeView(tv) } catch (_: Throwable) {} }
                }, 1800L)
            } catch (e: Throwable) { LogStore.err("LinkHub:toast", e) }
        }
    }
}

/** الدايرة العايمة + أزرارها. لو اللينك مش فيديو بتظهر «افتح في متصفح Joyboy» بدل أزرار الفيديو. */
class LinkBubble(
    private val ctx: Context, private val wm: WindowManager, private val type: Int,
    val links: List<String>, val red: Boolean,
    private val onOpen: (String, ClipVideo?, String) -> Unit
) {
    var onClosed: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val d = ctx.resources.displayMetrics.density
    private val results = HashMap<String, ClipVideo?>()
    private val doneSet = HashSet<String>()
    private val orb = ClipOrb(ctx)
    private val btns = LinearLayout(ctx)
    private val col = LinearLayout(ctx)
    private val rows = HashMap<String, View>()
    private var idx = 0
    private var t0 = System.currentTimeMillis()
    private var tIdx = t0
    private var life = 10_000L
    private var closed = false
    private var revealed = false
    private val ticker = object : Runnable { override fun run() { tick() } }

    private fun baseY(): Int = (ctx.resources.displayMetrics.heightPixels * 0.40f).toInt()

    fun start() {
        orb.count = links.size
        if (red) { orb.accent = 0xFFE53935.toInt(); orb.glyph = "▶" }
        btns.orientation = LinearLayout.VERTICAL; btns.layoutDirection = View.LAYOUT_DIRECTION_LTR; btns.visibility = View.GONE
        col.orientation = LinearLayout.VERTICAL; col.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val size = (64 * d).toInt()
        col.addView(orb, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.END })
        col.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        addBtn("مشاهدة ع التلفزيون", "📺", 0xFFE53935.toInt(), Color.WHITE, "tv")
        addBtn("تحميل", "⬇", 0xFF1E6FE0.toInt(), Color.WHITE, "dl")
        addBtn("مشاهدة وترجمة", "🌐", 0xFF34C759.toInt(), Color.WHITE, "tr")
        addBtn("مشاهدة بس", "▶", 0xFFFFC107.toInt(), 0xFF202020.toInt(), "watch")
        addBtn("افتح في متصفح Joyboy", "🧭", 0xFF7E57C2.toInt(), Color.WHITE, "web")
        addBtn("إلغاء", "✕", 0xFF757575.toInt(), Color.WHITE, "cancel")
        orb.setOnClickListener {
            val u = links[idx]
            val r = synchronized(results) { results[u] }
            close(true)
            onOpen(if (r == null) "web" else "orb", r, u)
        }
        orb.setOnLongClickListener {
            if (idx + 1 < links.size) {
                unreveal(); idx++; tIdx = System.currentTimeMillis()
                orb.count = links.size - idx; orb.thumb = null; orb.found = false; t0 = System.currentTimeMillis(); orb.elapsed = 0f
                sync()
            } else close(true)
            true
        }
        val lp = WindowManager.LayoutParams(-2, -2, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = (10 * d).toInt()
        lp.y = baseY()
        try { wm.addView(col, lp) } catch (e: Throwable) { LogStore.err("LinkBubble:add", e); closed = true; onClosed?.invoke(); return }
        col.alpha = 0f; col.scaleX = 0.4f; col.scaleY = 0.4f
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        Thread {
            for (u in links) {
                val r = try { ClipWatch.resolve(ctx, u) } catch (_: Throwable) { null }
                synchronized(results) { results[u] = r; doneSet.add(u) }
            }
        }.apply { isDaemon = true }.start()
        main.postDelayed(ticker, 80L)
    }

    private fun addBtn(label: String, glyph: String, fill: Int, gc: Int, act: String) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; alpha = 0f }
        val tv = TextView(ctx).apply {
            text = label; setTextColor(Color.WHITE); textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable().apply { setColor(0xCC101418.toInt()); cornerRadius = 14 * d }
        }
        val bs = (46 * d).toInt()
        row.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() })
        row.addView(ClipDot(ctx, glyph, fill, gc), LinearLayout.LayoutParams(bs, bs))
        row.setOnClickListener {
            val u = links[idx]
            val r = synchronized(results) { results[u] }
            close(true)
            if (act != "cancel") onOpen(act, r, u)
        }
        rows[act] = row
        btns.addView(row, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = (8 * d).toInt() })
    }

    private fun lift() {
        col.postDelayed({
            try {
                val lp = col.layoutParams as WindowManager.LayoutParams
                val maxY = ctx.resources.displayMetrics.heightPixels - col.height - (96 * d).toInt()
                lp.y = minOf(baseY(), maxY).coerceAtLeast((24 * d).toInt())
                wm.updateViewLayout(col, lp)
            } catch (_: Throwable) {}
        }, 120L)
    }

    /** video = true: كل الأزرار؛ false (لينك عادي): «افتح في متصفح Joyboy» + إلغاء بس */
    private fun reveal(video: Boolean) {
        if (revealed) return
        revealed = true
        for (k in listOf("tv", "dl", "tr", "watch")) rows[k]?.visibility = if (video) View.VISIBLE else View.GONE
        rows["web"]?.visibility = View.VISIBLE
        life = 5_000L; t0 = System.currentTimeMillis()   // الأزرار بتفضل 5 ثواني وتختفي لوحدها
        btns.visibility = View.VISIBLE
        var n = 0
        for (i in 0 until btns.childCount) {
            val v = btns.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            v.translationY = -10 * d; v.animate().alpha(1f).translationY(0f).setStartDelay(60L * n).setDuration(180).start(); n++
        }
        lift()
    }

    private fun unreveal() {
        if (!revealed) return
        revealed = false
        life = 10_000L
        btns.visibility = View.GONE
        for (i in 0 until btns.childCount) btns.getChildAt(i).alpha = 0f
        try {
            val lp = col.layoutParams as WindowManager.LayoutParams
            lp.y = baseY(); wm.updateViewLayout(col, lp)
        } catch (_: Throwable) {}
    }

    private fun sync() {
        val u = links[idx]
        val r = synchronized(results) { results[u] }
        val fin = synchronized(results) { u in doneSet }
        if (fin) {
            orb.found = r != null
            if (r?.thumb != null && orb.thumb !== r.thumb) orb.thumb = r.thumb
            if (r == null && red) { close(true); return }   // الدايرة الحمراء (المتصفح): الصفحة مفيهاش فيديو ← اختفي من غير ما تزعج
            if (r == null) { if (!red) orb.glyph = "🧭"; reveal(false); return }   // (v735) لينك عادي: افتحه في متصفح Joyboy
            reveal(true)
        }
    }

    private fun tick() {
        if (closed) return
        orb.elapsed = (System.currentTimeMillis() - t0).toFloat() / life
        sync()
        if (closed) return
        if (orb.elapsed >= 1f) {
            val fin = synchronized(results) { links[idx] in doneSet }
            if (fin || System.currentTimeMillis() - tIdx >= 26_000L) { close(true); return }
            t0 = System.currentTimeMillis() - (life * 0.97).toLong()   // لسه بيفحص: كمّل استنى (لحد 26 ثانية)
        }
        main.postDelayed(ticker, 80L)
    }

    fun close(animate: Boolean) {
        if (closed) return
        closed = true
        main.removeCallbacks(ticker)
        onClosed?.invoke()
        if (animate) {
            col.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(200).withEndAction { try { wm.removeView(col) } catch (_: Throwable) {} }.start()
        } else {
            try { wm.removeView(col) } catch (_: Throwable) {}
        }
    }
}
