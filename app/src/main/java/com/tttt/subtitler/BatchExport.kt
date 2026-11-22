package com.tttt.subtitler

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * (v1165) تنزيل الباتشات الصوتية مقسّمة ومترقمة: Download/Joyboy_Batches/Batches_<اسم الفيديو>.zip — جواه 001_00-00-00_00-01-00.aac … + batches.txt (فهرس) + translation_by_batch.txt (جمل كل باتش: الأصل ثم الترجمة).
 * (v1195) بيكتب الـ ZIP مباشرة في التنزيلات (من غير ما يحمّله كله في الذاكرة)، والباتش اللي ما طلعش صوته بيتسجّل سببه في batches.txt واللوج بدل ما يتخطّى بصمت.
 * (v1195) LogZip: زرار في نافذة اللوجات بينزّل ZIP فيه اللوج كامل + الأصل لوحده + الترجمة الناتجة لوحدها.
 */
object BatchExport {
    @Volatile private var busy = false

    internal class OutTarget(val stream: java.io.OutputStream, val uri: Uri?, val file: File?) {
        fun discard(act: Activity) {
            try { stream.close() } catch (_: Throwable) {}
            try { if (uri != null) act.contentResolver.delete(uri, null, null) else file?.delete() } catch (_: Throwable) {}
        }
    }

    internal fun hms(sec: Double): String { val t = sec.toInt().coerceAtLeast(0); return String.format(Locale.US, "%02d-%02d-%02d", t / 3600, (t / 60) % 60, t % 60) }
    internal fun clk(sec: Double): String { val t = sec.toInt().coerceAtLeast(0); return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60) }
    internal fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ").trim().take(60).ifBlank { "video" }
    internal fun oneLine(s: String) = s.replace("\r", "").replace("\n", " / ").trim()

    /** بيفتح ملف في Download/<folder> ويرجّع الـ stream بتاعه (بيتقفل بعد الكتابة) */
    internal fun openOut(act: Activity, folder: String, name: String, mime: String): OutTarget {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
            }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("ما قدرتش أكتب في التنزيلات")
            val os = act.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("ما قدرتش أفتح ملف التنزيل")
            return OutTarget(java.io.BufferedOutputStream(os), uri, null)
        }
        val dir = File(act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: act.filesDir, folder); dir.mkdirs()
        val f = File(dir, name)
        return OutTarget(java.io.BufferedOutputStream(java.io.FileOutputStream(f)), null, f)
    }

    /** جمل كل باتش (حسب توقيت بدايتها) بحالة الباتش: الأصل ثم الترجمة الناتجة */
    internal fun byBatchText(title: String, info: List<Engine.BatchInfo>, subs: List<Sub>): String {
        val sb = StringBuilder("الفيديو: ").append(title).append("\nجمل كل باتش — الأصل (اللي اتفرّغ) ثم الترجمة الناتجة\n\n")
        val sorted = subs.sortedBy { it.start }
        val stray = ArrayList<Sub>()
        var p = 0
        fun row(q: Sub, n: Int) {
            sb.append("  ").append(n).append(") [").append(clk(q.start)).append("]").append(if (q.isSound) " 🔊" else "").append('\n')
            sb.append("     أصل: ").append(oneLine(q.original)).append('\n')
            sb.append("     ترجمة: ").append(if (q.translated.isBlank()) "(لسه ما اترجمتش)" else oneLine(q.translated)).append('\n')
        }
        for (b in info) {
            val rows = ArrayList<Sub>()
            while (p < sorted.size && sorted[p].start < b.start - 0.001) { stray.add(sorted[p]); p++ }
            while (p < sorted.size && sorted[p].start < b.end) { rows.add(sorted[p]); p++ }
            sb.append("═══ باتش ").append(b.idx + 1).append(" — ").append(clk(b.start)).append(" ← ").append(clk(b.end)).append(" — ").append(b.mark).append(" — ").append(rows.size).append(" جملة ═══\n")
            if (rows.isEmpty()) sb.append("  (مفيش جمل)\n")
            rows.forEachIndexed { k, q -> row(q, k + 1) }
            sb.append('\n')
        }
        while (p < sorted.size) { stray.add(sorted[p]); p++ }
        if (stray.isNotEmpty()) {
            sb.append("═══ جمل برا حدود الباتشات الحالية — ").append(stray.size).append(" ═══\n")
            stray.forEachIndexed { k, q -> row(q, k + 1) }
        }
        return sb.toString()
    }

    fun run(act: Activity, engine: Engine, title: String) {
        if (busy) { Notice.show(act, "⏳ التنزيل شغّال لسه…", 1800L); return }
        busy = true
        LogStore.add("🎧 تنزيل الباتشات: بدأ — $title")
        Notice.show(act, "🎧 بجهّز الباتشات الصوتية…", 2000L)
        Thread {
            var msg: String
            var target: OutTarget? = null
            try {
                val info = try { engine.batches() } catch (_: Throwable) { emptyList() }
                val subsNow = try { ArrayList(engine.subs) } catch (_: Throwable) { ArrayList<Sub>() }
                val zname = "Batches_" + safe(title) + ".zip"
                val t = openOut(act, "Joyboy_Batches", zname, "application/zip"); target = t
                val index = StringBuilder("الفيديو: ").append(title).append("\nكل ملف صوت بيبدأ قبل الباتش بـ ").append(Engine.OVERLAP.toInt()).append(" ثواني (نفس اللي بيتبعت لجيميناي)، والاسم بيدل على حدود الباتش نفسه.\n\n")
                val fails = ArrayList<String>()
                var last = ""
                var n = 0
                java.util.zip.ZipOutputStream(t.stream).use { zo ->
                    n = engine.exportBatchAudio({ i, why -> fails.add("باتش " + (i + 1) + " — " + why) }) { i, total, st, en, bytes, mime ->
                        val ext = if (mime.contains("wav")) "wav" else "aac"
                        val name = String.format(Locale.US, "%03d_%s_%s.%s", i + 1, hms(st), hms(en), ext)
                        zo.putNextEntry(java.util.zip.ZipEntry(name)); zo.write(bytes); zo.closeEntry()
                        val cnt = subsNow.count { it.start >= st - 0.001 && it.start < en }
                        index.append("باتش رقم ").append(i + 1).append(" — من ").append(hms(st)).append(" إلى ").append(hms(en)).append(" — ").append(bytes.size / 1024).append("KB — ").append(cnt).append(" جملة — ").append(name).append("\n")
                        val m = "🎧 الباتش " + (i + 1) + " من " + total
                        if (m != last) { last = m; act.runOnUiThread { Notice.show(act, m, 900L) } }
                    }
                    if (n > 0) {
                        if (fails.isNotEmpty()) { index.append("\n⚠ باتشات ما طلعش صوتها (").append(fails.size).append("):\n"); fails.forEach { index.append("- ").append(it).append("\n") } }
                        zo.putNextEntry(java.util.zip.ZipEntry("batches.txt")); zo.write(index.toString().toByteArray(Charsets.UTF_8)); zo.closeEntry()
                        zo.putNextEntry(java.util.zip.ZipEntry("translation_by_batch.txt")); zo.write(byBatchText(title, info, subsNow).toByteArray(Charsets.UTF_8)); zo.closeEntry()
                    }
                }
                if (n > 0) {
                    val where = if (t.uri != null) "Download/Joyboy_Batches/" + zname else (t.file?.absolutePath ?: zname)
                    msg = "✅ نزّلت " + n + " باتش في ملف واحد: " + where + (if (fails.isNotEmpty()) "\n⚠ " + fails.size + " باتش ما طلعش صوته (التفاصيل في batches.txt)" else "")
                    LogStore.add("🎧 تنزيل الباتشات: خلص — $n باتش (فشل ${fails.size}) ← $where")
                } else {
                    t.discard(act); target = null
                    msg = "⚠ مفيش صوت أطلعه من الفيديو ده" + (if (fails.isNotEmpty()) "\n" + fails.first() else "")
                    LogStore.add("🎧 تنزيل الباتشات: مفيش ولا باتش اتصدّر — " + (fails.firstOrNull() ?: "المدة صفر أو مفيش باتشات"))
                }
            } catch (e: Throwable) {
                LogStore.err("BatchExport", e)
                try { target?.discard(act) } catch (_: Throwable) {}
                msg = "⚠ التنزيل فشل: " + (e.message ?: e.javaClass.simpleName)
                LogStore.add("🎧 تنزيل الباتشات: فشل — " + (e.message ?: e.javaClass.simpleName))
            }
            busy = false
            act.runOnUiThread { Notice.show(act, msg, 6000L) }
        }.also { it.isDaemon = true }.start()
    }
}

/**
 * (v1195) ZIP «اللوج + الترجمة»: Download/Joyboy_Logs/Log_<اسم الفيديو>_<تاريخ>.zip
 *  01_log_current_session.txt — اللوج كامل للجلسة الحالية (مع رأس الجهاز)
 *  02_log_previous_session.txt — لوج الجلسة اللي قبلها (لو موجود)
 *  03_log_this_video_run.txt — لوج تشغيلة الفيديو ده بس (لو موجود)
 *  10_original.srt / 11_original.txt — الأصل (النص المتفرّغ) لوحده بتوقيته / سطور بس
 *  20_translation.srt / 21_translation.txt — الترجمة الناتجة لوحدها بتوقيتها / سطور بس
 *  30_side_by_side.txt — كل جملة: توقيت + رقم الباتش + الأصل + الترجمة
 *  31_translation_by_batch.txt — جمل كل باتش بحالته (لو المحرك على الجهاز)
 */
object LogZip {
    @Volatile private var busy = false

    fun run(act: Activity, title: String, subs: List<Sub>, offsetMs: Long, engine: Engine?, videoRunLog: String) {
        if (busy) { Notice.show(act, "⏳ التنزيل شغّال لسه…", 1800L); return }
        busy = true
        Notice.show(act, "📦 بجهّز ZIP اللوج والترجمة…", 1800L)
        Thread {
            var msg: String
            var target: BatchExport.OutTarget? = null
            try {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
                val zname = "Log_" + BatchExport.safe(title) + "_" + stamp + ".zip"
                val info = try { engine?.batches() ?: emptyList() } catch (_: Throwable) { emptyList<Engine.BatchInfo>() }
                val sorted = subs.sortedBy { it.start }
                val orig = sorted.filter { !it.isSound && it.original.isNotBlank() }
                val tr = sorted.filter { it.translated.isNotBlank() }
                fun batchOf(q: Sub): String {
                    val b = info.firstOrNull { q.start >= it.start - 0.001 && q.start < it.end }
                    return if (b != null) (b.idx + 1).toString() else if (q.chunk >= 0) (q.chunk + 1).toString() else "?"
                }
                val t = BatchExport.openOut(act, "Joyboy_Logs", zname, "application/zip"); target = t
                fun put(zo: java.util.zip.ZipOutputStream, name: String, text: String) {
                    zo.putNextEntry(java.util.zip.ZipEntry(name)); zo.write(text.toByteArray(Charsets.UTF_8)); zo.closeEntry()
                }
                java.util.zip.ZipOutputStream(t.stream).use { zo ->
                    put(zo, "01_log_current_session.txt", LogStore.header(act) + "\n\n" + LogStore.currentText(400_000))
                    LogStore.prevText(400_000).takeIf { it.isNotBlank() }?.let { put(zo, "02_log_previous_session.txt", it) }
                    videoRunLog.takeIf { it.isNotBlank() }?.let { put(zo, "03_log_this_video_run.txt", it) }
                    put(zo, "10_original.srt", PlayerLogic.toSrtOriginal(sorted, offsetMs))
                    put(zo, "11_original.txt", orig.joinToString("\n") { BatchExport.oneLine(it.original) } + "\n")
                    put(zo, "20_translation.srt", PlayerLogic.toSrt(tr, offsetMs))
                    put(zo, "21_translation.txt", tr.joinToString("\n") { BatchExport.oneLine(it.translated) } + "\n")
                    val sb = StringBuilder("الفيديو: ").append(title).append("\nعدد الجمل: ").append(sorted.size).append(" — المترجم منها: ").append(tr.size).append("\n\n")
                    sorted.forEachIndexed { k, q ->
                        sb.append('#').append(k + 1).append(" [").append(BatchExport.clk(q.start)).append("] باتش ").append(batchOf(q)).append(if (q.isSound) " 🔊" else "").append('\n')
                        sb.append("أصل: ").append(BatchExport.oneLine(q.original)).append('\n')
                        sb.append("ترجمة: ").append(if (q.translated.isBlank()) "(مفيش)" else BatchExport.oneLine(q.translated)).append("\n\n")
                    }
                    put(zo, "30_side_by_side.txt", sb.toString())
                    if (info.isNotEmpty()) put(zo, "31_translation_by_batch.txt", BatchExport.byBatchText(title, info, sorted))
                }
                val where = if (t.uri != null) "Download/Joyboy_Logs/" + zname else (t.file?.absolutePath ?: zname)
                msg = "✅ نزّلت ZIP اللوج والترجمة (" + sorted.size + " جملة، " + tr.size + " مترجمة): " + where
                LogStore.add("📦 ZIP اللوج والترجمة: ${sorted.size} جملة (${tr.size} مترجمة) ← $where")
            } catch (e: Throwable) {
                LogStore.err("LogZip", e)
                try { target?.discard(act) } catch (_: Throwable) {}
                msg = "⚠ ZIP اللوج فشل: " + (e.message ?: e.javaClass.simpleName)
            }
            busy = false
            act.runOnUiThread { Notice.show(act, msg, 6000L) }
        }.also { it.isDaemon = true }.start()
    }
}
