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
        LinkHub.closeCur()
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
    /** (v735) TYPE_APPLICATION_OVERLAY لو إذن العرض فوق التطبيقات متاح (الأضمن، ومعاه بيتفتح البرنامج من الخلفية)، وإلا نافذة إمكانية الوصول */
    private fun winType(): Int =
        if (LinkHub.overlayOk(this)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    /** WindowManager الخدمة بيحمل توكن نافذة إمكانية الوصول ← مع TYPE_APPLICATION_OVERLAY بيرفض (type mismatch)؛ نستخدم اللي من الـapplication */
    private fun winMgr(type: Int): WindowManager =
        if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) windowMgr()
        else applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun appLabel(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    private fun onClipChanged() {
        val src = fgPkg   // نلتقط المصدر لحظة النسخ (قبل ما نافذة الالتقاط الشفافة تاخد الفوكس)
        val page = if (src in BROWSERS && lastUrl.isNotBlank()) (if (lastUrl.startsWith("http", true)) lastUrl else "https://$lastUrl") else ""
        if (Cfg.bool("lw_clip_off", false) && Cfg.bool("lw_hist_off", false)) return
        val desc = try { cm?.primaryClipDescription } catch (_: Throwable) { null }
        if (desc != null && !desc.hasMimeType("text/*")) return
        val type = winType()
        val t = LinkHub.readClip(this)
        if (t.isNotBlank()) { gotClip(t, src, page, type); return }
        // أندرويد 10+ بيمنع قراءة الكليبورد من الخلفية: نافذة صغيرة شفافة بتاخد الفوكس لحظة وتقرا
        if (grabbing) return
        grabbing = true
        LinkHub.grab(this, winMgr(type), type) { txt -> grabbing = false; if (txt.isNotBlank()) gotClip(txt, src, page, type) }
    }

    private fun gotClip(text: String, src: String, page: String, type: Int) {
        LinkHub.gotClip(this, winMgr(type), type, text, src, if (src.isBlank()) "" else appLabel(src), page)
    }

    // ---------- الدايرة العايمة ----------
    private fun show(links: List<String>, red: Boolean) {
        val type = winType()
        LinkHub.show(this, winMgr(type), type, links, red)
    }
}
