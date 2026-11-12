package com.tttt.subtitler

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.IOException

/**
 * (v324) تحميلات المتصفح (ملفات عادية: zip · apk · pdf · صور…) بتظهر في صفحة «المهام» بتقدّمها.
 * التحميل نفسه بيفضل شغّال في DownloadManager بتاع النظام (استكمال + إشعار الانتهاء)؛ المهمة هنا بتتابعه بس
 * (kind = "dm" في TaskCenter: كل واحدة في خيط لوحدها من غير طابور).
 * ✕ إلغاء / ⏹ إيقاف بيمسحوا التحميل من النظام؛ 🔁 إعادة بيبدأ تحميل جديد من الأول.
 */
/** (v736) حد 3 تحميلات متوازية لكل موقع: الباقي بيستنى في المهام («في الانتظار») ويبدأ لحظة ما واحد يخلص، بدل ما يتبعت كله لـDownloadManager ويفضل يعيد المحاولة بتأخير */
object DmGate {
    private val sems = HashMap<String, java.util.concurrent.Semaphore>()
    @Synchronized fun sem(host: String): java.util.concurrent.Semaphore = sems.getOrPut(host) { java.util.concurrent.Semaphore(3, true) }
}

object DmTasks {
    fun start(app: Context, url: String, name: String, mime: String?, ua: String, cookie: String?, referer: String) {
        TaskCenter.add(app, "dm", "⬇ $name") { t -> run(app, t, url, name, mime, ua, cookie, referer) }
    }

    /** (v716) لو DownloadManager فشل (403 / اسم غلط / مش بيدعم اللينك…) بنكمّل بنفس الكوكيز والـ UA والـ Referer بمُحمّل البرنامج نفسه بدل ما المهمة تفشل */
    private fun fallback(app: Context, t: TaskItem, url: String, name: String, mime: String?, ua: String, cookie: String?, referer: String, why: String) {
        val hd = HashMap<String, String>()
        if (ua.isNotEmpty()) hd["User-Agent"] = ua
        if (!cookie.isNullOrEmpty()) hd["Cookie"] = cookie
        if (referer.isNotBlank()) hd["Referer"] = referer
        val ctl = Downloader.Ctl(); ctl.ext = { t.cancelled }
        t.onCancel = { ctl.cancelled = true }
        t.msg = "بجرّب طريقة تانية…"; t.pct = 0; TaskCenter.changed()
        val path = try { Downloader.fileTo(app, url, hd, name, mime, ctl) { p -> t.pct = p.coerceIn(0, 99); TaskCenter.changed() } }
        catch (e: TaskCancelled) { throw e }
        catch (e: Throwable) { throw IOException("التحميل فشل ($why) — " + (e.message ?: e.javaClass.simpleName).take(60)) }
        t.outPath = path; t.msg = ""
        Downloader.takeLastOut()?.let { t.outUri = it.first; t.mime = it.second }
    }

    private fun run(app: Context, t: TaskItem, url: String, name0: String, mime: String?, ua: String, cookie: String?, referer: String) {
        if (!url.startsWith("http", true)) throw IOException("اللينك ده مش قابل للتحميل المباشر (" + url.substringBefore(':') + ")")
        val host = try { (java.net.URI(url).host ?: "").lowercase() } catch (_: Throwable) { "" }
        val sem = DmGate.sem(host)
        var waited = false
        while (true) {
            val ok = try { sem.tryAcquire(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { throw TaskCancelled() }
            if (ok) break
            if (t.cancelled) throw TaskCancelled()
            if (!waited) { waited = true; t.state = 0; t.msg = "⏳ مستني دوره (3 تحميلات شغّالة من نفس الموقع)"; TaskCenter.changed() }
        }
        try {
            if (t.cancelled) throw TaskCancelled()
            if (waited) { t.state = 1; t.msg = ""; TaskCenter.changed() }
            runBody(app, t, url, name0, mime, ua, cookie, referer)
        } finally { sem.release() }
    }

    private fun runBody(app: Context, t: TaskItem, url: String, name0: String, mime: String?, ua: String, cookie: String?, referer: String) {
        val name = name0.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(120).ifBlank { "download" }
        // (v783) تحميل قابل للإيقاف المؤقت والاستكمال لو السيرفر بيدعم Range؛ غير كده DownloadManager زي الأول
        if (app.getSharedPreferences("p", 0).getBoolean("sv_dl_own", true)) {
            val sz = ResumableDl.probe(url, ua, cookie, referer)
            if (sz >= 512 * 1024L) { ResumableDl.run(app, t, url, name, mime, ua, cookie, referer, sz); return }
        }
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        fun enq(): Long {
            val rq = DownloadManager.Request(Uri.parse(url))
            if (!cookie.isNullOrEmpty()) rq.addRequestHeader("Cookie", cookie)
            if (ua.isNotEmpty()) rq.addRequestHeader("User-Agent", ua)
            if (referer.isNotBlank()) rq.addRequestHeader("Referer", referer)
            if (!mime.isNullOrBlank()) rq.setMimeType(mime)
            rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            return try { dm.enqueue(rq) } catch (e: Throwable) { -1L }
        }
        var id = enq()
        if (id < 0) { fallback(app, t, url, name, mime, ua, cookie, referer, "enqueue"); return }
        t.onCancel = { try { dm.remove(id) } catch (_: Throwable) {} }
        var stuckSince = 0L; var kicks = 0
        while (true) {
            if (t.cancelled) { try { dm.remove(id) } catch (_: Throwable) {}; throw TaskCancelled() }
            val c = dm.query(DownloadManager.Query().setFilterById(id))
            var finished = false
            var failedWhy: String? = null
            var kick = false
            try {
                // اتمسح من تطبيق التنزيلات بتاع النظام = اتلغى
                if (!c.moveToFirst()) throw TaskCancelled()
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val local = try { c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) } catch (_: Throwable) { null }
                        val saved = local?.let { Uri.parse(it).lastPathSegment }?.takeIf { it.isNotBlank() } ?: name
                        t.outUri = try { dm.getUriForDownloadedFile(id) } catch (_: Throwable) { null }
                        t.mime = try { dm.getMimeTypeForDownloadedFile(id) } catch (_: Throwable) { null } ?: mime ?: "*/*"
                        t.outPath = Environment.DIRECTORY_DOWNLOADS + "/" + saved
                        t.outSize = if (total > 0) total else got
                        t.msg = ""
                        finished = true
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = try { c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) } catch (_: Throwable) { -1 }
                        failedWhy = "كود $reason"
                    }
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                        t.msg = "مستني النت أو الشبكة…"
                        // (v736) السيرفر رفض/قفل الاتصال (حد التحميلات المتزامنة): DownloadManager بيعيد بتأخير بيكبر — بنعيد الطلب بنفسنا بعد 25 ث
                        val why = if (st == DownloadManager.STATUS_PAUSED) (try { c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) } catch (_: Throwable) { -1 }) else 1
                        if (why == 1) {
                            val nw = System.currentTimeMillis()
                            if (stuckSince == 0L) stuckSince = nw
                            if (nw - stuckSince > 25_000L && kicks < 3) kick = true
                        } else stuckSince = 0L
                    }
                    else -> { stuckSince = 0L; t.msg = Sniff.fmtSize(got) + (if (total > 0) " / " + Sniff.fmtSize(total) else "") }
                }
                if (st == DownloadManager.STATUS_RUNNING && total > 0) t.pct = ((got * 100) / total).toInt().coerceIn(0, 99)
            } finally { try { c.close() } catch (_: Throwable) {} }
            if (failedWhy != null) { try { dm.remove(id) } catch (_: Throwable) {}; fallback(app, t, url, name, mime, ua, cookie, referer, failedWhy); return }
            if (finished) return
            if (kick) {
                try { dm.remove(id) } catch (_: Throwable) {}
                kicks++; stuckSince = 0L
                id = enq()
                if (id < 0) { fallback(app, t, url, name, mime, ua, cookie, referer, "enqueue"); return }
                t.msg = "↻ بعيد المحاولة…"
            }
            TaskCenter.changed()
            try { Thread.sleep(700) } catch (_: InterruptedException) { throw TaskCancelled() }
        }
    }
}

/**
 * (v783) تحميل ملفات المتصفح قابل للإيقاف المؤقت والاستكمال: HTTP Range + ملف .part في مساحة التطبيق.
 * بيشتغل بس لو السيرفر بيدعم Range وحجم الملف معروف (≥ 512KB)؛ غير كده التحميل بيكمّل بـDownloadManager زي الأول.
 * النت قطع / اتبدّل بين خلوي وواي فاي = بيكمّل من آخر بايت لوحده (لحد 12 محاولة).
 */
object ResumableDl {
    private fun conn(url: String, ua: String, cookie: String?, referer: String, range: String): java.net.HttpURLConnection {
        var u = url
        for (hop in 0..5) {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 20000
            if (ua.isNotEmpty()) c.setRequestProperty("User-Agent", ua)
            if (!cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
            if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect من غير Location")
                u = java.net.URL(java.net.URL(u), loc).toString(); c.disconnect(); continue
            }
            return c
        }
        throw IOException("تحويلات كتير")
    }

    /** حجم الملف لو Range مدعوم، وإلا -1 */
    fun probe(url: String, ua: String, cookie: String?, referer: String): Long {
        var c: java.net.HttpURLConnection? = null
        return try {
            c = conn(url, ua, cookie, referer, "bytes=0-0")
            if (c.responseCode != 206) -1L
            else (c.getHeaderField("Content-Range") ?: "").substringAfter('/', "").trim().toLongOrNull() ?: -1L
        } catch (_: Throwable) { -1L } finally { try { c?.disconnect() } catch (_: Throwable) {} }
    }

    private fun publish(app: Context, f: java.io.File, name: String, mime: String?): Pair<Uri?, String> {
        val m = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, m)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("مش قادر أكتب في مجلد التنزيلات")
            (app.contentResolver.openOutputStream(uri) ?: throw IOException("مش قادر أفتح الملف للكتابة")).use { out -> f.inputStream().use { it.copyTo(out, 65536) } }
            val done = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }
            app.contentResolver.update(uri, done, null, null)
            f.delete()
            return uri to (Environment.DIRECTORY_DOWNLOADS + "/" + name)
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); dir.mkdirs()
        var dst = java.io.File(dir, name); var n = 1
        while (dst.exists()) { dst = java.io.File(dir, name.substringBeforeLast('.', name) + " ($n)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); n++ }
        f.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 65536) } }
        f.delete()
        return Uri.fromFile(dst) to (Environment.DIRECTORY_DOWNLOADS + "/" + dst.name)
    }

    fun run(app: Context, t: TaskItem, url: String, name: String, mime: String?, ua: String, cookie: String?, referer: String, total: Long) {
        val dir = java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "dl"); dir.mkdirs()
        val key = java.security.MessageDigest.getInstance("SHA-1").digest((url + "|" + name).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val part = java.io.File(dir, "$key.part")
        t.partFiles.add(part.absolutePath)
        // (v819) تحميل بأكتر من مسار (زي IDM): الملف بيتقسّم لقطع بتتسحب مع بعض، وبيكمّل من اللي اتحمّل لو وقف أو فشل
        if (SegDl.conns() > 1 && total >= SegDl.MIN_SIZE) {
            val seg = java.io.File(dir, "$key.seg"); t.partFiles.add(seg.absolutePath)
            val hd = HashMap<String, String>()
            if (ua.isNotEmpty()) hd["User-Agent"] = ua
            if (!cookie.isNullOrEmpty()) hd["Cookie"] = cookie
            if (referer.isNotBlank()) hd["Referer"] = referer
            val ctl = Downloader.Ctl(); ctl.ext = { t.cancelled }
            t.onCancel = { ctl.cancelled = true }
            t.onPause = { p -> ctl.paused = p }
            val pg = Downloader.Prog(t)
            SegDl.fetch(url, hd, seg, total, SegDl.conns(), ctl, {
                while (ctl.paused && !ctl.stop()) { t.msg = "⏸ متوقف مؤقتًا — " + Sniff.fmtSize(t.dlDone) + " / " + Sniff.fmtSize(total); TaskCenter.changed(); try { Thread.sleep(300) } catch (_: InterruptedException) { throw TaskCancelled() } }
                if (ctl.stop()) throw TaskCancelled()
            }) { d ->
                t.pct = ((d * 100) / total).toInt().coerceIn(0, 99)
                t.msg = Sniff.fmtSize(d) + " / " + Sniff.fmtSize(total) + " · " + SegDl.conns() + " مسارات"
                pg.upd(d, total)
            }
            t.msg = "بحفظ الملف…"; t.pct = 99; TaskCenter.changed()
            val saved = publish(app, seg, name, mime)
            try { java.io.File(seg.path + ".meta").delete() } catch (_: Throwable) {}
            t.partFiles.remove(seg.absolutePath)
            t.outUri = saved.first; t.mime = mime?.takeIf { it.isNotBlank() } ?: "*/*"; t.outPath = saved.second; t.outSize = total; t.msg = ""
            return
        }
        val paused = java.util.concurrent.atomic.AtomicBoolean(false)
        t.onPause = { p -> paused.set(p) }
        val liveConn = java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>(null)
        t.onCancel = { try { liveConn.get()?.disconnect() } catch (_: Throwable) {} }   // (v819) الإلغاء بيقطع الاتصال فورًا
        var fails = 0
        var lastUi = 0L
        while (part.length() < total) {
            if (t.cancelled) { if (t.wipe) part.delete(); throw TaskCancelled() }
            if (paused.get()) { t.msg = "⏸ متوقف مؤقتًا — " + Sniff.fmtSize(part.length()) + " / " + Sniff.fmtSize(total); TaskCenter.changed(); try { Thread.sleep(300) } catch (_: InterruptedException) { throw TaskCancelled() }; continue }
            var c: java.net.HttpURLConnection? = null
            try {
                val from = part.length()
                c = conn(url, ua, cookie, referer, "bytes=$from-"); liveConn.set(c)
                val code = c.responseCode
                if (!(code == 206 || (code == 200 && from == 0L))) throw IOException("السيرفر رد بكود $code")
                if (code == 200 && from > 0L) throw IOException("السيرفر وقف الاستكمال")
                c.inputStream.use { ins ->
                    java.io.RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(from)
                        val buf = ByteArray(32768)
                        while (true) {
                            if (t.cancelled) { if (t.wipe) part.delete(); throw TaskCancelled() }
                            if (paused.get()) break
                            val n = ins.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); fails = 0
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 500L) {
                                lastUi = now
                                val got = raf.filePointer
                                t.pct = ((got * 100) / total).toInt().coerceIn(0, 99)
                                t.msg = Sniff.fmtSize(got) + " / " + Sniff.fmtSize(total)
                                TaskCenter.changed()
                            }
                        }
                    }
                }
            } catch (e: TaskCancelled) { throw e }
            catch (e: IOException) {
                if (t.cancelled) { if (t.wipe) part.delete(); throw TaskCancelled() }
                fails++
                if (fails > 12) throw IOException("التحميل وقف بعد محاولات كتير — " + (e.message ?: "").take(60) + " (اللي اتحمّل محفوظ، اضغط إعادة)")
                t.msg = "↻ النت قطع — هكمّل من " + Sniff.fmtSize(part.length()) + " (محاولة $fails)"; TaskCenter.changed()
                try { Thread.sleep(minOf(1500L * fails, 12000L)) } catch (_: InterruptedException) { throw TaskCancelled() }
            } finally { try { c?.disconnect() } catch (_: Throwable) {} }
        }
        if (part.length() != total) { part.delete(); throw IOException("الحجم النهائي مش مطابق — اتمسح الملف الناقص") }
        t.msg = "بحفظ الملف…"; t.pct = 99; TaskCenter.changed()
        val saved = publish(app, part, name, mime)
        t.outUri = saved.first; t.mime = mime?.takeIf { it.isNotBlank() } ?: "*/*"; t.outPath = saved.second; t.outSize = total; t.msg = ""
    }
}
