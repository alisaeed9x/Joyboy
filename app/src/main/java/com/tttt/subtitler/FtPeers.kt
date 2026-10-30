package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * (v653) نقل الملفات الذكي — خدمة الأجهزة:
 *  • اكتشاف: رد على بث UDP (18772) + سيرفر HTTP (18773) — والبديل لو الراوتر بيمنع البث: مسح الشبكة /24.
 *  • استقبال عرض نقل (/ft/offer): بيتحقق من توقيع المرسل، وبيستنى موافقة المستخدم (أو جهاز موثوق حدّده بنفسه).
 *  • تقديم الأجزاء المشفّرة (/ft/c) والهاش (/ft/h) للجهاز اللي وافق — كل طلب موقّع بمفتاح الجلسة.
 *  التلفزيون نفس الشيء: نفس الـAPK ← بيشغّلها TvHome، والموبايل بيشغّلها مع شاشة النقل.
 */
object FtPeers {
    const val PORT = 18773
    const val UDP_PORT = 18772
    const val VER = 1
    const val OFFER_WAIT_MS = 100_000L

    @Volatile private var started = false
    @Volatile private var app: Context? = null
    @Volatile private var udp: DatagramSocket? = null
    private var http: MiniHttp? = null
    private var tracking = false

    val running: Boolean get() = started

    @Synchronized fun start(ctx: Context): Boolean {
        if (started) return true
        val a = ctx.applicationContext
        app = a
        try { FtIdentity.init(a); FtStore.init(a); FtCrypto.identity(a) } catch (e: Throwable) { LogStore.err("FtPeers:init", e); return false }
        val srv = MiniHttp(PORT) { r -> route(r) }
        if (!srv.start()) return false
        http = srv
        started = true
        trackTop(a)
        startUdp()
        return true
    }

    @Synchronized fun stop() {
        started = false
        try { http?.stop() } catch (_: Throwable) {}
        http = null
        try { udp?.close() } catch (_: Throwable) {}
        udp = null
    }

    fun err(code: Int, e: String): HttpRes = HttpRes.json(JSONObject().put("ok", false).put("err", e).toString(), code)
    fun ok(x: JSONObject = JSONObject()): HttpRes = HttpRes.json(x.put("ok", true).toString())

    private fun trackTop(a: Context) {
        if (tracking) return
        tracking = true
        try {
            (a as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(act: Activity) { FtHub.topAct = WeakReference(act) }
                override fun onActivityPaused(act: Activity) { if (FtHub.topAct?.get() === act) FtHub.topAct = null }
                override fun onActivityCreated(act: Activity, b: Bundle?) {}
                override fun onActivityStarted(act: Activity) {}
                override fun onActivityStopped(act: Activity) {}
                override fun onActivitySaveInstanceState(act: Activity, b: Bundle) {}
                override fun onActivityDestroyed(act: Activity) {}
            })
        } catch (e: Throwable) { LogStore.err("FtPeers:track", e) }
    }

    // ───────── هوية الجهاز (hello) ─────────
    fun hello(): JSONObject {
        val a = app ?: throw IllegalStateException("FtPeers not started")
        val kp = FtCrypto.identity(a)
        return JSONObject().put("app", "joyboy-ft").put("v", VER).put("id", FtIdentity.id()).put("name", FtIdentity.name())
            .put("type", FtIdentity.type()).put("port", PORT).put("pub", FtCrypto.b64(kp.public.encoded))
    }

    private fun parsePeer(txt: String, ip: String, rtt: Int): FtPeer? {
        return try {
            val j = JSONObject(txt)
            if (j.optString("app") != "joyboy-ft") return null
            val pub = j.optString("pub")
            val raw = FtCrypto.unb64(pub)
            val id = j.optString("id")
            if (id.isEmpty() || raw.isEmpty()) return null
            // البصمة بتتحسب من المفتاح نفسه — مش بنصدّق أي حقل بيقولها
            FtPeer(id, FtIdentity.sanitize(j.optString("name")).ifEmpty { "Joyboy" }, j.optString("type", "phone"), ip, j.optInt("port", PORT), FtCrypto.fingerprint(raw), pub, j.optInt("v", 1), rtt, true)
        } catch (_: Throwable) { null }
    }

    // ───────── الرد على البحث ─────────
    private fun startUdp() {
        Thread {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(UDP_PORT))
                udp = s
                val buf = ByteArray(512)
                while (started) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val t = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (t.startsWith("JBFT?")) {
                        val reply = hello().toString().toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            } catch (e: Exception) { if (started) LogStore.err("FtPeers:udp", e) } finally { try { s?.close() } catch (_: Throwable) {} }
        }.also { it.isDaemon = true }.start()
    }

    // ───────── البحث (blocking — من خيط خلفي) ─────────
    /** بيدوّر على الأجهزة اللي شغّال عليها النقل الذكي + التلفزيونات اللي بتدعم الربط القديم. onFound بيتنادى من خيط خلفي كل ما يظهر جهاز */
    fun discover(ctx: Context, ms: Long = 3800L, onFound: ((FtPeer) -> Unit)? = null): List<FtPeer> {
        val a = ctx.applicationContext
        try { FtIdentity.init(a) } catch (_: Throwable) {}
        val me = FtIdentity.id()
        val found = ConcurrentHashMap<String, FtPeer>()
        val links = ConcurrentHashMap<String, RemoteClient.Found>()
        val t0 = System.currentTimeMillis()
        val myIp = RemoteClient.ipTowards("")
        val base = myIp.substringBeforeLast('.', "")

        fun add(p: FtPeer) {
            if (p.id == me) return
            val prev = found.put(p.id, p)
            if (prev == null) { try { onFound?.invoke(p) } catch (_: Throwable) {} }
        }

        // 1) بث UDP
        val udpT = Thread {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket()
                s.broadcast = true; s.soTimeout = 250
                val msg = "JBFT?".toByteArray(Charsets.UTF_8)
                val targets = ArrayList<InetAddress>()
                try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
                if (base.isNotEmpty()) try { targets.add(InetAddress.getByName("$base.255")) } catch (_: Exception) {}
                val buf = ByteArray(1024)
                var lastSend = 0L
                while (System.currentTimeMillis() - t0 < ms) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 700L) { lastSend = now; for (t in targets) try { s.send(DatagramPacket(msg, msg.size, t, UDP_PORT)) } catch (_: Exception) {} }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val rtt = (System.currentTimeMillis() - lastSend).toInt().coerceIn(1, 5000)
                        val f = parsePeer(String(p.data, 0, p.length, Charsets.UTF_8), p.address.hostAddress ?: "", rtt)
                        if (f != null) add(f)
                    } catch (_: SocketTimeoutException) {}
                }
            } catch (_: Exception) {} finally { try { s?.close() } catch (_: Exception) {} }
        }
        udpT.isDaemon = true; udpT.start()

        // 2) تلفزيونات الربط القديم (بتشتغل بالتوازي)
        val linkT = Thread {
            try { for (f in RemoteClient.discover(ms)) links[f.ip] = f } catch (_: Throwable) {}
        }
        linkT.isDaemon = true; linkT.start()

        // 3) لو البث ما لقاش حاجة في 1.6 ثانية: مسح الشبكة (بيشتغل حتى لو الراوتر بيمنع البث)
        while (System.currentTimeMillis() - t0 < 1600L && found.isEmpty()) try { Thread.sleep(100) } catch (_: InterruptedException) {}
        if (found.isEmpty() && base.isNotEmpty()) {
            val ex = Executors.newFixedThreadPool(40)
            for (i in 1..254) {
                val ip = "$base.$i"
                if (ip == myIp) continue
                ex.execute {
                    val t1 = System.currentTimeMillis()
                    val (c, t) = FtNet.call(ip, PORT, "/ft/hello", null, 450)
                    if (c == 200) { val f = parsePeer(t, ip, (System.currentTimeMillis() - t1).toInt().coerceIn(1, 5000)); if (f != null) add(f) }
                }
            }
            ex.shutdown()
            val left = ms - (System.currentTimeMillis() - t0)
            try { ex.awaitTermination(left.coerceAtLeast(600L), TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            ex.shutdownNow()
        }
        try { udpT.join(ms + 800L) } catch (_: InterruptedException) {}
        try { linkT.join(1500L) } catch (_: InterruptedException) {}

        // دمج: تلفزيون ظاهر بالاتنين = جهاز واحد فيه الاتنين، وتلفزيون قديم = ظاهر بالربط بس
        val out = ArrayList<FtPeer>()
        val usedLinks = HashSet<String>()
        for (p in found.values) {
            val l = links[p.ip]
            if (l != null) usedLinks.add(p.ip)
            out.add(if (l != null) FtPeer(p.id, p.name, p.type, p.ip, p.port, p.fp, p.pub, p.ver, p.rtt, true, l) else p)
        }
        for ((ip, l) in links) {
            if (ip in usedLinks) continue
            val p = FtPeer("tv:" + l.id, l.name, "tv", ip, l.port, "", "", 0, 0, false, l)
            out.add(p)
            try { onFound?.invoke(p) } catch (_: Throwable) {}
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** فحص سريع إن الجهاز لسه موجود (blocking) */
    fun ping(p: FtPeer): Boolean {
        if (!p.ft) return true
        val (c, t) = FtNet.call(p.ip, p.port, "/ft/hello", null, 900)
        if (c != 200) return false
        val f = parsePeer(t, p.ip, 0) ?: return false
        return f.id == p.id && f.fp == p.fp
    }

    // ───────── التوجيه ─────────
    private fun route(r: HttpReq): HttpRes {
        val a = app ?: return err(503, "off")
        if (!r.path.startsWith("/ft/")) return err(404, "nf")
        return when (r.path) {
            "/ft/hello" -> HttpRes.json(hello().toString())
            "/ft/offer" -> FtIn.offer(a, r)
            "/ft/c" -> FtOut.chunk(r)
            "/ft/h" -> FtOut.hash(r)
            "/ft/d" -> FtOut.fileDone(r)
            "/ft/x" -> FtOut.cancel(r)
            else -> err(404, "nf")
        }
    }
}

/** (المستقبِل) استقبال عرض النقل ← موافقة المستخدم ← تبادل المفاتيح */
object FtIn {
    fun offer(app: Context, r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Throwable) { return FtPeers.err(400, "bad") }
        if (j.optInt("v") != FtPeers.VER) return FtPeers.err(400, "ver")
        val sid = j.optString("sid")
        if (sid.length != 32 || !sid.all { it in '0'..'9' || it in 'a'..'f' }) return FtPeers.err(400, "sid")
        if (FtHub.find(sid) != null) return FtPeers.err(409, "dup")
        val from = j.optJSONObject("from") ?: return FtPeers.err(400, "from")
        val pubB = try { FtCrypto.unb64(from.optString("pub")) } catch (_: Throwable) { return FtPeers.err(400, "pub") }
        val epubS = j.optString("epub")
        val pub = try { FtCrypto.pubOf(pubB) } catch (_: Throwable) { return FtPeers.err(400, "pub") }
        val sig = try { FtCrypto.unb64(j.optString("sig")) } catch (_: Throwable) { return FtPeers.err(400, "sig") }
        // تحقق من هوية المرسل: التوقيع لازم يكون من صاحب المفتاح العام اللي بعته
        if (!FtCrypto.verify(pub, (sid + epubS).toByteArray(Charsets.UTF_8), sig)) return FtPeers.err(403, "sig")
        val epubSb = try { FtCrypto.unb64(epubS) } catch (_: Throwable) { return FtPeers.err(400, "epub") }
        val senderEph = try { FtCrypto.pubOf(epubSb) } catch (_: Throwable) { return FtPeers.err(400, "epub") }
        val fp = FtCrypto.fingerprint(pubB)

        val arr = j.optJSONArray("files") ?: return FtPeers.err(400, "files")
        if (arr.length() == 0 || arr.length() > FtOut.MAX_FILES) return FtPeers.err(400, "files")
        val files = ArrayList<FtFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return FtPeers.err(400, "files")
            val rel = FtPath.cleanRel(o.optString("n"))
            val sz = o.optLong("s", -1L)
            if (rel.isEmpty() || sz < 0L) return FtPeers.err(400, "files")
            files.add(FtFile(i, rel, sz, XCat.catOf(rel), null))
        }
        if (FtHub.pendingIncoming().size >= 4) return FtPeers.err(409, "busy")

        val peerName = FtIdentity.sanitize(from.optString("name")).ifEmpty { "Joyboy" }
        val s = FtSession(sid, false, from.optString("id"), peerName, from.optString("type", "phone"), r.peer, from.optInt("port", FtPeers.PORT), fp, files)
        s.retry = j.optBoolean("retry")
        s.status = FtSession.WAITING
        s.msg = "طلب استقبال"
        // استقبال تلقائي بس من جهاز المستخدم حدّده «موثوق» بنفسه ولسه بنفس البصمة، ولو فعّل الإعداد ده
        if (FtIdentity.autoTrusted() && FtStore.isTrusted(s.peerId, fp)) { s.decision = 1; s.autoAccepted = true }
        FtHub.add(s)
        if (!s.autoAccepted) FtNotify.incoming(app, s) else FtHub.changed()

        val until = System.currentTimeMillis() + FtPeers.OFFER_WAIT_MS
        while (s.decision == 0 && !s.cancelled && System.currentTimeMillis() < until) { try { Thread.sleep(200) } catch (_: InterruptedException) {} }
        FtNotify.clear(app, s)

        if (s.decision != 1 || s.cancelled || s.acceptedFiles.isEmpty()) {
            val timeout = s.decision == 0 && !s.cancelled
            s.finish(if (s.cancelled) FtSession.CANCELLED else FtSession.REJECTED, if (timeout) "انتهت المهلة من غير رد" else if (s.cancelled) "المرسل ألغى" else "رفضت الاستقبال")
            for (f in s.files) { if (f.state == FtState.WAIT) { f.state = FtState.CANCEL; f.err = s.msg } }
            FtLog.all(s)
            FtHub.changed()
            return FtPeers.ok(JSONObject().put("accepted", false).put("reason", if (timeout) "timeout" else "rejected"))
        }

        return try {
            val ek = FtCrypto.newKeyPair()
            val secret = FtCrypto.ecdh(ek.private, senderEph)
            val key = FtCrypto.sessionKey(secret, sid)
            val epubR = FtCrypto.b64(ek.public.encoded)
            val me = FtCrypto.identity(app)
            val myFp = FtCrypto.fingerprint(me.public.encoded)
            val sig2 = FtCrypto.sign(me.private, (sid + epubS + epubR).toByteArray(Charsets.UTF_8))
            s.sas = FtCrypto.sas(fp, myFp, epubSb, FtCrypto.unb64(epubR))
            // الملفات اللي المستخدم شالها من القايمة بتتعلّم مرفوضة
            val acc = JSONArray()
            for (f in s.files) { if (f.accepted) acc.put(f.idx) else { f.state = FtState.CANCEL; f.err = "استبعدتها" } }
            if (s.trustAfter) FtStore.trust(s.peerId, fp, s.peerName)
            s.key = key
            s.status = FtSession.RUNNING
            s.startedAt = System.currentTimeMillis()
            s.lastTouch = s.startedAt
            s.msg = "بيستقبل…"
            FtRecv.start(app, s)
            FtHub.changed()
            FtPeers.ok(JSONObject().put("accepted", true).put("epub", epubR).put("sig", FtCrypto.b64(sig2)).put("pub", FtCrypto.b64(me.public.encoded)).put("acc", acc))
        } catch (e: Throwable) {
            LogStore.err("FtIn:accept", e)
            s.finish(FtSession.FAILED, "فشل تجهيز الاتصال الآمن")
            FtHub.changed()
            FtPeers.err(500, "crypto")
        }
    }
}

/** (المرسِل) تقديم الأجزاء المشفّرة للجهاز اللي وافق */
object FtOut {
    const val MAX_FILES = 1500

    private fun sess(r: HttpReq): FtSession? {
        val s = FtHub.find(r.query["sid"] ?: "") ?: return null
        return if (s.outgoing) s else null
    }

    private fun dead(s: FtSession): Boolean = s.cancelled || s.status == FtSession.FAILED || s.status == FtSession.CANCELLED || s.status == FtSession.REJECTED

    fun chunk(r: HttpReq): HttpRes {
        val s = sess(r) ?: return FtPeers.err(404, "nf")
        val key = s.key ?: return FtPeers.err(404, "nf")
        if (dead(s)) return FtPeers.err(404, "gone")
        val fi = r.query["f"]?.toIntOrNull() ?: return FtPeers.err(400, "f")
        val n = r.query["n"]?.toLongOrNull() ?: return FtPeers.err(400, "n")
        if (!FtCrypto.safeEq(FtCrypto.tag(key, s.sid, "c", fi, n), r.header("x-ft-t"))) return FtPeers.err(403, "tag")
        val f = s.files.getOrNull(fi) ?: return FtPeers.err(404, "nf")
        val src = f.srcObj
        if (!f.accepted || src == null || n < 0L) return FtPeers.err(404, "nf")
        val start = n * FtCrypto.CHUNK
        if (start >= f.size) return FtPeers.err(416, "range")
        val len = minOf(FtCrypto.CHUNK.toLong(), f.size - start).toInt()
        val buf = ByteArray(len)
        try {
            val ins = src.open(start)
            try {
                var got = 0
                while (got < len) { val k = ins.read(buf, got, len - got); if (k < 0) break; got += k }
                if (got != len) return FtPeers.err(500, "short")
            } finally { try { ins.close() } catch (_: Throwable) {} }
        } catch (e: Throwable) { LogStore.err("FtOut:read", e); return FtPeers.err(500, "read") }
        val enc = try { FtCrypto.encChunk(key, s.sid, fi, n, buf, len) } catch (e: Throwable) { LogStore.err("FtOut:enc", e); return FtPeers.err(500, "enc") }
        if (f.state == FtState.WAIT) f.state = FtState.RUN
        if (start + len > f.done) f.done = start + len
        s.lastTouch = System.currentTimeMillis()
        return HttpRes(200, "application/octet-stream", enc)
    }

    /** SHA-256 للملف كله (بيتحسب أول مرة تتطلب وبيتخزّن) + توقيع HMAC بمفتاح الجلسة */
    fun hash(r: HttpReq): HttpRes {
        val s = sess(r) ?: return FtPeers.err(404, "nf")
        val key = s.key ?: return FtPeers.err(404, "nf")
        if (dead(s)) return FtPeers.err(404, "gone")
        val fi = r.query["f"]?.toIntOrNull() ?: return FtPeers.err(400, "f")
        if (!FtCrypto.safeEq(FtCrypto.tag(key, s.sid, "h", fi, 0L), r.header("x-ft-t"))) return FtPeers.err(403, "tag")
        val f = s.files.getOrNull(fi) ?: return FtPeers.err(404, "nf")
        val src = f.srcObj ?: return FtPeers.err(404, "nf")
        val sha: String = synchronized(f) {
            if (f.sha.isEmpty()) {
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    val ins = src.open(0L)
                    try {
                        val b = ByteArray(262144)
                        while (true) { val k = ins.read(b); if (k < 0) break; md.update(b, 0, k); s.lastTouch = System.currentTimeMillis() }
                    } finally { try { ins.close() } catch (_: Throwable) {} }
                    f.sha = FtCrypto.hex(md.digest())
                } catch (e: Throwable) { LogStore.err("FtOut:hash", e); return FtPeers.err(500, "hash") }
            }
            f.sha
        }
        return FtPeers.ok(JSONObject().put("sha", sha).put("mac", FtCrypto.hmac(key, s.sid + "|H|" + fi + "|" + sha)))
    }

    /** المستقبِل بيبلّغ: الملف خلص (ok) أو فشل (fail) */
    fun fileDone(r: HttpReq): HttpRes {
        val s = sess(r) ?: return FtPeers.err(404, "nf")
        val key = s.key ?: return FtPeers.err(404, "nf")
        val j = try { JSONObject(r.bodyText()) } catch (_: Throwable) { return FtPeers.err(400, "bad") }
        val fi = j.optInt("f", -1)
        if (!FtCrypto.safeEq(FtCrypto.tag(key, s.sid, "d", fi, 0L), r.header("x-ft-t"))) return FtPeers.err(403, "tag")
        val f = s.files.getOrNull(fi) ?: return FtPeers.err(404, "nf")
        if (j.optString("st") == "ok") { f.done = f.size; f.state = FtState.DONE; f.err = "" }
        else { f.state = FtState.FAIL; f.err = j.optString("err").take(120).ifEmpty { "فشل عند المستقبِل" } }
        s.lastTouch = System.currentTimeMillis()
        FtLog.file(s, f)
        FtHub.changed()
        return FtPeers.ok()
    }

    /** إلغاء من الطرف التاني (أي اتجاه). قبل الموافقة: بيتقبل من نفس عنوان الجهاز؛ بعدها: لازم توقيع مفتاح الجلسة */
    fun cancel(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Throwable) { return FtPeers.err(400, "bad") }
        val s = FtHub.find(j.optString("sid")) ?: return FtPeers.err(404, "nf")
        val key = s.key
        if (key == null) { if (r.peer != s.peerIp) return FtPeers.err(403, "peer") }
        else if (!FtCrypto.safeEq(FtCrypto.tag(key, s.sid, "x", 0, 0L), r.header("x-ft-t"))) return FtPeers.err(403, "tag")
        s.cancelled = true
        if (s.active) s.finish(FtSession.CANCELLED, "التاني ألغى النقل")
        for (f in s.files) { if (f.state == FtState.WAIT || f.state == FtState.RUN) { f.state = FtState.CANCEL; f.err = "اتلغى" } }
        FtLog.all(s)
        FtHub.changed()
        return FtPeers.ok()
    }
}

/** إشعار «فيه حد عايز يبعتلك ملفات» لما شاشة النقل مش ظاهرة */
object FtNotify {
    private const val CH = "ft_in"
    private fun nid(s: FtSession): Int = 7400 + (s.sid.hashCode() and 0xff)

    fun incoming(app: Context, s: FtSession) {
        val txt = s.peerName + " عايز يبعتلك " + s.files.size + " ملف (" + Sniff.fmtSize(s.files.sumOf { it.size }) + ")"
        if (!FtHub.uiVisible) {
            try {
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CH, "طلبات استقبال ملفات", NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(app, 7, Intent(app, FileTransferActivity::class.java).putExtra("ft_sid", s.sid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(nid(s), Notification.Builder(app, CH).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("📥 طلب استقبال ملفات").setContentText(txt).setAutoCancel(true).setContentIntent(pi).setCategory(Notification.CATEGORY_MESSAGE).build())
            } catch (_: Throwable) {}
            try { FtHub.topAct?.get()?.let { a -> a.runOnUiThread { Notice.show(a, "📥 $txt — افتح «نقل الملفات»", 5000L) } } } catch (_: Throwable) {}
        }
        FtHub.changed()
    }

    fun clear(app: Context, s: FtSession) {
        try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(nid(s)) } catch (_: Throwable) {}
    }
}
