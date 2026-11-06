package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v799) عدّاد داتا التطبيق كله — مصدر واحد لأرقام الكبسولة العايمة في كل الشاشات.
 * - BrowserSaver (المتصفح) بيعدّ بنفسه وبيكتب في نفس الأرقام (saverOwns = true).
 * - أي شاشة تانية: DataCapsule بيشغّل العدّاد ده (قياس TrafficStats لتطبيق Joyboy كل ثانية، منسوب لنوع الشبكة وقتها).
 * التخزين: نفس مفتاح المتصفح sv_hist = [خلوي النهارده, واي فاي النهارده, توفير].
 */
object DataMeter {
    @Volatile var sCell = 0L
    @Volatile var sWifi = 0L
    @Volatile var dCell = 0L
    @Volatile var dWifi = 0L
    @Volatile var speed = 0.0
    @Volatile var saverOwns = false

    private var dayKey = ""
    private var loaded = false
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private var ticks = 0

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("p", 0)

    fun ensureLoaded(c: Context) {
        if (loaded) return
        loaded = true
        dayKey = today()
        try {
            val h = JSONObject(prefs(c).getString("sv_hist", "{}") ?: "{}")
            val a = h.optJSONArray(dayKey)
            dCell = a?.optLong(0) ?: 0L; dWifi = a?.optLong(1) ?: 0L
        } catch (_: Throwable) {}
    }

    fun persist(c: Context) {
        try {
            if (dayKey.isEmpty()) dayKey = today()
            val p = prefs(c)
            val h = try { JSONObject(p.getString("sv_hist", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            val saved = h.optJSONArray(dayKey)?.optLong(2) ?: 0L
            h.put(dayKey, JSONArray().put(dCell).put(dWifi).put(saved))
            val keys = ArrayList<String>(); val it = h.keys(); while (it.hasNext()) keys.add(it.next())
            keys.sort(); while (keys.size > 100) h.remove(keys.removeAt(0))
            p.edit().putString("sv_hist", h.toString()).apply()
        } catch (_: Throwable) {}
    }

    /** هل النت الحالي خلوي؟ (VPN: بنعتمد على «محدودة» من النظام — نفس قاعدة المتصفح) */
    fun isCell(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val cap = if (n != null) cm.getNetworkCapabilities(n) else null
        if (cap == null) false
        else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) cm.isActiveNetworkMetered
        else cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    } catch (_: Throwable) { false }

    private fun hasNet(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val cap = if (n != null) cm.getNetworkCapabilities(n) else null
        cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) { false }

    /** بيبدأ القياس من الصفر (من غير ما يعدّ الفجوة اللي فاتت) */
    fun rebase() { lastRx = -1L; lastTx = -1L; lastAt = 0L }

    /** تيكة قياس واحدة — بتتنادى بس لما المتصفح مش هو اللي بيعدّ */
    fun tick(c: Context) {
        ensureLoaded(c)
        if (saverOwns) { rebase(); return }
        if (dayKey != today()) { persist(c); dayKey = today(); dCell = 0L; dWifi = 0L }
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return
        val now = android.os.SystemClock.elapsedRealtime()
        var dRx = 0L; var d = 0L
        if (lastRx >= 0L) { dRx = maxOf(0L, rx - lastRx); d = dRx + maxOf(0L, tx - lastTx) }
        lastRx = rx; lastTx = tx
        val dt = if (lastAt > 0L) maxOf(0.2, (now - lastAt) / 1000.0) else 1.0
        lastAt = now
        if (d > 0L && hasNet(c)) { if (isCell(c)) { sCell += d; dCell += d } else { sWifi += d; dWifi += d } }
        speed = speed * 0.6 + (dRx / dt) * 0.4
        ticks++
        if (ticks % 10 == 0) persist(c)
    }

    fun fmt(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1048576L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        b < 1073741824L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    }
}

/**
 * (v799) سحب الكبسولة بحرية (يمين/شمال/فوق/تحت) — لما تسيبها بتلزق في أقرب حافة (شمال أو يمين).
 * الجنب المحفوظ: sv_cap_side (0 شمال · 1 يمين) والارتفاع: sv_cap_ly — مشترك بين المتصفح وباقي الشاشات.
 */
class CapMover(
    private val v: View, private val frame: FrameLayout, private val p: SharedPreferences,
    private val onSide: (Int) -> Unit, private val onTap: (MotionEvent) -> Unit, private val onLong: () -> Unit
) : View.OnTouchListener {
    private val den = v.resources.displayMetrics.density
    private var dX = 0f; private var dY = 0f; private var bX = 0f; private var bY = 0f
    private var moved = false
    var side = p.getInt("sv_cap_side", 0).coerceIn(0, 1); private set

    fun gravity(): Int = (if (side == 1) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL

    /** ارتفاع/مكان آمن جوه الشاشة (بيتنادى بعد أي تغيير في حجم الكبسولة) */
    fun clampIdle() {
        val w = frame.width; val h = frame.height
        if (w == 0 || h == 0 || v.width == 0) return
        val minY = -v.top.toFloat(); val maxY = maxOf(minY, (h - v.bottom).toFloat())
        v.translationX = 0f; v.translationY = v.translationY.coerceIn(minY, maxY)
    }

    private fun clampDrag() {
        val w = frame.width; val h = frame.height
        if (w == 0 || h == 0) return
        val minX = -v.left.toFloat(); val maxX = maxOf(minX, (w - v.right).toFloat())
        val minY = -v.top.toFloat(); val maxY = maxOf(minY, (h - v.bottom).toFloat())
        v.translationX = v.translationX.coerceIn(minX, maxX)
        v.translationY = v.translationY.coerceIn(minY, maxY)
    }

    private fun snap() {
        val w = frame.width
        val visualLeft = v.left + v.translationX
        val cx = visualLeft + v.width / 2f
        val ns = if (w > 0 && cx > w / 2f) 1 else 0
        val changed = ns != side
        side = ns
        p.edit().putInt("sv_cap_side", ns).putFloat("sv_cap_ly", v.translationY).apply()
        if (changed) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.gravity = gravity(); v.layoutParams = lp
            onSide(ns)
            v.translationX = 0f
            v.post {
                v.translationX = visualLeft - v.left
                v.animate().translationX(0f).setDuration(170L).start()
            }
        } else {
            v.animate().translationX(0f).setDuration(170L).start()
        }
    }

    override fun onTouch(view: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dX = e.rawX; dY = e.rawY; bX = v.translationX; bY = v.translationY; moved = false; v.animate().cancel() }
            MotionEvent.ACTION_MOVE -> {
                val ddx = e.rawX - dX; val ddy = e.rawY - dY
                if (!moved && (Math.abs(ddx) > 8f * den || Math.abs(ddy) > 8f * den)) moved = true
                if (moved) { v.translationX = bX + ddx; v.translationY = bY + ddy; clampDrag() }
            }
            MotionEvent.ACTION_UP -> {
                if (moved) snap()
                else if (e.eventTime - e.downTime > 600L) onLong()
                else onTap(e)
            }
            MotionEvent.ACTION_CANCEL -> { if (moved) snap() }
        }
        return true
    }

    companion object {
        /** خلفية الكبسولة: الدايري على الجنب اللي ناحية الشاشة */
        fun bg(color: Int, side: Int, den: Float): GradientDrawable {
            val r = 14f * den
            return GradientDrawable().apply {
                setColor(color)
                cornerRadii = if (side == 1) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setStroke(Math.max(1, den.toInt()), 0x55FFFFFF)
            }
        }
        fun pad(t: TextView, side: Int, den: Float) {
            val a = (7f * den).toInt(); val b = (10f * den).toInt(); val vv = (6f * den).toInt()
            if (side == 1) t.setPadding(b, vv, a, vv) else t.setPadding(a, vv, b, vv)
        }
    }
}

/**
 * (v799) كبسولة استهلاك الداتا في كل شاشات التطبيق (مش المتصفح بس): تتنده من onResume/onPause.
 * بتتحط فوق محتوى الشاشة (android.R.id.content)، بتتسحب بحرية وتلزق في أقرب حافة، ودوسة = توسّع/تصغّر.
 * المتصفح ليه كبسولته (BrowserSaver) بنفس المكان والأرقام؛ التلفزيون مفيهوش كبسولة.
 */
object DataCapsule {
    private val main = Handler(Looper.getMainLooper())
    private var cur: TextView? = null
    private var curAct: Activity? = null
    private var mover: CapMover? = null
    private var expanded = false
    private var lastCol = 0
    private var ticker: Runnable? = null

    fun attach(act: Activity) {
        try {
            if (act is BrowserActivity) return   // المتصفح عنده كبسولته الخاصة
            if (try { DeviceUi.isTv(act) } catch (_: Throwable) { false }) return
            val p = act.getSharedPreferences("p", 0)
            if (!p.getBoolean("sv_cap", true)) { detach(act); return }
            val content = act.findViewById<FrameLayout>(android.R.id.content) ?: return
            detach(curAct)   // شاشة واحدة بس في المقدمة
            DataMeter.ensureLoaded(act)
            DataMeter.rebase()
            val den = act.resources.displayMetrics.density
            val t = TextView(act).apply {
                textSize = 10.5f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR
                elevation = 5f * den; isFocusable = false
            }
            val mv = CapMover(t, content, p,
                onSide = { s -> CapMover.pad(t, s, den); lastCol = 0; paint(act) },
                onTap = { _ -> expanded = !expanded; paint(act); t.post { mover?.clampIdle() } },
                onLong = { expanded = !expanded; paint(act); t.post { mover?.clampIdle() } })
            CapMover.pad(t, mv.side, den)
            t.setOnTouchListener(mv)
            content.addView(t, FrameLayout.LayoutParams(-2, -2, mv.gravity()))
            t.translationY = p.getFloat("sv_cap_ly", 0f)
            cur = t; curAct = act; mover = mv; lastCol = 0
            paint(act)
            t.post { mv.clampIdle() }
            val r = object : Runnable {
                override fun run() {
                    if (cur !== t) return
                    try { DataMeter.tick(act); paint(act) } catch (e: Throwable) { LogStore.err("DataCapsule:tick", e) }
                    main.postDelayed(this, 1000L)
                }
            }
            ticker = r; main.postDelayed(r, 1000L)
        } catch (e: Throwable) { LogStore.err("DataCapsule:attach", e) }
    }

    /** بتتنده من onPause — بتشيل الكبسولة وتحفظ الأرقام (عشان المتصفح لو اتفتح يكمّل من نفس الرقم) */
    fun detach(act: Activity?) {
        try {
            val t = cur
            ticker?.let { main.removeCallbacks(it) }; ticker = null
            if (act != null) DataMeter.persist(act)
            if (t != null) (t.parent as? android.view.ViewGroup)?.removeView(t)
        } catch (_: Throwable) {}
        cur = null; curAct = null; mover = null
    }

    private fun paint(act: Activity) {
        val t = cur ?: return
        val sb = StringBuilder()
        val sc = DataMeter.sCell; val sw = DataMeter.sWifi; val dc = DataMeter.dCell; val dw = DataMeter.dWifi
        if (!expanded) sb.append("📶 ").append(DataMeter.fmt(sc)).append("\n🛜 ").append(DataMeter.fmt(sw))
        else {
            sb.append("الجلسة  Σ ").append(DataMeter.fmt(sc + sw)).append('\n').append("📶 ").append(DataMeter.fmt(sc)).append("   🛜 ").append(DataMeter.fmt(sw)).append('\n')
            sb.append("اليوم  Σ ").append(DataMeter.fmt(dc + dw)).append('\n').append("📶 ").append(DataMeter.fmt(dc)).append("   🛜 ").append(DataMeter.fmt(dw)).append('\n')
            sb.append("⬇ ").append(DataMeter.fmt(DataMeter.speed.toLong())).append("/s")
            sb.append("\nاسحبها لأي مكان · دوسة = تصغير")
        }
        t.text = sb.toString()
        val p = act.getSharedPreferences("p", 0)
        val lim = (p.getString("sv_lim_day", "0")?.toLongOrNull() ?: 0L) * 1048576L
        val col = when {
            lim > 0 && dc >= lim -> 0xE6B71C1C.toInt()
            lim > 0 && dc >= lim * 8 / 10 -> 0xE6E65100.toInt()
            DataMeter.isCell(act) -> 0xE61E2A3A.toInt()
            else -> 0xE6244A36.toInt()
        }
        if (col != lastCol) { lastCol = col; try { t.background = CapMover.bg(col, mover?.side ?: 0, act.resources.displayMetrics.density) } catch (_: Throwable) {} }
    }
}
