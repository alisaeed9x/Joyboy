package com.tttt.joyboy.desk

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * جانب «الجهاز» من بروتوكول الريموت — نفس بروتوكول TvLink بتاع التلفزيون (بورت 18770 + UDP 18771)،
 * فالموبايل بيلاقي اللابتوب كأنه تلفزيون من غير أي تعديل على الموبايل.
 */
object Link {
    const val PORT = 18770
    const val UDP_PORT = 18771
    private const val FAKE_VER = 2_000_000_000L   // أعلى من أي نسخة موبايل ← الموبايل ما يعرضش «حدّث التطبيق» للابتوب
    @Volatile var pairCode = ""
    @Volatile private var pairCodeUntil = 0L
    private var badTries = 0
    private var lockUntil = 0L
    private var srv: HttpServer? = null
    @Volatile private var running = false
    var onNotice: (String) -> Unit = {}
    var onRemotesChanged: () -> Unit = {}

    class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray, val peer: String, val ex: HttpExchange) {
        fun text() = String(body, Charsets.UTF_8)
        fun json(): JSONObject = try { JSONObject(text()) } catch (_: Throwable) { JSONObject() }
        fun h(k: String) = headers[k.lowercase()] ?: ""
    }

    fun start() {
        if (running) return
        running = true
        val s = HttpServer.create(InetSocketAddress(PORT), 0)
        s.executor = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        s.createContext("/") { ex -> try { handle(ex) } catch (t: Throwable) { try { send(ex, 500, JSONObject().put("ok", false).put("err", "srv")) } catch (_: Throwable) {} } finally { try { ex.close() } catch (_: Throwable) {} } }
        s.start(); srv = s
        Thread { udp() }.also { it.isDaemon = true }.start()
        Thread { watcher() }.also { it.isDaemon = true }.start()
    }

    fun newCode(): String { val c = (java.security.SecureRandom().nextInt(90000) + 10000).toString(); pairCode = c; pairCodeUntil = System.currentTimeMillis() + 600_000L; return c }

    private fun hello() = JSONObject().put("app", "subtitler-tv").put("kind", "desktop").put("id", Store.id).put("name", Store.name).put("port", PORT)
        .put("ver", FAKE_VER).put("vname", "Joyboy Desk 1").put("needCode", true)

    private fun udp() {
        try {
            val s = DatagramSocket(null); s.reuseAddress = true; s.broadcast = true; s.bind(InetSocketAddress(UDP_PORT))
            val buf = ByteArray(512)
            while (running) {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                if (String(p.data, 0, p.length, Charsets.UTF_8).startsWith("SUBTV?")) {
                    val r = hello().toString().toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(r, r.size, p.address, p.port))
                }
            }
        } catch (_: Throwable) {}
    }

    private fun watcher() {
        while (running) {
            try {
                for (rm in Store.remotes.values) if (rm.announced && !rm.alive()) { rm.announced = false; onNotice("📱 الموبايل «${rm.name}» انقطع"); onRemotesChanged() }
                Thread.sleep(3000)
            } catch (_: Throwable) {}
        }
    }

    // ===== HTTP =====
    fun send(ex: HttpExchange, code: Int, j: JSONObject) = sendRaw(ex, code, "application/json; charset=utf-8", j.toString().toByteArray(Charsets.UTF_8))
    private fun sendRaw(ex: HttpExchange, code: Int, type: String, b: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, if (b.isEmpty()) -1L else b.size.toLong())
        if (b.isNotEmpty()) ex.responseBody.use { it.write(b) }
    }
    private fun err(ex: HttpExchange, code: Int, e: String) = send(ex, code, JSONObject().put("ok", false).put("err", e))
    private fun ok(ex: HttpExchange, x: JSONObject = JSONObject()) = send(ex, 200, x.put("ok", true))

    /** تقديم ملف بطلبات Range (للتحميل المستأنف والتشغيل المباشر) */
    fun serveFile(ex: HttpExchange, f: File, mime: String) {
        val size = f.length()
        var start = 0L; var end = size - 1; var code = 200
        val rg = ex.requestHeaders.getFirst("Range")
        if (rg != null && rg.startsWith("bytes=")) {
            val p = rg.substring(6).split("-")
            try { if (p[0].isNotEmpty()) start = p[0].toLong(); if (p.size > 1 && p[1].isNotEmpty()) end = p[1].toLong() } catch (_: Exception) {}
            if (start > end || start >= size) { ex.responseHeaders.add("Content-Range", "bytes */$size"); ex.sendResponseHeaders(416, -1); return }
            end = minOf(end, size - 1); code = 206
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
        }
        ex.responseHeaders.add("Content-Type", mime); ex.responseHeaders.add("Accept-Ranges", "bytes")
        val len = end - start + 1
        ex.sendResponseHeaders(code, if (len == 0L) -1 else len)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start); val buf = ByteArray(131072); var left = len
            ex.responseBody.use { o -> while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n } }
        }
    }

    private fun handle(ex: HttpExchange) {
        val u = ex.requestURI
        val q = HashMap<String, String>()
        (u.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.forEach { kv -> val i = kv.indexOf('='); if (i > 0) q[URLDecoder.decode(kv.substring(0, i), "UTF-8")] = URLDecoder.decode(kv.substring(i + 1), "UTF-8") }
        val hd = HashMap<String, String>(); ex.requestHeaders.forEach { (k, v) -> if (v.isNotEmpty()) hd[k.lowercase()] = v[0] }
        val body = ex.requestBody.use { it.readBytes() }
        val r = Req(ex.requestMethod, u.path, q, hd, body, ex.remoteAddress.address.hostAddress ?: "", ex)
        if (r.path == "/hello") return send(ex, 200, hello())
        if (r.path == "/pair") return pair(r)
        val rm = Store.remotes.values.firstOrNull { it.token == r.h("x-token") && it.token.isNotEmpty() } ?: return err(ex, 401, "auth")
        rm.ip = r.peer
        touch(rm, r)
        when (r.path) {
            "/ping" -> ok(ex, JSONObject().put("ver", FAKE_VER).put("vname", "Joyboy Desk 1").put("name", Store.name).put("tv", Store.id)
                .put("mfr", "PC").put("model", "Windows").put("ctx", if (Player.hasMedia()) "player" else "home").put("acc", false).put("vol", Player.volume()).put("push", Push.pending()))
            "/player" -> player(r)
            "/open" -> open(r)
            "/pull" -> Xfer.pull(r)
            "/lib/folders" -> Lib.folders(r)
            "/lib/videos" -> Lib.videos(r)
            "/lib/thumb" -> Lib.thumb(r)
            "/file/lib" -> Lib.file(r)
            "/xfer/list" -> Xfer.list(r)
            "/file/any" -> Xfer.file(r)
            "/subup" -> subUp(r)
            "/recent" -> ok(ex, JSONObject().put("items", JSONArray()))
            "/bye" -> { rm.announced = false; rm.lastSeen = 0L; onNotice("📱 الموبايل «${rm.name}» فصل"); onRemotesChanged(); ok(ex) }
            "/cmd" -> { val c = r.json().optString("c"); Player.cmd(c); ok(ex) }
            "/key", "/ptr", "/look", "/cfg", "/keys", "/clear", "/type", "/sync", "/offer", "/priv", "/abridge", "/cosurf" -> ok(ex)
            else -> err(ex, 404, "nf")
        }
    }

    private fun touch(rm: Store.Remote, r: Req) {
        rm.lastSeen = System.currentTimeMillis()
        val nm = r.h("x-name")
        if (nm.isNotEmpty()) try { val d = URLDecoder.decode(nm, "UTF-8"); if (d.isNotBlank() && d != rm.name) { rm.name = d; Store.saveRemotes() } } catch (_: Exception) {}
        if (!rm.announced) { rm.announced = true; onNotice("📱 الموبايل «${rm.name}» اتصل"); onRemotesChanged() }
    }

    private fun pair(r: Req) {
        val now = System.currentTimeMillis()
        if (now < lockUntil) return err(r.ex, 403, "locked")
        val j = r.json()
        val id = j.optString("id"); val nm = j.optString("name").ifBlank { "موبايل" }; val code = j.optString("code")
        if (id.isBlank()) return err(r.ex, 403, "bad")
        val known = Store.remotes.containsKey(id)
        val codeOk = code.isNotEmpty() && code == pairCode && now < pairCodeUntil
        // أول موبايل بيتقرن من غير كود (زي التلفزيون)؛ أي موبايل بعده لازم يكتب الكود اللي ظاهر على اللابتوب
        val needCode = Store.remotes.isNotEmpty() && !known
        if (needCode && !codeOk) {
            if (code.isNotEmpty()) { badTries++; if (badTries >= 8) { badTries = 0; lockUntil = now + 60000L } }
            else { val c = if (pairCode.isNotEmpty() && now < pairCodeUntil) pairCode else newCode(); onNotice("🔐 كود الإقران: $c") }
            return err(r.ex, 403, "need_code")
        }
        badTries = 0
        val tok = Store.hex(24)
        val ex = Store.remotes[id]
        if (ex != null) { ex.token = tok; ex.name = nm } else Store.remotes[id] = Store.Remote(id, nm, tok)
        Store.saveRemotes(); if (codeOk) pairCode = ""
        onNotice("📱 اتقرن موبايل جديد: «$nm»"); onRemotesChanged()
        ok(r.ex, JSONObject().put("token", tok).put("tv", Store.id).put("name", Store.name))
    }

    private fun open(r: Req) {
        val j = r.json()
        val url = j.optString("url").trim()
        if (url.startsWith("tv://", true)) {   // فيديو من مكتبة اللابتوب اختاره الموبايل
            val v = Lib.find(url.substring(5).trim()) ?: return err(r.ex, 404, "nf")
            Player.play(v.f.toURI().toString(), v.f.name.substringBeforeLast('.'), j.optLong("startms", 0L), "", "", "")
            return ok(r.ex, JSONObject().put("visible", true))
        }
        if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return err(r.ex, 403, "badurl")
        Player.play(url, j.optString("title"), j.optLong("startms", 0L), j.optString("ref"), j.optString("ua"), j.optString("cookie"))
        ok(r.ex, JSONObject().put("visible", true))
    }

    private fun player(r: Req) {
        val j = r.json(); val a = j.optString("a")
        if (a.isNotEmpty() && a != "state") {
            if (!Player.hasMedia()) return ok(r.ex, JSONObject().put("none", true))
            Player.act(a, j.optLong("v"))
            try { Thread.sleep(80) } catch (_: Exception) {}
        }
        if (!Player.hasMedia()) return ok(r.ex, JSONObject().put("none", true))
        ok(r.ex, JSONObject().put("st", Player.state()))
    }

    /** ملف ترجمة جاي من الموبايل (على قطع) ← بيتحفظ ويتحمّل في المشغّل */
    private val subParts = HashMap<String, Array<String?>>()
    private fun subUp(r: Req) {
        val j = r.json()
        val id = j.optString("id"); val t = j.optInt("t", 1).coerceIn(1, 60); val i = j.optInt("i", 0)
        if (id.isBlank() || i !in 0 until t) return err(r.ex, 400, "bad")
        val parts = synchronized(subParts) { if (subParts.size > 3 && !subParts.containsKey(id)) subParts.clear(); subParts.getOrPut(id) { arrayOfNulls(t) } }
        if (parts.size != t) return err(r.ex, 400, "bad")
        val full = synchronized(parts) { parts[i] = j.optString("d"); parts.all { it != null } }
        if (!full) return ok(r.ex, JSONObject().put("done", false))
        synchronized(subParts) { subParts.remove(id) }
        val txt = synchronized(parts) { parts.joinToString("") { it ?: "" } }
        val nm = Cat.safeName(j.optString("n", "mobile.srt")).ifBlank { "mobile.srt" }
        val f = File(Store.home, "sub_" + nm.let { if (it.endsWith(".srt", true)) it else "$it.srt" })
        f.writeText(txt, Charsets.UTF_8)
        val applied = Player.loadSub(f)
        ok(r.ex, JSONObject().put("done", true).put("player", applied))
    }
}
