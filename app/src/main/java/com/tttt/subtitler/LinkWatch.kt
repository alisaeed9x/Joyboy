package com.tttt.subtitler

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v729/v730) مراقب اللينكات: شغّال في الخلفية على طول (مش ويدجت) وبيظهر الدايرة لوحده فوق أي تطبيق، وكل نسخ بيتحفظ في سجل البرنامج:
 *  - أول ما تنسخ لينك (من أي تطبيق) بتظهر دايرة بتلف بتدوّر على فيديو، ولما تلقاه بتظهر أزرار: تلفزيون · تحميل · شاهد وترجم · شاهد.
 *  - لو فتحت صفحة فيديو في متصفح (Chrome · Firefox · Samsung…) بتظهر دايرة حمراء بنفس الأزرار.
 * خدمة إمكانية وصول مستقلة (بتتفعّل مرة واحدة)؛ قراءة الشاشة محصورة في تطبيقات المتصفحات بس (link_watch_config.xml).
 */
object LinkWatch {
    @Volatile var svc: LinkWatchService? = null
    val connected: Boolean get() = svc != null

    /** متفعّلة في إعدادات السيستم (حتى لو لسه ما اتوصّلتش) */
    fun enabled(ctx: Context): Boolean = try {
        (android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("LinkWatchService")
    } catch (_: Throwable) { false }

    fun openSettings(a: Activity) {
        try { a.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) { try { Notice.show(a, "ما قدرتش أفتح إعدادات إمكانية الوصول — افتحها من إعدادات الجهاز", 4000L) } catch (_: Throwable) {} }
    }

    private val VID_RX = Regex(
        "(youtube\\.com/(watch|shorts|live)|youtu\\.be/|facebook\\.com/(watch|reel|[^/]+/videos)|fb\\.watch/|tiktok\\.com/.+/video/|vm\\.tiktok\\.com/|" +
        "instagram\\.com/(reel|reels|p|tv)/|(twitter|x)\\.com/.+/status/|vimeo\\.com/\\d+|dailymotion\\.com/video/|twitch\\.tv/videos/)",
        RegexOption.IGNORE_CASE)

    /** فحص رخيص قبل ما نظهر الدايرة الحمراء: لينك فيديو مباشر أو موقع فيديو معروف */
    fun likelyVideo(u: String): Boolean = Sniff.isDirect(u) || VID_RX.containsMatchIn(u)
}

class LinkWatchService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cm: ClipboardManager? = null
    private var clipL: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var bubble: Bubble? = null
    private var lastUrl = ""
    private var fgPkg = ""   // (v732) آخر تطبيق كان قدّام عينك: مصدر النسخ
    private var grabbing = false
    private val scan = Runnable { scanBrowser() }

    private val BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "org.mozilla.fenix", "com.sec.android.app.sbrowser", "com.microsoft.emmx", "com.brave.browser", "com.opera.browser",
        "com.opera.mini.native", "com.kiwibrowser.browser", "com.vivaldi.browser", "com.duckduckgo.mobile.android")
    private val ID_SUFFIXES = listOf("url_bar", "mozac_browser_toolbar_url_view", "location_bar_edit_text", "url_field", "url_bar_title")

    override fun onServiceConnected() {
        super.onServiceConnected()
        LinkWatch.svc = this
        try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}
        try {
            val c = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val l = ClipboardManager.OnPrimaryClipChangedListener { main.post { onClipChanged() } }
            c.addPrimaryClipChangedListener(l)
            cm = c; clipL = l
        } catch (e: Throwable) { LogStore.err("LinkWatch:connect", e) }
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return super.onUnbind(intent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun shutdown() {
        LinkWatch.svc = null
        try { clipL?.let { cm?.removePrimaryClipChangedListener(it) } } catch (_: Throwable) {}
        clipL = null
        main.removeCallbacks(scan)
        bubble?.close(false)
    }

    private fun windowMgr(): WindowManager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }

    // ---------- المتصفحات: قراءة شريط العنوان ----------
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.startsWith("com.android.systemui") && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) fgPkg = pkg
        if (pkg !in BROWSERS) return
        main.removeCallbacks(scan)
        main.postDelayed(scan, 700L)
    }

    private fun scanBrowser() {
        if (Cfg.bool("lw_web_off", false)) return
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in BROWSERS) return
        var url = ""
        for (suffix in ID_SUFFIXES) {
            val ns = try { root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + suffix) } catch (_: Throwable) { null } ?: continue
            for (n in ns) { val t = n.text?.toString()?.trim() ?: ""; if (t.isNotEmpty()) { url = t; break } }
            if (url.isNotEmpty()) break
        }
        if (url.isEmpty() || url.contains(' ') || !url.contains('.')) return
        if (url == lastUrl) return
        lastUrl = url
        if (!LinkWatch.likelyVideo(url)) return
        val full = if (url.startsWith("http", true)) url else "https://" + url
        show(listOf(full), true)
    }

    // ---------- الكليبورد ----------
    private fun readClip(): String = try { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "" } catch (_: Throwable) { "" }

    private fun appLabel(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    private fun onClipChanged() {
        val src = fgPkg   // نلتقط المصدر لحظة النسخ (قبل ما نافذة الالتقاط الشفافة تاخد الفوكس)
        val page = if (src in BROWSERS && lastUrl.isNotBlank()) (if (lastUrl.startsWith("http", true)) lastUrl else "https://$lastUrl") else ""
        if (Cfg.bool("lw_clip_off", false) && Cfg.bool("lw_hist_off", false)) return
        val desc = try { cm?.primaryClipDescription } catch (_: Throwable) { null }
        if (desc != null && !desc.hasMimeType("text/*")) return
        val t = readClip()
        if (t.isNotBlank()) { gotClip(t, src, page); return }
        // أندرويد 10+ بيمنع قراءة الكليبورد من الخلفية: نافذة صغيرة شفافة بتاخد الفوكس لحظة وتقرا
        if (grabbing) return
        grabbing = true
        grabWithFocus { txt -> grabbing = false; if (txt.isNotBlank()) gotClip(txt, src, page) }
    }

    private fun gotClip(text: String, src: String = "", page: String = "") {
        val t = text.trim()
        if (t.isEmpty()) return
        // (v730) كل نص بتنسخه بيتحفظ في سجل البرنامج الدائم (لينك أو غيره)، حتى لو مفيهوش فيديو
        if (!Cfg.bool("lw_hist_off", false)) { try { ClipHistory.add(this, t, src, if (src.isBlank()) "" else appLabel(src), page) } catch (_: Throwable) {} }
        if (Cfg.bool("lw_clip_off", false)) return   // لقط اللينكات واقف (السجل بيفضل شغّال لوحده)
        if (ClipWatch.isSeen(this, t)) return
        val links = ClipWatch.extract(t)
        if (links.isEmpty()) return
        ClipWatch.markSeen(this, t)
        show(links, false)
    }

    private fun focusType(): Int =
        if (Build.VERSION.SDK_INT >= 26 && android.provider.Settings.canDrawOverlays(this)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun grabWithFocus(cb: (String) -> Unit) {
        val w = windowMgr()
        val v = View(this)
        val lp = WindowManager.LayoutParams(1, 1, focusType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        try { w.addView(v, lp) } catch (e: Throwable) { cb(""); return }
        main.postDelayed({
            val t = readClip()
            try { w.removeView(v) } catch (_: Throwable) {}
            cb(t)
        }, 150L)
    }

    // ---------- الدايرة العايمة ----------
    private fun show(links: List<String>, red: Boolean) {
        bubble?.close(false)
        val b = Bubble(links, red)
        bubble = b
        b.start()
    }

    private fun openMain(act: String, c: ClipVideo?, url: String) {
        try {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("clip_act", act).putExtra("clip_url", c?.url ?: url).putExtra("clip_title", c?.title ?: "")
                .putExtra("clip_play", c?.playUrl ?: "").putExtra("clip_kind", c?.kind ?: "WEB")
                .putExtra("clip_ref", c?.ref ?: "").putExtra("clip_ua", c?.ua ?: "")
            startActivity(i)
        } catch (e: Throwable) { LogStore.err("LinkWatch:open", e) }
    }

    private inner class Bubble(val links: List<String>, val red: Boolean) {
        private val ctx: Context = this@LinkWatchService
        private val d = resources.displayMetrics.density
        private val results = HashMap<String, ClipVideo?>()
        private val doneSet = HashSet<String>()
        private val orb = ClipOrb(ctx)
        private val btns = LinearLayout(ctx)
        private val col = LinearLayout(ctx)
        private var idx = 0
        private var t0 = System.currentTimeMillis()
        private var tIdx = t0
        private var life = 10_000L
        private var closed = false
        private var revealed = false
        private val ticker = object : Runnable { override fun run() { tick() } }

        private fun baseY(): Int = (resources.displayMetrics.heightPixels * 0.40f).toInt()

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
            addBtn("إلغاء", "✕", 0xFF757575.toInt(), Color.WHITE, "cancel")
            orb.setOnClickListener {
                val u = links[idx]
                val r = synchronized(results) { results[u] }
                close(true)
                openMain("orb", r, u)
            }
            orb.setOnLongClickListener {
                if (idx + 1 < links.size) {
                    unreveal(); idx++; tIdx = System.currentTimeMillis()
                    orb.count = links.size - idx; orb.thumb = null; orb.found = false; t0 = System.currentTimeMillis(); orb.elapsed = 0f
                    sync()
                } else close(true)
                true
            }
            val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = (10 * d).toInt()
            lp.y = baseY()
            try { windowMgr().addView(col, lp) } catch (e: Throwable) { LogStore.err("LinkWatch:add", e); closed = true; return }
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
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC101418.toInt()); cornerRadius = 14 * d }
            }
            val bs = (46 * d).toInt()
            row.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() })
            row.addView(ClipDot(ctx, glyph, fill, gc), LinearLayout.LayoutParams(bs, bs))
            row.setOnClickListener {
                val u = links[idx]
                val r = synchronized(results) { results[u] }
                close(true)
                if (act != "cancel") openMain(act, r, u)
            }
            btns.addView(row, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = (8 * d).toInt() })
        }

        private fun lift() {
            col.postDelayed({
                try {
                    val lp = col.layoutParams as WindowManager.LayoutParams
                    val maxY = resources.displayMetrics.heightPixels - col.height - (96 * d).toInt()
                    lp.y = minOf(baseY(), maxY).coerceAtLeast((24 * d).toInt())
                    windowMgr().updateViewLayout(col, lp)
                } catch (_: Throwable) {}
            }, 120L)
        }

        private fun reveal() {
            if (revealed) return
            revealed = true
            life = 5_000L; t0 = System.currentTimeMillis()   // (v730) الأزرار بتفضل 5 ثواني وتختفي لوحدها
            btns.visibility = View.VISIBLE
            for (i in 0 until btns.childCount) { val v = btns.getChildAt(i); v.translationY = -10 * d; v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(180).start() }
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
                lp.y = baseY(); windowMgr().updateViewLayout(col, lp)
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
            }
            if (fin && r != null) reveal()
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
            if (this@LinkWatchService.bubble === this) this@LinkWatchService.bubble = null
            val w = windowMgr()
            if (animate) {
                col.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(200).withEndAction { try { w.removeView(col) } catch (_: Throwable) {} }.start()
            } else {
                try { w.removeView(col) } catch (_: Throwable) {}
            }
        }
    }
}
