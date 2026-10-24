package com.tttt.subtitler

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * (v518) جانب الموبايل من «ريموت التلفزيون»:
 *  اكتشاف تلقائي (عنوان محفوظ ← بحث UDP ← مسح الشبكة) · اقتران (أول مرة تلقائي، وبعدها بكود 5 أرقام) ·
 *  اتصال بنبضة كل 4 ثواني وإعادة اتصال تلقائية · أوامر (صوت/تشغيل) · إرسال لينك/نص/مفاتيح Gemini · عرض تحديث (الموبايل بيوزّع الـAPK بتاعه).
 */
object RemoteClient {
    class Tv(val id: String, var name: String, var ip: String, var port: Int, var token: String)
    class Found(val id: String, val name: String, val ip: String, val port: Int, val ver: Long, val needCode: Boolean)
    enum class St { IDLE, CONNECTING, CONNECTED, LOST }

    @Volatile var state = St.IDLE
        private set
    @Volatile var current: Tv? = null
        private set
    @Volatile var tvVer = 0L
        private set
    @Volatile var tvVName = ""
        private set
    @Volatile var lastError = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var appRef: Context? = null
    @Volatile private var loopGen = 0
    @Volatile private var autoOffered = false

    internal fun app(): Context = appRef ?: throw IllegalStateException("RemoteClient not initialised")

    fun init(ctx: Context) {
        appRef = ctx.applicationContext
        try { Cfg.ensure(ctx.applicationContext) } catch (_: Throwable) {}
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { main.post { for (l in listeners) try { l() } catch (_: Throwable) {} } }

    // ===== هوية الموبايل والتلفزيونات المقترنة =====
    fun phoneId(): String {
        var id = Cfg.str("tvlink_phone_id", "")
        if (id.isBlank()) { val b = ByteArray(8); SecureRandom().nextBytes(b); id = b.joinToString("") { String.format("%02x", it) }; Cfg.put("tvlink_phone_id", id) }
        return id
    }

    fun phoneName(): String {
        val n = Cfg.str("tvlink_phone_name", "")
        if (n.isNotBlank()) return n
        val m = try { Build.MODEL ?: "" } catch (_: Throwable) { "" }
        return if (m.isBlank()) "موبايل" else m
    }

    fun pairedList(): List<Tv> {
        val out = ArrayList<Tv>()
        try {
            val a = JSONArray(Cfg.str("tvlink_tvs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id"); val tk = o.optString("token")
                if (id.isNotBlank() && tk.isNotBlank()) out.add(Tv(id, o.optString("name", "تلفزيون"), o.optString("ip"), o.optInt("port", TvLink.PORT), tk))
            }
        } catch (_: Throwable) {}
        return out
    }

    @Synchronized fun saveTv(tv: Tv) {
        val all = pairedList().filter { it.id != tv.id }.toMutableList()
        all.add(tv)
        persist(all)
    }

    private fun persist(all: List<Tv>) {
        val a = JSONArray()
        for (t in all) a.put(JSONObject().put("id", t.id).put("name", t.name).put("ip", t.ip).put("port", t.port).put("token", t.token))
        Cfg.put("tvlink_tvs", a.toString())
    }

    fun lastTv(): Tv? {
        val all = pairedList()
        val last = Cfg.str("tvlink_last", "")
        return all.firstOrNull { it.id == last } ?: all.firstOrNull()
    }

    fun forget(ctx: Context, id: String) {
        if (current?.id == id) disconnect(ctx)
        persist(pairedList().filter { it.id != id })
        fire()
    }

    // ===== HTTP =====
    private fun call(ip: String, port: Int, path: String, body: String?, token: String, ms: Int = 3000, manual: Boolean = false): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            c.connectTimeout = ms; c.readTimeout = ms; c.useCaches = false
            if (token.isNotEmpty()) c.setRequestProperty("X-Token", token)
            c.setRequestProperty("X-Ver", LinkVer.code(app()).toString())
            c.setRequestProperty("X-Name", URLEncoder.encode(phoneName(), "UTF-8"))
            if (manual) c.setRequestProperty("X-Manual", "1")
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val ins = if (code in 200..299) c.inputStream else c.errorStream
            val txt = if (ins != null) ins.use { String(it.readBytes(), Charsets.UTF_8) } else ""
            Pair(code, txt)
        } catch (e: Exception) { Pair(-1, e.message ?: "") } finally { try { c?.disconnect() } catch (_: Exception) {} }
    }

    // ===== عناوين الشبكة =====
    private fun lanIp(): String {
        var fallback = ""
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                val nm = ni.name ?: ""
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a.isLoopbackAddress || a !is java.net.Inet4Address || !a.isSiteLocalAddress) continue
                    val ip = a.hostAddress ?: continue
                    if (nm.startsWith("wlan") || nm.startsWith("eth")) return ip
                    if (fallback.isEmpty()) fallback = ip
                }
            }
        } catch (_: Exception) {}
        return if (fallback.isNotEmpty()) fallback else TypeServer.ip()
    }

    /** عنوان الموبايل اللي التلفزيون يقدر يوصله (نفس الشبكة الفرعية) */
    internal fun ipTowards(tvIp: String): String {
        val pre = tvIp.substringBeforeLast('.', "")
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val addrs = ifs.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a.isLoopbackAddress || a !is java.net.Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    if (pre.isNotEmpty() && ip.startsWith("$pre.")) return ip
                }
            }
        } catch (_: Exception) {}
        return lanIp()
    }

    // ===== الاكتشاف التلقائي =====
    private fun parseFound(txt: String, ip: String): Found? = try {
        val j = JSONObject(txt)
        if (j.optString("app") != "subtitler-tv") null
        else Found(j.optString("id"), j.optString("name", "تلفزيون"), ip, j.optInt("port", TvLink.PORT), j.optLong("ver"), j.optBoolean("needCode"))
    } catch (_: Exception) { null }

    /** بحث عن تلفزيونات Subtitler على نفس الواي فاي: بث UDP أولًا، ولو مالقاش يمسح الشبكة. (بيتنادى من thread خلفي) */
    fun discover(ms: Long = 4000L): List<Found> {
        val found = ConcurrentHashMap<String, Found>()
        val t0 = System.currentTimeMillis()
        val myIp = lanIp()
        val base = myIp.substringBeforeLast('.', "")
        val udp = Thread {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket()
                s.broadcast = true; s.soTimeout = 300
                val msg = "SUBTV?".toByteArray(Charsets.UTF_8)
                val targets = ArrayList<InetAddress>()
                try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
                if (base.isNotEmpty()) try { targets.add(InetAddress.getByName("$base.255")) } catch (_: Exception) {}
                val buf = ByteArray(1024)
                var lastSend = 0L
                while (System.currentTimeMillis() - t0 < ms) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 800) { lastSend = now; for (t in targets) try { s.send(DatagramPacket(msg, msg.size, t, TvLink.UDP_PORT)) } catch (_: Exception) {} }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val f = parseFound(String(p.data, 0, p.length, Charsets.UTF_8), p.address.hostAddress ?: "")
                        if (f != null && f.id.isNotEmpty()) found[f.id] = f
                    } catch (_: SocketTimeoutException) {}
                }
            } catch (_: Exception) {} finally { try { s?.close() } catch (_: Exception) {} }
        }
        udp.isDaemon = true; udp.start()
        // لو البث ما لقاش حاجة في 1.2 ثانية: مسح الشبكة /24 (بيشتغل حتى لو الراوتر بيمنع البث)
        while (System.currentTimeMillis() - t0 < 1200 && found.isEmpty()) try { Thread.sleep(100) } catch (_: InterruptedException) {}
        if (found.isEmpty() && base.isNotEmpty()) {
            val ex = Executors.newFixedThreadPool(40)
            for (i in 1..254) {
                val ip = "$base.$i"
                if (ip == myIp) continue
                ex.execute {
                    if (found.isNotEmpty()) return@execute
                    val (c, t) = call(ip, TvLink.PORT, "/hello", null, "", 450)
                    if (c == 200) { val f = parseFound(t, ip); if (f != null && f.id.isNotEmpty()) found[f.id] = f }
                }
            }
            ex.shutdown()
            val left = ms - (System.currentTimeMillis() - t0)
            try { ex.awaitTermination(left.coerceAtLeast(500L), TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            ex.shutdownNow()
        }
        try { udp.join(500) } catch (_: InterruptedException) {}
        return found.values.toList()
    }

    /** فحص سريع للعنوان المحفوظ قبل البحث الكامل */
    fun probe(tv: Tv): Boolean {
        val (c, t) = call(tv.ip, tv.port, "/hello", null, "", 700)
        if (c != 200) return false
        val f = parseFound(t, tv.ip) ?: return false
        return f.id == tv.id
    }

    // ===== الاقتران =====
    /** @return التلفزيون المقترن، أو null مع سبب: need_code / locked / رسالة */
    fun pair(f: Found, code: String): Pair<Tv?, String> {
        val body = JSONObject().put("id", phoneId()).put("name", phoneName()).put("code", code).toString()
        val (c, t) = call(f.ip, f.port, "/pair", body, "", 4000)
        if (c == 200) {
            return try {
                val j = JSONObject(t)
                val tv = Tv(f.id, j.optString("name", f.name), f.ip, f.port, j.optString("token"))
                if (tv.token.isBlank()) Pair(null, "رد غير مفهوم") else { saveTv(tv); Pair(tv, "") }
            } catch (_: Exception) { Pair(null, "رد غير مفهوم") }
        }
        if (c == 403 && t.contains("need_code")) return Pair(null, "need_code")
        if (c == 403 && t.contains("locked")) return Pair(null, "locked")
        return Pair(null, if (c < 0) "ما قدرتش أوصل للتلفزيون" else "فشل الاقتران ($c)")
    }

    // ===== الاتصال والنبضة =====
    fun connect(ctx: Context, tv: Tv, manual: Boolean = true) {
        appRef = ctx.applicationContext
        val gen: Int
        synchronized(this) {
            loopGen++; gen = loopGen
            current = tv; state = St.CONNECTING; lastError = ""; autoOffered = false
            Cfg.put("tvlink_last", tv.id)
        }
        fire()
        RemoteService.start(ctx.applicationContext)
        Thread { loop(gen, tv, manual) }.also { it.isDaemon = true }.start()
    }

    private fun stopWith(gen: Int, msg: String) {
        synchronized(this) {
            if (gen != loopGen) return
            loopGen++; state = St.IDLE; lastError = msg
        }
        ApkServe.stop()
        fire()
    }

    private fun loop(gen: Int, tv: Tv, manual0: Boolean) {
        var manual = manual0
        var fails = 0
        var lastDisc = 0L
        while (gen == loopGen) {
            val (c, t) = call(tv.ip, tv.port, "/ping", null, tv.token, 2500, manual)
            if (gen != loopGen) return
            if (c == 200) {
                manual = false; fails = 0
                try {
                    val j = JSONObject(t)
                    tvVer = j.optLong("ver"); tvVName = j.optString("vname")
                    val nm = j.optString("name")
                    if (nm.isNotBlank() && nm != tv.name) { tv.name = nm; saveTv(tv) }
                } catch (_: Exception) {}
                if (state != St.CONNECTED) {
                    state = St.CONNECTED; lastError = ""; fire()
                    if (!autoOffered && LinkVer.code(app()) > tvVer) { autoOffered = true; offerUpdate(true) }
                }
            } else if (c == 401) {
                persist(pairedList().filter { it.id != tv.id })
                stopWith(gen, "التلفزيون مش فاكر الموبايل ده — اقترن تاني بالكود"); return
            } else if (c == 409) {
                stopWith(gen, "التلفزيون فصل الموبايل ده — دوس اتصال لو عايز ترجع"); return
            } else {
                fails++
                if (fails >= 2 && state != St.LOST) { state = St.LOST; lastError = "الاتصال انقطع — بحاول أرجّع تلقائي"; fire() }
                if (fails >= 3 && System.currentTimeMillis() - lastDisc > 20000L) {
                    lastDisc = System.currentTimeMillis()
                    val f = discover(3000L).firstOrNull { it.id == tv.id }
                    if (f != null && (f.ip != tv.ip || f.port != tv.port)) { tv.ip = f.ip; tv.port = f.port; saveTv(tv) }
                }
            }
            try { Thread.sleep(if (state == St.CONNECTED) 4000L else 3000L) } catch (_: InterruptedException) {}
        }
    }

    fun disconnect(ctx: Context) {
        val tv = current
        synchronized(this) { loopGen++; state = St.IDLE; current = null; lastError = "" }
        ApkServe.stop()
        if (tv != null) Thread { try { call(tv.ip, tv.port, "/bye", "{}", tv.token, 1500) } catch (_: Throwable) {} }.start()
        fire()
        try { RemoteService.stop(ctx.applicationContext) } catch (_: Throwable) {}
    }

    /** يوصل بآخر تلفزيون (أو الوحيد المقترن): فحص سريع للعنوان ثم بحث تلقائي. بيستنى لحد ما يتصل. (thread خلفي) */
    fun ensureConnected(ctx: Context, timeoutMs: Long = 7000L): Boolean {
        appRef = ctx.applicationContext
        if (state == St.CONNECTED) return true
        val tv = lastTv() ?: return false
        if (state == St.IDLE || current?.id != tv.id) {
            if (!probe(tv)) {
                val f = discover(3500L).firstOrNull { it.id == tv.id } ?: return false
                tv.ip = f.ip; tv.port = f.port; saveTv(tv)
            }
            connect(ctx, tv, true)
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (state == St.CONNECTED) return true
            if (state == St.IDLE) return false
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        return state == St.CONNECTED
    }

    // ===== الإرسال للتلفزيون (كلها blocking — نادّيها من thread خلفي) =====
    private fun post(path: String, body: JSONObject): Pair<Int, String> {
        val tv = current ?: return Pair(-1, "مش متصل بتلفزيون")
        return call(tv.ip, tv.port, path, body.toString(), tv.token, 4000)
    }

    /** أمر سريع من غير ما تستنى: vol_up / vol_down / mute / playpause */
    fun cmd(c: String) {
        Thread { try { post("/cmd", JSONObject().put("c", c).put("cid", UUID.randomUUID().toString())) } catch (_: Throwable) {} }.start()
    }

    /** @return null لو نجح (والـvisible بتقول لو شاشة اللانشر ظاهرة)، أو رسالة الخطأ */
    fun openLink(url: String, title: String = ""): String? {
        val (c, t) = post("/open", JSONObject().put("url", url).put("title", title))
        if (c == 200) return null
        if (c == 403) return "اللينك مش مقبول"
        return if (c < 0) "ما قدرتش أوصل للتلفزيون" else "فشل ($c)"
    }

    fun sendText(text: String): String? {
        val (c, _) = post("/type", JSONObject().put("text", text))
        return if (c == 200) null else if (c < 0) "ما قدرتش أوصل للتلفزيون" else "فشل ($c)"
    }

    fun hasKeys(): Boolean = Cfg.keys("keys").isNotEmpty() || Cfg.keys("backup").isNotEmpty() || Cfg.keys("extra").isNotEmpty()

    fun sendKeys(): String? {
        if (!hasKeys()) return "مفيش مفاتيح Gemini على الموبايل"
        val j = JSONObject()
        for (f in listOf("keys", "backup", "extra", "keymodes")) j.put(f, Cfg.str(f))
        val (c, _) = post("/keys", j)
        return if (c == 200) null else if (c < 0) "ما قدرتش أوصل للتلفزيون" else "فشل ($c)"
    }

    /** @return "ok" لو التلفزيون استلم العرض، أو رسالة */
    fun offerUpdate(auto: Boolean): String {
        val tv = current ?: return "مش متصل بتلفزيون"
        val ai = app().applicationInfo
        val f = File(ai.sourceDir)
        if (!f.isFile) return "ما لقيتش ملف التطبيق على الموبايل"
        if (ai.splitSourceDirs != null && ai.splitSourceDirs!!.isNotEmpty()) return "التطبيق متثبّت على الموبايل كـ split APK — مش هينفع يتوزّع"
        val ip = ipTowards(tv.ip)
        if (ip.isEmpty()) return "مش عارف عنوان الموبايل على الواي فاي"
        if (!ApkServe.start()) return "ما قدرتش أشغّل سيرفر التحديث"
        val url = "http://$ip:${ApkServe.PORT}/apk?t=${ApkServe.newToken()}"
        val body = JSONObject().put("ver", LinkVer.code(app())).put("vname", LinkVer.name(app())).put("url", url).put("auto", auto)
        val (c, t) = post("/offer", body)
        return when {
            c == 200 -> "ok"
            t.contains("not_newer") -> "التلفزيون عنده نفس النسخة أو أحدث"
            t.contains("declined") -> "declined"
            c < 0 -> "ما قدرتش أوصل للتلفزيون"
            else -> "فشل ($c)"
        }
    }
}

/** (v518) الموبايل بيوزّع ملف الـAPK بتاعه للتلفزيون على الواي فاي — بتوكن مؤقت، وبس لحد 20 دقيقة */
object ApkServe {
    const val PORT = 18772
    private var http: MiniHttp? = null
    @Volatile private var token = ""
    @Volatile private var until = 0L

    fun start(): Boolean {
        if (http?.running == true) return true
        val s = MiniHttp(PORT) { r -> handle(r) }
        if (!s.start()) return false
        http = s
        return true
    }

    fun newToken(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        token = b.joinToString("") { String.format("%02x", it) }
        until = System.currentTimeMillis() + 20 * 60 * 1000L
        return token
    }

    private fun handle(r: HttpReq): HttpRes {
        if (r.path != "/apk" || token.isEmpty() || r.query["t"] != token || System.currentTimeMillis() > until) return HttpRes.text("no", 403)
        val f = File(RemoteClient.app().applicationInfo.sourceDir)
        return if (f.isFile) HttpRes.file(f, "application/vnd.android.package-archive") else HttpRes.text("nf", 404)
    }

    fun stop() { try { http?.stop() } catch (_: Throwable) {}; http = null; token = "" }
}
