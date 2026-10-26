package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * (v564) «الموبايل → التلفزيون»: سيرفر HTTP على الموبايل بيقدّم ملف فيديو من الموبايل بطلبات Range.
 * التلفزيون بيسحب الفيديو على دفعات (مش سكرين ميرورينج): لو لفّيت في نص الفيديو بيطلب من المكان ده بس وبعدين يكمّل.
 * الملف ما بيتنقلش كله ولا بيتحفظ على الموبايل — بيتقرا من مكانه الأصلي (Uri) حسب الطلب.
 */
object CastServer {
    private const val PORT = 8765
    private class Item(val uri: Uri, val name: String, val mime: String, val size: Long)

    private val items = HashMap<String, Item>()
    @Volatile private var srv: ServerSocket? = null
    private var appCtx: Context? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    /** إجمالي اللي اتبعت للتلفزيون (للعرض/التشخيص) */
    @Volatile var sentBytes = 0L

    private fun ensure(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        if (srv != null) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            srv = s
            Thread {
                while (srv === s) {
                    try { val c = s.accept(); pool.execute { serve(c) } } catch (_: Exception) { if (srv !== s) break }
                }
            }.also { it.isDaemon = true }.start()
            true
        } catch (e: Exception) { LogStore.err("CastServer:start", e); srv = null; false }
    }

    private fun sizeOf(ctx: Context, u: Uri): Long {
        try {
            if (u.scheme == "file") return File(u.path ?: "").length()
            ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { if (it.length > 0L) return it.length }
        } catch (_: Throwable) {}
        try { ctx.contentResolver.openFileDescriptor(u, "r")?.use { return it.statSize } } catch (_: Throwable) {}
        return -1L
    }

    /** يسجّل ملف ويرجّع لينك التلفزيون (أو null لو مفيش واي فاي/مفيش حجم) */
    fun publish(ctx: Context, uriStr: String, title: String): String? {
        val u = if (uriStr.startsWith("/")) Uri.fromFile(File(uriStr)) else Uri.parse(uriStr)
        val size = sizeOf(ctx, u)
        if (size <= 0L) return null
        val ip = TypeServer.ip()
        if (ip.isEmpty() || !ensure(ctx)) return null
        val raw = (u.lastPathSegment ?: "video.mp4").substringAfterLast('/')
        val ext = raw.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "mp4"
        val mime = try { ctx.contentResolver.getType(u) } catch (_: Throwable) { null }?.takeIf { it.startsWith("video/") } ?: when (ext) {
            "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"; "3gp" -> "video/3gpp"; "ts" -> "video/mp2t"; else -> "video/mp4"
        }
        val tok = ByteArray(10).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        synchronized(items) { items.clear(); items[tok] = Item(u, "video.$ext", mime, size) }   // فيديو واحد في المرة
        sentBytes = 0L
        return "http://$ip:$PORT/cast/$tok/video.$ext?tvcast=1"
    }

    fun clear() { synchronized(items) { items.clear() } }

    /** يفتح الملف ويفضّل الـ ParcelFileDescriptor حيّ طول القراءة (لو اتقفل/اتجمّع الـfd بيتقفل معاه) */
    private fun <T> withFile(it: Item, body: (FileInputStream) -> T): T? {
        return try {
            if (it.uri.scheme == "file") FileInputStream(File(it.uri.path ?: "")).use(body)
            else appCtx?.contentResolver?.openFileDescriptor(it.uri, "r")?.use { pfd -> FileInputStream(pfd.fileDescriptor).let(body) }
        } catch (e: java.io.IOException) { throw e } catch (_: Throwable) { null }
    }

    private fun serve(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 20000
                s.tcpNoDelay = true
                val ins = s.getInputStream()
                val head = StringBuilder()
                while (head.length < 8192) { val b = ins.read(); if (b < 0) return; head.append(b.toChar()); if (head.endsWith("\r\n\r\n")) break }
                val lines = head.toString().split("\r\n")
                val rl = lines[0].split(" ")
                if (rl.size < 2) return
                val method = rl[0].uppercase()
                val parts = rl[1].substringBefore('?').split('/')
                val out = s.getOutputStream()
                fun fail(code: Int, m: String) { out.write("HTTP/1.1 $code $m\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush() }
                if (parts.size < 4 || parts[1] != "cast") { fail(404, "Not Found"); return }
                val it = synchronized(items) { items[parts[2]] } ?: run { fail(404, "Not Found"); return }
                var rng = ""
                for (i in 1 until lines.size) if (lines[i].startsWith("range:", true)) rng = lines[i].substring(6).trim()
                var start = 0L; var end = it.size - 1
                var partial = false
                if (rng.startsWith("bytes=")) {
                    val r = rng.substring(6).split(",")[0].trim()
                    val a = r.substringBefore('-'); val b = r.substringAfter('-', "")
                    if (a.isEmpty()) { val n = b.toLongOrNull() ?: 0L; start = (it.size - n).coerceAtLeast(0L) }
                    else { start = a.toLongOrNull() ?: 0L; if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(it.size - 1) }
                    partial = true
                    if (start > end || start >= it.size) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${it.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return }
                }
                val len = end - start + 1
                out.write(((if (partial) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${it.size}\r\n" else "HTTP/1.1 200 OK\r\n") +
                    "Content-Type: ${it.mime}\r\nContent-Length: $len\r\nAccept-Ranges: bytes\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
                if (method == "HEAD") { out.flush(); return }
                withFile(it) { f ->
                    val ch = f.channel
                    ch.position(start)
                    val buf = java.nio.ByteBuffer.allocate(131072)
                    var left = len
                    while (left > 0) {
                        buf.clear(); if (left < buf.capacity()) buf.limit(left.toInt())
                        val n = ch.read(buf); if (n <= 0) break
                        out.write(buf.array(), 0, n); left -= n; sentBytes += n
                    }
                    out.flush()
                }
            }
        } catch (_: Exception) { /* التلفزيون قطع الطلب (لفّ في الفيديو) — طبيعي */ }
    }
}
