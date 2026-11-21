package com.tttt.subtitler

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * (v1165) تنزيل الباتشات الصوتية مقسّمة ومترقمة: Download/Joyboy_Batches/Batches_<اسم الفيديو>.zip — جواه 001_00-00-00_00-01-00.aac … + ملف فهرس batches.txt (رقم الباتش · من · إلى · الحجم). نفس حدود الباتشات اللي بتتبعت لجيميناي ونفس ضغط الصوت.
 */
object BatchExport {
    @Volatile private var busy = false

    private fun hms(sec: Double): String { val t = sec.toInt().coerceAtLeast(0); return String.format(java.util.Locale.US, "%02d-%02d-%02d", t / 3600, (t / 60) % 60, t % 60) }
    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ").trim().take(60).ifBlank { "video" }

    fun run(act: Activity, engine: Engine, title: String) {
        if (busy) { Notice.show(act, "⏳ التنزيل شغّال لسه…", 1800L); return }
        busy = true
        Notice.show(act, "🎧 بجهّز الباتشات الصوتية…", 2000L)
        Thread {
            var msg: String
            val tmp = File(act.cacheDir, "batches_" + System.currentTimeMillis() + ".zip")
            try {
                val index = StringBuilder("الفيديو: ").append(title).append("\n\n")
                var last = ""
                var n = 0
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp))).use { zo ->
                    n = engine.exportBatchAudio { i, total, st, en, bytes, mime ->
                        val ext = if (mime.contains("wav")) "wav" else "aac"
                        val name = String.format(java.util.Locale.US, "%03d_%s_%s.%s", i + 1, hms(st), hms(en), ext)
                        zo.putNextEntry(java.util.zip.ZipEntry(name)); zo.write(bytes); zo.closeEntry()
                        index.append("باتش رقم ").append(i + 1).append(" — من ").append(hms(st)).append(" إلى ").append(hms(en)).append(" — ").append(bytes.size / 1024).append("KB — ").append(name).append("\n")
                        val m = "🎧 الباتش " + (i + 1) + " من " + total
                        if (m != last) { last = m; act.runOnUiThread { Notice.show(act, m, 900L) } }
                    }
                    if (n > 0) { zo.putNextEntry(java.util.zip.ZipEntry("batches.txt")); zo.write(index.toString().toByteArray(Charsets.UTF_8)); zo.closeEntry() }
                }
                if (n > 0) {
                    val zname = "Batches_" + safe(title) + ".zip"
                    save(act, "Joyboy_Batches", zname, "application/zip", tmp.readBytes())
                    msg = "✅ نزّلت " + n + " باتش في ملف واحد: Download/Joyboy_Batches/" + zname
                } else msg = "⚠ مفيش صوت أطلعه من الفيديو ده"
            } catch (e: Throwable) { LogStore.err("BatchExport", e); msg = "⚠ التنزيل فشل: " + (e.message ?: "") }
            try { tmp.delete() } catch (_: Throwable) {}
            busy = false
            act.runOnUiThread { Notice.show(act, msg, 5000L) }
        }.also { it.isDaemon = true }.start()
    }

    private fun save(act: Activity, folder: String, name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
            }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("ما قدرتش أكتب في التنزيلات")
            act.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val dir = File(act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: act.filesDir, folder); dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
    }
}
