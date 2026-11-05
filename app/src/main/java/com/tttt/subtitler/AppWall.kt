package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * (v721) خلفية متغيّرة لنسخة الموبايل والتابلت (زي خلفية التلفزيون): Bing + صور عشوائية، بتتغيّر لوحدها ورا الواجهة.
 * فوق الصورة طبقة تعتيم بلون الثيم (قابلة للتحكم) عشان الأزرار والكلام يفضلوا باينين.
 * الإعدادات: app_wall (auto/bing/picsum/off) · app_wall_sec (30/60/300/900/3600/0=ثابتة) · app_wall_scrim (0..95)
 * التغيير التلقائي بيوقف على النت المحدود (باقة الموبايل)؛ زرار "غيّر دلوقتي" بيشتغل دايماً.
 */
object AppWall {
    private val h = Handler(Looper.getMainLooper())
    private var iv: ImageView? = null
    private var scrim: View? = null
    private var actRef: WeakReference<Activity>? = null
    private var themeBg = Color.BLACK
    private var seq = 0
    private var n = 0
    private var bingUrls: List<String> = emptyList()
    private val busy = AtomicBoolean(false)

    private val secNames = linkedMapOf("30" to "30 ثانية", "60" to "دقيقة", "300" to "5 دقايق", "900" to "15 دقيقة", "3600" to "ساعة", "0" to "ثابتة")

    fun mode(): String { val m = Cfg.str("app_wall", "auto"); return if (m == "off" || m == "bing" || m == "picsum") m else "auto" }
    fun enabled(): Boolean = mode() != "off"
    private fun fixed(): Boolean = Cfg.str("app_wall_sec", "300") == "0"
    private fun everyMs(): Long = (Cfg.str("app_wall_sec", "300").toLongOrNull() ?: 300L).coerceIn(15L, 3600L) * 1000L
    private fun file(c: Context): File = File(c.filesDir, "app_wall_cur.jpg")
    fun secLabel(): String = secNames[Cfg.str("app_wall_sec", "300")] ?: "5 دقايق"
    fun secOf(label: String): String = secNames.entries.firstOrNull { it.value == label }?.key ?: "300"

    private fun metered(c: Context): Boolean = try {
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    } catch (_: Throwable) { false }

    fun scrimColor(): Int {
        val p = Cfg.int("app_wall_scrim", 55).coerceIn(0, 95)
        return Color.argb(p * 255 / 100, Color.red(themeBg), Color.green(themeBg), Color.blue(themeBg))
    }
    fun refreshScrim() { scrim?.setBackgroundColor(scrimColor()) }

    private val loop = object : Runnable {
        override fun run() {
            val a = actRef?.get() ?: return
            if (a.isDestroyed || a.isFinishing || !enabled()) return
            if (fixed()) { if (!file(a).exists()) next(a, false, false); return }
            val focused = try { a.window?.decorView?.hasWindowFocus() == true } catch (_: Throwable) { true }
            if (focused && !metered(a)) next(a, false, false)
            h.postDelayed(this, everyMs())
        }
    }

    /** بيحط الخلفية + طبقة التعتيم في أسفل الـ frame (قبل ما تضيف الواجهة فوقهم). */
    fun attach(a: Activity, frame: FrameLayout, th: Theme) {
        detach()
        themeBg = th.bg
        val v = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE; isFocusable = false; isClickable = false }
        val s = View(a).apply { setBackgroundColor(scrimColor()); visibility = View.GONE; isFocusable = false; isClickable = false }
        frame.addView(v, FrameLayout.LayoutParams(-1, -1))
        frame.addView(s, FrameLayout.LayoutParams(-1, -1))
        iv = v; scrim = s; actRef = WeakReference(a)
        applyNow(a)
    }

    fun detach() { seq++; h.removeCallbacks(loop); iv = null; scrim = null; actRef = null }   // (v737) seq++: أي تحميل/بدل صورة شغّال ييجي بعد الفصل يتجاهل نفسه

    /** (v737) من onDestroy: يفصل بس لو الأكتفيتي دي هي اللي ماسكاها — نسخة جديدة اتعملها attach قبل ما القديمة تتدمّر ما تتمسحش. الـImageView/الطبقة بيمسكوا الأكتفيتي (Context) فلازم يتشالوا هنا وإلا الأوبجكت بيحتفظ بيها لحد الـattach الجاي. */
    fun detach(a: Activity) { if (actRef?.get() === a) detach() }

    /** بيطبّق الإعدادات الحالية (تشغيل/إيقاف/نوع/مدة). */
    fun applyNow(a: Activity) {
        val my = ++seq
        h.removeCallbacks(loop)
        val v = iv ?: return
        if (!enabled()) { v.setImageDrawable(null); v.visibility = View.GONE; scrim?.visibility = View.GONE; return }
        val f = file(a)
        Thread {
            try { if (f.exists()) show(a, decode(f), my) } catch (e: Throwable) { LogStore.err("AppWall:apply", e) }
        }.apply { isDaemon = true }.start()
        val wait = if (fixed() || !f.exists()) 0L else (everyMs() - (System.currentTimeMillis() - f.lastModified())).coerceAtLeast(0L)
        h.postDelayed(loop, wait)
    }

    fun reapply(a: Activity) { applyNow(a) }

    /** (v772) هل الأكتفيتي دي هي اللي ماسكة الخلفية دلوقتي؟ (لانشر الموبايل بيرجّعها لنفسه لما Main/المتصفح يمسكوها وبعدين نرجع للرئيسية) */
    fun ownedBy(a: Activity): Boolean = actRef?.get() === a

    /** زرار "غيّر الخلفية دلوقتي". */
    fun nextNow(a: Activity) {
        if (!enabled()) { Cfg.put("app_wall", "auto"); applyNow(a) }
        Notice.show(a, "بجيب خلفية جديدة…", 1500L)
        next(a, true, true)
    }

    private fun next(a: Activity, force: Boolean, fresh: Boolean) {
        val mode = mode()
        if (mode == "off" || !busy.compareAndSet(false, true)) return
        val my = seq
        val useBing = if (fresh) mode != "picsum" else when (mode) { "bing" -> true; "picsum" -> false; else -> (n++ % 2 == 0) }
        Thread {
            try {
                val f = file(a)
                val ok = fetch(a, useBing, f, fresh) || fetch(a, if (fresh) false else (!useBing && mode == "auto"), f, fresh)
                if (ok) show(a, decode(f), my)
                else if (force) a.runOnUiThread { if (!a.isDestroyed) Notice.show(a, "مقدرتش أنزّل خلفية جديدة (اتأكد من النت)", 2400L) }
            } catch (e: Throwable) { LogStore.err("AppWall:next", e) } finally { busy.set(false) }
        }.apply { isDaemon = true }.start()
    }

    private fun show(a: Activity, bmp: Bitmap?, my: Int) {
        a.runOnUiThread {
            val v = iv ?: return@runOnUiThread
            if (a.isDestroyed || my != seq || bmp == null || !enabled()) return@runOnUiThread
            val newD = BitmapDrawable(a.resources, bmp)
            val first = v.visibility != View.VISIBLE
            val oldD = if (first) null else v.drawable
            v.visibility = View.VISIBLE; scrim?.visibility = View.VISIBLE
            if (oldD != null) {
                val td = TransitionDrawable(arrayOf(oldD, newD)); td.isCrossFadeEnabled = true
                v.setImageDrawable(td); td.startTransition(700)
                h.postDelayed({ if (!a.isDestroyed && my == seq) v.setImageDrawable(newD) }, 760L)
            } else {
                v.setImageDrawable(newD)
                v.alpha = 0f; v.animate().alpha(1f).setDuration(350L).start()
            }
        }
    }

    private fun decode(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var ss = 1; while (o.outWidth / ss > 2200) ss *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss })
    } catch (_: Throwable) { null }

    private fun httpBytes(url: String): ByteArray? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 20000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Subtitler")
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }

    private fun fetch(a: Activity, bing: Boolean, dest: File, fresh: Boolean): Boolean {
        return try {
            val url = if (bing) {
                if (bingUrls.isEmpty()) {
                    val j = org.json.JSONObject(String(httpBytes("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US") ?: return false))
                    val arr = j.getJSONArray("images")
                    bingUrls = (0 until arr.length()).map { "https://www.bing.com" + arr.getJSONObject(it).getString("url") }
                }
                if (fresh) { val seen = Cfg.str("app_wall_seen", "").split("\n"); bingUrls.firstOrNull { it !in seen } ?: return false }
                else bingUrls.randomOrNull() ?: return false
            } else {
                val dm = a.resources.displayMetrics
                val w = minOf(dm.widthPixels, 1600).coerceAtLeast(480)
                val hh = (w.toLong() * dm.heightPixels / dm.widthPixels.coerceAtLeast(1)).toInt().coerceIn(480, 2400)
                "https://picsum.photos/seed/" + System.currentTimeMillis() + "/" + w + "/" + hh
            }
            val data = httpBytes(url) ?: return false
            if (data.size < 20_000) return false
            val tmp = File(dest.path + ".tmp"); tmp.writeBytes(data)
            if (decode(tmp) == null) { tmp.delete(); return false }
            val ok = tmp.renameTo(dest)
            if (ok && bing) Cfg.put("app_wall_seen", (Cfg.str("app_wall_seen", "").split("\n").filter { it.isNotBlank() } + url).takeLast(40).joinToString("\n"))
            ok
        } catch (e: Throwable) { LogStore.err("AppWall:fetch", e); false }
    }
}
