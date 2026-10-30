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
    private class Item(val uri: Uri, val name: String, val mime: String, val size: Long) {
        /** (v676) ترجمة الفيديو (ملف جنبه أو مسار جواه) بصيغة SRT — بتتجهّز في الخلفية والتلفزيون بيسحبها من /cast/<tok>/sub.srt */
        @Volatile var srt: String? = null
        val subDone = java.util.concurrent.CountDownLatch(1)
    }

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
        val item = Item(u, "video.$ext", mime, size)
        synchronized(items) { items.clear(); items[tok] = item }   // فيديو واحد في المرة
        val app = ctx.applicationContext
        Thread { try { item.srt = SubSidecar.prepareSrt(app, u) } catch (e: Throwable) { LogStore.err("CastServer:sub", e) } finally { item.subDone.countDown() } }.also { it.isDaemon = true }.start()   // (v676)
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
                // (v676) ترجمة الفيديو للتلفزيون: بنستنى لحد ما الموبايل يخلّص تجهيزها (ملف جنب الفيديو أو استخراج مسار جواه)؛ مفيش ترجمة ← 404
                if (parts[3].startsWith("sub")) {
                    try { it.subDone.await(60, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                    val t = it.srt
                    if (t.isNullOrEmpty()) { fail(404, "Not Found"); return }
                    val bytes = t.toByteArray(Charsets.UTF_8)
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
                    if (method != "HEAD") out.write(bytes)
                    out.flush(); return
                }
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

/**
 * (v676) ملف ترجمة جنب الفيديو (سوفت سب) — مشترك بين المشغّل (فيديو محلي) و CastServer (الموبايل بيجهّز الترجمة ويبعتها للتلفزيون مع الفيديو).
 */
object SubSidecar {
    fun find(ctx: Context, u: Uri): java.io.File? = try {
        var path: String? = null
        if (u.scheme == "file") path = u.path
        else if (u.scheme == "content") ctx.contentResolver.query(u, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) path = c.getString(0) }
        val f = path?.let { java.io.File(it) }
        val dir = f?.parentFile
        if (f == null || dir == null) { LogStore.add("💬 سوفت صب: مسار الفيديو مش معروف (" + (u.scheme ?: "?") + ") — مش هقدر ألاقي ملف ترجمة جنبه"); null } else {
            val exts = listOf("srt", "ass", "ssa", "vtt", "ttml")
            val base = f.nameWithoutExtension.lowercase()
            fun norm(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
            val nb = norm(base)
            // (v661) تطابق متسامح: نفس الاسم · name.ar.srt · أو نفس الاسم بعد شيل المسافات/الرموز + لاحقة قصيرة (لغة) — من غير ما «movie1» تلقط «movie10»
            fun matches(name: String): Boolean {
                val n = name.lowercase()
                if (n == base || n.startsWith("$base.")) return true
                val nn = norm(n)
                return nb.isNotEmpty() && nn.startsWith(nb) && nn.length - nb.length <= 8
            }
            var files: List<java.io.File> = dir.listFiles()?.toList().orEmpty()
            if (files.isEmpty()) {   // مفيش صلاحية تعدّد الملفات: نجرّب فهرس الميديا
                try {
                    val out = ArrayList<java.io.File>()
                    ctx.contentResolver.query(android.provider.MediaStore.Files.getContentUri("external"), arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                        android.provider.MediaStore.MediaColumns.DATA + " LIKE ?", arrayOf(dir.absolutePath + "/%"), null)?.use { c -> while (c.moveToNext()) { val pth = c.getString(0) ?: continue; val ff = java.io.File(pth); if (ff.parentFile == dir) out.add(ff) } }
                    files = out
                } catch (e: Throwable) { LogStore.err("Player:sidecarMS", e) }
            }
            var c = files.filter { it.isFile && it.extension.lowercase() in exts && matches(it.nameWithoutExtension) }
            // (v667) مفيش تطابق بالاسم: فولدر فيه «1.mp4» و«ترجمة رقم 1.srt» (أو فولدر فرعي اسمه ترجمة/subs) ← نطابق بالرقم الأخير في الاسم
            if (c.isEmpty()) {
                fun digits(x: String): String = buildString { for (ch in x) append(if (ch in '\u0660'..'\u0669') ('0' + (ch - '\u0660')) else if (ch in '\u06F0'..'\u06F9') ('0' + (ch - '\u06F0')) else ch) }
                fun lastNum(x: String): Long? = Regex("\\d+").findAll(digits(x)).lastOrNull()?.value?.toLongOrNull()
                val vn = lastNum(f.nameWithoutExtension)
                if (vn != null) {
                    val pool = ArrayList<java.io.File>(files)
                    try {
                        files.filter { it.isDirectory && Regex("ترجم|subs?|subtitle|caption|srt", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                            .forEach { d -> d.listFiles()?.let { pool.addAll(it) } }
                    } catch (_: Throwable) {}
                    c = pool.filter { it.isFile && it.extension.lowercase() in exts && lastNum(it.nameWithoutExtension) == vn }
                    if (c.isNotEmpty()) LogStore.add("💬 سوفت صب: تطابق بالرقم ($vn) ← «" + c.first().name + "»")
                }
            }
            val r = c.sortedWith(compareBy({ x -> val n = x.name.lowercase(); if (n.contains(".ar.") || n.contains("arab") || n.contains(".ara.")) 0 else 1 }, { x -> exts.indexOf(x.extension.lowercase()) }, { it.name })).firstOrNull()
            LogStore.add("💬 سوفت صب: فحص ملف جنب الفيديو — الفولدر فيه " + files.size + " ملف، " + (if (r != null) "لقيت «" + r.name + "»" else "مفيش ملف ترجمة بنفس الاسم"))
            r
        }
    } catch (e: Throwable) { LogStore.err("Player:sidecar", e); null }

    private fun ts(sec: Double): String {
        val ms = (sec * 1000.0).toLong().coerceAtLeast(0L)
        return "%02d:%02d:%02d,%03d".format(ms / 3600000L, (ms / 60000L) % 60L, (ms / 1000L) % 60L, ms % 1000L)
    }

    /** جمل ← نص SRT */
    fun toSrt(cues: List<SubFileTr.Cue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c -> sb.append(i + 1).append('\n').append(ts(c.start)).append(" --> ").append(ts(maxOf(c.end, c.start + 0.2))).append('\n').append(c.text.trim()).append("\n\n") }
        return sb.toString()
    }

    /** ترجمة الفيديو للتلفزيون بصيغة SRT: ملف جنب الفيديو الأول (srt/ass/vtt…) وإلا مسار جوه الفيديو؛ null = مفيش */
    fun prepareSrt(ctx: Context, u: Uri): String? {
        try {
            val sc = find(ctx, u)
            if (sc != null) { val c = SubFileTr.parse(SubFileTr.decode(sc.readBytes())); if (c.isNotEmpty()) return toSrt(c) }
        } catch (e: Throwable) { LogStore.err("SubSidecar:file", e) }
        try {
            if (u.scheme == "file" || u.scheme == "content") SubFileTr.readEmbedded(ctx, u)?.let { if (it.second.isNotEmpty()) return toSrt(it.second) }
        } catch (e: Throwable) { LogStore.err("SubSidecar:emb", e) }
        return null
    }
}
