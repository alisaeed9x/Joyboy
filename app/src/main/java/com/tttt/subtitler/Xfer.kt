@file:OptIn(UnstableApi::class)
package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * (v596) تكامل الموبايل ↔ التلفزيون:
 *  1) نقل ملفات مباشر على الواي فاي بشريط تقدم (موبايل → تلفزيون و تلفزيون → موبايل) — XferOut / TvXfer / XferUi
 *  2) «سماعة خاصة»: الصوت بيتشغّل على الموبايل (سماعاته) والتلفزيون بيتكتم، والموبايل بيمشي مع مكان الفيديو — PrivateListen
 *  الملفات بتتبعت بطلبات Range (مفيش تحميل كامل في الرام) وبتستكمل لو الاتصال قطع.
 */
object Xfer {
    fun safeName(n: String): String {
        val b = n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trim('.')
        return if (b.isEmpty()) "file" else b.take(120)
    }

    /** (v607) فولدر الملفات الواصلة حسب النوع: فيديوهات Movies/Joyboy · صوتيات Music/Joyboy · صور Pictures/Joyboy · APK Download/Joyboy/APK · غير كده Download/Joyboy. لو مفيش كتابة: فولدر التطبيق */
    fun inboxDir(ctx: Context, cat: String = XCat.VIDEO): File {
        val pub: String; val sub: String
        when (cat) {
            XCat.AUDIO -> { pub = Environment.DIRECTORY_MUSIC; sub = "Joyboy" }
            XCat.IMAGE -> { pub = Environment.DIRECTORY_PICTURES; sub = "Joyboy" }
            XCat.APK, XCat.APP -> { pub = Environment.DIRECTORY_DOWNLOADS; sub = "Joyboy/APK" }
            XCat.VIDEO -> { pub = Environment.DIRECTORY_MOVIES; sub = "Joyboy" }
            else -> { pub = Environment.DIRECTORY_DOWNLOADS; sub = "Joyboy" }
        }
        try {
            val d = File(Environment.getExternalStoragePublicDirectory(pub), sub)
            if ((d.isDirectory || d.mkdirs()) && d.canWrite()) return d
        } catch (_: Throwable) {}
        val e = File(ctx.getExternalFilesDir(pub) ?: ctx.filesDir, sub)
        e.mkdirs()
        return e
    }

    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists() && !File(dir, name + ".part").exists()) return f
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        var i = 1
        while (i < 1000) {
            f = File(dir, base + " (" + i + ")" + (if (ext.isEmpty() || ext == name) "" else ".$ext"))
            if (!f.exists() && !File(dir, f.name + ".part").exists()) return f
            i++
        }
        return File(dir, System.currentTimeMillis().toString() + "_" + name)
    }

    fun toUri(s: String): Uri = if (s.startsWith("/")) Uri.fromFile(File(s)) else Uri.parse(s)

    fun sizeOf(ctx: Context, u: Uri): Long {
        try {
            if (u.scheme == "file") return File(u.path ?: "").length()
            ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { if (it.length > 0L) return it.length }
        } catch (_: Throwable) {}
        try { ctx.contentResolver.openFileDescriptor(u, "r")?.use { return it.statSize } } catch (_: Throwable) {}
        return -1L
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"; "ts" -> "video/mp2t"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"; "heic", "heif" -> "image/heif"
        "apk" -> "application/vnd.android.package-archive"; "apks", "xapk", "apkm" -> "application/octet-stream"
        else -> "application/octet-stream"
    }
}

/** مصدر ملف من Uri (file أو content) بيتقرا من مكانه الأصلي من أي موضع */
class UriSrc(private val ctx: Context, private val u: Uri, override val size: Long, override val name: String) : RangeSource {
    override fun open(start: Long): InputStream {
        if (u.scheme == "file" || u.scheme == null) {
            val f = FileInputStream(File(u.path ?: ""))
            if (start > 0) f.channel.position(start)
            return f
        }
        val pfd = ctx.contentResolver.openFileDescriptor(u, "r") ?: throw IOException("nf")
        val f = object : FileInputStream(pfd.fileDescriptor) {
            override fun close() { try { super.close() } finally { try { pfd.close() } catch (_: Throwable) {} } }
        }
        if (start > 0) f.channel.position(start)
        return f
    }
}

private class CountingIn(private val inner: InputStream, start: Long, private val onPos: (Long) -> Unit) : InputStream() {
    private var pos = start
    override fun read(): Int { val b = inner.read(); if (b >= 0) { pos++; onPos(pos) }; return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = inner.read(b, off, len); if (n > 0) { pos += n; onPos(pos) }; return n }
    override fun close() { inner.close() }
}

/** (الموبايل) سيرفر صغير بيقدّم الملفات المطلوب إرسالها للتلفزيون — لينك عشوائي لكل ملف، والتلفزيون هو اللي بيسحب */
object XferOut {
    const val PORT = 8766
    class Item(val tok: String, val name: String, val size: Long, val src: RangeSource) {
        @Volatile var max = 0L
        @Volatile var cancelled = false
    }
    private val items = ConcurrentHashMap<String, Item>()
    private var http: MiniHttp? = null

    @Synchronized private fun ensure(): Boolean {
        if (http?.running == true) return true
        val m = MiniHttp(PORT) { r -> handle(r) }
        if (!m.start()) return false
        http = m
        return true
    }

    private class Counted(private val it: Item) : RangeSource {
        override val size: Long get() = it.size
        override val name: String get() = it.name
        override fun open(start: Long): InputStream = CountingIn(it.src.open(start), start) { p ->
            if (it.cancelled) throw IOException("cancelled")
            if (p > it.max) it.max = p
        }
    }

    private fun handle(r: HttpReq): HttpRes {
        val p = r.path.split('/')
        if (p.size < 4 || p[1] != "x") return HttpRes.text("nf", 404)
        val it = items[p[2]] ?: return HttpRes.text("nf", 404)
        if (it.cancelled) return HttpRes.text("gone", 404)
        return HttpRes.stream(Counted(it), "application/octet-stream")
    }

    fun offer(ctx: Context, uri: Uri, name: String, size: Long): Item? {
        if (size <= 0L || !ensure()) return null
        val tok = ByteArray(10).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val it = Item(tok, name, size, UriSrc(ctx.applicationContext, uri, size, name))
        items[tok] = it
        return it
    }

    fun urlFor(it: Item, ip: String): String = "http://$ip:$PORT/x/${it.tok}/" + URLEncoder.encode(it.name, "UTF-8").replace("+", "%20")
    fun drop(it: Item) { items.remove(it.tok) }
}

/** (التلفزيون) استقبال ملف من الموبايل + تقديم ملفات للموبايل + كتم الصوت للسماعة الخاصة */
object TvXfer {
    private val h = Handler(Looper.getMainLooper())
    private fun err(code: Int, e: String) = HttpRes.json(JSONObject().put("ok", false).put("err", e).toString(), code)
    private fun ok(x: JSONObject = JSONObject()) = HttpRes.json(x.put("ok", true).toString())

    /** فيديو من مكتبة التلفزيون بالـid (للموبايل: تحميل) */
    fun fileLib(app: Context, r: HttpReq): HttpRes {
        val v = BrainRpc.findVideo(app, r.query["id"] ?: "") ?: return err(404, "nf")
        val u = Xfer.toUri(v.uri)
        val sz = Xfer.sizeOf(app, u)
        if (sz <= 0L) return err(404, "nf")
        return HttpRes.stream(UriSrc(app, u, sz, v.name), Xfer.mimeOf(v.name))
    }

    /** الملف اللي المشغّل شغّاله دلوقتي (لو محلي) — للسماعة الخاصة */
    fun fileCur(app: Context, r: HttpReq): HttpRes {
        val u = PlayerActivity.curUri() ?: return err(404, "nf")
        if (u.scheme == "http" || u.scheme == "https") return err(404, "remote")
        val sz = Xfer.sizeOf(app, u)
        if (sz <= 0L) return err(404, "nf")
        val nm = (u.lastPathSegment ?: "video.mp4").substringAfterLast('/')
        return HttpRes.stream(UriSrc(app, u, sz, nm), Xfer.mimeOf(nm))
    }

    /** الموبايل بيقول: اسحب الملف ده من عندي. لازم اللينك يكون على الموبايل نفسه (نفس الـIP اللي كلّمنا) */
    fun pull(app: Context, r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val url = j.optString("url")
        val name = Xfer.safeName(j.optString("name"))
        val size = j.optLong("size")
        val cat = j.optString("cat").ifEmpty { XCat.catOf(name) }
        val host = try { Uri.parse(url).host ?: "" } catch (_: Throwable) { "" }
        if (!url.startsWith("http://") || host.isEmpty() || host != r.peer) return err(403, "src")
        TaskCenter.add(app, "dm", "📥 $name") { t -> download(app, t, url, name, size, cat) }
        TvLink.notice("📥 بيوصل ملف من الموبايل: $name", 3000L)
        return ok()
    }

    private fun download(app: Context, t: TaskItem, url: String, name: String, size0: Long, cat: String = XCat.VIDEO) {
        val dir = Xfer.inboxDir(app, cat)
        val out = Xfer.uniqueFile(dir, name)
        val part = File(dir, out.name + ".part")
        part.delete()
        var total = size0
        var tries = 0
        while (true) {
            if (t.cancelled) { part.delete(); throw TaskCancelled() }
            var c: HttpURLConnection? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                c = conn
                conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.useCaches = false
                val have = part.length()
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("الموبايل رد بـ $code")
                val append = code == 206 && have > 0
                if (!append && have > 0) part.delete()
                if (total <= 0L) total = (if (append) have else 0L) + conn.contentLengthLong
                FileOutputStream(part, append).use { fo ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(131072)
                        var got = if (append) have else 0L
                        var last = 0L
                        while (true) {
                            if (t.cancelled) throw TaskCancelled()
                            val n = ins.read(buf)
                            if (n < 0) break
                            fo.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (now - last > 400L) {
                                last = now
                                t.pct = if (total > 0L) ((got * 100) / total).toInt().coerceIn(0, 99) else 0
                                t.msg = Sniff.fmtSize(got) + (if (total > 0L) " / " + Sniff.fmtSize(total) else "")
                                TaskCenter.changed()
                            }
                        }
                    }
                }
                if (total > 0L && part.length() < total) throw IOException("الملف ناقص")
                break
            } catch (e: TaskCancelled) { part.delete(); throw e
            } catch (e: IOException) {
                tries++
                if (tries >= 6) throw e
                try { Thread.sleep(1200) } catch (_: InterruptedException) {}
            } finally { try { c?.disconnect() } catch (_: Throwable) {} }
        }
        if (!part.renameTo(out)) throw IOException("ما قدرتش أحفظ الملف")
        t.outPath = out.absolutePath; t.outSize = out.length(); t.msg = ""
        try { android.media.MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), null, null) } catch (_: Throwable) {}
        try { VideoScan.cache = null } catch (_: Throwable) {}
        TvLink.notice("✅ وصل ملف من الموبايل: " + out.name, 3500L)
    }

    // ===== السماعة الخاصة: كتم صوت التلفزيون طول ما الموبايل بيبعت نبضة كل ~2 ثانية (لو الموبايل اختفى نرجّع الصوت) =====
    private var privPrev = -1
    private var appRef: Context? = null
    @Volatile private var privAt = 0L
    private val privDog = object : Runnable {
        override fun run() {
            if (privPrev < 0) return
            if (System.currentTimeMillis() - privAt > 9000L) restore() else h.postDelayed(this, 3000L)
        }
    }

    @Synchronized private fun restore() {
        val a = appRef ?: return
        if (privPrev >= 0) { SysVol.set(a, if (privPrev > 0) privPrev else 30); privPrev = -1 }
        h.removeCallbacks(privDog)
    }

    @Synchronized fun priv(app: Context, r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        appRef = app.applicationContext
        if (!j.optBoolean("on")) { restore(); return ok() }
        privAt = System.currentTimeMillis()
        if (privPrev < 0) { privPrev = SysVol.get(app); h.postDelayed(privDog, 3000L) }
        SysVol.set(app, 0)
        return ok(JSONObject().put("muted", SysVol.get(app) == 0))
    }
}

/** (الموبايل) شاشات نقل الملفات: إرسال ملف للتلفزيون + تحميل فيديو من مكتبة التلفزيون */
object XferUi {
    private val h = Handler(Looper.getMainLooper())

    private class Prog(val act: Activity, title: String) {
        @Volatile var cancelled = false
        private val tv = TextView(act).apply { textSize = 14f; gravity = Gravity.CENTER }
        private val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        private val dlg: AlertDialog
        init {
            val d = act.resources.displayMetrics.density
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (14 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
                addView(tv, LinearLayout.LayoutParams(-1, -2))
                addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() })
            }
            dlg = AlertDialog.Builder(act).setTitle(title).setView(box).setCancelable(false).setNegativeButton("إلغاء") { _, _ -> cancelled = true }.create()
            dlg.show()
        }
        fun set(msg: String, frac: Float) { act.runOnUiThread { tv.text = msg; bar.progress = (frac * 1000f).toInt().coerceIn(0, 1000) } }
        fun close() { act.runOnUiThread { try { dlg.dismiss() } catch (_: Throwable) {} } }
    }

    private fun toast(act: Activity, m: String) { act.runOnUiThread { Notice.show(act, m, 3200L) } }

    /** (v607) أي Dialog لازم يتبني على الـUI thread — الإرسال بيشتغل في خيط خلفي */
    private fun <T> onUi(act: Activity, f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        var r: Any? = null
        val l = java.util.concurrent.CountDownLatch(1)
        act.runOnUiThread { try { r = f() } finally { l.countDown() } }
        l.await(5, java.util.concurrent.TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST") return r as T
    }

    internal fun meta(ctx: Context, u: Uri): Pair<String, Long> {
        var name = ""; var size = -1L
        try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: ""
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Throwable) {}
        if (name.isEmpty()) name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
        if (size <= 0L) size = Xfer.sizeOf(ctx, u)
        return Pair(name, size)
    }

    private fun speed(bytes: Long, t0: Long): String {
        val s = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.5)
        return String.format("%.1f MB/s", bytes / s / 1048576.0)
    }

    /** (v652) إشعار تقدم الإرسال في شريط الحالة: نسبة + سرعة + الوقت المتبقي + عداد الملفات، وبيتحوّل لـ«خلص» في الآخر */
    private class SendNotif(val ctx: Context) {
        @Volatile var cancelled = false
        private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        private val id = 7311
        private var lastAt = 0L
        private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { cancelled = true } }
        init {
            try {
                nm.createNotificationChannel(NotificationChannel(CH, "إرسال ملفات للتلفزيون", NotificationManager.IMPORTANCE_LOW))
                nm.createNotificationChannel(NotificationChannel(CH_DONE, "خلص إرسال الملفات", NotificationManager.IMPORTANCE_DEFAULT))
            } catch (_: Throwable) {}
            try {
                val f = IntentFilter(ACT_CANCEL)
                if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(rx, f)
            } catch (_: Throwable) {}
        }
        private fun open(): PendingIntent? = try {
            PendingIntent.getActivity(ctx, 0, Intent(ctx, TransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } catch (_: Throwable) { null }
        private fun fmtT(sec: Long): String { val s = sec.coerceAtLeast(0L); return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60) }

        /** done/total بالبايت لكل الدفعة، t0 بداية الدفعة، file = رقم الملف الحالي من العدد الكلي */
        fun progress(file: Int, files: Int, name: String, done: Long, total: Long, t0: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastAt < 350L) return
            lastAt = now
            val pct = (done * 100L / total.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
            val el = ((now - t0) / 1000.0).coerceAtLeast(0.5)
            val bps = done / el
            val left = if (bps > 1.0) ((total - done) / bps).toLong() else -1L
            val txt = "$pct% · " + String.format("%.1f MB/s", bps / 1048576.0) + (if (left >= 0) " · باقي " + fmtT(left) else "") + "  (" + Sniff.fmtSize(done) + " / " + Sniff.fmtSize(total) + ")"
            val cancel = try { PendingIntent.getBroadcast(ctx, 1, Intent(ACT_CANCEL).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) } catch (_: Throwable) { null }
            try {
                val b = Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("📤 إرسال للتلفزيون" + (if (files > 1) " ($file/$files)" else ""))
                    .setContentText(txt).setSubText(name).setStyle(Notification.BigTextStyle().bigText(txt + "\n" + name))
                    .setProgress(1000, (done * 1000L / total.coerceAtLeast(1L)).toInt().coerceIn(0, 1000), false)
                    .setOngoing(true).setOnlyAlertOnce(true).setNumber((files - file + 1).coerceAtLeast(1)).setContentIntent(open())
                if (cancel != null) b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), "إلغاء", cancel).build())
                nm.notify(id, b.build())
            } catch (_: Throwable) {}
        }
        fun connecting(msg: String, file: Int, files: Int) {
            try {
                nm.notify(id, Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("📤 إرسال للتلفزيون" + (if (files > 1) " ($file/$files)" else "")).setContentText(msg)
                    .setProgress(0, 0, true).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open()).build())
            } catch (_: Throwable) {}
        }
        private fun end(icon: Int, title: String, text: String) {
            try { ctx.unregisterReceiver(rx) } catch (_: Throwable) {}
            try {
                nm.cancel(id)
                nm.notify(id + 1, Notification.Builder(ctx, CH_DONE).setSmallIcon(icon).setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open()).build())
            } catch (_: Throwable) {}
        }
        fun done(sent: Int, files: Int, bytes: Long, t0: Long) {
            val sec = ((System.currentTimeMillis() - t0) / 1000L).coerceAtLeast(1L)
            end(android.R.drawable.stat_sys_upload_done, "✅ خلص الإرسال للتلفزيون", "$sent من $files ملف · " + Sniff.fmtSize(bytes) + " في " + fmtT(sec))
        }
        fun fail(msg: String) = end(android.R.drawable.stat_notify_error, "⚠ الإرسال وقف", msg)
        fun stopped() = end(android.R.drawable.stat_notify_error, "⏹ اتلغى الإرسال", "")
        companion object { const val CH = "xfer_send"; const val CH_DONE = "xfer_done"; const val ACT_CANCEL = "com.tttt.joyboy.XFER_CANCEL" }
    }

    /** موبايل ← تلفزيون: الملفات بتتبعت واحد ورا التاني — والتقدم في إشعار شريط الحالة (v652) */
    fun sendUris(act: Activity, uris: List<Uri>, names: List<String>? = null) {
        val ctx = act.applicationContext
        Notice.show(act, "📤 بدأ الإرسال — التقدم في شريط الإشعارات", 2600L)
        Thread {
            val nf = SendNotif(ctx)
            val metas = uris.mapIndexed { i, u ->
                val (n0, sz) = meta(ctx, u)
                Pair(Xfer.safeName(names?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: n0), sz)
            }
            val totalBytes = metas.sumOf { it.second.coerceAtLeast(0L) }.coerceAtLeast(1L)
            var doneBytes = 0L; var sent = 0
            val t0 = System.currentTimeMillis()
            for ((idx, uri) in uris.withIndex()) {
                val (name, size) = metas[idx]
                var item: XferOut.Item? = null
                try {
                    if (size <= 0L) { toast(act, "⚠ ما قدرتش أعرف حجم «$name»"); continue }
                    nf.connecting("بوصّل بالتلفزيون… $name", idx + 1, uris.size)
                    if (!RemoteClient.ensureConnected(ctx, 8000L)) { nf.fail("ما قدرتش أوصل للتلفزيون"); return@Thread }
                    val tv = RemoteClient.current ?: run { nf.fail("مش متصل بالتلفزيون"); return@Thread }
                    val ip = RemoteClient.ipTowards(tv.ip)
                    val it = XferOut.offer(ctx, uri, name, size)
                    if (ip.isEmpty() || it == null) { nf.fail("لازم الموبايل والتلفزيون على نفس الواي فاي"); return@Thread }
                    item = it
                    val (c, _) = RemoteClient.postJson("/pull", JSONObject().put("url", XferOut.urlFor(it, ip)).put("name", name).put("size", size).put("cat", XCat.catOf(name)))
                    if (c != 200) { nf.fail("التلفزيون رفض الملف ($c) — حدّث البرنامج على التلفزيون"); return@Thread }
                    var idle = 0; var lastMax = -1L
                    while (!nf.cancelled) {
                        val m = it.max
                        nf.progress(idx + 1, uris.size, name, doneBytes + m, totalBytes, t0)
                        if (m >= size) break
                        idle = if (m == lastMax) idle + 1 else 0; lastMax = m
                        if (idle > 75) { nf.fail("التلفزيون وقف السحب"); return@Thread }   // 30 ثانية من غير تقدم
                        try { Thread.sleep(400) } catch (_: InterruptedException) {}
                    }
                    if (nf.cancelled) { it.cancelled = true; nf.stopped(); return@Thread }
                    doneBytes += size; sent++
                    nf.progress(idx + 1, uris.size, name, doneBytes, totalBytes, t0, force = true)
                } catch (e: Throwable) { LogStore.err("Xfer:send", e); nf.fail("فشل الإرسال"); return@Thread }
                finally { item?.let { x -> h.postDelayed({ XferOut.drop(x) }, 60000L) } }
            }
            if (sent > 0) nf.done(sent, uris.size, doneBytes, t0) else nf.fail("مفيش ملف اتبعت")
        }.also { it.isDaemon = true }.start()
    }

    /** تلفزيون ← موبايل: قايمة فولدرات ثم فيديوهات من مكتبة التلفزيون، والاختيار بينزّل */
    fun browseTv(act: Activity) {
        Notice.show(act, "⏳ بجيب مكتبة التلفزيون…", 1800L)
        Thread {
            try {
                if (!RemoteClient.ensureConnected(act.applicationContext, 8000L)) { toast(act, "⚠ ما قدرتش أوصل للتلفزيون"); return@Thread }
                val (c, t) = RemoteClient.getText("/lib/folders", 10000)
                if (c != 200) { toast(act, if (c == 403) "⚠ التلفزيون محتاج إذن الفيديوهات" else "⚠ ما قدرتش أجيب المكتبة ($c)"); return@Thread }
                val fa = JSONObject(t).optJSONArray("folders") ?: return@Thread
                val keys = ArrayList<String>(); val labels = ArrayList<String>()
                for (i in 0 until fa.length()) {
                    val f = fa.getJSONObject(i)
                    keys.add(f.optString("key")); labels.add("📁 " + f.optString("name") + "  (" + f.optInt("count") + ")")
                }
                act.runOnUiThread {
                    if (keys.isEmpty()) { Notice.show(act, "مفيش فيديوهات على التلفزيون", 2500L); return@runOnUiThread }
                    AlertDialog.Builder(act).setTitle("📥 اختار فولدر").setItems(labels.toTypedArray()) { _, i -> pickVideo(act, keys[i]) }.setNegativeButton("إغلاق", null).show()
                }
            } catch (e: Throwable) { LogStore.err("Xfer:browse", e); toast(act, "⚠ حصلت مشكلة") }
        }.also { it.isDaemon = true }.start()
    }

    private fun pickVideo(act: Activity, key: String) {
        Thread {
            try {
                val (c, t) = RemoteClient.getText("/lib/videos?folder=" + URLEncoder.encode(key, "UTF-8"), 10000)
                if (c != 200) { toast(act, "⚠ ما قدرتش أجيب الفيديوهات ($c)"); return@Thread }
                val va = JSONObject(t).optJSONArray("videos") ?: return@Thread
                val ids = ArrayList<Long>(); val names = ArrayList<String>(); val sizes = ArrayList<Long>(); val labels = ArrayList<String>()
                for (i in 0 until va.length()) {
                    val v = va.getJSONObject(i)
                    ids.add(v.optLong("id")); names.add(v.optString("name")); sizes.add(v.optLong("size"))
                    labels.add(v.optString("title") + "  ·  " + Sniff.fmtSize(v.optLong("size")))
                }
                act.runOnUiThread {
                    AlertDialog.Builder(act).setTitle("📥 اختار فيديو للتحميل").setItems(labels.toTypedArray()) { _, i -> download(act, ids[i], names[i], sizes[i]) }.setNegativeButton("رجوع", null).show()
                }
            } catch (e: Throwable) { LogStore.err("Xfer:pick", e); toast(act, "⚠ حصلت مشكلة") }
        }.also { it.isDaemon = true }.start()
    }

    private fun download(act: Activity, id: Long, name0: String, size0: Long) {
        val ctx = act.applicationContext
        val name = Xfer.safeName(name0)
        val p = Prog(act, "📥 $name")
        Thread {
            val dir = Xfer.inboxDir(ctx)
            val out = Xfer.uniqueFile(dir, name)
            val part = File(dir, out.name + ".part")
            part.delete()
            var total = size0
            var tries = 0
            val t0 = System.currentTimeMillis()
            try {
                while (!p.cancelled) {
                    val tv = RemoteClient.current ?: throw IOException("مش متصل بالتلفزيون")
                    var c: HttpURLConnection? = null
                    try {
                        val conn = URL("http://" + tv.ip + ":" + tv.port + "/file/lib?id=" + id).openConnection() as HttpURLConnection
                        c = conn
                        conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.useCaches = false
                        if (tv.token.isNotEmpty()) conn.setRequestProperty("X-Token", tv.token)
                        conn.setRequestProperty("X-Ver", LinkVer.code(ctx).toString())
                        val have = part.length()
                        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                        val code = conn.responseCode
                        if (code != 200 && code != 206) throw IOException("التلفزيون رد بـ $code")
                        val append = code == 206 && have > 0
                        if (!append && have > 0) part.delete()
                        if (total <= 0L) total = (if (append) have else 0L) + conn.contentLengthLong
                        FileOutputStream(part, append).use { fo ->
                            conn.inputStream.use { ins ->
                                val buf = ByteArray(131072)
                                var got = if (append) have else 0L
                                var last = 0L
                                while (!p.cancelled) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    fo.write(buf, 0, n); got += n
                                    val now = System.currentTimeMillis()
                                    if (now - last > 300L) { last = now; p.set(Sniff.fmtSize(got) + (if (total > 0L) " / " + Sniff.fmtSize(total) else "") + " · " + speed(got, t0), if (total > 0L) got.toFloat() / total else 0f) }
                                }
                            }
                        }
                        if (p.cancelled) break
                        if (total > 0L && part.length() < total) throw IOException("الملف ناقص")
                        if (!part.renameTo(out)) throw IOException("ما قدرتش أحفظ الملف")
                        try { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null) } catch (_: Throwable) {}
                        p.close(); toast(act, "✅ اتحمّل: " + out.name + "  (" + dir.name + ")")
                        return@Thread
                    } catch (e: IOException) {
                        tries++
                        if (tries >= 6) throw e
                        p.set("الاتصال قطع — بحاول أكمّل… ($tries)", if (total > 0L) part.length().toFloat() / total else 0f)
                        try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                    } finally { try { c?.disconnect() } catch (_: Throwable) {} }
                }
                part.delete(); p.close(); toast(act, "⏹ اتلغى التحميل")
            } catch (e: Throwable) { LogStore.err("Xfer:dl", e); p.close(); toast(act, "⚠ فشل التحميل: " + (e.message ?: "")) }
        }.also { it.isDaemon = true }.start()
    }
}

/** (الموبايل) «سماعة خاصة»: بيشغّل صوت اللي شغّال على التلفزيون في سماعات الموبايل ويكتم التلفزيون */
object PrivateListen {
    @Volatile var on = false
        private set
    @Volatile var status = ""
        private set
    @Volatile var listener: ((Boolean, String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var cur = ""
    private var warnedMute = false

    private fun setStatus(s: String) { status = s; val l = listener; if (l != null) main.post { try { l(on, s) } catch (_: Throwable) {} } }

    fun start(ctx: Context) {
        if (on) return
        on = true; warnedMute = false
        setStatus("🎧 بوصّل…")
        val app = ctx.applicationContext
        Thread {
            while (on) {
                try { tick(app) } catch (e: Throwable) { LogStore.err("PrivListen", e) }
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        if (!on) return
        on = false
        main.post { try { player?.release() } catch (_: Throwable) {}; player = null; cur = "" }
        Thread { try { RemoteClient.postJson("/priv", JSONObject().put("on", false)) } catch (_: Throwable) {} }.also { it.isDaemon = true }.start()
        setStatus("")
    }

    private fun tick(app: Context) {
        if (!RemoteClient.ensureConnected(app, 6000L)) { setStatus("⚠ مش متصل بالتلفزيون"); main.post { try { player?.playWhenReady = false } catch (_: Throwable) {} }; return }
        val (pc, pb) = RemoteClient.postJson("/priv", JSONObject().put("on", true))
        if (pc != 200) { setStatus("⚠ التلفزيون محتاج تحديث للسماعة الخاصة"); return }
        val muted = try { JSONObject(pb).optBoolean("muted", true) } catch (_: Throwable) { true }
        if (!muted && !warnedMute) { warnedMute = true; main.post { Notice.show(app, "⚠ التلفزيون مارضيش يتكتم — اكتمه بالريموت", 3500L) } }
        val t0 = System.currentTimeMillis()
        val st = RemoteClient.player("state")
        val t1 = System.currentTimeMillis()
        val src = st?.optString("src", "") ?: ""
        if (st == null || src.isEmpty()) {
            setStatus("🎧 شغّال — مستني فيديو على التلفزيون")
            main.post { try { player?.playWhenReady = false } catch (_: Throwable) {} }
            return
        }
        val tv = RemoteClient.current ?: return
        val url: String
        val hdr = HashMap<String, String>()
        if (src == "local") { url = "http://" + tv.ip + ":" + tv.port + "/file/cur"; hdr["X-Token"] = tv.token }
        else { url = src; st.optJSONObject("hdr")?.let { o -> val ks = o.keys(); while (ks.hasNext()) { val k = ks.next(); hdr[k] = o.optString(k) } } }
        val playing = st.optBoolean("playing")
        val pos = st.optLong("pos") + (t1 - t0) / 2
        main.post { applyState(app, url, hdr, playing, pos, t1) }
        setStatus("🎧 الصوت على الموبايل" + (if (playing) "" else " (واقف)"))
    }

    private fun applyState(app: Context, url: String, hdr: Map<String, String>, playing: Boolean, pos: Long, at: Long) {
        if (!on) return
        var p = player
        if (p == null || url != cur) {
            try { p?.release() } catch (_: Throwable) {}
            val dsf = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(hdr)
            val np = ExoPlayer.Builder(app).setMediaSourceFactory(DefaultMediaSourceFactory(dsf)).build()
            np.trackSelectionParameters = np.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            np.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            np.setWakeMode(C.WAKE_MODE_NETWORK)
            np.setMediaItem(MediaItem.fromUri(url))
            np.prepare()
            np.seekTo((pos + (System.currentTimeMillis() - at)).coerceAtLeast(0L))
            player = np; cur = url; p = np
        }
        val pl = p ?: return
        val target = (pos + (System.currentTimeMillis() - at)).coerceAtLeast(0L)
        if (playing) {
            if (Math.abs(pl.currentPosition - target) > 1500L) pl.seekTo(target)
            if (!pl.playWhenReady) pl.playWhenReady = true
        } else {
            if (pl.playWhenReady) pl.playWhenReady = false
            if (Math.abs(pl.currentPosition - pos) > 800L) pl.seekTo(pos)
        }
    }
}
