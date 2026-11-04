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
