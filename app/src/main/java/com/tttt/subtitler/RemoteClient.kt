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
    /** (v519) إيه اللي ظاهر على التلفزيون (home/player/browser/app/other) وهل خدمة «إمكانية الوصول» شغّالة عليه */
    @Volatile var tvCtx = ""
        private set
    @Volatile var tvAcc = false
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
                    parseCtx(j)
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
    internal fun postJson(path: String, body: JSONObject): Pair<Int, String> = post(path, body)

    /** (v543 م3) GET بيرجّع bytes (الصور المصغّرة) — blocking، نادّيها من thread خلفي بس */
    internal fun getBytes(path: String, ms: Int = 8000): Pair<Int, ByteArray> {
        val tv = current ?: return Pair(-1, ByteArray(0))
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://" + tv.ip + ":" + tv.port + path).openConnection() as HttpURLConnection
            c.connectTimeout = ms; c.readTimeout = ms; c.useCaches = false
            if (tv.token.isNotEmpty()) c.setRequestProperty("X-Token", tv.token)
            c.setRequestProperty("X-Ver", LinkVer.code(app()).toString())
            c.setRequestProperty("X-Name", URLEncoder.encode(phoneName(), "UTF-8"))
            val code = c.responseCode
            val ins = if (code in 200..299) c.inputStream else c.errorStream
            Pair(code, if (ins != null) ins.use { it.readBytes() } else ByteArray(0))
        } catch (e: Exception) { Pair(-1, ByteArray(0)) } finally { try { c?.disconnect() } catch (_: Exception) {} }
    }

    /** (v543 م3) GET نصّي (ردود JSON بتاعة /lib/…) */
    internal fun getText(path: String, ms: Int = 8000): Pair<Int, String> {
        val (c, b) = getBytes(path, ms)
        return Pair(c, String(b, Charsets.UTF_8))
    }

    private fun post(path: String, body: JSONObject): Pair<Int, String> {
        val tv = current ?: return Pair(-1, "مش متصل بتلفزيون")
        return call(tv.ip, tv.port, path, body.toString(), tv.token, 4000)
    }

    private fun parseCtx(j: JSONObject) {
        val c = j.optString("ctx"); val a = j.optBoolean("acc")
        if (c.isNotEmpty() && (c != tvCtx || a != tvAcc)) { tvCtx = c; tvAcc = a; fire() }
    }

    /** (v519) زرار ريموت (up/down/left/right/ok/back/home/menu/recents/ff/rew/next/prev) — من غير ما تستنى */
    fun key(k: String) {
        Thread {
            try {
                val (c, t) = post("/key", JSONObject().put("k", k).put("cid", UUID.randomUUID().toString()))
                if (c == 200) try { parseCtx(JSONObject(t)) } catch (_: Exception) {}
            } catch (_: Throwable) {}
        }.also { it.isDaemon = true }.start()
    }

    /** (v520) أمر/استعلام مشغّل الفيديو (blocking). بيرجّع حالة المشغّل، أو null لو مفيش مشغّل/فشل */
    fun player(a: String, v: Long = 0L): JSONObject? {
        val (c, t) = post("/player", JSONObject().put("a", a).put("v", v).put("cid", UUID.randomUUID().toString()))
        if (c != 200) return null
        return try { JSONObject(t).optJSONObject("st") } catch (_: Exception) { null }
    }

    // تجميع حركة التاتش باد: بنبعت كل ~15ms مجموع الحركة بدل طلب لكل لمسة
    private val pMv = FloatArray(2)
    private val pSc = FloatArray(2)
    private var pBusy = false

    /** حركة المؤشر (dx/dy نسبة من حجم التاتش باد) */
    fun ptrMove(dx: Float, dy: Float) { synchronized(pMv) { pMv[0] += dx; pMv[1] += dy }; pump() }

    /** تمرير بإصبعين */
    fun ptrScroll(dx: Float, dy: Float) { synchronized(pMv) { pSc[0] += dx; pSc[1] += dy }; pump() }

    /** tap / long */
    fun ptrClick(op: String) {
        Thread { try { post("/ptr", JSONObject().put("op", op)) } catch (_: Throwable) {} }.also { it.isDaemon = true }.start()
    }

    private fun pump() {
        synchronized(pMv) { if (pBusy) return; pBusy = true }
        Thread {
            try {
                while (true) {
                    var mx = 0f; var my = 0f; var sx = 0f; var sy = 0f
                    synchronized(pMv) {
                        mx = pMv[0]; my = pMv[1]; sx = pSc[0]; sy = pSc[1]
                        pMv[0] = 0f; pMv[1] = 0f; pSc[0] = 0f; pSc[1] = 0f
                        if (mx == 0f && my == 0f && sx == 0f && sy == 0f) { pBusy = false; return@Thread }
                    }
                    if (mx != 0f || my != 0f) post("/ptr", JSONObject().put("op", "mv").put("dx", mx.toDouble()).put("dy", my.toDouble()))
                    if (sx != 0f || sy != 0f) post("/ptr", JSONObject().put("op", "scroll").put("dx", sx.toDouble()).put("dy", sy.toDouble()))
                    try { Thread.sleep(12L) } catch (_: InterruptedException) {}
                }
            } catch (_: Throwable) { synchronized(pMv) { pBusy = false } }
        }.also { it.isDaemon = true }.start()
    }

    /** أمر سريع من غير ما تستنى: vol_up / vol_down / mute / playpause */
    fun cmd(c: String) {
        Thread { try { post("/cmd", JSONObject().put("c", c).put("cid", UUID.randomUUID().toString())) } catch (_: Throwable) {} }.start()
    }

    /** @return null لو نجح (والـvisible بتقول لو شاشة اللانشر ظاهرة)، أو رسالة الخطأ */
    fun openLink(url: String, title: String = "", how: String = "", ref: String = "", ua: String = "", cookie: String = "", startMs: Long = 0L): String? {
        val body = JSONObject().put("url", url).put("title", title).put("how", how).put("ref", ref).put("ua", ua).put("cookie", cookie)   // (v533) ref/ua/cookie للينكات المصطادة من المتصفح
        if (startMs > 0L) body.put("startms", startMs)   // (v543) كمّل من نفس المكان
        val (c, t) = post("/open", body)
        if (c == 200) return null
        if (c == 403) return "اللينك مش مقبول"
        return if (c < 0) "ما قدرتش أوصل للتلفزيون" else "فشل ($c)"
    }

    /** search=true (v533): التلفزيون يخرج من أي مكان (مشغّل/متصفح/تطبيق) ويفتح الرئيسية ويبحث في شريط البحث */
    fun sendText(text: String, search: Boolean = false): String? {
        val (c, _) = post("/type", JSONObject().put("text", text).put("search", search))
        return if (c == 200) null else if (c < 0) "ما قدرتش أوصل للتلفزيون" else "فشل ($c)"
    }

    fun hasKeys(): Boolean = Cfg.keys("keys").isNotEmpty() || Cfg.keys("backup").isNotEmpty() || Cfg.keys("extra").isNotEmpty()

    /** (v522) كل إعدادات الترجمة اللي بتتنقل: مفاتيح Gemini + المزوّدين الخارجيين + الموديلات */
    /** (v529) «أماكن» المفاتيح: أساسي/احتياطي/إضافي/بصري + عدد الأساسي والاحتياطي. بتتنقل زي ما هي حتى لو فاضية (عشان القديم على التلفزيون يتمسح ومفتاح ما يفضلش في مكان غلط) */
    val PLACE_FIELDS = listOf("keys", "backup", "extra", "keymodes", "viskeys", "nmain", "nback")
    val KEY_FIELDS = listOf("keys", "backup", "extra", "keymodes", "nmain", "nback", "viskeys", "prefine", "vis_auto", "model", "libre_url", "libre_key", "fl_gem_key", "fl_groq_key", "fl_url", "fl_srv_key", "fl_srv_model", "audd_token", "tx_order", "ext_primary", "ext_strict", "tr_s_model", "vc_model", "off_model")

    fun sendKeys(): String? {
        if (!hasKeys()) return "مفيش مفاتيح Gemini على الموبايل"
        val j = JSONObject()
        for (f in KEY_FIELDS) {
            var v = when (f) {
                "model" -> Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }   // (v529) الموديل الفعلي المستخدم (حتى لو الموبايل ماسجّلوش صراحةً)
                "prefine" -> Cfg.str("prefine", "1")
                "vis_auto" -> Cfg.str("vis_auto", "1")
                else -> Cfg.str(f)
            }
            if (v.isBlank() && (f == "nmain" || f == "nback")) v = "0"
            if (v.isBlank() && f == "keymodes") v = "[]"
            if (f in PLACE_FIELDS) j.put(f, v) else if (v.isNotBlank()) j.put(f, v)
        }
        j.put("place", true)
        val (c, _) = post("/keys", j)
        return if (c == 200) null else if (c < 0) "ما قدرتش أوصل للتلفزيون" else "فشل ($c)"
    }

    /** (v522) مشغّل الموسيقى على التلفزيون: state أو أمر (toggle/next/prev/seek/seekto/shuffle/mode). بيرجّع الحالة أو null */
    fun music(a: String, v: Long = 0L): JSONObject? {
        val (c, t) = post("/music", JSONObject().put("a", a).put("v", v).put("cid", UUID.randomUUID().toString()))
        if (c != 200) return null
        return try { JSONObject(t).optJSONObject("st") } catch (_: Exception) { null }
    }

    fun musicLyrics(): String? {
        val (c, t) = post("/music", JSONObject().put("a", "lyrics"))
        if (c != 200) return null
        return try { JSONObject(t).optString("lyrics") } catch (_: Exception) { null }
    }


    /** (v521) «📤 ادفع التحديث للتلفزيون» من ⋯ المزيد: بيتصل لو لازم وبعدين يبعت العرض إجباري (من غير مقارنة نسخ) */
    fun forcePushFlow(act: android.app.Activity) {
        try { init(act) } catch (_: Throwable) {}
        if (lastTv() == null && current == null) {
            Notice.show(act, "مفيش تلفزيون مقترن — افتح 📱 الريموت واقترن الأول", 3500L)
            try { act.startActivity(android.content.Intent(act, RemoteActivity::class.java)) } catch (_: Throwable) {}
            return
        }
        Notice.show(act, "📺 بوصّل بالتلفزيون وببعت التحديث…", 5000L)
        Thread {
            var msg: String
            try {
                msg = if (!ensureConnected(act, 9000L)) "⚠ ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else when (val r = offerUpdate(false, true)) {
                    "ok" -> "✅ التحديث اتبعت — شوف رسالة التحديث على التلفزيون (افتح اللانشر لو مش ظاهرة)"
                    else -> "⚠ $r"
                }
            } catch (e: Throwable) { LogStore.err("Remote:forcePush", e); msg = "⚠ حصلت مشكلة" }
            main.post { Notice.show(act, msg, 5000L) }
        }.also { it.isDaemon = true }.start()
    }

    /** @return "ok" لو التلفزيون استلم العرض، أو رسالة */
    fun offerUpdate(auto: Boolean, force: Boolean = false): String {
        ApkServe.custom = null
        val tv = current ?: return "مش متصل بتلفزيون"
        val ai = app().applicationInfo
        val f = File(ai.sourceDir)
        if (!f.isFile) return "ما لقيتش ملف التطبيق على الموبايل"
        if (ai.splitSourceDirs != null && ai.splitSourceDirs!!.isNotEmpty()) return "التطبيق متثبّت على الموبايل كـ split APK — مش هينفع يتوزّع"
        val ip = ipTowards(tv.ip)
        if (ip.isEmpty()) return "مش عارف عنوان الموبايل على الواي فاي"
        if (!ApkServe.start()) return "ما قدرتش أشغّل سيرفر التحديث"
        val url = "http://$ip:${ApkServe.PORT}/apk?t=${ApkServe.newToken()}"
        val body = JSONObject().put("ver", LinkVer.code(app())).put("vname", LinkVer.name(app())).put("url", url).put("auto", auto).put("force", force)
        val (c, t) = post("/offer", body)
        return when {
            c == 200 -> "ok"
            t.contains("not_newer") -> "التلفزيون عنده نفس النسخة أو أحدث"
            t.contains("older") -> "نسخة الموبايل أقدم من اللي على التلفزيون — أندرويد مش هيسمح بالنزول لنسخة أقدم"
            t.contains("declined") -> "declined"
            c < 0 -> "ما قدرتش أوصل للتلفزيون"
            else -> "فشل ($c)"
        }
    }
}

/** (v540) يبعت أي APK (مثلاً اللانشر) للتلفزيون: الحزمة والنسخة بتتقرا من الملف نفسه، والتلفزيون بيقبله بس لو الحزمة بتاعته */
fun RemoteClient.offerApk(f: File, force: Boolean): String {
    val tv = current ?: return "مش متصل بتلفزيون"
    val pi = try { app().packageManager.getPackageArchiveInfo(f.path, 0) } catch (_: Throwable) { null } ?: return "الملف ده مش APK سليم"
    val ver = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    val ip = ipTowards(tv.ip)
    if (ip.isEmpty()) return "مش عارف عنوان الموبايل على الواي فاي"
    ApkServe.custom = f
    if (!ApkServe.start()) return "ما قدرتش أشغّل سيرفر التحديث"
    val url = "http://$ip:${ApkServe.PORT}/apk?t=${ApkServe.newToken()}"
    val body = JSONObject().put("ver", ver).put("vname", pi.versionName ?: "").put("pkg", pi.packageName).put("url", url).put("auto", false).put("force", force)
    val (c, t) = postJson("/offer", body)
    return when {
        c == 200 -> "ok"
        t.contains("wrongapp") -> "الحزمة (${pi.packageName}) مش بتاعة التطبيق اللي على التلفزيون"
        t.contains("not_newer") -> "التلفزيون عنده نفس النسخة أو أحدث (فعّل الدفع الإجباري لو عايز تعيد التثبيت)"
        t.contains("older") -> "النسخة أقدم من اللي على التلفزيون — أندرويد مش هيسمح بالنزول"
        c < 0 -> "ما قدرتش أوصل للتلفزيون"
        else -> "فشل ($c)"
    }
}

/** (v518) الموبايل بيوزّع ملف الـAPK بتاعه للتلفزيون على الواي فاي — بتوكن مؤقت، وبس لحد 20 دقيقة */
object ApkServe {
    const val PORT = 18772
    private var http: MiniHttp? = null
    @Volatile private var token = ""
    @Volatile private var until = 0L
    @Volatile var custom: File? = null   // (v540) لو متحدد: بيتوزّع بدل APK التطبيق نفسه

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
        val f = custom ?: File(RemoteClient.app().applicationInfo.sourceDir)
        return if (f.isFile) HttpRes.file(f, "application/vnd.android.package-archive") else HttpRes.text("nf", 404)
    }

    fun stop() { try { http?.stop() } catch (_: Throwable) {}; http = null; token = "" }
}

/** (v529) لما مفاتيح توصل من الموبايل والإعدادات مفتوحة: Main بيسجّل هنا دالة تعيد تحميل الخانات من Cfg (من غير ما الحفظ القديم يمسح الجديد) */
object KeysSync { @Volatile var reload: (() -> Unit)? = null }
