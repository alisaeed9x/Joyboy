package com.tttt.subtitler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * (v519) تنفيذ أوامر ريموت الموبايل على التلفزيون (أزرار + تاتش باد).
 *  - جوه تطبيقنا (اللانشر / المشغّل / المتصفح / الملفات): بنحقن الزراير بـ Instrumentation فبتتحرك الفوكس زي ريموت حقيقي،
 *    والتاتش باد بيرسم مؤشر فوق الشاشة وبيبعت دوسة/تمرير حقيقي.
 *  - في تطبيق تاني (نتفليكس مثلًا): محتاج تفعيل «إمكانية الوصول» لـ Subtitler مرة واحدة (TvAccessService تحت) —
 *    من غيره بيشتغل الصوت والتشغيل/الإيقاف وزراير الميديا (AudioManager) وفتح الرئيسية بس.
 */
object TvInput {
    /** حساسية المؤشر: 1.0 = إصبعك عدّى عرض التاتش باد كله ← المؤشر عدّى عرض الشاشة × GAIN */
    const val GAIN = 1.7f

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
    private var cursorRef: WeakReference<TvCursor>? = null
    private var lastNeed = 0L

    /** إيه اللي ظاهر على التلفزيون دلوقتي — الموبايل بيغيّر لوحة الأزرار على أساسه */
    fun context(): String = when (TvLink.topActivity()) {
        null -> "other"
        is PlayerActivity -> "player"
        is BrowserActivity -> "browser"
        is TvActivity -> "home"
        else -> "app"
    }

    // ===== الأزرار =====
    fun key(k: String) {
        val code = when (k) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU
            "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "home" -> KeyEvent.KEYCODE_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH
            else -> return
        }
        worker.execute { try { dispatchKey(k, code) } catch (e: Throwable) { LogStore.err("TvInput:key", e) } }
    }

    private fun dispatchKey(k: String, code: Int) {
        val a = TvLink.topActivity()
        when (k) {
            "home" -> { if (!TvLink.showTv("home")) { if (!TvAccess.global(AccessibilityService.GLOBAL_ACTION_HOME)) goHome() }; return }   // (v522) رئيسية اللانشر الأول
            "recents" -> { if (!TvAccess.global(AccessibilityService.GLOBAL_ACTION_RECENTS)) needAccess(); return }
            "ff", "rew" -> {
                // مشغّلنا: يمين/شمال = تقديم/ترجيع 10 ثواني. غيره: زراير الميديا
                if (a is PlayerActivity) { inject(if (k == "ff") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT); return }
                mediaKey(code); return
            }
            "next", "prev" -> { mediaKey(code); return }
        }
        if (a != null && inject(code)) return
        if (!TvAccess.key(code)) needAccess()
    }

    /** حقن زرار في نافذتنا. false = فيه نافذة تطبيق تاني في الواجهة (أندرويد بيرفض) */
    private fun inject(code: Int): Boolean = try {
        Instrumentation().sendKeyDownUpSync(code); true
    } catch (_: SecurityException) { false } catch (e: Throwable) { LogStore.err("TvInput:inject", e); false }

    private fun mediaKey(code: Int) {
        val c = TvLink.appCtx() ?: return
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t + 40L, KeyEvent.ACTION_UP, code, 0))
        } catch (e: Throwable) { LogStore.err("TvInput:media", e) }
    }

    private fun goHome() {
        val c = TvLink.appCtx() ?: return
        try { c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Throwable) { LogStore.err("TvInput:home", e) }
    }

    private fun needAccess() {
        val n = System.currentTimeMillis()
        if (n - lastNeed < 6000L) return
        lastNeed = n
        val c = TvLink.appCtx() ?: return
        main.post { try { Toast.makeText(c, "⚠ للتحكم في تطبيقات تانية فعّل «إمكانية الوصول» لـ Subtitler مرة واحدة (⋯ المزيد ← 📱 ريموت الموبايل)", Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
    }

    // ===== التاتش باد =====
    /** op: mv (تحريك) · tap · long · scroll — dx/dy نسبة من عرض/طول التاتش باد على الموبايل */
    fun ptr(op: String, dx: Float, dy: Float) {
        main.post {
            try {
                val a = TvLink.topActivity()
                if (a != null) inApp(a, op, dx, dy)
                else if (!TvAccess.ptr(op, dx, dy)) needAccess()
            } catch (e: Throwable) { LogStore.err("TvInput:ptr", e) }
        }
    }

    private fun cursorFor(a: Activity, decor: ViewGroup): TvCursor {
        var c = cursorRef?.get()
        if (c == null || c.parent !== decor) {
            (c?.parent as? ViewGroup)?.removeView(c)
            c = TvCursor(a, Themes.byId(Cfg.str("theme", "mx")).primary)
            decor.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cursorRef = WeakReference(c)
        }
        return c
    }

    private fun inApp(a: Activity, op: String, dx: Float, dy: Float) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        val c = cursorFor(a, decor)
        val w = decor.width.coerceAtLeast(1).toFloat(); val h = decor.height.coerceAtLeast(1).toFloat()
        when (op) {
            "mv" -> c.moveBy(dx * w * GAIN, dy * h * GAIN)
            "tap" -> { c.pulse(); touch(a, decor, c.cx, c.cy, 60L) }
            "long" -> { c.pulse(); touch(a, decor, c.cx, c.cy, 750L) }
            "scroll" -> { c.poke(); wheel(a, c.cx, c.cy, dx.coerceIn(-1f, 1f), dy.coerceIn(-1f, 1f)) }
        }
    }

    /** دوسة حقيقية عن طريق نظام الإدخال (فبتروح للنافذة اللي في الواجهة حتى لو دايلوج) وfallback مباشر على الأكتيفيتي */
    private fun touch(a: Activity, decor: ViewGroup, cx: Float, cy: Float, holdMs: Long) {
        val loc = IntArray(2); decor.getLocationOnScreen(loc)
        val sx = loc[0] + cx; val sy = loc[1] + cy
        worker.execute {
            var ok = false
            try {
                val inst = Instrumentation()
                val t = SystemClock.uptimeMillis()
                val dn = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx, sy, 0)
                dn.source = InputDevice.SOURCE_TOUCHSCREEN
                inst.sendPointerSync(dn); dn.recycle()
                ok = true
                try { Thread.sleep(holdMs) } catch (_: InterruptedException) {}
                val t2 = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, sx, sy, 0)
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                inst.sendPointerSync(up); up.recycle()
            } catch (e: Throwable) {
                if (!ok) main.post { try { fallbackTap(a, cx, cy) } catch (_: Throwable) {} }
                else LogStore.err("TvInput:touch", e)
            }
        }
    }

    private fun fallbackTap(a: Activity, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val dn = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); dn.source = InputDevice.SOURCE_TOUCHSCREEN
        a.dispatchTouchEvent(dn); dn.recycle()
        main.postDelayed({
            try {
                val t2 = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0); up.source = InputDevice.SOURCE_TOUCHSCREEN
                a.dispatchTouchEvent(up); up.recycle()
            } catch (_: Throwable) {}
        }, 60L)
    }

    /** عجلة ماوس وهمية عند المؤشر (بتسكرول القوايم والويب) */
    private fun wheel(a: Activity, x: Float, y: Float, sx: Float, sy: Float) {
        val t = SystemClock.uptimeMillis()
        val pp = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val pc = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, sy * 40f); setAxisValue(MotionEvent.AXIS_HSCROLL, -sx * 40f)
        })
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        a.window.decorView.dispatchGenericMotionEvent(ev); ev.recycle()
    }
}

/** حالة خدمة «إمكانية الوصول» — بتتفعّل مرة واحدة من إعدادات التلفزيون */
object TvAccess {
    @Volatile var svc: TvAccessService? = null
    val connected: Boolean get() = svc != null

    fun global(action: Int): Boolean { val s = svc ?: return false; return try { s.performGlobalAction(action) } catch (_: Throwable) { false } }
    fun key(code: Int): Boolean { val s = svc ?: return false; return try { s.key(code) } catch (_: Throwable) { false } }
    fun ptr(op: String, dx: Float, dy: Float): Boolean { val s = svc ?: return false; s.ptr(op, dx, dy); return true }

    /** متفعّلة في إعدادات السيستم (حتى لو لسه ما اتوصّلتش) */
    fun enabled(ctx: Context): Boolean = try {
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("TvAccessService")
    } catch (_: Throwable) { false }

    fun openSettings(a: Activity) {
        try { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) { try { Notice.show(a, "ما قدرتش أفتح إعدادات إمكانية الوصول — افتحها من إعدادات التلفزيون ← إمكانية الوصول", 4000L) } catch (_: Throwable) {} }
    }
}

/**
 * (v519) خدمة إمكانية الوصول: بتخلّي ريموت الموبايل يتحكم في أي تطبيق على التلفزيون.
 *  - أسهم/OK: أندرويد 13+ بأوامر DPAD الرسمية، وأقدم من كده بنحرّك التركيز بالبحث في عناصر الشاشة.
 *  - رجوع/رئيسية/أخيرة: أوامر السيستم.
 *  - التاتش باد: مؤشر فوق كل التطبيقات + دوسة/ضغط مطوّل/سحب بإيماءات حقيقية.
 * مفيش أي قراءة لمحتوى الشاشة لأغراض تانية، ومفيش أحداث بتتسجّل.
 */
class TvAccessService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cur: TvCursor? = null

    override fun onServiceConnected() { super.onServiceConnected(); TvAccess.svc = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        TvAccess.svc = null
        h.post { dropCursor() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() { TvAccess.svc = null; dropCursor(); super.onDestroy() }

    private fun dropCursor() {
        try { cur?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        cur = null
    }

    // ===== (v536) تشغيل/إيقاف الواي فاي بدوسة واحدة: أندرويد 10+ بيمنع التطبيقات من WifiManager.setWifiEnabled،
    // فبنفتح شاشة الواي فاي ونضغط مفتاحها بإمكانية الوصول ونرجع للانشر على طول (بتوميض لحظة، مش بتفضل ظاهرة) =====
    fun wifiToggle(want: Boolean, back: Intent?, done: (Boolean) -> Unit) {
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                val ok: Boolean? = try { clickWifiSwitch(want) } catch (e: Throwable) { LogStore.err("TvAccess:wifi", e); null }
                if (ok == true) {
                    h.postDelayed({ try { back?.let { startActivity(it) } } catch (_: Throwable) {}; done(true) }, 450L)
                } else if (tries < 24) {
                    h.postDelayed(this, 250L)
                } else {
                    try { back?.let { startActivity(it) } } catch (_: Throwable) {}
                    done(false)
                }
            }
        }
        h.postDelayed(r, 700L)
    }

    /** true = اتضغط أو كان على الحالة المطلوبة · null = لسه مالقيتش المفتاح (الشاشة لسه بتفتح) */
    private fun clickWifiSwitch(want: Boolean): Boolean? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() == packageName) return null
        val sw = ArrayList<AccessibilityNodeInfo>()
        collectCheckable(root, sw, 0)
        if (sw.isEmpty()) return null
        val pick = sw.firstOrNull { isWifiLabel(it) } ?: (if (sw.size == 1) sw[0] else null) ?: return null
        if (pick.isChecked == want) return true
        var n: AccessibilityNodeInfo? = pick
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent; hops++
        }
        return null
    }

    private fun collectCheckable(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, d: Int) {
        if (d > 14) return
        if (n.isCheckable) out.add(n)
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; collectCheckable(c, out, d + 1) }
    }

    private fun isWifiLabel(n: AccessibilityNodeInfo): Boolean {
        val sb = StringBuilder()
        addTexts(n, sb, 0)
        n.parent?.let { addTexts(it, sb, 0) }
        val t = sb.toString().lowercase()
        return t.contains("wi-fi") || t.contains("wifi") || t.contains("wi‑fi") || t.contains("واي")
    }

    private fun addTexts(n: AccessibilityNodeInfo, sb: StringBuilder, d: Int) {
        if (d > 2) return
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; addTexts(c, sb, d + 1) }
    }

    // ===== أزرار =====
    fun key(code: Int): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) return performGlobalAction(GLOBAL_ACTION_BACK)
        if (Build.VERSION.SDK_INT >= 33) {
            val ga = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> GLOBAL_ACTION_DPAD_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> GLOBAL_ACTION_DPAD_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> GLOBAL_ACTION_DPAD_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER -> GLOBAL_ACTION_DPAD_CENTER
                else -> -1
            }
            if (ga != -1) return performGlobalAction(ga)
        }
        return focusMove(code)
    }

    private fun focusMove(code: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val now = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) return now?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        val t = (now ?: root).focusSearch(dir) ?: return false
        return t.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || t.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }

    // ===== مؤشر وإيماءات =====
    fun ptr(op: String, dx: Float, dy: Float) {
        h.post { try { doPtr(op, dx, dy) } catch (e: Throwable) { LogStore.err("TvAccess:ptr", e) } }
    }

    private fun cursor(): TvCursor? {
        cur?.let { return it }
        return try {
            val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val c = TvCursor(this, Themes.byId(Cfg.str("theme", "mx")).primary)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            w.addView(c, lp)
            wm = w; cur = c; c
        } catch (e: Throwable) { LogStore.err("TvAccess:cursor", e); null }
    }

    private fun doPtr(op: String, dx: Float, dy: Float) {
        val c = cursor() ?: return
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val hh = dm.heightPixels.toFloat()
        when (op) {
            "mv" -> c.moveBy(dx * w * TvInput.GAIN, dy * hh * TvInput.GAIN)
            "tap" -> { c.pulse(); gesture(c.cx, c.cy, c.cx, c.cy, 60L) }
            "long" -> { c.pulse(); gesture(c.cx, c.cy, c.cx, c.cy, 800L) }
            "scroll" -> {
                // المحتوى بيمشي مع الصوابع: إصبعين لتحت = المحتوى ينزل (يظهر اللي فوق)
                c.poke()
                val ex = (c.cx + dx.coerceIn(-1f, 1f) * w * 0.35f).coerceIn(2f, w - 2f)
                val ey = (c.cy + dy.coerceIn(-1f, 1f) * hh * 0.35f).coerceIn(2f, hh - 2f)
                gesture(c.cx, c.cy, ex, ey, 220L)
            }
        }
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, ms.coerceAtLeast(1L))).build()
        dispatchGesture(g, null, null)
    }
}
