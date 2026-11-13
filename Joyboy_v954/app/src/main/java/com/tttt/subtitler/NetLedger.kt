package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v722) سجل استهلاك النت الدائم — بيفرّق واي فاي / داتا الموبايل.
 * - بياخد عيّنة كل ثانية من عدّاد النظام للتطبيق كله (TrafficStats على الـUID): ده بيشمل كل حاجة (المتصفح، تشغيل الفيديو الأونلاين، الـAPI...).
 * - كل فرق بيتحسب على نوع الشبكة وقتها (واي فاي / موبايل / أخرى) وبيتنسب لـ: اليوم، الجلسة، الصفحة اللي مفتوحة، والفيديو الحالي.
 * - الملفات: بتتسجل من أماكن التحميل/الـAPI نفسها (حجم المحتوى، تقريبي).
 * - بيتحفظ على الجهاز (آخر 45 يوم) وبيفضل بعد قفل البرنامج.
 * ملحوظة: الترافيك المحلي على الواي فاي (زي التلفزيون على نفس الشبكة) بيتحسب واي فاي.
 */
object NetLedger {
    const val WIFI = 0
    const val MOBILE = 1
    const val OTHER = 2
    private const val KEEP_DAYS = 45
    private const val NO_PAGE = "⚙ الخلفية (من غير شاشة)"
    private const val GAP_PAGE = "⏳ بين التشغيلات (قبل ما العدّاد يشتغل)"

    class Rec(val kind: String, val name: String, val day: String) {
        var first = 0L
        var last = 0L
        var ref = ""    // (v760) مسار/Uri/لينك بيفتح العنصر
        var mime = ""
        val dn = LongArray(3)
        val up = LongArray(3)
        fun add(t: Int, d: Long, u: Long, now: Long) {
            dn[t] += d; up[t] += u
            if (first == 0L) first = now
            last = now
        }
    }

    /** صف جاهز للعرض (مجمّع على كذا يوم) */
    class Row(val name: String, var first: Long, var last: Long) {
        var ref = ""
        var mime = ""
        val dn = LongArray(3)
        val up = LongArray(3)
        fun wifi() = dn[WIFI] + up[WIFI]
        fun mobile() = dn[MOBILE] + up[MOBILE]
        fun other() = dn[OTHER] + up[OTHER]
        fun total() = wifi() + mobile() + other()
    }

    private val lock = Any()
    private val recs = HashMap<String, Rec>()
    private var started = false
    private var app: Context? = null
    private var store: File? = null
    private var dirty = false
    @Volatile private var flushReq = false
    private var lastRx = -1L
    private var lastTx = -1L
    private var sesName = ""
    private var sesStart = 0L

    // سياق النسب
    @Volatile private var resumedName: String? = null
    @Volatile private var webHost: String? = null
    private class Vid(val name: String, val ref: String?)
    private val videos = LinkedHashMap<String, Vid>()
    private val files = LinkedHashMap<Long, String>()                    // (v760) خيط التحميل ← اسم الملف الجاري
    private val fileRefs = HashMap<String, Pair<String, String>>()       // اسم الملف ← (مرجع، mime) — بيتقري تحت lock
    @Volatile private var webRef: String? = null
    @Volatile private var tabName: String? = null
    private val fileScope = ThreadLocal<String?>()

    private fun dayOf(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    fun timeOf(ms: Long): String = if (ms <= 0L) "—" else SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    fun dateTimeOf(ms: Long): String = if (ms <= 0L) "—" else SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(ms))

    // ===================== بداية التشغيل =====================
    fun start(ctx: Context) {
        synchronized(lock) {
            if (started) return
            started = true
            val a = ctx.applicationContext
            app = a
            store = File(a.filesDir, "netledger.json")
            if (ctx is Activity) resumedName = ctx.javaClass.simpleName
            val now = System.currentTimeMillis()
            sesStart = now
            sesName = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
            val gapBase = load()
            val uid = android.os.Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            if (rx >= 0 && tx >= 0) {
                lastRx = rx; lastTx = tx
                if (gapBase != null && rx >= gapBase.first && tx >= gapBase.second) {
                    val dr = rx - gapBase.first; val dt = tx - gapBase.second
                    if (dr + dt > 0) addLocked(transport(), dr, dt, now, GAP_PAGE, null, null, null)
                }
            }
            try {
                (a as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(x: Activity, b: Bundle?) {}
                    override fun onActivityStarted(x: Activity) {}
                    override fun onActivityResumed(x: Activity) { resumedName = x.javaClass.simpleName }
                    override fun onActivityPaused(x: Activity) { if (resumedName == x.javaClass.simpleName) resumedName = null; flushReq = true }
                    override fun onActivityStopped(x: Activity) {}
                    override fun onActivitySaveInstanceState(x: Activity, b: Bundle) {}
                    override fun onActivityDestroyed(x: Activity) {}
                })
            } catch (_: Throwable) {}
        }
        val th = Thread {
            var n = 0
            while (true) {
                try { Thread.sleep(1000L) } catch (_: InterruptedException) {}
                try { tick() } catch (_: Throwable) {}
                n++
                if (flushReq || n % 15 == 0) { flushReq = false; try { save() } catch (_: Throwable) {} }
            }
        }
        th.isDaemon = true; th.name = "NetLedger"; th.start()
    }

    // ===================== ضبط السياق =====================
    /** اسم المتصفح الحالي (دومين الصفحة) */
    fun web(url: String?) {
        webRef = if (url.isNullOrBlank() || !url.startsWith("http", true)) null else url
        webHost = try { if (url.isNullOrBlank()) null else (java.net.URI(url).host ?: url.take(40)).removePrefix("www.") } catch (_: Throwable) { url?.take(40) }
    }
    /** تبويب الشاشة الرئيسية (فيديوهات / يوتيوب / ...) */
    fun tab(name: String?) { tabName = name }
    /** الفيديو الشغّال دلوقتي (owner = مين فاتحه: "player" أو "bg:<id>")؛ null = اتقفل */
    fun setVideo(owner: String, name: String?, ref: String? = null) {
        synchronized(videos) {
            videos.remove(owner)
            if (!name.isNullOrBlank()) videos[owner] = Vid(name.trim().take(90), ref?.takeIf { it.isNotBlank() })
        }
    }
    private fun curVid(): Vid? = synchronized(videos) { videos.values.lastOrNull() }
    private fun curFile(): String? = synchronized(files) { files.values.lastOrNull() }
    /** كل شبكة بتتعمل في الخيط ده جوه الكتلة دي بتتسجل على الملف ده */
    fun <T> inFile(name: String, f: () -> T): T {
        val old = fileScope.get(); val nm = name.take(90); val tid = Thread.currentThread().id
        fileScope.set(nm)
        synchronized(files) { files[tid] = nm }
        try { return f() } finally {
            fileScope.set(old)
            synchronized(files) { if (old != null) files[tid] = old else files.remove(tid) }
        }
    }
    /** (v760) مرجع الملف الجاري تحميله/استلامه في الخيط ده (مسار أو Uri) عشان صفحة الداتا تفتحه */
    fun fileRef(ref: String, mime: String) {
        try {
            val nm = fileScope.get() ?: return
            val now = System.currentTimeMillis()
            synchronized(lock) {
                fileRefs[nm] = Pair(ref, mime)
                val rc = rec("file", dayOf(now), nm); rc.ref = ref; rc.mime = mime
                dirty = true
            }
        } catch (_: Throwable) {}
    }

    private fun pageName(): String {
        val r = resumedName ?: return NO_PAGE
        return when (r) {
            "MainActivity" -> "🏠 الرئيسية" + (tabName?.let { " — $it" } ?: "")
            "LaunchSplashActivity" -> "🚀 شاشة الفتح"
            "PlayerActivity" -> "🎬 المشغّل"
            "BrowserActivity" -> "🌐 المتصفح" + (webHost?.let { " — $it" } ?: "")
            "LaunchBrowserActivity" -> "🌐 المتصفح"
            "LaunchYoutubeActivity" -> "▶ يوتيوب"
            "TvActivity" -> "📺 واجهة التلفزيون"
            "FileTransferActivity", "TransferActivity" -> "📤 النقل"
            "TvFilesActivity", "FileManagerActivity" -> "🗂 الملفات"
            "RemoteActivity", "LaunchRemoteActivity" -> "🎮 الريموت"
            else -> r.removeSuffix("Activity")
        }
    }

    // ===================== نوع الشبكة =====================
    @Suppress("DEPRECATION")
    fun transport(): Int {
        try {
            val a = app ?: return OTHER
            val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return OTHER
            val c = cm.getNetworkCapabilities(n) ?: return OTHER
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                var found = -1
                for (x in cm.allNetworks) {
                    val cc = cm.getNetworkCapabilities(x) ?: continue
                    if (cc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    if (!cc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                    if (cc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { found = WIFI; break }
                    if (cc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && found < 0) found = MOBILE
                }
                return if (found < 0) OTHER else found
            }
            return when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> WIFI
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> MOBILE
                else -> OTHER
            }
        } catch (_: Throwable) { return OTHER }
    }
    fun transportLabel(t: Int = transport()) = when (t) { WIFI -> "📶 واي فاي"; MOBILE -> "📱 داتا الموبايل"; else -> "⛔ مفيش نت / أخرى" }

    // ===================== العدّ =====================
    @Volatile var lastBps = 0L
    private var bpsN = 0
    fun lastSpeed(): Long = if (lastBps > 0L) lastBps else (try { Cfg.str("last_dl_bps", "0").toLongOrNull() ?: 0L } catch (_: Throwable) { 0L })

    private fun tick() {
        if (app == null) return
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return
        if (lastRx < 0) { lastRx = rx; lastTx = tx; return }
        val dr = (rx - lastRx).coerceAtLeast(0L); val dt = (tx - lastTx).coerceAtLeast(0L)
        lastRx = rx; lastTx = tx
        if (dr > 200_000L) { lastBps = if (lastBps <= 0L) dr else (lastBps * 0.6 + dr * 0.4).toLong(); if (++bpsN % 10 == 0) try { Cfg.put("last_dl_bps", lastBps.toString()) } catch (_: Throwable) {} }   // (v754) آخر سرعة (بايت/ث، التيك كل ثانية)
        if (dr == 0L && dt == 0L) return
        val t = transport(); val now = System.currentTimeMillis()
        val vd = curVid(); val fn = curFile(); val pr = if (resumedName == "BrowserActivity") webRef else null
        synchronized(lock) { addLocked(t, dr, dt, now, pageName(), pr, vd, fn) }
    }

    private fun rec(kind: String, day: String, name: String): Rec {
        val k = "$kind|$day|$name"
        return recs.getOrPut(k) { Rec(kind, name, day) }
    }
    /** (v760) كل بايت بيتحسب في قسم واحد بس: ملف شغّال ← «ملفات»، وإلا فيديو مفتوح ← «فيديوهات»، وإلا ← «صفحات» (متصفح/واجهة/خلفية) */
    private fun addLocked(t: Int, d: Long, u: Long, now: Long, page: String, pref: String?, video: Vid?, file: String?) {
        val day = dayOf(now)
        rec("day", day, day).add(t, d, u, now)
        rec("ses", day, sesName).add(t, d, u, now)
        when {
            file != null -> {
                rec("cat", day, "file").add(t, d, u, now)
                val rc = rec("file", day, file); rc.add(t, d, u, now)
                if (rc.ref.isEmpty()) fileRefs[file]?.let { rc.ref = it.first; rc.mime = it.second }
            }
            video != null -> {
                rec("cat", day, "video").add(t, d, u, now)
                val rc = rec("vid", day, video.name); rc.add(t, d, u, now)
                if (rc.ref.isEmpty() && video.ref != null) rc.ref = video.ref
            }
            else -> {
                rec("cat", day, "page").add(t, d, u, now)
                val rc = rec("page", day, page); rc.add(t, d, u, now)
                if (pref != null) rc.ref = pref
            }
        }
        dirty = true
    }

    /** (v760) ما بقاش بيسجّل حاجة: كل الترافيك بيتحسب من عدّاد النظام وبيتصنّف تلقائي (فيديو/ملف/صفحة) — كان بيعمل عدّ مزدوج. */
    @Suppress("UNUSED_PARAMETER")
    fun explicit(category: String, up: Long, down: Long) {}

    // ===================== الاستعلام =====================
    private fun daySet(days: Int): Set<String> {
        val now = System.currentTimeMillis(); val s = HashSet<String>()
        for (i in 0 until days.coerceIn(1, 60)) s.add(dayOf(now - i * 86_400_000L))
        return s
    }

    /** صفوف نوع معيّن مجمّعة على آخر n يوم، مرتّبة بالأكبر استهلاكًا (الداتا الأول) */
    fun rows(kind: String, days: Int): List<Row> {
        val ds = daySet(days); val m = HashMap<String, Row>()
        synchronized(lock) {
            for (r in recs.values) {
                if (r.kind != kind || r.day !in ds) continue
                val row = m.getOrPut(r.name) { Row(r.name, r.first, r.last) }
                for (i in 0..2) { row.dn[i] += r.dn[i]; row.up[i] += r.up[i] }
                if (r.ref.isNotEmpty()) { row.ref = r.ref; row.mime = r.mime }
                if (r.first in 1 until row.first || row.first == 0L) row.first = r.first
                if (r.last > row.last) row.last = r.last
            }
        }
        return m.values.sortedWith(compareByDescending<Row> { it.mobile() }.thenByDescending { it.total() })
    }

    /** (v760) تقسيم الاستهلاك لـ3 أقسام متنافية [فيديوهات، ملفات، صفحات] — الأيام القديمة (قبل v760) بتتقدّر: فيديو = سجل الفيديوهات والباقي صفحات */
    fun catRows(days: Int): List<Row> {
        val ds = daySet(days)
        val out = arrayOf(Row("video", 0L, 0L), Row("file", 0L, 0L), Row("page", 0L, 0L))
        synchronized(lock) {
            val withCat = HashSet<String>()
            for (r in recs.values) if (r.kind == "cat" && r.day in ds) withCat.add(r.day)
            val dayTot = HashMap<String, Row>(); val vidTot = HashMap<String, Row>()
            for (r in recs.values) {
                if (r.day !in ds) continue
                if (r.kind == "cat") {
                    val t = when (r.name) { "video" -> out[0]; "file" -> out[1]; else -> out[2] }
                    for (i in 0..2) { t.dn[i] += r.dn[i]; t.up[i] += r.up[i] }
                } else if (r.day !in withCat) {
                    val t = when (r.kind) { "day" -> dayTot.getOrPut(r.day) { Row(r.day, 0L, 0L) }; "vid" -> vidTot.getOrPut(r.day) { Row(r.day, 0L, 0L) }; else -> null }
                    if (t != null) for (i in 0..2) { t.dn[i] += r.dn[i]; t.up[i] += r.up[i] }
                }
            }
            for ((d, dr) in dayTot) {
                val vr = vidTot[d]
                for (i in 0..2) {
                    val vd = vr?.dn?.get(i) ?: 0L; val vu = vr?.up?.get(i) ?: 0L
                    out[0].dn[i] += vd; out[0].up[i] += vu
                    out[2].dn[i] += (dr.dn[i] - vd).coerceAtLeast(0L); out[2].up[i] += (dr.up[i] - vu).coerceAtLeast(0L)
                }
            }
        }
        return out.toList()
    }

    /** إجمالي كل الأيام في المدى (من سجل «day») */
    fun totalOf(days: Int): Row {
        val t = Row("total", 0L, 0L)
        for (r in rows("day", days)) { for (i in 0..2) { t.dn[i] += r.dn[i]; t.up[i] += r.up[i] } }
        return t
    }

    /** يوم بيوم (الأحدث أول) */
    fun perDay(days: Int): List<Row> = rows("day", days).sortedByDescending { it.name }

    fun clear() {
        synchronized(lock) { recs.clear(); dirty = true }
        try { save() } catch (_: Throwable) {}
    }

    fun flushNow() { try { save() } catch (_: Throwable) {} }

    // ===================== الحفظ والتحميل =====================
    private fun bootKey() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** بيرجّع (آخر rx, آخر tx) محفوظين لو نفس الإقلاع، عشان نعوّض اللي اتنقل بين التشغيلات */
    private fun load(): Pair<Long, Long>? {
        val f = store ?: return null
        if (!f.exists()) return null
        try {
            val j = JSONObject(f.readText(Charsets.UTF_8))
            val arr = j.optJSONArray("r") ?: JSONArray()
            val keep = daySet(KEEP_DAYS + 1)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONArray(i)
                val r = Rec(o.getString(0), o.getString(2), o.getString(1))
                if (r.day !in keep) continue
                r.first = o.getLong(3); r.last = o.getLong(4)
                for (k in 0..2) { r.dn[k] = o.getLong(5 + k * 2); r.up[k] = o.getLong(6 + k * 2) }
                r.ref = o.optString(11, ""); r.mime = o.optString(12, "")
                recs["${r.kind}|${r.day}|${r.name}"] = r
            }
            val boot = j.optLong("boot", 0L); val lrx = j.optLong("lrx", -1L); val ltx = j.optLong("ltx", -1L)
            if (boot != 0L && Math.abs(boot - bootKey()) < 60_000L && lrx >= 0 && ltx >= 0) return Pair(lrx, ltx)
        } catch (_: Throwable) {}
        return null
    }

    private fun save() {
        val f = store ?: return
        val arr = JSONArray()
        val lrx: Long; val ltx: Long
        synchronized(lock) {
            if (!dirty && f.exists()) return
            val keep = daySet(KEEP_DAYS)
            val it = recs.values.iterator()
            while (it.hasNext()) { if (it.next().day !in keep) it.remove() }
            // سقف الحجم: لو كتر، نشيل الأصغر في الأيام القديمة
            if (recs.size > 3500) {
                val today = dayOf(System.currentTimeMillis())
                val drop = recs.values.filter { it.day != today && it.kind != "day" && it.kind != "cat" }.sortedBy { it.dn.sum() + it.up.sum() }.take(recs.size - 3000)
                for (r in drop) recs.remove("${r.kind}|${r.day}|${r.name}")
            }
            for (r in recs.values) {
                val o = JSONArray().put(r.kind).put(r.day).put(r.name).put(r.first).put(r.last)
                for (k in 0..2) { o.put(r.dn[k]); o.put(r.up[k]) }
                o.put(r.ref).put(r.mime)
                arr.put(o)
            }
            dirty = false
            lrx = lastRx; ltx = lastTx
        }
        val j = JSONObject().put("v", 1).put("boot", bootKey()).put("lrx", lrx).put("ltx", ltx).put("r", arr)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(j.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.writeText(j.toString(), Charsets.UTF_8); tmp.delete() }
    }
}
