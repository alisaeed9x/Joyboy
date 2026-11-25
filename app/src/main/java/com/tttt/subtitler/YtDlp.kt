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

    /** (v1292) قايمة اختيار قبل التحميل: جودة الفيديو أو صوت mp3 */
    fun ask(act: android.app.Activity, url: String) {
        val labels = arrayOf("⬇ فيديو — أعلى جودة (mp4)", "⬇ فيديو — 720p", "⬇ فيديو — 480p", "🎵 صوت بس (mp3)")
        GAlert(act).setTitle("⬇ تحميل بـ yt-dlp").setItems(labels) { _, w ->
            when (w) {
                1 -> start(act, url, maxH = 720)
                2 -> start(act, url, maxH = 480)
                3 -> start(act, url, audioOnly = true)
                else -> start(act, url)
            }
            Notice.show(act, "اتضاف لمركز المهام (yt-dlp)", 2200L)
        }.setNegativeButton("إلغاء", null).show()
    }

    /** بيدّي لينك (صفحة فيديو من أي موقع) لـyt-dlp. audioOnly = صوت mp3. maxH = أقصى جودة (0 = أعلى جودة). */
    fun start(ctx: Context, url: String, audioOnly: Boolean = false, maxH: Int = 0): TaskItem {
        val app = ctx.applicationContext
        val title = (if (audioOnly) "🎵 " else "⬇ ") + url.removePrefix("https://").removePrefix("http://").take(60)
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
