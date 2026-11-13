package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (v855) إرسال/استقبال مع الآيفون (وأي جهاز عنده متصفح): سيرفر ويب محلي صغير على الواي فاي بكود سري مؤقت.
 *  • الآيفون يمسح الـQR (أو يكتب الرابط في Safari) ← صفحة فيها: تنزيل الملفات اللي اخترتها · ورفع ملفات من الآيفون للموبايل.
 *  • من غير تطبيق على الآيفون ومن غير إنترنت. السيرفر شغّال بس وإنت فاتح النافذة وبيقفل لما تقفلها.
 *  • الملفات الواصلة بتتحفظ في نفس فولدرات الاستقبال العادية (Xfer.inboxDir) ومابتكتبش فوق ملف موجود.
 */
object FtWeb {
    const val PORT = 18771
    @Volatile var running = false
        private set
    @Volatile var key = ""
        private set
    /** أسماء الملفات اللي وصلت من المتصفح في الجلسة الحالية */
    val received = CopyOnWriteArrayList<String>()
    /** اسم + نسبة الملف اللي بيتستقبل دلوقتي ("" = مفيش) */
    @Volatile var uploading = ""
    @Volatile var onEvent: (() -> Unit)? = null

    @Volatile private var srv: ServerSocket? = null
    @Volatile private var app: Context? = null
    @Volatile private var share: List<FtPick> = emptyList()
    private val main = Handler(Looper.getMainLooper())

    /** بيشغّل السيرفر (لو مش شغّال) ويرجّع الرابط الكامل؛ "" = مفيش شبكة محلية أو البورت مشغول */
    fun start(ctx: Context, picks: List<FtPick>): String {
        app = ctx.applicationContext
        share = picks
        received.clear(); uploading = ""
        val ip = TypeServer.ip()
        if (ip.isEmpty()) return ""
        if (!running) {
            key = (100000 + SecureRandom().nextInt(900000)).toString() + (1000 + SecureRandom().nextInt(9000)).toString()
            val latch = CountDownLatch(1)
            var ok = false
            Thread {
                var made: ServerSocket? = null
                try {
                    made = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                    srv = made; running = true; ok = true
                } catch (e: Exception) { LogStore.err("FtWeb:bind", e) }
                latch.countDown()
                val ss = made ?: return@Thread
                try {
                    while (running && srv === ss) {
                        try {
                            val s = ss.accept()
                            Thread { handle(s) }.also { it.isDaemon = true }.start()
                        } catch (_: Exception) { if (!running || srv !== ss) break }
                    }
                } catch (_: Throwable) {}
                if (srv === ss) { running = false; srv = null }
                try { ss.close() } catch (_: Throwable) {}
            }.also { it.isDaemon = true }.start()
            try { latch.await(1500L, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            if (!ok) { key = ""; return "" }
        }
        return "http://$ip:$PORT/?k=$key"
    }

    fun stop() {
        running = false; key = ""; share = emptyList(); onEvent = null; uploading = ""
        try { srv?.close() } catch (_: Throwable) {}
        srv = null
    }

    // ───────── HTTP ─────────
    private fun readLine(ins: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (c == 10) break
            if (c != 13) sb.write(c)
            if (sb.size() > 16384) return null
        }
        return sb.toString("UTF-8")
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.size + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        out.write(body); out.flush()
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun fmtSize(b: Long): String = when {
        b < 0L -> ""
        b < 1024L -> "$b B"
        b < 1048576L -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        b < 1073741824L -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
    }

    private fun handle(s: Socket) {
        try {
            s.use { sock ->
                sock.soTimeout = 30000
                val ins = java.io.BufferedInputStream(sock.getInputStream(), 16384)
                val out = sock.getOutputStream()
                val reqLine = readLine(ins) ?: return
                val parts = reqLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]; val target = parts[1]
                var len = -1L
                while (true) {
                    val hl = readLine(ins) ?: break
                    if (hl.isEmpty()) break
                    if (hl.lowercase().startsWith("content-length:")) len = hl.substringAfter(":").trim().toLongOrNull() ?: -1L
                }
                val path = target.substringBefore('?'); val q = target.substringAfter('?', "")
                fun qp(k: String): String {
                    val v = q.split("&").firstOrNull { it.startsWith("$k=") }?.substringAfter("=") ?: return ""
                    return try { URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { "" }
                }
                if (!running || key.isEmpty() || qp("k") != key) { reply(out, 403, "text/plain; charset=utf-8", "403".toByteArray()); return }
                when {
                    method == "GET" && path == "/" -> reply(out, 200, "text/html; charset=utf-8", pageHtml().toByteArray(Charsets.UTF_8))
                    method == "GET" && path.startsWith("/d/") -> download(out, path.removePrefix("/d/").toIntOrNull() ?: -1)
                    method == "POST" && path == "/u" -> upload(ins, out, qp("n"), len)
                    else -> reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray())
                }
            }
        } catch (_: Throwable) {}
    }

    private fun pageHtml(): String {
        val sb = StringBuilder()
        val pk = share
        if (pk.isNotEmpty()) {
            sb.append("<h3>⬇ ملفات مبعوتة ليك (").append(pk.size).append(")</h3>")
            for ((i, p) in pk.withIndex()) {
                val nm = p.rel.substringAfterLast('/')
                sb.append("<a class=\"f\" href=\"/d/").append(i).append("?k=").append(key).append("\">").append(esc(nm)).append(" <small>").append(fmtSize(p.size)).append("</small></a>")
            }
        }
        val js = """
var K='@@K@@';
function add(t){var d=document.createElement('div');d.className='r';d.textContent=t;document.getElementById('s').appendChild(d);return d}
function one(f){
 return new Promise(function(res){
  var d=add('⏳ '+f.name);
  var x=new XMLHttpRequest();
  x.open('POST','/u?k='+K+'&n='+encodeURIComponent(f.name));
  x.upload.onprogress=function(e){if(e.lengthComputable){d.textContent='⏳ '+f.name+' — '+Math.round(e.loaded*100/e.total)+'%'}};
  x.onload=function(){d.textContent=(x.status==200?'✅ ':'⚠ ')+f.name;res()};
  x.onerror=function(){d.textContent='⚠ '+f.name;res()};
  x.send(f)
 })
}
async function up(){
 var fs=document.getElementById('f').files;
 for(var i=0;i<fs.length;i++){await one(fs[i])}
}
"""
        return "<!doctype html><html dir=\"rtl\" lang=\"ar\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Joyboy — نقل ملفات</title>" +
            "<style>body{font-family:-apple-system,sans-serif;background:#171512;color:#f3ead8;padding:18px;line-height:1.7}h2{margin:0 0 6px}h3{margin:22px 0 8px;color:#ffb340}" +
            "a.f{display:block;background:#26221c;border:1px solid #3a342b;border-radius:12px;padding:13px;margin:7px 0;color:#f3ead8;text-decoration:none;font-size:16px;word-break:break-all}small{opacity:.65}" +
            "label.b,button{display:block;width:100%;box-sizing:border-box;text-align:center;background:#ffb340;color:#000;border:0;border-radius:12px;padding:14px;font-size:17px;margin:8px 0}" +
            ".r{background:#26221c;border-radius:10px;padding:9px 12px;margin:5px 0;word-break:break-all}input[type=file]{display:none}</style>" +
            "<body><h2>📲 Joyboy</h2><div style=\"opacity:.7;font-size:13px\">نقل ملفات بين الموبايل والآيفون على نفس الواي فاي</div>" +
            sb.toString() +
            "<h3>⬆ ابعت ملفات للموبايل</h3><label class=\"b\" for=\"f\">📎 اختار ملفات من الجهاز ده</label><input id=\"f\" type=\"file\" multiple onchange=\"up()\"><div id=\"s\"></div>" +
            "<script>" + js.replace("@@K@@", key) + "</script></body></html>"
    }

    private fun download(out: OutputStream, i: Int) {
        val ctx = app
        val pk = share.getOrNull(i)
        if (ctx == null || pk == null) { reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray()); return }
        val ins: InputStream? = try {
            if (pk.uri.scheme == "file") { val f = File(pk.uri.path ?: ""); if (f.isFile) f.inputStream() else null }
            else ctx.contentResolver.openInputStream(pk.uri)
        } catch (_: Throwable) { null }
        if (ins == null) { reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray()); return }
        ins.use { inp ->
            val nm = pk.rel.substringAfterLast('/').ifEmpty { "file" }
            val enc = URLEncoder.encode(nm, "UTF-8").replace("+", "%20")
            val sb = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n")
            if (pk.size >= 0L) sb.append("Content-Length: ").append(pk.size).append("\r\n")
            sb.append("Content-Disposition: attachment; filename*=UTF-8''").append(enc).append("\r\nConnection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            val buf = ByteArray(65536)
            while (true) { val r = inp.read(buf); if (r < 0) break; out.write(buf, 0, r) }
            out.flush()
        }
    }

    private fun upload(ins: InputStream, out: OutputStream, rawName: String, len: Long) {
        val ctx = app
        if (ctx == null || len < 0L) { reply(out, 411, "text/plain; charset=utf-8", "411".toByteArray()); return }
        val name = Xfer.safeName(rawName)
        val dir = Xfer.inboxDir(ctx, XCat.catOf(name))
        val outF = Xfer.uniqueFile(dir, name)
        val part = File(dir, outF.name + ".part")
        var got = 0L
        try {
            part.outputStream().use { fo ->
                val buf = ByteArray(65536)
                var lastUi = 0L
                while (got < len) {
                    val r = ins.read(buf, 0, minOf(buf.size.toLong(), len - got).toInt())
                    if (r < 0) break
                    fo.write(buf, 0, r); got += r
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 400L) { lastUi = now; uploading = name + " — " + (if (len > 0L) (got * 100L / len).toInt() else 0) + "%"; main.post { onEvent?.invoke() } }
                }
            }
        } catch (e: Throwable) { LogStore.err("FtWeb:up", e); got = -1L }
        uploading = ""
        if (got != len) {
            try { part.delete() } catch (_: Throwable) {}
            try { reply(out, 400, "text/plain; charset=utf-8", "ERR".toByteArray()) } catch (_: Throwable) {}
            main.post { onEvent?.invoke() }
            return
        }
        if (!part.renameTo(outF)) { try { part.delete() } catch (_: Throwable) {}; reply(out, 500, "text/plain; charset=utf-8", "ERR".toByteArray()); return }
        try { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(outF.absolutePath), null, null) } catch (_: Throwable) {}
        received.add(outF.name)
        main.post { onEvent?.invoke() }
        reply(out, 200, "text/plain; charset=utf-8", "OK".toByteArray())
    }

    // ───────── الواجهة: نافذة QR ─────────
    /** picks فاضية = استقبال بس (من الآيفون للموبايل)؛ فيها ملفات = إرسال للآيفون + استقبال */
    fun showDialog(act: Activity, ui: Ui, th: Theme, picks: List<FtPick>) {
        val url = start(act, picks)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)) }
        val status = ui.text("", 13.5f, th.primary, true).apply { setPadding(0, ui.dp(8), 0, 0) }
        if (url.isEmpty()) {
            col.addView(ui.text("⚠ مفيش شبكة محلية — شغّل الواي فاي (أو الهوت سبوت) ووصّل الآيفون بنفس الشبكة، وبعدين افتح النافذة تاني.", 15f, th.text))
        } else {
            val qr = QrCode.bitmap(url, ui.dp(220))
            if (qr != null) col.addView(ImageView(act).apply { setImageBitmap(qr); setBackgroundColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }, LinearLayout.LayoutParams(ui.dp(220), ui.dp(220)).apply { bottomMargin = ui.dp(10) })
            val how = if (picks.isEmpty()) "1) وصّل الآيفون بنفس الواي فاي\n2) افتح كاميرا الآيفون وامسح الكود (أو اكتب الرابط في Safari)\n3) دوس «اختار ملفات» في الصفحة — الملفات هتوصل هنا لوحدها"
            else "1) وصّل الآيفون بنفس الواي فاي\n2) افتح كاميرا الآيفون وامسح الكود (أو اكتب الرابط في Safari)\n3) دوس على الملف في الصفحة ينزل عند الآيفون (" + picks.size + " ملف) — وتقدر كمان ترفع ملفات من الآيفون"
            col.addView(ui.text(how, 14f, th.text))
            col.addView(ui.text(url, 11.5f, th.muted).apply { setPadding(0, ui.dp(8), 0, 0); setTextIsSelectable(true) })
            col.addView(ui.noHint().apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        col.addView(status)
        fun refresh() {
            val sb = StringBuilder()
            if (FtWeb.uploading.isNotEmpty()) sb.append("⏳ بيستقبل: ").append(FtWeb.uploading).append("\n")
            if (received.isNotEmpty()) { sb.append("✅ وصل ").append(received.size).append(" ملف:\n"); for (n in received.takeLast(5)) sb.append("• ").append(n).append("\n") }
            status.text = if (sb.isEmpty() && url.isNotEmpty()) "⏳ مستني الآيفون…" else sb.toString().trim()
        }
        refresh()
        onEvent = { refresh() }
        val sc = ScrollView(act).apply { addView(col) }
        val dlg = GAlert(act).setTitle("🍎 آيفون / أي جهاز بمتصفح").setView(sc).setNegativeButton("إغلاق", null).create()
        dlg.setOnDismissListener { stop() }
        dlg.show()
    }
}
