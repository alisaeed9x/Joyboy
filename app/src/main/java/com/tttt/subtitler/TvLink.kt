package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** (v518) رقم/اسم النسخة المثبّتة — الموبايل والتلفزيون بيقارنوا بيها للتحديث عبر الواي فاي */
object LinkVer {
    fun code(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Throwable) { 0L }

    fun name(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
}

/**
 * (v518) جانب التلفزيون من «ريموت الموبايل»:
 *  - سيرفر HTTP على المنفذ 18770 + رد على البحث التلقائي (UDP 18771) فالموبايل يلاقي التلفزيون لوحده.
 *  - اقتران: أول ريموت بيتقرن تلقائي من غير أي حاجة؛ أي ريموت بعده محتاج كود 5 أرقام بيظهر في شاشة «الريموتات».
 *  - أكتر من ريموت في نفس الوقت، كل واحد له اسمه وتوكن خاص. أي أمر بيتنفّذ بأسبقية الوصول.
 *  - نبضة (/ping) كل كام ثانية: لو وقفت أكتر من 12 ثانية الريموت يتعتبر منقطع وبيطلع إشعار.
 *  - أوامر: صوت/كتم/تشغيل-إيقاف · فتح لينك · نص للبحث · نقل مفاتيح Gemini · عرض تحديث من الموبايل.
 */
object TvLink {
    const val PORT = 18770
    const val UDP_PORT = 18771
    private const val ALIVE_MS = 12000L

    class Remote(val id: String, var name: String, var token: String) {
        @Volatile var lastSeen = 0L
        @Volatile var ip = ""
        @Volatile var kicked = false
        @Volatile var announced = false
        @Volatile var phoneVer = 0L
        fun alive(): Boolean = lastSeen > 0L && System.currentTimeMillis() - lastSeen < ALIVE_MS
    }

    class Offer(val ver: Long, val vname: String, val url: String, val from: String, val force: Boolean = false)   // (v521) force = دفع إجباري من الموبايل

    private val h = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val remotes = ConcurrentHashMap<String, Remote>()
    @Volatile private var started = false
    private var http: MiniHttp? = null
    @Volatile private var pairCode = ""
    @Volatile private var pairCodeUntil = 0L
    @Volatile private var badTries = 0
    @Volatile private var lockUntil = 0L
    @Volatile var pendingOffer: Offer? = null
    @Volatile private var offerShowing = false
    @Volatile private var declinedVer = 0L
    private var top: WeakReference<Activity>? = null
    private val seenCmd = LinkedHashSet<String>()

    val running: Boolean get() = started

    /** (v519) أكتيفيتي تطبيقنا الظاهرة دلوقتي (null = تطبيق تاني قدّام أو الشاشة مقفولة) */
    fun topActivity(): Activity? = top?.get()
    fun appCtx(): Context? = if (started) app else null

    // ===== تشغيل =====
    @Synchronized fun start(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        try { Cfg.ensure(app) } catch (_: Throwable) {}
        loadRemotes()
        val srv = MiniHttp(PORT) { r -> route(r) }
        if (!srv.start()) return
        http = srv
        started = true
        trackActivities()
        startUdp()
        startWatcher()
    }

    private fun trackActivities() {
        try {
            (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) { top = WeakReference(a); if (a is TvActivity) offerIfAny(a); TvUpdate.resumePending(a) }
                override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        } catch (e: Throwable) { LogStore.err("TvLink:track", e) }
    }

    private fun startUdp() {
        Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(UDP_PORT))
                val buf = ByteArray(512)
                while (started) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val t = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (t.startsWith("SUBTV?")) {
                        val reply = hello().toString().toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            } catch (e: Exception) { LogStore.err("TvLink:udp", e) }
        }.also { it.isDaemon = true }.start()
    }

    /** بيراقب انقطاع الريموتات (نبضة وقفت) ويطلّع إشعار «انقطع» */
    private fun startWatcher() {
        Thread {
            while (started) {
                try {
                    for (rm in remotes.values) {
                        if (rm.announced && !rm.alive()) { rm.announced = false; notice("📱 الريموت «" + rm.name + "» انقطع") }
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(2500) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    // ===== هوية التلفزيون =====
    fun tvId(): String {
        var id = Cfg.str("tvlink_id", "")
        if (id.isBlank()) { id = randHex(8); Cfg.put("tvlink_id", id) }
        return id
    }

    fun tvName(): String {
        val n = Cfg.str("tvlink_name", "")
        if (n.isNotBlank()) return n
        val m = try { Build.MODEL ?: "" } catch (_: Throwable) { "" }
        return if (m.isBlank()) "التلفزيون" else "تلفزيون $m"
    }

    private fun randHex(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b)
        return b.joinToString("") { String.format("%02x", it) }
    }

    private fun hello(): JSONObject = JSONObject()
        .put("app", "subtitler-tv").put("id", tvId()).put("name", tvName()).put("port", PORT)
        .put("ver", LinkVer.code(app)).put("vname", LinkVer.name(app)).put("needCode", remotes.isNotEmpty())

    // ===== قايمة الريموتات =====
    private fun loadRemotes() {
        try {
            val a = JSONArray(Cfg.str("tvlink_remotes", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id"); val tk = o.optString("token")
                if (id.isNotBlank() && tk.isNotBlank()) remotes[id] = Remote(id, o.optString("name", "موبايل"), tk)
            }
        } catch (_: Throwable) {}
    }

    private fun saveRemotes() {
        try {
            val a = JSONArray()
            for (r in remotes.values) a.put(JSONObject().put("id", r.id).put("name", r.name).put("token", r.token))
            Cfg.put("tvlink_remotes", a.toString())
        } catch (_: Throwable) {}
    }

    fun remoteList(): List<Remote> = remotes.values.sortedBy { it.name }

    fun kick(id: String) {
        val r = remotes[id] ?: return
        r.kicked = true; r.lastSeen = 0L
        if (r.announced) { r.announced = false; notice("📱 الريموت «" + r.name + "» اتفصل") }
    }

    fun forget(id: String) {
        val r = remotes.remove(id) ?: return
        saveRemotes()
        notice("🗑 اتمسح الريموت «" + r.name + "» من المقترنين")
    }

    /** كود إقران جديد (5 أرقام) صالح 10 دقايق — بيتعرض في شاشة «الريموتات» */
    fun newCode(): String {
        val c = (SecureRandom().nextInt(90000) + 10000).toString()
        pairCode = c; pairCodeUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        return c
    }

    fun address(): String { val i = TypeServer.ip(); return if (i.isEmpty()) "" else "$i:$PORT" }

    // ===== إشعارات التلفزيون =====
    fun notice(msg: String) {
        h.post {
            try { val a = top?.get(); if (a != null && !a.isFinishing && !a.isDestroyed) Notice.show(a, msg, 2600L) } catch (_: Throwable) {}
        }
    }

    // ===== التوجيه =====
    private fun err(code: Int, e: String) = HttpRes.json(JSONObject().put("ok", false).put("err", e).toString(), code)
    private fun ok(extra: JSONObject = JSONObject()) = HttpRes.json(extra.put("ok", true).toString())

    private fun route(r: HttpReq): HttpRes {
        if (r.path == "/hello") return HttpRes.json(hello().toString())
        if (r.path == "/pair") return pair(r)
        val rm = authOf(r) ?: return err(401, "auth")
        rm.ip = r.peer
        if (rm.kicked) { if (r.header("x-manual") == "1") rm.kicked = false else return err(409, "kicked") }
        touch(rm, r)
        return when (r.path) {
            "/ping" -> ok(JSONObject().put("ver", LinkVer.code(app)).put("vname", LinkVer.name(app)).put("name", tvName()).put("tv", tvId())
                .put("ctx", TvInput.context()).put("acc", TvAccess.connected))
            "/cmd" -> cmd(r)
            "/key" -> keyCmd(r)
            "/ptr" -> ptrCmd(r)
            "/player" -> playerCmd(r)
            "/open" -> open(r)
            "/type" -> typeText(r)
            "/keys" -> keys(r, rm)
            "/offer" -> offer(r, rm)
            "/bye" -> { rm.announced = false; rm.lastSeen = 0L; notice("📱 الريموت «" + rm.name + "» فصل"); ok() }
            else -> err(404, "nf")
        }
    }

    private fun authOf(r: HttpReq): Remote? {
        val t = r.header("x-token")
        if (t.isEmpty()) return null
        return remotes.values.firstOrNull { it.token == t }
    }

    private fun touch(rm: Remote, r: HttpReq) {
        rm.lastSeen = System.currentTimeMillis()
        r.header("x-ver").toLongOrNull()?.let { rm.phoneVer = it }
        val nm = r.header("x-name")
        if (nm.isNotEmpty()) try { val d = URLDecoder.decode(nm, "UTF-8"); if (d.isNotBlank() && d != rm.name) { rm.name = d; saveRemotes() } } catch (_: Exception) {}
        if (!rm.announced) { rm.announced = true; notice("📱 الريموت «" + rm.name + "» اتصل") }
    }

    private fun pair(r: HttpReq): HttpRes {
        val now = System.currentTimeMillis()
        if (now < lockUntil) return err(403, "locked")
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val id = j.optString("id"); val nm = j.optString("name").ifBlank { "موبايل" }; val code = j.optString("code")
        if (id.isBlank()) return err(403, "bad")
        val codeOk = code.isNotEmpty() && code == pairCode && now < pairCodeUntil
        val first = remotes.isEmpty()
        if (!first && !codeOk) {
            if (code.isNotEmpty()) { badTries++; if (badTries >= 8) { badTries = 0; lockUntil = now + 60000L } }
            return err(403, "need_code")
        }
        badTries = 0
        val tok = randHex(24)
        val ex = remotes[id]
        if (ex != null) { ex.token = tok; ex.name = nm; ex.kicked = false } else remotes[id] = Remote(id, nm, tok)
        saveRemotes()
        if (codeOk) pairCode = ""   // الكود استُخدم
        notice("📱 اتقرن ريموت جديد: «$nm»")
        return ok(JSONObject().put("token", tok).put("tv", tvId()).put("name", tvName()))
    }

    // ===== الأوامر =====
    private fun cmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val c = j.optString("c"); val cid = j.optString("cid")
        if (cid.isNotEmpty()) synchronized(seenCmd) {
            if (!seenCmd.add(cid)) return ok(JSONObject().put("dup", true))   // نفس الأمر وصل مرتين
            if (seenCmd.size > 60) { val it = seenCmd.iterator(); it.next(); it.remove() }
        }
        exec(c)
        return ok()
    }

    private fun dup(cid: String): Boolean {
        if (cid.isEmpty()) return false
        synchronized(seenCmd) {
            if (!seenCmd.add(cid)) return true
            if (seenCmd.size > 60) { val it = seenCmd.iterator(); it.next(); it.remove() }
        }
        return false
    }

    /** (v519) زرار ريموت: up/down/left/right/ok/back/home/menu/recents/ff/rew/next/prev */
    private fun keyCmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val ctx = JSONObject().put("ctx", TvInput.context()).put("acc", TvAccess.connected)
        if (dup(j.optString("cid"))) return ok(ctx.put("dup", true))
        val k = j.optString("k")
        if (k.isEmpty()) return err(403, "empty")
        TvInput.key(k)
        return ok(ctx)
    }

    /** (v520) مشغّل الفيديو: a = state أو أمر (toggle/seek/seekto/prev/next/speed/fit/cc/scale/shift/audio/quality/translate/more) و v = قيمة */
    private fun playerCmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val a = j.optString("a")
        if (a != "state" && a.isNotEmpty()) {
            if (dup(j.optString("cid"))) return ok(JSONObject().put("dup", true))
            if (!PlayerActivity.remoteAct(a, j.optLong("v"))) return ok(JSONObject().put("none", true))
        }
        val st = PlayerActivity.remoteState() ?: return ok(JSONObject().put("none", true))
        return ok(JSONObject().put("st", st))
    }

    /** (v519) تاتش باد: mv / tap / long / scroll (dx, dy نسبة من حجم التاتش باد) */
    private fun ptrCmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val op = j.optString("op")
        if (op.isEmpty()) return err(403, "empty")
        TvInput.ptr(op, j.optDouble("dx", 0.0).toFloat(), j.optDouble("dy", 0.0).toFloat())
        return ok(JSONObject().put("ctx", TvInput.context()).put("acc", TvAccess.connected))
    }

    /** أوامر الصوت والتشغيل — بتتنفّذ على الـUI thread */
    fun exec(c: String) {
        h.post {
            try {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                when (c) {
                    "vol_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "vol_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    "playpause" -> { if (!PlayerActivity.remoteToggle()) { if (MusicEngine.isPlaying) MusicEngine.pause() else MusicEngine.resume() } }
                }
            } catch (e: Throwable) { LogStore.err("TvLink:exec", e) }
        }
    }

    // ===== فتح لينك على التلفزيون =====
    private fun open(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val u = j.optString("url").trim()
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return err(403, "badurl")
        val title = j.optString("title")
        val visible = top?.get() != null
        h.post {
            try {
                app.startActivity(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(Links.EX_URL, u).putExtra(Links.EX_HOW, Links.AUTO).putExtra(Links.EX_TITLE, title))
            } catch (e: Throwable) { LogStore.err("TvLink:open", e) }
        }
        return ok(JSONObject().put("visible", visible))
    }

    private fun typeText(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val t = j.optString("text")
        if (t.isEmpty()) return err(403, "empty")
        TypeServer.lastText = t; TypeServer.lastAt = System.currentTimeMillis()
        notice("⌨ وصل نص من الموبايل")
        return ok()
    }

    // ===== نقل مفاتيح Gemini من الموبايل =====
    private fun keys(r: HttpReq, rm: Remote): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        var n = 0
        for (f in listOf("keys", "backup", "extra", "keymodes")) {
            val v = j.optString(f)
            if (v.isNotBlank()) { Cfg.put(f, v); n++ }
        }
        if (n == 0) return err(403, "empty")
        notice("🔑 وصلت مفاتيح Gemini من «" + rm.name + "»")
        return ok(JSONObject().put("n", n))
    }

    // ===== تحديث من الموبايل =====
    private fun offer(r: HttpReq, rm: Remote): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val ver = j.optLong("ver"); val url = j.optString("url"); val vname = j.optString("vname")
        if (!url.startsWith("http://", true)) return err(403, "badurl")
        val force = j.optBoolean("force")
        if (force) { if (ver < LinkVer.code(app)) return err(403, "older") }   // (v521) الإجباري بيعدّي نفس النسخة (إعادة تثبيت) بس أندرويد مبيسمحش بنزول لنسخة أقدم
        else if (ver <= LinkVer.code(app)) return err(403, "not_newer")
        if (!force && j.optBoolean("auto") && ver == declinedVer) return err(403, "declined")
        pendingOffer = Offer(ver, vname, url, rm.name, force)
        val a = top?.get()
        h.post { val t = top?.get(); if (t is TvActivity) offerIfAny(t) }
        return ok(JSONObject().put("shown", a is TvActivity))
    }

    private fun offerIfAny(a: Activity) {
        val o = pendingOffer ?: return
        if (offerShowing || a.isFinishing || a.isDestroyed) return
        if (if (o.force) o.ver < LinkVer.code(app) else o.ver <= LinkVer.code(app)) { pendingOffer = null; return }
        offerShowing = true
        TvUpdate.ask(a, o) { accepted ->
            offerShowing = false
            pendingOffer = null
            if (!accepted && !o.force) declinedVer = o.ver
        }
    }
}
