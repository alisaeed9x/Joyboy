package com.tttt.subtitler

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** (v1288) yt-dlp (youtubedl-android: yt-dlp + ffmpeg) كمحرك تحميل لأي لينك. بيتسجّل كمهمة في مركز المهام. */
object YtDlp {
    @Volatile private var ready = false

    @Synchronized private fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        try { com.yausername.ffmpeg.FFmpeg.getInstance().init(ctx.applicationContext) } catch (_: Throwable) {}
        ready = true
        autoFresh(ctx.applicationContext)   // (v1308)
    }

    /** (v1308) التطبيق بيحدّث yt-dlp من جوّاه (المكتبة المضمّنة بتقدم بعد 90 يوم) — بيرجّع (نجح؟ · رسالة للمستخدم) */
    fun selfUpdate(ctx: Context): Pair<Boolean, String> {
        val app = ctx.applicationContext
        return try {
            init(app)
            var st = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            if (st != YoutubeDL.UpdateStatus.DONE) {
                st = try { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.NIGHTLY) } catch (_: Throwable) { st }
            }
            val v = try { YoutubeDL.getInstance().versionName(app) } catch (_: Throwable) { null }
            Cfg.put("ytdlp_fresh", System.currentTimeMillis().toString())
            if (st == YoutubeDL.UpdateStatus.DONE) true to "✅ اتحدّث yt-dlp" + (if (v != null) " للنسخة $v" else "")
            else true to "✅ yt-dlp محدّث أصلًا" + (if (v != null) " ($v)" else "")
        } catch (e: Throwable) {
            false to "⚠ فشل تحديث yt-dlp: " + (e.message ?: e.javaClass.simpleName).take(90)
        }
    }

    /** (v1308) تحديث صامت في الخلفية مرة كل 3 أيام — عشان المشكلة مترجعش */
    private fun autoFresh(app: Context) {
        try {
            val last = Cfg.str("ytdlp_fresh", "0").toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - last < 3L * 24 * 3600 * 1000) return
            Cfg.put("ytdlp_fresh", System.currentTimeMillis().toString())
            Thread { try { selfUpdate(app) } catch (_: Throwable) {} }.apply { isDaemon = true }.start()
        } catch (_: Throwable) {}
    }

    /** خيار تحميل في القايمة: الليبل (جودة + حجم تقريبي لو متاح) · maxH = أقصى ارتفاع (0 = أعلى جودة) · audioOnly = mp3 */
    private class QOpt(val label: String, val maxH: Int, val audioOnly: Boolean)

    /** (v1308) الشيت بيفتح فورًا بكل الجودات (من غير ما يستنى الأحجام)، وبعدين الجودات الحقيقية للمصدر وأحجامها بتظهر جنب كل صف لما yt-dlp يجيبها */
    fun ask(act: android.app.Activity, url: String) {
        val th = Themes.current(); val ui = Ui(act, th)
        val box = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        val d = GAlert(act).setTitle("⬇ تحميل بـ yt-dlp").setView(box).setNegativeButton("إلغاء", null).show()
        class RowV(val maxH: Int, val audioOnly: Boolean, val base: String, val tv: android.widget.TextView)
        val rows = ArrayList<RowV>()
        fun addRow(label: String, maxH: Int, audioOnly: Boolean) {
            val tv = ui.text(label, 14.5f, th.text).apply {
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); foreground = YtSkin.ripple()
                setOnClickListener {
                    d.dismiss(); start(act, url, audioOnly = audioOnly, maxH = maxH)
                    Notice.show(act, "اتضاف لمركز المهام (yt-dlp)", 2200L)
                }
            }
            box.addView(tv)
            rows.add(RowV(maxH, audioOnly, label, tv))
        }
        addRow("⬇ فيديو — أعلى جودة (mp4)", 0, false)
        addRow("⬇ فيديو — 2160p (4K)", 2160, false)
        addRow("⬇ فيديو — 1440p (2K)", 1440, false)
        addRow("⬇ فيديو — 1080p", 1080, false)
        addRow("⬇ فيديو — 720p", 720, false)
        addRow("⬇ فيديو — 480p", 480, false)
        addRow("⬇ فيديو — 360p", 360, false)
        addRow("🎵 صوت بس (mp3)", 0, true)
        Thread {
            val qi = try { init(act.applicationContext); qualityInfo(url) } catch (e: Throwable) { LogStore.err("YtDlp:info", e); null }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                val (byH, aSize) = qi ?: return@runOnUiThread   // ماجبناش معلومات: القايمة الكاملة فاضلة زي ما هي
                val maxH = byH.firstKey()
                fun sizeFor(h: Int): Long {
                    var best = -1L
                    for ((fh, fs) in byH) { if (h == 0 || fh <= h) { if (fs > best) best = fs; break } }
                    return if (best > 0 && aSize > 0) best + aSize else best
                }
                for (r in ArrayList(rows)) {
                    if (r.audioOnly) {
                        if (aSize > 0) r.tv.text = r.base + " · ~" + Sniff.fmtSize(aSize)
                        continue
                    }
                    if (r.maxH > 0 && r.maxH > maxH) { box.removeView(r.tv); rows.remove(r); continue }   // جودة أعلى من الفيديو نفسه: مالهاش لازمة
                    val sz = sizeFor(r.maxH)
                    if (sz > 0) r.tv.text = r.base + " · ~" + Sniff.fmtSize(sz)
                    if (r.maxH == 0) r.tv.text = "⬇ فيديو — أعلى جودة (" + maxH + "p)" + (if (sz > 0) " · ~" + Sniff.fmtSize(sz) else "")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** (v1302+v1308) خريطة (ارتفاع ← أكبر حجم صيغة عنده) + حجم أحسن مسار صوت — من معلومات yt-dlp من غير تحميل. null = ماقدرناش نجيب معلومات */
    private fun qualityInfo(url: String): Pair<java.util.TreeMap<Int, Long>, Long>? {
        val info = try { YoutubeDL.getInstance().getInfo(url) } catch (_: Throwable) { null } ?: return null
        val fmts = try { info.formats } catch (_: Throwable) { null } ?: return null
        fun sizeOf(f: com.yausername.youtubedl_android.mapper.VideoFormat): Long = try {
            val s = f.fileSize; if (s > 0) s else f.fileSizeApproximate   // (v1304 fix: المكتبة بتسمّيهم fileSize/fileSizeApproximate)
        } catch (_: Throwable) { -1L }
        var aSize = -1L
        for (f in fmts) {
            val vc: String? = try { f.vcodec } catch (_: Throwable) { null }
            val ac: String? = try { f.acodec } catch (_: Throwable) { null }
            if ((vc == null || vc == "none") && ac != null && ac != "none") { val sz = sizeOf(f); if (sz > aSize) aSize = sz }
        }
        val byH = java.util.TreeMap<Int, Long>(java.util.Collections.reverseOrder())
        for (f in fmts) {
            val vc: String? = try { f.vcodec } catch (_: Throwable) { null }
            if (vc == null || vc == "none") continue
            val h = try { f.height } catch (_: Throwable) { 0 }
            if (h <= 0) continue
            val sz = sizeOf(f); if (sz > (byH[h] ?: -1L)) byH[h] = sz
        }
        if (byH.isEmpty()) return null
        return byH to aSize
    }

    /** (v1308) fallback تلقائي للمحمّل العادي (روابط مباشرة من YtFast) لو yt-dlp فشل نهائيًا — بيختار أقرب جودة ≤ maxH */
    private fun fallbackOld(app: Context, url: String, audioOnly: Boolean, maxH: Int): Boolean {
        return try {
            var id = url.substringAfter("watch?v=", "")
            if (id.length < 11) id = url.substringAfter("youtu.be/", "")
            id = id.take(11)
            if (id.length != 11) return false
            val g = try { YtFast.video(id, 0) } catch (_: Throwable) { null } ?: return false
            val it = NpItem(id, g.title, "", "", "", 0L, -1L, "", 0, url)
            if (audioOnly) {
                var au = g.audio ?: ""
                if (au.isBlank()) try {
                    val a = org.json.JSONArray(g.qlist)
                    for (i in 0 until a.length()) { val u2 = a.getJSONObject(i).optString("a"); if (u2.isNotBlank()) { au = u2; break } }
                } catch (_: Throwable) {}
                if (au.isBlank()) au = try { YtFast.audio(id, 0)?.url ?: "" } catch (_: Throwable) { "" }
                if (au.isBlank()) return false
                YtDlTask.start(app, it, au, null, g.ua, true, 0)
            } else {
                var bestU = g.url; var bestA = g.audio ?: ""; var bestH = 0
                try {
                    val a = org.json.JSONArray(g.qlist)
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val h = o.optInt("h", 0); val u2 = o.optString("u")
                        if (u2.isNotBlank() && h > bestH && (maxH <= 0 || h <= maxH)) { bestH = h; bestU = u2; bestA = o.optString("a", bestA) }
                    }
                } catch (_: Throwable) {}
                if (bestU.isBlank()) return false
                YtDlTask.start(app, it, bestU, bestA.ifBlank { null }, g.ua, false, bestH)
            }
            true
        } catch (_: Throwable) { false }
    }

    /** بيدّي لينك (صفحة فيديو من أي موقع) لـyt-dlp. audioOnly = صوت mp3. maxH = أقصى جودة (0 = أعلى جودة). (v1304) clipStart/clipEnd بالثواني = اقتصاص مقطع بس */
    fun start(ctx: Context, url: String, audioOnly: Boolean = false, maxH: Int = 0, clipStart: Long = -1, clipEnd: Long = -1): TaskItem {
        val app = ctx.applicationContext
        val title = (if (clipStart >= 0) "✂ yt-dlp: " else if (audioOnly) "🎵 yt-dlp: " else "⬇ yt-dlp: ") + url.removePrefix("https://").removePrefix("http://").take(60)   // (v1295) البادئة بتميّزه في صفحة التحميلات
        return TaskCenter.add(app, "download", title, "") { t ->
            init(app)
            val id = "ytdlp-" + t.id
            val tmp = File(app.cacheDir, id); tmp.deleteRecursively(); tmp.mkdirs()
            t.onCancel = { try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Throwable) {} }
            val req = YoutubeDLRequest(url)
            req.addOption("-o", File(tmp, "%(title).80s.%(ext)s").absolutePath)
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            if (audioOnly) { req.addOption("-x"); req.addOption("--audio-format", "mp3") }
            else { req.addOption("-f", if (maxH > 0) "bv*[height<=$maxH]+ba/b[height<=$maxH]/b" else "bv*+ba/b"); req.addOption("--merge-output-format", "mp4") }
            if (clipStart >= 0) {   // (v1304) اقتصاص مقطع: قصّ بالثواني (keyframes مظبوطة عند القص)
                fun tf(sec: Long): String = String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
                req.addOption("--download-sections", "*" + tf(clipStart) + "-" + (if (clipEnd > clipStart) tf(clipEnd) else "inf"))
                req.addOption("--force-keyframes-at-cuts")
                req.addOption("--merge-output-format", "mp4")
            }
            val onLine: (Float, Long, String?) -> Unit = { p, _, line ->
                t.pct = p.toInt().coerceIn(0, 99); t.msg = (line ?: "").take(80); TaskCenter.changed()
            }
            var err: Throwable? = null
            try {
                YoutubeDL.getInstance().execute(req, id, onLine)
            } catch (e: Throwable) { err = e }
            if (err != null) {
                // (v1308) النسخة المضمّنة قديمة (yt-dlp بيقفل بعد 90 يوم): حدّث ذاتيًا وأعد المحاولة مرة واحدة
                val m = err?.message ?: ""
                if (m.contains("older than", true) || m.contains("ERROR", true)) {
                    t.msg = "⏳ yt-dlp قديم — بنحدّثه ونعيد المحاولة…"; TaskCenter.changed()
                    selfUpdate(app)
                    try { YoutubeDL.getInstance().execute(req, id, onLine); err = null } catch (e: Throwable) { err = e }
                }
            }
            if (err != null) {
                // (v1308) fallback تلقائي للمحمّل العادي (روابط مباشرة) — التحميل ما يتعطلش (القص ✂ مالوش fallback)
                if (clipStart < 0 && fallbackOld(app, url, audioOnly, maxH)) {
                    throw java.io.IOException("⚠ yt-dlp فشل — التحميل مكمّل بالمحمّل العادي في مهمة جديدة تحت ⬇")
                }
                throw err as Throwable
            }
            val f = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.maxByOrNull { it.length() }
                ?: throw java.io.IOException("yt-dlp ما نزّلش ملف")
            val mime = if (f.extension == "mp3") "audio/mpeg" else if (f.extension == "webm") "video/webm" else if (f.extension == "mkv") "video/x-matroska" else "video/mp4"
            t.outSize = f.length(); t.mime = mime
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Subtitler")
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("ما قدرتش أكتب في التنزيلات")
                app.contentResolver.openOutputStream(uri)?.use { os -> f.inputStream().use { it.copyTo(os) } }
                t.outUri = uri; t.msg = "Download/Subtitler/" + f.name
            } else {
                val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
                val dst = File(dir, f.name); f.copyTo(dst, true)
                t.outUri = android.net.Uri.fromFile(dst); t.outPath = dst.absolutePath; t.msg = dst.absolutePath
            }
            tmp.deleteRecursively()
        }
    }
}
