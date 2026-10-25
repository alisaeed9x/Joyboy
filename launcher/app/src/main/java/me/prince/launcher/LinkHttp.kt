package me.prince.launcher

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * (v518) سيرفر HTTP صغير من غير مكتبات — بيتستخدم في ربط الموبايل بالتلفزيون (TvLink على التلفزيون، RemoteClient على الموبايل).
 * بيدعم GET/POST وهيدرز وبودي صغير (JSON) وبيبعت ملف كبير (APK) بالتدفق.
 */
class HttpReq(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,   // المفاتيح lowercase
    val body: ByteArray,
    val peer: String
) {
    fun bodyText(): String = String(body, Charsets.UTF_8)
    fun header(k: String): String = headers[k.lowercase()] ?: ""
}

class HttpRes(val code: Int, val type: String = "application/json; charset=utf-8", val body: ByteArray = ByteArray(0), val file: File? = null) {
    companion object {
        fun json(s: String, code: Int = 200) = HttpRes(code, "application/json; charset=utf-8", s.toByteArray(Charsets.UTF_8))
        fun text(s: String, code: Int = 200) = HttpRes(code, "text/plain; charset=utf-8", s.toByteArray(Charsets.UTF_8))
        fun file(f: File, type: String) = HttpRes(200, type, ByteArray(0), f)
    }
}

class MiniHttp(private val port: Int, private val handler: (HttpReq) -> HttpRes) {
    @Volatile var running = false
        private set
    private var srv: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            srv = s
            running = true
            Thread {
                while (running) {
                    try {
                        val c = s.accept()
                        pool.execute { serve(c) }
                    } catch (_: Exception) { if (!running) break }
                }
            }.also { it.isDaemon = true }.start()
            true
        } catch (e: Exception) { android.util.Log.w("MiniHttp", "MiniHttp:$port", e); running = false; false }
    }

    fun stop() {
        running = false
        try { srv?.close() } catch (_: Exception) {}
        srv = null
    }

    private fun readHead(ins: InputStream): String? {
        val bo = ByteArrayOutputStream()
        var state = 0
        while (bo.size() < 16384) {
            val b = ins.read()
            if (b < 0) return null
            bo.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return String(bo.toByteArray(), Charsets.ISO_8859_1)
        }
        return null
    }

    private fun serve(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 10000
                val ins = s.getInputStream()
                val head = readHead(ins) ?: return
                val lines = head.split("\r\n")
                val rl = lines[0].split(" ")
                if (rl.size < 2) return
                val method = rl[0].uppercase()
                val full = rl[1]
                val path = full.substringBefore('?')
                val q = HashMap<String, String>()
                if (full.contains('?')) full.substringAfter('?').split('&').forEach { kv ->
                    if (kv.isEmpty()) return@forEach
                    val k = kv.substringBefore('=')
                    val v = kv.substringAfter('=', "")
                    try { q[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") } catch (_: Exception) {}
                }
                val hd = HashMap<String, String>()
                for (i in 1 until lines.size) {
                    val l = lines[i]; val c = l.indexOf(':')
                    if (c > 0) hd[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
                }
                val len = (hd["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 262144)
                val body = ByteArray(len)
                var got = 0
                while (got < len) { val r = ins.read(body, got, len - got); if (r < 0) break; got += r }
                val peer = try { s.inetAddress?.hostAddress ?: "" } catch (_: Exception) { "" }
                val res = try { handler(HttpReq(method, path, q, hd, if (got == len) body else body.copyOf(got), peer)) }
                catch (e: Throwable) { android.util.Log.w("MiniHttp", "MiniHttp:handler", e); HttpRes.text("ERR", 500) }
                write(s, res, method == "HEAD")
            }
        } catch (_: Exception) {}
    }

    private fun write(s: Socket, r: HttpRes, headOnly: Boolean) {
        val out = s.getOutputStream()
        val f = r.file
        val len: Long = if (f != null) f.length() else r.body.size.toLong()
        val msg = when (r.code) { 200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "X" }
        out.write("HTTP/1.1 ${r.code} $msg\r\nContent-Type: ${r.type}\r\nContent-Length: $len\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray(Charsets.US_ASCII))
        if (!headOnly) {
            if (f != null) {
                FileInputStream(f).use { fi ->
                    val buf = ByteArray(65536)
                    while (true) { val n = fi.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                }
            } else out.write(r.body)
        }
        out.flush()
    }
}
