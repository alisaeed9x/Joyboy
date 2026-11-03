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
        val name = name0.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(120).ifBlank { "download" }
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val rq = DownloadManager.Request(Uri.parse(url))
        if (!cookie.isNullOrEmpty()) rq.addRequestHeader("Cookie", cookie)
        if (ua.isNotEmpty()) rq.addRequestHeader("User-Agent", ua)
        if (referer.isNotBlank()) rq.addRequestHeader("Referer", referer)
        if (!mime.isNullOrBlank()) rq.setMimeType(mime)
        rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        val id = try { dm.enqueue(rq) } catch (e: Throwable) { -1L }
        if (id < 0) { fallback(app, t, url, name, mime, ua, cookie, referer, "enqueue"); return }
        t.onCancel = { try { dm.remove(id) } catch (_: Throwable) {} }
        while (true) {
            if (t.cancelled) { try { dm.remove(id) } catch (_: Throwable) {}; throw TaskCancelled() }
            val c = dm.query(DownloadManager.Query().setFilterById(id))
            var finished = false
            var failedWhy: String? = null
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
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> t.msg = "مستني النت أو الشبكة…"
                    else -> t.msg = Sniff.fmtSize(got) + (if (total > 0) " / " + Sniff.fmtSize(total) else "")
                }
                if (st == DownloadManager.STATUS_RUNNING && total > 0) t.pct = ((got * 100) / total).toInt().coerceIn(0, 99)
            } finally { try { c.close() } catch (_: Throwable) {} }
            if (failedWhy != null) { try { dm.remove(id) } catch (_: Throwable) {}; fallback(app, t, url, name, mime, ua, cookie, referer, failedWhy); return }
            if (finished) return
            TaskCenter.changed()
            try { Thread.sleep(700) } catch (_: InterruptedException) { throw TaskCancelled() }
        }
    }
}
