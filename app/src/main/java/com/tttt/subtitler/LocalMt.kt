package com.tttt.subtitler

import java.io.File

/** (v696) جسر JNI لـ llama.cpp (cpp/llm_jni.cpp) — جوه نفس المكتبة subwhisper (ggml واحدة) */
object LlmLib {
    @Volatile var loaded = false; private set
    init { try { System.loadLibrary("subwhisper"); loaded = true } catch (_: Throwable) {} }
    @JvmStatic external fun nInit(path: String, nCtx: Int): Long
    @JvmStatic external fun nFree(h: Long)
    @JvmStatic external fun nTranslate(h: Long, user: String, threads: Int, maxNew: Int): ByteArray
}

/**
 * (v696) 📴 ترجمة أوفلاين كاملة بـ Hy-MT2-1.8B (Tencent) على الجهاز — من غير نت ولا مفاتيح ولا سيرفر. الناتج فصحى.
 * الملف برّه الـAPK: بيتنزّل مرة (من الرابط في المتصفح أو من التطبيق) ويتختار بـ ModelStore أو يتحط في Android/media.
 * البرومبت والإعدادات من كارت Tencent الرسمي: من غير system prompt · temperature 0.7 · top_p 0.6 · top_k 20 · repeat 1.05.
 * ما اتجرّبش على جهاز: السرعة الحقيقية (توكن/ث) والجودة على العربي لازم تتقاس.
 */
object LocalMt {
    class Model(val id: String, val label: String, val file: String, val approxMb: Int, val note: String)
    val models = listOf(
        Model("q4", "⚡ Q4_K_M", "Hy-MT2-1.8B-Q4_K_M.gguf", 1130, "الافتراضي — 1.13GB"),
        Model("q6", "🎯 Q6_K", "Hy-MT2-1.8B-Q6_K.gguf", 1470, "أدق شوية — 1.47GB (اسم الملف اتأكد منه من صفحة الريبو)")
    )
    const val BASE_URL = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/"
    /** رابط المتصفح (للتنزيل اليدوي) */
    fun url(m: Model = model()): String = BASE_URL + m.file

    fun model(): Model = models.firstOrNull { it.id == Cfg.str("mt_model", "q4") } ?: models[0]
    fun file(m: Model = model()): File? = Offline.pick("mt/" + m.file, m.approxMb * 600_000L)
    fun downloaded(m: Model = model()): Boolean = file(m)?.let { it.isFile && it.length() > m.approxMb * 600_000L } ?: false
    fun supported(): Boolean = LlmLib.loaded
    fun ready(): Boolean = supported() && downloaded()

    // لغات المصدر ← الاسم الإنجليزي الكامل (Tencent بتطلب الاسم الكامل)
    private val names = mapOf("ja" to "Japanese", "en" to "English", "ko" to "Korean", "zh" to "Chinese", "fr" to "French", "es" to "Spanish",
        "de" to "German", "it" to "Italian", "ru" to "Russian", "tr" to "Turkish", "hi" to "Hindi", "fa" to "Persian", "ur" to "Urdu", "ar" to "Arabic")

    @Volatile private var h = 0L
    @Volatile private var loadedFile = ""
    private val lock = Any()

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(3, 4)

    /** بيحمّل الموديل لو لسه. بيرمي SttInitFail لو فشل */
    fun prepare(log: (String) -> Unit) = synchronized(lock) {
        val f = file() ?: throw SttInitFail("مفيش مساحة تخزين")
        if (!ready()) throw SttInitFail("موديل الترجمة مش موجود — نزّله أو اختاره من الإعدادات")
        if (h != 0L && loadedFile == f.path) return@synchronized
        releaseLocked()
        val t0 = System.currentTimeMillis()
        Offline.loadBegin(model().id, model().approxMb, "Hy-MT2")   // (v700) مؤشر تحميل موديل الترجمة
        try { h = LlmLib.nInit(f.path, 2048) } finally { Offline.loadEnd(h != 0L) }
        if (h == 0L) throw SttInitFail("موديل الترجمة ما اتحمّلش")
        loadedFile = f.path
        log("📴 موديل الترجمة اتحمّل في ${System.currentTimeMillis() - t0}ms")
    }

    private fun releaseLocked() { if (h != 0L) { LlmLib.nFree(h); h = 0L; loadedFile = "" } }
    /** تحرير الرام (بعد التفريغ/الترجمة أو قبل تحميل Whisper الكبير) */
    fun release() = synchronized(lock) { releaseLocked() }
    fun isLoaded(): Boolean = h != 0L

    /** جملة واحدة ← عربي (فصحى). [src] كود لغة المصدر ("" = تلقائي؛ Hy-MT2 بيعرفها من النص) */
    fun translate(text: String, src: String = ""): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val prompt = "Translate the following text into Arabic. Note that you should only output the translated result without any additional explanation:\n" + t
        val raw = synchronized(lock) {
            if (h == 0L) throw SttInitFail("موديل الترجمة مش متحمّل")
            String(LlmLib.nTranslate(h, prompt, threads(), (t.length * 3 + 64).coerceIn(64, 600)), Charsets.UTF_8)
        }
        if (raw.startsWith("ERR")) throw java.io.IOException(raw.replace('\t', ' '))
        return raw.trim()
    }

    /** قايمة جمل ← قايمة ترجمات بنفس الترتيب. جملة فشلت = تفضل بأصلها (مش بتوقّف الباقي). [onProgress] (خلص، الكلّي) */
    fun translateAll(lines: List<String>, src: String, log: (String) -> Unit, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<String> {
        prepare(log)
        val out = ArrayList<String>(lines.size)
        var bad = 0
        for ((i, l) in lines.withIndex()) {
            out.add(try { translate(l, src).ifBlank { l } } catch (e: Throwable) { bad++; if (bad <= 3) log("⚠ ترجمة جملة فشلت: " + (e.message ?: "").take(80)); l })
            onProgress(i + 1, lines.size)
        }
        return out
    }

    fun status(): String = when {
        !LlmLib.loaded -> "⛔ الترجمة الأوفلاين مش مدعومة على جهازك (محتاجة معالج 64-bit)."
        !downloaded() -> "⬇ موديل الترجمة لسه مش موجود (~${model().approxMb} ميجا، مرة واحدة)."
        else -> "✅ ترجمة أوفلاين: Hy-MT2 ${model().id} على الجهاز."
    }
}
