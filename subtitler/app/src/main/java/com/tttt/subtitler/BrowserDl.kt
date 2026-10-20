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

    private fun run(app: Context, t: TaskItem, url: String, name: String, mime: String?, ua: String, cookie: String?, referer: String) {
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val rq = DownloadManager.Request(Uri.parse(url))
        if (!cookie.isNullOrEmpty()) rq.addRequestHeader("Cookie", cookie)
        if (ua.isNotEmpty()) rq.addRequestHeader("User-Agent", ua)
        if (referer.isNotBlank()) rq.addRequestHeader("Referer", referer)
        if (!mime.isNullOrBlank()) rq.setMimeType(mime)
        rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        val id = dm.enqueue(rq)
        t.onCancel = { try { dm.remove(id) } catch (_: Throwable) {} }
        while (true) {
            if (t.cancelled) { try { dm.remove(id) } catch (_: Throwable) {}; throw TaskCancelled() }
            val c = dm.query(DownloadManager.Query().setFilterById(id))
            var finished = false
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
                        throw IOException("التحميل فشل (كود $reason)")
                    }
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> t.msg = "مستني النت أو الشبكة…"
                    else -> t.msg = Sniff.fmtSize(got) + (if (total > 0) " / " + Sniff.fmtSize(total) else "")
                }
                if (st == DownloadManager.STATUS_RUNNING && total > 0) t.pct = ((got * 100) / total).toInt().coerceIn(0, 99)
            } finally { try { c.close() } catch (_: Throwable) {} }
            if (finished) return
            TaskCenter.changed()
            try { Thread.sleep(700) } catch (_: InterruptedException) { throw TaskCancelled() }
        }
    }
}
