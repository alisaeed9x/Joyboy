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
    }

    /** خيار تحميل في القايمة: الليبل (جودة + حجم تقريبي لو متاح) · maxH = أقصى ارتفاع (0 = أعلى جودة) · audioOnly = mp3 */
    private class QOpt(val label: String, val maxH: Int, val audioOnly: Boolean)

    /** (v1292) قايمة اختيار قبل التحميل + (v1302) الجودات الحقيقية للمصدر بأحجامها التقريبية من yt-dlp (من غير تحميل) */
    fun ask(act: android.app.Activity, url: String) {
        val th = Themes.current(); val ui = Ui(act, th)
        val box = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.text("⏳ بنجيب الجودات والأحجام…", 14f, th.muted).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)) })
        val d = GAlert(act).setTitle("⬇ تحميل بـ yt-dlp").setView(box).setNegativeButton("إلغاء", null).show()
        fun fill(opts: List<QOpt>) = act.runOnUiThread {
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            box.removeAllViews()
            for (o in opts) box.addView(ui.text(o.label, 14.5f, th.text).apply {
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); foreground = YtSkin.ripple()
                setOnClickListener {
                    d.dismiss(); start(act, url, audioOnly = o.audioOnly, maxH = o.maxH)
                    Notice.show(act, "اتضاف لمركز المهام (yt-dlp)", 2200L)
                }
            })
        }
        Thread {
            val got = try { init(act.applicationContext); qualities(url) } catch (e: Throwable) { LogStore.err("YtDlp:info", e); null }
            if (got != null && got.isNotEmpty()) fill(got)
            else fill(listOf(   // fallback: القايمة الثابتة (زي v1292) لو yt-dlp ماجبش معلومات
                QOpt("⬇ فيديو — أعلى جودة (mp4)", 0, false),
                QOpt("⬇ فيديو — 720p", 720, false),
                QOpt("⬇ فيديو — 480p", 480, false),
                QOpt("🎵 صوت بس (mp3)", 0, true)))
        }.start()
    }

    /** (v1302) الجودات المتاحة فعلًا في المصدر + الحجم التقريبي (فيديو + أحسن صوت) — من معلومات yt-dlp من غير تحميل. null = ماقدرناش نجيب معلومات */
    private fun qualities(url: String): List<QOpt>? {
        val info = try { YoutubeDL.getInstance().getInfo(url) } catch (_: Throwable) { null } ?: return null
        val fmts = try { info.formats } catch (_: Throwable) { null } ?: return null
        fun sizeOf(f: com.yausername.youtubedl_android.mapper.VideoFormat): Long = try {
            val s = f.fileSize; if (s > 0) s else f.fileSizeApproximate   // (v1304 fix: المكتبة بتسمّيهم fileSize/fileSizeApproximate)
        } catch (_: Throwable) { -1L }
        var aSize = -1L   // حجم أحسن مسار صوت (بيتحط على الفيديو عند الدمج)
        for (f in fmts) {
            val vc: String? = try { f.vcodec } catch (_: Throwable) { null }
            val ac: String? = try { f.acodec } catch (_: Throwable) { null }
            if ((vc == null || vc == "none") && ac != null && ac != "none") { val s = sizeOf(f); if (s > aSize) aSize = s }
        }
        val byH = java.util.TreeMap<Int, Long>(java.util.Collections.reverseOrder())
        for (f in fmts) {
            val vc: String? = try { f.vcodec } catch (_: Throwable) { null }
            if (vc == null || vc == "none") continue
            val h = try { f.height } catch (_: Throwable) { 0 }
            if (h <= 0) continue
            val s = sizeOf(f); if (s > (byH[h] ?: -1L)) byH[h] = s
        }
        if (byH.isEmpty()) return null
        fun suffix(vSize: Long): String = if (vSize > 0 && aSize > 0) " · ~" + Sniff.fmtSize(vSize + aSize) else ""   // الحجم التقريبي (فيديو + صوت) — لو مش متاح بنعرض الجودة بس
        val out = ArrayList<QOpt>()
        val bestH = byH.firstKey(); out.add(QOpt("⬇ أعلى جودة — " + bestH + "p" + suffix(byH[bestH] ?: -1L), 0, false))
        var n = 0
        for ((h, s) in byH) { if (h == bestH) continue; if (++n > 5) break; out.add(QOpt("⬇ " + h + "p" + suffix(s), h, false)) }
        out.add(QOpt("🎵 صوت بس (mp3)" + (if (aSize > 0) " · ~" + Sniff.fmtSize(aSize) else ""), 0, true))
        return out
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
            YoutubeDL.getInstance().execute(req, id) { p, _, line ->
                t.pct = p.toInt().coerceIn(0, 99); t.msg = line.take(80); TaskCenter.changed()
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
