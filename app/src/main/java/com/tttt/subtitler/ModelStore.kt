package com.tttt.subtitler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * (v696) موديلات كبيرة (Whisper turbo ~574MB · Hy-MT2 ~1.1GB) بتفضل برّه التطبيق.
 * طريقتين من غير نت: (1) الملف يتحط في Android/media/<الحزمة>/models/... (Offline.pick بيلاقيه لوحده)،
 * (2) الملف اتحمّل من المتصفح في Download، والمستخدم بيختاره مرة بزرار «اختار الملف» (ACTION_OPEN_DOCUMENT):
 * بنحفظ الصلاحية الدائمة وبنفتحه كـ /proc/self/fd/N — whisper/llama بيقروه mmap من غير ما نكوّش نسخة تانية.
 * [rel] نفس مفاتيح Offline.pick: "whisper/<ملف>" و "mt/<ملف>".
 * لو الصلاحية راحت (إعادة تثبيت) الملف بيتطلب يتختار تاني — ولو كان في Android/media مابيحتاجش حاجة.
 */
object ModelStore {
    private val open = HashMap<String, ParcelFileDescriptor>()
    private const val KEY = "mdl_uri_"
    /** الأسماء اللي بنعرف نربط بيها الملف المختار (rel ← أقل حجم مقبول بالبايت) */
    fun known(): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (w in Offline.models) m["whisper/" + w.file] = w.approxMb * 600_000L
        for (q in LocalMt.models) m["mt/" + q.file] = q.approxMb * 600_000L
        return m
    }

    @Synchronized fun resolve(rel: String): File? = open[rel]?.let { File("/proc/self/fd/" + it.fd) }

    /** بعد فتح التطبيق: رجّع كل الملفات المختارة قبل كده (لو الصلاحية لسه سارية) */
    @Synchronized fun restore(ctx: Context) {
        for ((rel, min) in known()) {
            if (open.containsKey(rel)) continue
            val s = Cfg.str(KEY + rel, "")
            if (s.isEmpty()) continue
            try { openUri(ctx, rel, Uri.parse(s), min) } catch (_: Throwable) { Cfg.put(KEY + rel, "") }
        }
    }

    /** المستخدم اختار [uri] لموديل [rel]. بيرجّع null لو تمام، وإلا سبب الفشل */
    @Synchronized fun attach(ctx: Context, rel: String, uri: Uri): String? {
        val min = known()[rel] ?: return "موديل غير معروف"
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        return try { openUri(ctx, rel, uri, min); Cfg.put(KEY + rel, uri.toString()); null }
        catch (e: Throwable) { (e.message ?: e.javaClass.simpleName).take(100) }
    }

    private fun openUri(ctx: Context, rel: String, uri: Uri, min: Long) {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("مقدرتش أفتح الملف")
        if (pfd.statSize in 0 until min) { pfd.close(); throw java.io.IOException("الملف أصغر من المتوقع — اتأكد إنه التنزيل الكامل") }
        open.remove(rel)?.let { try { it.close() } catch (_: Throwable) {} }
        open[rel] = pfd
    }

    @Synchronized fun detach(rel: String) {
        open.remove(rel)?.let { try { it.close() } catch (_: Throwable) {} }
        Cfg.put(KEY + rel, "")
    }
    fun attached(rel: String): Boolean = open.containsKey(rel)
}
