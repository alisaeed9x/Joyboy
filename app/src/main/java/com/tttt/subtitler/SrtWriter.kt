package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** بيكتب ملف SRT جنب الفيديو (نفس الاسم) أول ما الترجمة تخلص — MX Player وأي مشغّل بيلقطه لوحده.
 *  محتاج صلاحية «إدارة كل الملفات» (أو إن الفولدر قابل للكتابة). مابيمسحش SRT مش بتاعنا: لو فيه ملف بنفس الاسم بيكتب `اسم.ar.srt`. */
object SrtWriter {
    private val lock = Any()
    private fun registry(ctx: Context) = File(ctx.filesDir, "srt_written.txt")

    fun pathOf(ctx: Context, uri: String): File? = try {
        ctx.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.startsWith("/") }?.let { File(it) } else null
        }
    } catch (_: Exception) { null }

    /** (v1137) ملف النص الأصلي المخفي المقابل لملف ترجمة كتبه البرنامج: «.movie.orig.srt» */
    fun origOf(srt: File): File = File(srt.parentFile, "." + srt.nameWithoutExtension + ".orig.srt")
    /** (v1137) النص الأصلي المحفوظ لملف الترجمة ده (لو البرنامج هو اللي كتبه وفيه نسخة أصلية) وإلا null */
    fun origFor(srt: File): File? = try { origOf(srt).takeIf { it.isFile && it.length() > 0L } } catch (_: Exception) { null }
    /** (v1137) الملف ده كتبه البرنامج (ترجمة محفوظة) */
    fun isOurs(ctx: Context, f: File): Boolean = try { File(ctx.filesDir, "srt_written.txt").readLines().contains(f.path) } catch (_: Exception) { false }

    /** بيرجّع المسار اللي اتكتب فيه أو null */
    fun save(ctx: Context, uri: String?, subs: List<Sub>, offsetMs: Long): String? {
        if (uri.isNullOrEmpty() || subs.isEmpty()) return null
        synchronized(lock) {
            try {
                val video = pathOf(ctx, uri) ?: return null
                val dir = video.parentFile ?: return null
                val base = video.nameWithoutExtension
                val reg = registry(ctx)
                val written = try { reg.readLines().toHashSet() } catch (_: Exception) { hashSetOf<String>() }
                var out = File(dir, "$base.srt")
                if (out.exists() && out.path !in written) out = File(dir, "$base.ar.srt")
                if (out.exists() && out.path !in written) return null
                val tmp = File(dir, out.name + ".tmp")
                tmp.writeText(PlayerLogic.toSrt(subs, offsetMs), Charsets.UTF_8)
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) { tmp.delete(); return null }
                if (out.path !in written) reg.appendText(out.path + "\n")
                // (v1137) النص الأصلي في ملف مخفي جنب الترجمة (.اسم.orig.srt): ملف الترجمة بيعرض المترجم بس، و«ترجمة ملف الترجمة» بتقرا الأصلي بدل ما تترجم فوق العربي
                try {
                    val orig = origOf(out)
                    if (subs.any { !it.isSound && it.original.isNotBlank() && it.original.trim() != it.translated.trim() }) {
                        val ot = File(dir, orig.name + ".tmp")
                        ot.writeText(PlayerLogic.toSrtOriginal(subs, offsetMs), Charsets.UTF_8)
                        if (orig.exists()) orig.delete()
                        if (!ot.renameTo(orig)) ot.delete()
                    }
                } catch (_: Exception) { }
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.path), arrayOf("application/x-subrip"), null)
                return out.path
            } catch (_: Exception) { return null }
        }
    }
}
