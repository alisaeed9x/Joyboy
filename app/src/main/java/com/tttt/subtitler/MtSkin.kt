package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.EditText
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * (v897) «الجلاس في البرنامج كله» — موبايل/تابلت بس (التلفزيون ما بيتلمسش).
 *  1) MtAurora: خلفية متحركة (أضواء ملوّنة بتتحرك ببطء) هي اللي الزجاج بيتبلّر فوقها — من غيرها الكروت الزجاجية كانت بتبان سودا مسطّحة.
 *  2) MtSkin.install: بيركّب الخلفية دي كـ window background لكل شاشات Joyboy (الرئيسية · المتصفح · الملفات · النقل…) من غير ما نعدّل كل شاشة.
 *  3) MtSkin.sweep: بيمشي على الواجهة ويحوّل أي خلفية صلبة (لون الصفحة/الكارت/السطح) لشفافة أو لزجاج، فمفيش حتة سودا مصمتة.
 */
class MtAurora(private val th: Theme, private val animated: Boolean, private val intensity: Float) : Drawable() {
    private class Blob(val fx: Float, val fy: Float, val ax: Float, val ay: Float, val sp: Float, val ph: Float, val rad: Float, val color: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var blobs: List<Blob> = emptyList()
    private var shaders: Array<Shader?> = arrayOfNulls(0)
    private val hosts = ArrayList<View>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidateSelf()
            for (h in hosts) { try { h.invalidate() } catch (_: Throwable) {} }
            handler.postDelayed(this, 40L)   // ~25fps: الحركة بطيئة فمش محتاجة أكتر + بتوفّر بطارية
        }
    }

    /** views بتتعمل لها invalidate مع كل نبضة (الشريط الزجاجي اللي بيبلّر الخلفية دي) */
    fun addHost(v: View) { if (!hosts.contains(v)) hosts.add(v) }
    fun removeHost(v: View) { hosts.remove(v) }

    fun start() {
        if (!animated || running) return
        running = true; handler.removeCallbacks(tick); handler.post(tick)
    }
    fun stop() { running = false; handler.removeCallbacks(tick) }

    private fun hueShift(c: Int, deg: Float): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        hsv[1] = hsv[1].coerceIn(0.55f, 1f); hsv[2] = hsv[2].coerceIn(0.7f, 1f)
        return Color.HSVToColor(hsv)
    }

    override fun onBoundsChange(b: android.graphics.Rect) {
        super.onBoundsChange(b)
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) { blobs = emptyList(); shaders = arrayOfNulls(0); return }
        val amoled = th.bg == 0xFF000000.toInt()
        val a = (if (th.isLight) 0x52 else if (amoled) 0x3A else 0x5C) * intensity
        val alpha = a.toInt().coerceIn(0, 0xFF)
        val p = th.primary
        val cols = intArrayOf(p, hueShift(p, 215f), th.accent, hueShift(p, 285f), hueShift(p, 150f))
        blobs = listOf(
            Blob(0.15f, 0.10f, 0.20f, 0.10f, 0.23f, 0.0f, 0.62f, Glass.alpha(cols[0], alpha)),
            Blob(0.90f, 0.30f, 0.18f, 0.14f, 0.19f, 1.7f, 0.58f, Glass.alpha(cols[1], alpha)),
            Blob(0.25f, 0.62f, 0.22f, 0.12f, 0.27f, 3.1f, 0.60f, Glass.alpha(cols[3], alpha)),
            Blob(0.85f, 0.88f, 0.20f, 0.10f, 0.21f, 4.4f, 0.66f, Glass.alpha(cols[2], alpha)),
            Blob(0.50f, 0.45f, 0.25f, 0.18f, 0.16f, 5.6f, 0.46f, Glass.alpha(cols[4], (alpha * 0.55f).toInt()))
        )
        val big = max(w, h)
        shaders = Array<Shader?>(blobs.size) { i ->
            val bl = blobs[i]
            RadialGradient(0f, 0f, big * bl.rad, bl.color, bl.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.clipRect(b)
        canvas.drawColor(th.bg)
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val t = if (animated) (SystemClock.uptimeMillis() % 3_600_000L) / 1000f else 0f
        val big = max(w, h)
        for (i in blobs.indices) {
            val bl = blobs[i]; val sh = shaders.getOrNull(i) ?: continue
            val tt = t * bl.sp + bl.ph
            val cx = b.left + w * (bl.fx + bl.ax * sin(tt))
            val cy = b.top + h * (bl.fy + bl.ay * cos(tt * 0.83f))
            canvas.save()
            canvas.translate(cx, cy)
            paint.shader = sh
            val r = big * bl.rad
            canvas.drawCircle(0f, 0f, r, paint)
            canvas.restore()
        }
        paint.shader = null
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

object MtSkin {
    /** شغّال؟ موبايل/تابلت + مش مقفول من الإعدادات */
    fun on(): Boolean = try { MtGlass.on() && Cfg.bool("glass_all", true) } catch (_: Throwable) { false }

    /** الحركة: مقفولة لو المستخدم قفلها، أو توفير الطاقة، أو إعدادات النظام مقفّلة الأنيميشن، أو جهاز ضعيف (من غير ما يختار بإيده) */
    fun animated(ctx: Context): Boolean = try {
        if (!Cfg.bool("glass_anim", !lowEnd(ctx))) false
        else {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val scale = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            !(pm?.isPowerSaveMode ?: false) && scale > 0f
        }
    } catch (_: Throwable) { false }

    fun lowEnd(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        am.isLowRamDevice || mi.totalMem < 3_500_000_000L || Runtime.getRuntime().availableProcessors() < 6
    } catch (_: Throwable) { false }

    /** «شدة الزجاج» 0..100 (افتراضي 70) */
    fun intensity(): Float = try { Cfg.int("glass_aurora", 70).coerceIn(0, 100) / 70f } catch (_: Throwable) { 1f }

    fun aurora(ctx: Context, th: Theme): MtAurora = MtAurora(th, animated(ctx), intensity())

    // شاشات مش بنركّب عليها الخلفية: المشغّل (فيديو)، التلفزيون، سكان الكاميرا، السبلاش، لانشرات الأيقونات
    private val skip = setOf("PlayerActivity", "TvActivity", "QrScanActivity", "LaunchSplashActivity", "TvFilesActivity", "LaunchBrowserActivity", "LaunchYoutubeActivity", "LaunchRemoteActivity", "SendToTvActivity")

    private class Slot(val aurora: MtAurora, var listener: ViewTreeObserver.OnGlobalLayoutListener?, var pending: Boolean)
    private val slots = WeakHashMap<Activity, Slot>()
    private val handler = Handler(Looper.getMainLooper())

    private fun eligible(a: Activity): Boolean {
        val n = a.javaClass.name
        if (!n.startsWith("com.tttt.subtitler.")) return false
        if (a.javaClass.simpleName in skip) return false
        return on()
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                try {
                    Cfg.ensure(a)
                    if (!eligible(a)) return
                    val th = Glass.theme()
                    var s = slots[a]
                    if (s == null) {
                        val au = aurora(a, th)
                        s = Slot(au, null, false)
                        slots[a] = s
                        a.window.setBackgroundDrawable(au)
                        try { a.window.statusBarColor = 0; a.window.navigationBarColor = 0 } catch (_: Throwable) {}   // شفافين عشان الخلفية تبان تحتهم
                        au.addHost(a.window.decorView)
                        val decor = a.window.decorView
                        val l = ViewTreeObserver.OnGlobalLayoutListener { schedule(a) }
                        s.listener = l
                        decor.viewTreeObserver.addOnGlobalLayoutListener(l)
                    }
                    s.aurora.start()
                    schedule(a)
                } catch (e: Throwable) { try { LogStore.err("MtSkin:resume", e) } catch (_: Throwable) {} }
            }
            override fun onActivityPaused(a: Activity) { slots[a]?.aurora?.stop() }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                val s = slots.remove(a) ?: return
                s.aurora.stop()
                try { s.listener?.let { a.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) } } catch (_: Throwable) {}
            }
        })
    }

    /** مسح الواجهة بعد ما تستقر (debounce) — بيمسك كمان العناصر اللي بتتبني متأخر (قوايم · تابات) */
    private fun schedule(a: Activity) {
        val s = slots[a] ?: return
        if (s.pending) return
        s.pending = true
        handler.postDelayed({
            s.pending = false
            try { if (!a.isFinishing && !a.isDestroyed) sweep(a.window.decorView, Glass.theme()) } catch (e: Throwable) { try { LogStore.err("MtSkin:sweep", e) } catch (_: Throwable) {} }
        }, 220L)
    }

    private fun opaque(c: Int) = Color.alpha(c) == 255

    /** يحوّل الخلفيات الصلبة (لون الصفحة ← شفاف · لون الكارت/السطح ← زجاج) في الشجرة كلها */
    fun sweep(root: View, th: Theme) {
        val d = root.resources.displayMetrics.density
        fun walk(v: View, depth: Int) {
            if (depth > 40) return
            if (v is WebView) return   // صفحات الويب بتبني خلفيتها بنفسها
            if (v !is EditText && v !is android.widget.ProgressBar) {
                when (val bg = v.background) {
                    is ColorDrawable -> {
                        val c = bg.color
                        if (opaque(c)) {
                            if (c == th.bg) v.background = null   // الصفحة نفسها ← الخلفية المتحركة تظهر
                            else if (c == th.card || c == th.surface) v.background = ColorDrawable(MtGlass.flatFill(th))
                        }
                    }
                    is GradientDrawable -> {
                        val cl = try { bg.color } catch (_: Throwable) { null }
                        if (cl != null && bg.colors == null) {
                            val c = cl.defaultColor
                            if (opaque(c) && (c == th.card || c == th.surface || c == th.bg)) {
                                val radii = try { bg.cornerRadii } catch (_: Throwable) { null }
                                val rad = try { bg.cornerRadius } catch (_: Throwable) { 0f }
                                val ng = if (c == th.surface) MtGlass.glassWell(th, rad, d) else MtGlass.glassCard(th, rad, d)
                                if (radii != null) ng.cornerRadii = radii
                                v.background = ng
                            }
                        }
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
    }
}
