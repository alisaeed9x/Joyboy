package com.tttt.subtitler

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * (v462) «اكتب من الموبايل» لوضع التلفزيون: سيرفر محلي صغير على شبكة البيت —
 * صفحة ويب فيها صندوق كتابة، وأي نص يتبعت بيتحط في [lastText] وشاشة بحث التلفزيون
 * بتستخدمه (بدل لوحة مفاتيح الريموت). من غير أي مكتبة خارجية.
 */
object TypeServer {
    const val PORT = 18765

    @Volatile var running = false
        private set
    @Volatile var lastText = ""
    @Volatile var lastAt = 0L

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                ServerSocket(PORT).use { srv ->
                    while (running) {
                        try {
                            val sock = srv.accept()
                            Thread { handle(sock) }.start()
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) { LogStore.err("TypeSrv", e); running = false }
        }.start()
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 8000
                val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val line = br.readLine() ?: return
                var len = 0
                while (true) {
                    val hl = br.readLine() ?: break
                    if (hl.isEmpty()) break
                    if (hl.lowercase().startsWith("content-length:")) len = hl.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                val out = s.getOutputStream()
                fun send(body: String, type: String) {
                    val b = body.toByteArray(Charsets.UTF_8)
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(b); out.flush()
                }
                if (line.startsWith("POST")) {
                    val buf = CharArray(len.coerceIn(0, 65536)); var got = 0
                    while (got < buf.size) { val r = br.read(buf, got, buf.size - got); if (r < 0) break; got += r }
                    val body = String(buf, 0, got)
                    val t = try { URLDecoder.decode(body.substringAfter("t=", ""), "UTF-8") } catch (_: Exception) { "" }
                    if (t.isNotEmpty()) { lastText = t; lastAt = System.currentTimeMillis() }
                    send("OK", "text/plain; charset=utf-8")
                } else {
                    send("""<!doctype html><html dir="rtl" lang="ar"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Subtitler TV</title><body style="font-family:sans-serif;background:#171512;color:#f3ead8;padding:20px"><h3>📺 اكتب هنا وهيوصل للتلفزيون</h3><textarea id="t" rows="4" style="width:100%;font-size:18px;border-radius:10px;padding:10px;box-sizing:border-box"></textarea><button onclick="go()" style="width:100%;margin-top:10px;font-size:18px;padding:12px;border-radius:10px;background:#ffb340;border:0">ابعت للتلفزيون</button><p id="s"></p><script>async function go(){try{let r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'t='+encodeURIComponent(document.getElementById('t').value)});document.getElementById('s').textContent=r.ok?'✓ اتبعت — بص على شاشة التلفزيون':'⚠ حصلت مشكلة'}catch(e){document.getElementById('s').textContent='⚠ '+e}}</script></body></html>""", "text/html; charset=utf-8")
                }
            }
        } catch (_: Exception) {}
    }

    fun ip(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val addrs = ifs.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    fun url(): String { val i = ip(); return if (i.isEmpty()) "" else "http://$i:$PORT" }
}
