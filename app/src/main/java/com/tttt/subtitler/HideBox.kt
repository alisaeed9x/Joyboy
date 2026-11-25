package com.tttt.subtitler

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import java.io.File

/** (v1314) الإخفاء الفعلي: بنقل الملف جوه فولدر «.JoyboyHidden» في جذر التخزين الداخلي.
 *  - مخفي عن باقي التطبيقات: اسم الفولدر بيبدأ بنقطة + جواه .nomedia ← فهرسة الميديا بتتخطاه، فمابيظهرش في بحث/جاليري أي تطبيق تاني.
 *  - بيفضل مكانه لو التطبيق اتمسح (مش فولدر خاص بالتطبيق) — والملفات بتفضل قابلة للوصول بأي مدير ملفات.
 *  خريطة «اسم الملف المخفي ← مساره الأصلي» محفوظة في hidebox_map.txt عشان الإظهار يرجّعه مكانه بالظبط. */
object HideBox {
    private const val DIR_NAME = ".JoyboyHidden"

    fun dir(): File {
        val d = File(Environment.getExternalStorageDirectory(), DIR_NAME)
        if (!d.exists()) d.mkdirs()
        val nm = File(d, ".nomedia"); if (!nm.exists()) try { nm.createNewFile() } catch (_: Exception) {}
        return d
    }

    fun isHiddenPath(path: String): Boolean = try { File(path).parentFile?.path == dir().path } catch (_: Exception) { false }

    private fun mapFile(ctx: Context) = File(ctx.filesDir, "hidebox_map.txt")

    fun origFor(ctx: Context, hiddenName: String): String? = try {
        mapFile(ctx).readLines().firstOrNull { it.substringBefore('\t') == hiddenName }?.substringAfter('\t', "")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun rememberOrig(ctx: Context, hiddenName: String, origPath: String) {
        try {
            val lines = try { mapFile(ctx).readLines().filter { it.substringBefore('\t') != hiddenName } } catch (_: Exception) { emptyList() }
            mapFile(ctx).writeText((lines + "$hiddenName\t$origPath").joinToString("\n"))
        } catch (_: Exception) {}
    }

    private fun forget(ctx: Context, hiddenName: String) {
        try { mapFile(ctx).writeText(mapFile(ctx).readLines().filter { it.substringBefore('\t') != hiddenName }.joinToString("\n")) } catch (_: Exception) {}
    }

    /** اسم فريد جوه فولدر الإخفاء (لو فيه ملف بنفس الاسم بنزوّد رقم) */
    private fun uniqueName(d: File, name: String): String {
        if (!File(d, name).exists()) return name
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) { val n = if (ext.isEmpty()) base + "_" + i else base + "_" + i + "." + ext; if (!File(d, n).exists()) return n; i++ }
    }

    fun scan(ctx: Context, vararg paths: String) { try { MediaScannerConnection.scanFile(ctx, arrayOf(*paths), null, null) } catch (_: Exception) {} }

    /** نقل فعلي لفولدر الإخفاء. بيرجّع الملف في مكانه الجديد، أو null لو ماقدرش (يبقى الإخفاء المنطقي هو البديل) */
    fun hide(ctx: Context, src: File): File? {
        if (!src.exists()) return null
        val d = dir()
        val dst = File(d, uniqueName(d, src.name))
        var ok = try { src.renameTo(dst) } catch (_: Exception) { false }
        if (!ok) {
            ok = try { src.copyTo(dst, true) && src.delete() } catch (_: Exception) { false }
            if (!ok && src.exists()) try { dst.delete() } catch (_: Exception) {}   // نسخة ناقصة تتشال، الأصل يفضل مكانه
        }
        if (!ok || !dst.exists()) return null
        rememberOrig(ctx, dst.name, src.path)
        scan(ctx, src.path)   // يتشال من فهرس الميديا بتاع باقي التطبيقات
        return dst
    }

    /** رجوع للمسار الأصلي (أو جذر التخزين لو المسار القديم مش متاح). false لو ماقدرش */
    fun unhide(ctx: Context, hidden: File): Boolean {
        if (!hidden.exists()) return false
        val orig = origFor(ctx, hidden.name)
        val target = when {
            orig != null && File(orig).parentFile != null && (File(orig).parentFile!!.exists() || File(orig).parentFile!!.mkdirs()) -> File(orig)
            else -> File(Environment.getExternalStorageDirectory(), hidden.name)
        }
        if (target.exists()) return false
        var ok = try { hidden.renameTo(target) } catch (_: Exception) { false }
        if (!ok) ok = try { hidden.copyTo(target, true) && hidden.delete() } catch (_: Exception) { false }
        if (!ok) return false
        forget(ctx, hidden.name)
        scan(ctx, target.path)   // يرجع يتفهرس تاني في نفس اللحظة
        return true
    }

    /** عناصر فولدر الإخفاء كعناصر مكتبة (للعرض في شاشة المخفي) — امتدادات فيديو/صوت بس */
    fun items(ctx: Context): List<VideoItem> {
        val d = dir()
        val exts = VideoLib.EXTS + setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")
        val out = ArrayList<VideoItem>()
        d.listFiles()?.forEach { f ->
            if (!f.isFile || f.name.startsWith(".")) return@forEach
            if (f.extension.lowercase() !in exts) return@forEach
            out.add(VideoItem(0L, Uri.fromFile(f).toString(), f.name, d.path, DIR_NAME, d.path, f.length(), 0L, f.lastModified() / 1000L, f.lastModified() / 1000L))
        }
        return out.sortedBy { it.name.lowercase() }
    }
}
