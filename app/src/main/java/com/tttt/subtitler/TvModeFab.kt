package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (v554/v556) «وضع التلفزيون» على الموبايل بدل الريموت:
 *  - والتطبيق ظاهر بيدوّر في الخلفية (واي فاي بس) على تلفزيونات Subtitler. لو لقى: زرار 📺 عايم «توصّل؟» بيظهر 5 ثواني وبعدين يختفي
 *    (أو يفضل ظاهر دايمًا لو فعّلت «زرار التلفزيون دايمًا ظاهر» من «المزيد»). الدوسة: تلفزيون واحد = اتصال؛ أكتر من واحد = قايمة صغيرة تختار منها.
 *  - الاقتران تلقائي لو لازم (وبيسأل عن الكود لو التلفزيون طلبه).
 *  - جوه الوضع: زرار «✖ خروج» + خط أحمر «إنت دلوقتي في وضع التلفزيون» في كل شاشات التطبيق؛ فيديو/يوتيوب/لينك مصطاد بيتفتح على التلفزيون
 *    ومكتبة الفيديو بتبقى مكتبة التلفزيون. الخروج = قطع الاتصال. مابيظهرش على التلفزيون ولا المشغّل.
 */
object TvModeFab {
    private class Layer(val btn: TextView, val banner: TextView)

    private const val PROMPT_MS = 5000L
    private val main = Handler(Looper.getMainLooper())
    private var installed = false
    private var app: Context? = null
    private val layers = HashMap<Activity, Layer>()   // thread الواجهة بس
    @Volatile private var resumed = 0
    @Volatile private var polling = false
    @Volatile private var busy = false
    @Volatile private var tvs: List<RemoteClient.Found> = emptyList()   // آخر تلفزيونات اتلاقت
    @Volatile private var promptUntil = 0L
    @Volatile private var seenIds: Set<String> = emptySet()
    @Volatile private var sessionFresh = true
    @Volatile private var lastPauseAt = 0L
    private var picker: PopupWindow? = null
    /** بيتنادى (على thread الواجهة) كل ما الحالة تتحدّث — Main بيستخدمه يبدّل مكتبة الفيديو */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val a = ctx.applicationContext
        app = a
        try { Cfg.ensure(a); Cfg.put(PlayTarget.KEY, "0") } catch (_: Throwable) {}   // كل مرة التطبيق يفتح: وضع التلفزيون مقفول لحد ما تتصل
        try {
            (a as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(act: Activity) {
                    resumed++
                    if (resumed == 1 && System.currentTimeMillis() - lastPauseAt > 60000L) sessionFresh = true
                    if (skip(act)) return
                    attach(act); startPoll()
                }
                override fun onActivityPaused(act: Activity) { if (resumed > 0) resumed--; if (resumed == 0) lastPauseAt = System.currentTimeMillis(); try { picker?.dismiss() } catch (_: Throwable) {} }
                override fun onActivityDestroyed(act: Activity) { layers.remove(act) }
                override fun onActivityCreated(act: Activity, b: Bundle?) {}
                override fun onActivityStarted(act: Activity) {}
                override fun onActivityStopped(act: Activity) {}
                override fun onActivitySaveInstanceState(act: Activity, b: Bundle) {}
            })
        } catch (e: Throwable) { LogStore.err("TvModeFab:install", e) }
    }

    private fun skip(a: Activity): Boolean = try {
        DeviceUi.isTv(a) || a is TvActivity || a is PlayerActivity || a is RemoteActivity || a is SendToTvActivity
    } catch (_: Throwable) { true }

    private fun always(): Boolean = try { Cfg.str("tv_fab_always", "0") == "1" } catch (_: Throwable) { false }
    fun label(): String = if (PlayTarget.enabled()) "📺  خروج من وضع التلفزيون" else "📺  اتصال بالتلفزيون (وضع التلفزيون)"
    fun alwaysLabel(): String = if (always()) "📌  زرار التلفزيون: ظاهر دايمًا (دوس يخليه يظهر لما يلاقي تلفزيون بس)" else "📌  زرار التلفزيون: يظهر لما يلاقي تلفزيون (دوس يخليه ظاهر دايمًا)"
    fun toggleAlways(a: Activity) {
        Cfg.put("tv_fab_always", if (always()) "0" else "1")
        Notice.show(a, if (always()) "📌 زرار التلفزيون هيفضل ظاهر" else "📌 الزرار هيظهر لما يلاقي تلفزيون بس", 2600L)
        refreshAll()
    }
    /** من «المزيد»: دخول/خروج وضع التلفزيون */
    fun toggle(a: Activity) = onTap(a)

    // ===== الواجهة =====
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun attach(a: Activity) {
        try {
            val root = a.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
            var l = layers[a]
            if (l == null) {
                val btn = TextView(a).apply {
                    setTextColor(Color.WHITE); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER; setPadding(dp(a, 14), dp(a, 9), dp(a, 14), dp(a, 9))
                    elevation = dp(a, 10).toFloat(); visibility = View.GONE
                    setOnClickListener { onTap(a) }
                }
                val banner = TextView(a).apply {
                    setTextColor(Color.WHITE); textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER; setBackgroundColor(0xF0D32F2F.toInt())
                    text = "📺 إنت دلوقتي في وضع التلفزيون — كل حاجة بتتفتح على التلفزيون"
                    elevation = dp(a, 12).toFloat(); visibility = View.GONE
                    setOnClickListener { onTap(a) }
                }
                l = Layer(btn, banner); layers[a] = l
            }
            // setContentView ممكن يكون مسح الـviews: ضيفهم تاني لو اتشالوا
            if (l.btn.parent == null) root.addView(l.btn, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { bottomMargin = dp(a, 150); marginEnd = dp(a, 12) })
            if (l.banner.parent == null) root.addView(l.banner, FrameLayout.LayoutParams(-1, dp(a, 24), Gravity.TOP))
            paint(a, l)
        } catch (e: Throwable) { LogStore.err("TvModeFab:attach", e) }
    }

    private fun pill(c: Context, color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(c, 24).toFloat(); setStroke(dp(c, 1), 0x55FFFFFF) }

    private fun paint(a: Activity, l: Layer) {
        val on = PlayTarget.enabled()
        val list = tvs
        val prompting = System.currentTimeMillis() < promptUntil && list.isNotEmpty()
        l.banner.visibility = if (on) View.VISIBLE else View.GONE
        l.btn.visibility = if (on || prompting || always()) View.VISIBLE else View.GONE
        if (on) { l.btn.text = "✖ خروج من وضع التلفزيون"; l.btn.background = pill(a, 0xF0D32F2F.toInt()) }
        else {
            l.btn.text = when {
                list.size == 1 -> "📺 «" + list[0].name + "» — توصّل؟"
                list.size > 1 -> "📺 في " + list.size + " تلفزيونات — توصّل؟"
                else -> "📺 وضع التلفزيون"
            }
            l.btn.background = pill(a, 0xF01E6BFF.toInt())
        }
    }

    private fun refreshAll() {
        for ((a, l) in layers.toList()) {
            if (a.isDestroyed || a.isFinishing) { layers.remove(a); continue }
            if (l.btn.parent == null || l.banner.parent == null) attach(a) else paint(a, l)
        }
        for (cb in listeners) try { cb() } catch (e: Throwable) { LogStore.err("TvModeFab:cb", e) }
    }

    // ===== الاكتشاف في الخلفية (والتطبيق ظاهر، واي فاي بس) =====
    private fun onWifi(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (_: Throwable) { true }

    @Synchronized private fun startPoll() {
        if (polling) return
        polling = true
        Thread {
            try {
                while (resumed > 0) {
                    val c = app ?: break
                    if (!PlayTarget.enabled() && !busy) {
                        if (onWifi(c)) {
                            val fs = try { RemoteClient.init(c); RemoteClient.discover(2500L) } catch (_: Throwable) { emptyList() }
                            onFound(fs)
                        } else onFound(emptyList())
                    }
                    val wait = if (tvs.isNotEmpty()) 25000L else 45000L
                    var t = 0L
                    while (t < wait && resumed > 0) { try { Thread.sleep(500) } catch (_: InterruptedException) {}; t += 500 }
                }
            } catch (e: Throwable) { LogStore.err("TvModeFab:poll", e) } finally { polling = false }
        }.also { it.isDaemon = true }.start()
    }

    /** نتيجة بحث: حدّث القايمة، ولو اتلقى تلفزيون جديد (أو جلسة جديدة) اعرض «توصّل؟» 5 ثواني */
    private fun onFound(fs: List<RemoteClient.Found>) {
        tvs = fs
        val ids = fs.map { it.id }.toSet()
        if (ids.isNotEmpty() && (sessionFresh || ids != seenIds)) { promptUntil = System.currentTimeMillis() + PROMPT_MS; main.postDelayed({ refreshAll() }, PROMPT_MS + 100L) }
        if (ids.isNotEmpty()) sessionFresh = false
        seenIds = ids
        main.post { refreshAll() }
    }

    // ===== الدوسة =====
    private fun onTap(a: Activity) {
        if (busy) return
        if (PlayTarget.enabled()) { exit(a); return }
        promptUntil = 0L
        busy = true
        Notice.show(a, "📺 بدوّر على التلفزيونات…", 2500L)
        Thread {
            val fs = try { RemoteClient.init(a); RemoteClient.discover(2500L) } catch (e: Throwable) { LogStore.err("TvModeFab:tap", e); emptyList() }
            tvs = fs
            a.runOnUiThread {
                busy = false
                refreshAll()
                when {
                    fs.isEmpty() -> Notice.show(a, "مفيش تلفزيون ظاهر على الواي فاي — اتأكد إنه شغّال (وعليه Subtitler) وعلى نفس الشبكة", 3800L)
                    fs.size == 1 -> connectTo(a, fs[0])
                    else -> showPicker(a, fs)
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** قايمة صغيرة عايمة بالتلفزيونات اللي اتلاقت */
    private fun showPicker(a: Activity, fs: List<RemoteClient.Found>) {
        try {
            picker?.dismiss()
            val paired = try { RemoteClient.pairedList().map { it.id }.toSet() } catch (_: Throwable) { emptySet() }
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                background = pill(a, 0xF2232838.toInt()).apply { cornerRadius = dp(a, 16).toFloat() }
                setPadding(dp(a, 6), dp(a, 8), dp(a, 6), dp(a, 8))
            }
            box.addView(TextView(a).apply { text = "📺 اختار التلفزيون"; setTextColor(0xFFB8BDC9.toInt()); textSize = 12f; setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 6)) })
            val pw = PopupWindow(box, -2, -2, true)
            pw.isOutsideTouchable = true; pw.elevation = dp(a, 12).toFloat()
            for (f in fs) {
                box.addView(TextView(a).apply {
                    text = f.name + (if (f.id in paired) "" else "  (جديد)"); setTextColor(Color.WHITE); textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12))
                    setOnClickListener { try { pw.dismiss() } catch (_: Throwable) {}; connectTo(a, f) }
                })
            }
            picker = pw
            pw.showAtLocation(a.window.decorView, Gravity.END or Gravity.BOTTOM, dp(a, 12), dp(a, 210))
        } catch (e: Throwable) { LogStore.err("TvModeFab:picker", e) }
    }

    /** وصّل بتلفزيون معيّن: مقترن = اتصال على طول، غير كده اقتران الأول */
    private fun connectTo(a: Activity, f: RemoteClient.Found) {
        val tv = try { RemoteClient.pairedList().firstOrNull { it.id == f.id } } catch (_: Throwable) { null }
        if (tv == null) { pairFlow(a, f, ""); return }
        try { tv.ip = f.ip; tv.port = f.port; RemoteClient.saveTv(tv); Cfg.put("tvlink_last", tv.id) } catch (_: Throwable) {}
        enter(a)
    }

    private fun enter(a: Activity) {
        busy = true
        Notice.show(a, "📺 بوصّل بالتلفزيون…", 2500L)
        Thread {
            val ok = try { RemoteClient.ensureConnected(a, 8000L) } catch (e: Throwable) { LogStore.err("TvModeFab:enter", e); false }
            a.runOnUiThread {
                busy = false
                if (ok) {
                    Cfg.put(PlayTarget.KEY, "1")
                    Notice.show(a, "📺 وضع التلفزيون شغّال — اللي تختاره بيتفتح على التلفزيون", 3000L)
                } else Notice.show(a, "⚠ ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي", 3500L)
                refreshAll()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun exit(a: Activity) {
        Cfg.put(PlayTarget.KEY, "0")
        try { RemoteClient.disconnect(a.applicationContext) } catch (_: Throwable) {}
        Notice.show(a, "📱 خرجت من وضع التلفزيون", 2200L)
        refreshAll()
    }

    private fun pairFlow(a: Activity, f: RemoteClient.Found, code: String) {
        busy = true
        Notice.show(a, "📺 بقترن بـ" + f.name + "…", 2500L)
        Thread {
            val r = try { RemoteClient.pair(f, code) } catch (e: Throwable) { LogStore.err("TvModeFab:pair", e); Pair(null, "حصلت مشكلة") }
            a.runOnUiThread {
                busy = false
                val tv = r.first
                when {
                    tv != null -> { Cfg.put("tvlink_last", tv.id); enter(a) }
                    r.second == "need_code" -> askCode(a, f)
                    r.second == "locked" -> Notice.show(a, "🔒 التلفزيون قافل الاقتران دلوقتي — جرّب بعد شوية", 3500L)
                    else -> Notice.show(a, "⚠ " + r.second, 3500L)
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun askCode(a: Activity, f: RemoteClient.Found) {
        val et = EditText(a).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "الكود"; gravity = Gravity.CENTER }
        try {
            AlertDialog.Builder(a).setTitle("📺 كود الاقتران").setMessage("اكتب الكود الظاهر على التلفزيون")
                .setView(et)
                .setPositiveButton("اقتران") { _, _ -> pairFlow(a, f, et.text.toString().trim()) }
                .setNegativeButton("إلغاء", null).show()
        } catch (e: Throwable) { LogStore.err("TvModeFab:askCode", e) }
    }
}
