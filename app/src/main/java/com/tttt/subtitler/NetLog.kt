package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v791) لوج النت العام — كل اتصال جديد بالنت (مين فتحه + لأنهي سيرفر) وكام ميجا استهلك كل مصدر.
 *
 * إزاي بيشتغل (تقريبي، مش عدّاد لكل سوكت):
 *  1) الاتصالات: غلاف حوالين ProxySelector الافتراضي — أي اتصال جديد من HttpURLConnection/OkHttp بيعدّي عليه، فنعرف السيرفر والكلاس اللي طلبه من الـstack.
 *  2) الحجم: كل ثانية بنقرا عدّاد النظام للتطبيق (TrafficStats UID) والفرق بنوزّعه على الخيوط اللي كانت واقفة على قراءة من الشبكة وقتها (عيّنة stack)،
 *     ولو مفيش: على آخر اتصالات اتفتحت، ولو مفيش: «مش ظاهر» (ميديا/نظام/Chromium). صفحات المتصفح بتتحسب من BrowserSaver.
 *  3) بيتحفظ آخر 7 أيام + آخر 300 اتصال في filesDir/netlog.json.
 */
object NetLog {
    class Src(val name: String) {
        var bytes = 0L; var mob = 0L; var hits = 0; var conns = 0; var first = 0L; var last = 0L
        val hosts = LinkedHashMap<String, Int>()
    }
    class Ev(val t: Long, val host: String, val who: String)

    private const val KEEP_DAYS = 7
    private const val MAX_EV = 300
    private const val WEB = "🌐 المتصفح (صفحات الويب)"
    private const val UNK = "❓ مش ظاهر (ميديا / نظام / Chromium)"

    private val lock = Any()
    private val days = java.util.TreeMap<String, HashMap<String, Src>>()
    private val events = java.util.ArrayDeque<Ev>()
    private val recent = HashMap<String, Long>()
    private var app: Context? = null
    private var store: File? = null
    @Volatile private var started = false
    private var ht: HandlerThread? = null
    private var hd: Handler? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var dirty = false
    private var tickN = 0
    @Volatile private var webAt = 0L
    private var lastWebHost = ""

    private fun dayOf(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
    private fun src(day: String, name: String): Src = days.getOrPut(day) { HashMap() }.getOrPut(name) { Src(name) }
    private fun addHost(s: Src, host: String) {
        s.hosts[host] = (s.hosts[host] ?: 0) + 1
        if (s.hosts.size > 14) { val k = s.hosts.keys.firstOrNull(); if (k != null) s.hosts.remove(k) }
    }

    // ===================== التشغيل =====================
    fun start(ctx: Context) {
        if (started) return
        started = true
        try {
            app = ctx.applicationContext
            store = File(ctx.applicationContext.filesDir, "netlog.json")
            load()
            val cur = ProxySelector.getDefault()
            if (cur !is Sel) ProxySelector.setDefault(Sel(cur))
            val t = HandlerThread("netlog").also { it.isDaemon = true; it.start() }
            ht = t; hd = Handler(t.looper)
            hd?.postDelayed({ tick() }, 1000L)
        } catch (_: Throwable) { started = false }
    }

    private class Sel(val base: ProxySelector?) : ProxySelector() {
        override fun select(uri: URI?): MutableList<Proxy> {
            try { if (uri != null) onConn(uri) } catch (_: Throwable) {}
            return try { base?.select(uri) ?: mutableListOf(Proxy.NO_PROXY) } catch (_: Throwable) { mutableListOf(Proxy.NO_PROXY) }
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) { try { base?.connectFailed(uri, sa, ioe) } catch (_: Throwable) {} }
    }

    // ===================== تسجيل الاتصالات =====================
    private fun pretty(cls: String, m: String): String {
        val c = cls.substringAfterLast('.'); val p = c.split('$')
        val inner = p.drop(1).firstOrNull { it.isNotEmpty() && !it[0].isDigit() }
        return p[0] + "." + (inner ?: m)
    }
    private val skipPre = listOf("java.", "javax.", "android.", "com.android.", "libcore.", "dalvik.", "sun.", "org.conscrypt.", "okhttp3.", "okio.", "kotlin.", "com.android.okhttp.")
    private fun callerName(st: Array<StackTraceElement>, threadName: String): String {
        for (f in st) if (f.className.startsWith("com.tttt.subtitler") && !f.className.contains("NetLog")) return pretty(f.className, f.methodName)
        for (f in st) if (skipPre.none { f.className.startsWith(it) } && !f.className.contains("NetLog")) return pretty(f.className, f.methodName) + " [" + threadName.take(24) + "]"
        return "thread:" + threadName.take(24)
    }

    private fun onConn(uri: URI) {
        val host = uri.host ?: return
        val th0 = Thread.currentThread()
        val who = callerName(th0.stackTrace, th0.name)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val s = src(dayOf(now), who)
            s.conns++; addHost(s, host)
            if (s.first == 0L) s.first = now
            s.last = now
            events.addLast(Ev(now, host, who))
            while (events.size > MAX_EV) events.pollFirst()
            recent[who] = now
            dirty = true
        }
    }

    /** من BrowserSaver.intercept: كل طلب صفحة ويب (الاتصالات بتاعت WebView مش بتعدّي على ProxySelector) */
    fun web(host: String) {
        if (host.isEmpty()) return
        val now = System.currentTimeMillis()
        webAt = now
        synchronized(lock) {
            val s = src(dayOf(now), WEB); addHost(s, host); s.conns++
            if (s.first == 0L) s.first = now
            s.last = now
            if (host != lastWebHost) { lastWebHost = host; events.addLast(Ev(now, host, WEB)); while (events.size > MAX_EV) events.pollFirst() }
            dirty = true
        }
    }

    // ===================== توزيع الحجم =====================
    private fun isNetRead(f: StackTraceElement): Boolean {
        val c = f.className; val m = f.methodName
        return m.startsWith("socketRead") || m == "SSL_read" || c.contains("NativeCrypto") || c.contains("NativeSsl") || c.contains("ConscryptFileDescriptorSocket") ||
            c.contains("ConscryptEngineSocket") || c.contains("SocketInputStream") || (c.contains("okhttp") && m == "read")
    }

    private fun sampleReaders(): HashMap<String, Int> {
        val w = HashMap<String, Int>()
        try {
            val me = Thread.currentThread()
            for ((t, st) in Thread.getAllStackTraces()) {
                if (t === me || st.isEmpty()) continue
                if (st.any { it.className.contains("NetLog") }) continue
                if (st.take(18).none { isNetRead(it) }) continue
                val k = callerName(st, t.name)
                w[k] = (w[k] ?: 0) + 1
            }
        } catch (_: Throwable) {}
        return w
    }

    private fun attribute(d: Long) {
        val now = System.currentTimeMillis()
        val w = sampleReaders()
        if (now - webAt < 3000L) w[WEB] = (w[WEB] ?: 0) + 2
        if (w.isEmpty()) synchronized(lock) { for ((k, t) in recent) if (now - t < 6000L) w[k] = 1 }
        if (w.isEmpty()) w[UNK] = 1
        val tot = w.values.sum().toDouble()
        val mobile = try { NetLedger.transport() == NetLedger.MOBILE } catch (_: Throwable) { false }
        synchronized(lock) {
            val day = dayOf(now)
            for ((k, c) in w) {
                val share = (d * c / tot).toLong()
                val s = src(day, k)
                s.bytes += share; if (mobile) s.mob += share
                s.hits++
                if (s.first == 0L) s.first = now
                s.last = now
            }
            while (days.size > KEEP_DAYS) days.pollFirstEntry()
            dirty = true
        }
    }

    private fun tick() {
        try {
            val uid = android.os.Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
            if (rx >= 0 && tx >= 0) {
                if (lastRx < 0) { lastRx = rx; lastTx = tx }
                else {
                    val d = (rx - lastRx).coerceAtLeast(0L) + (tx - lastTx).coerceAtLeast(0L)
                    lastRx = rx; lastTx = tx
                    if (d >= 1024L) attribute(d)
                }
            }
            if (++tickN % 20 == 0 && dirty) save()
        } catch (_: Throwable) {}
        hd?.postDelayed({ tick() }, 1000L)
    }

    // ===================== الاستعلام =====================
    fun rows(nDays: Int): List<Src> {
        val now = System.currentTimeMillis()
        val ds = (0 until nDays.coerceIn(1, KEEP_DAYS)).map { dayOf(now - it * 86_400_000L) }.toSet()
        val m = HashMap<String, Src>()
        synchronized(lock) {
            for ((d, mm) in days) {
                if (d !in ds) continue
                for ((n, s) in mm) {
                    val r = m.getOrPut(n) { Src(n) }
                    r.bytes += s.bytes; r.mob += s.mob; r.hits += s.hits; r.conns += s.conns
                    if (r.first == 0L || (s.first in 1 until r.first)) r.first = s.first
                    if (s.last > r.last) r.last = s.last
                    for ((h, c) in s.hosts) r.hosts[h] = (r.hosts[h] ?: 0) + c
                }
            }
        }
        return m.values.sortedByDescending { it.bytes }
    }

    fun recentEvents(n: Int): List<Ev> = synchronized(lock) { events.toList().takeLast(n).reversed() }

    fun clear() {
        synchronized(lock) { days.clear(); events.clear(); recent.clear(); dirty = true }
        try { save() } catch (_: Throwable) {}
    }

    // ===================== حفظ / تحميل =====================
    private fun save() {
        val f = store ?: return
        val j = JSONObject()
        synchronized(lock) {
            val dj = JSONObject()
            for ((d, mm) in days) {
                val o = JSONObject()
                for ((n, s) in mm) {
                    val h = JSONObject(); for ((k, v) in s.hosts) h.put(k, v)
                    o.put(n, JSONArray().put(s.bytes).put(s.mob).put(s.hits).put(s.conns).put(s.first).put(s.last).put(h))
                }
                dj.put(d, o)
            }
            j.put("days", dj)
            val ev = JSONArray()
            for (e in events.toList().takeLast(150)) ev.put(JSONArray().put(e.t).put(e.host).put(e.who))
            j.put("ev", ev)
            dirty = false
        }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun load() {
        try {
            val f = store ?: return
            if (!f.exists()) return
            val j = JSONObject(f.readText())
            val dj = j.optJSONObject("days")
            synchronized(lock) {
                if (dj != null) {
                    val it = dj.keys()
                    while (it.hasNext()) {
                        val d = it.next(); val o = dj.getJSONObject(d); val m = HashMap<String, Src>()
                        val k2 = o.keys()
                        while (k2.hasNext()) {
                            val n = k2.next(); val a = o.getJSONArray(n); val s = Src(n)
                            s.bytes = a.optLong(0); s.mob = a.optLong(1); s.hits = a.optInt(2); s.conns = a.optInt(3); s.first = a.optLong(4); s.last = a.optLong(5)
                            a.optJSONObject(6)?.let { h -> val hk = h.keys(); while (hk.hasNext()) { val x = hk.next(); s.hosts[x] = h.optInt(x) } }
                            m[n] = s
                        }
                        days[d] = m
                    }
                }
                val ev = j.optJSONArray("ev")
                if (ev != null) for (i in 0 until ev.length()) { val e = ev.getJSONArray(i); events.addLast(Ev(e.optLong(0), e.optString(1), e.optString(2))) }
            }
        } catch (_: Throwable) {}
    }

    // ===================== الشاشة =====================
    private fun hostsOf(s: Src, n: Int = 4): String =
        s.hosts.entries.sortedByDescending { it.value }.take(n).joinToString("، ") { it.key.take(30) + "(" + it.value + ")" }

    private fun report(nDays: Int): String {
        val rs = rows(nDays); val sb = StringBuilder()
        sb.append("📡 لوج النت — ").append(if (nDays <= 1) "النهارده" else "آخر $nDays أيام").append('\n')
        sb.append("الإجمالي: ").append(NetUse.fmt(rs.sumOf { it.bytes })).append("  (📶 موبايل ").append(NetUse.fmt(rs.sumOf { it.mob })).append(")\n\n")
        for (s in rs.take(30)) {
            sb.append("• ").append(s.name).append(" — ").append(NetUse.fmt(s.bytes))
            if (s.mob > 0) sb.append(" (📶 ").append(NetUse.fmt(s.mob)).append(")")
            sb.append(" · اتصالات ").append(s.conns).append('\n')
            if (s.hosts.isNotEmpty()) sb.append("   ← ").append(hostsOf(s, 6)).append('\n')
        }
        sb.append("\nآخر اتصالات:\n")
        for (e in recentEvents(40)) sb.append(clock(e.t)).append("  ").append(e.host).append("  ←  ").append(e.who).append('\n')
        return sb.toString()
    }

    fun show(act: Activity, ui: Ui, th: Theme) {
        var range = 1
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)) }
        lateinit var render: () -> Unit
        render = {
            body.removeAllViews()
            val rs = rows(range)
            body.addView(ui.text("📡 مين بيتصل بالنت وبياخد كام", 15f, th.primary, true))
            body.addView(ui.text("الاتصالات حقيقية (سيرفر + مين فتحه). الأحجام تقريبية: عدّاد النظام للتطبيق متوزّع على اللي كان بيقرا من الشبكة وقتها.", 11f, th.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(6)) })
            body.addView(ui.button(if (range == 1) "📅 النهارده — دوس لآخر 7 أيام" else "📅 آخر 7 أيام — دوس للنهارده") { range = if (range == 1) 7 else 1; render() })
            body.addView(ui.text("الإجمالي: " + NetUse.fmt(rs.sumOf { it.bytes }) + "   (📶 موبايل " + NetUse.fmt(rs.sumOf { it.mob }) + ")", 13f, th.text, true).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
            if (rs.isEmpty()) body.addView(ui.text("لسه مفيش اتصالات مسجّلة. استنى شوية واستعمل التطبيق وارجع.", 12f, th.muted))
            val tot = rs.sumOf { it.bytes }.coerceAtLeast(1L)
            for (s in rs.take(25)) {
                val pc = if (s.bytes > 0) " (" + (s.bytes * 100 / tot) + "%)" else ""
                body.addView(ui.text("• " + s.name + " — " + NetUse.fmt(s.bytes) + pc + (if (s.mob > 0) "  📶 " + NetUse.fmt(s.mob) else ""), 12.5f, th.text, true).apply { setPadding(0, ui.dp(6), 0, 0) })
                val sub = "اتصالات: " + s.conns + " · آخر نشاط " + clock(s.last) + (if (s.hosts.isEmpty()) "" else "\n← " + hostsOf(s))
                body.addView(ui.text(sub, 11f, th.muted))
            }
            body.addView(ui.text("🔌 آخر 40 اتصال", 13f, th.primary, true).apply { setPadding(0, ui.dp(12), 0, ui.dp(2)) })
            val evs = recentEvents(40)
            if (evs.isEmpty()) body.addView(ui.text("—", 12f, th.muted))
            for (e in evs) body.addView(ui.text(clock(e.t) + "  " + e.host.take(34) + "  ←  " + e.who.take(38), 11f, th.text))
            body.addView(ui.button("🔄 حدّث") { render() })
            body.addView(ui.button("📋 انسخ التقرير") {
                try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("netlog", report(range))); Notice.show(act, "📋 اتنسخ التقرير", 1800L) } catch (_: Throwable) {}
            })
            body.addView(ui.button("🗑 امسح اللوج") {
                android.app.AlertDialog.Builder(act).setTitle("مسح لوج النت؟").setMessage("هيتمسح كل الاتصالات والأحجام المسجّلة. عدّادات الداتا التانية مش هتتأثر.")
                    .setPositiveButton("امسح") { _, _ -> clear(); render() }.setNegativeButton("إلغاء", null).show()
            })
        }
        render()
        ui.sheet(act, "📡 لوج النت", listOf<View>(ScrollView(act).apply { addView(body) })).show()
    }
}
