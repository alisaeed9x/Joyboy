package com.tttt.subtitler

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * (v408) ترجمة ML Kit (جوجل) — بتشتغل أوفلاين على الجهاز من غير مفتاح. الهدف دايمًا عربي فصحى؛ اللهجة بتتحوّل بعدين زي باقي المترجمين.
 * موديل كل لغة مصدر (~30MB) بيتحمّل أول مرة (محتاج نت مرة واحدة) وبعدها بيشتغل بدون نت. لازم يتنادى من خيط خلفي (Tasks.await).
 */
object MlKitTr {
    private val cache = ConcurrentHashMap<String, Translator>()

    /** كود لغة المصدر بتاع ML Kit، أو null لو مش مدعومة */
    fun srcTag(sl0: String, sample: String): String? {
        val sl = when (sl0) { "auto" -> ExtTranslate.guessLang(sample); "zh-CN", "zh-TW" -> "zh"; else -> sl0.substringBefore('-') }
        return TranslateLanguage.fromLanguageTag(sl)
    }

    private fun translator(src: String): Translator = cache.getOrPut(src) {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.ARABIC).build())
    }

    /** موديل لغة المصدر (والعربي) نازل على الجهاز؟ — لازم من خيط خلفي */
    fun ready(sl: String): Boolean {
        val src = srcTag(sl, "") ?: return false
        val mgr = RemoteModelManager.getInstance()
        fun has(tag: String) = Tasks.await(mgr.isModelDownloaded(TranslateRemoteModel.Builder(tag).build()), 20, TimeUnit.SECONDS) == true
        return has(src) && has(TranslateLanguage.ARABIC)
    }

    /** (v408) تنزيل موديل اللغة لو مش نازل. null = جاهز، غير كده سبب الفشل — لازم من خيط خلفي */
    fun prepare(sl: String): String? {
        val src = srcTag(sl, "") ?: return "اللغة دي مش مدعومة في ML Kit"
        return ensure(src, true)
    }

    /** (v411) رسالة للمستخدم (إشعار جوه التطبيق) — الـ Engine بيظبطها. الافتراضي مفيش */
    @Volatile var say: (String) -> Unit = {}

    /**
     * (v411) بيتأكد إن موديل لغة المصدر + العربي نازلين، ولو لأ بينزّلهم واحد واحد مع إشعار تقدم (إشعار نظام + رسالة بالتطبيق: «جاري تحميل لغة: ياباني»).
     * null = جاهز، غير كده سبب الفشل. لازم من خيط خلفي.
     */
    /** (v413) لغات اليوزر وافق على تنزيلها. من غير موافقة الترجمة بتسأل (ask) بدل ما تنزّل لوحدها */
    val approved: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var ask: (String) -> Unit = {}

    /** (v413) تنزيل من زرار (الإعدادات / شاشة ⚙ في المشغّل) */
    fun downloadAsync(act: android.app.Activity, lang: String) {
        if (lang.isBlank()) { Notice.show(act, "اختار لغة الكلام الأول (مش «تلقائي») عشان أعرف أنزّل موديل أنهي لغة", 3000L); return }
        Notice.show(act, "⬇ بنجهّز موديل ترجمة اللغة…", 1800L)
        Thread {
            val e = try { prepare(lang) } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }
            act.runOnUiThread { Notice.show(act, if (e == null) "✅ موديل الترجمة جاهز (بيشتغل من غير نت)" else "⚠ موديل الترجمة: $e", 3500L) }
        }.also { it.isDaemon = true }.start()
    }

    fun ensure(src: String, approve: Boolean = false): String? {
        val mgr = RemoteModelManager.getInstance()
        val need = try { listOf(src, TranslateLanguage.ARABIC).distinct().filter { tag -> Tasks.await(mgr.isModelDownloaded(TranslateRemoteModel.Builder(tag).build()), 20, TimeUnit.SECONDS) != true } }
                   catch (e: Exception) { return (e.message ?: e.javaClass.simpleName).take(100) }
        if (need.isEmpty()) return null
        // (v413) مفيش نت والموديل مش نازل: التحميل كان بيستنى لحد 10 دقايق ويقفل الترجمة كلها — دلوقتي بيفشل فورًا
        if (!approve) { val t = need.firstOrNull { it !in approved }; if (t != null) { ask(src); return "موديل لغة " + OffLang.nameOf(t) + " مش نازل — وافق على تنزيله" } }
        approved.addAll(need)
        if (!TrWizard.online()) { val m = "مفيش نت وموديل اللغة (" + need.joinToString("، ") { OffLang.nameOf(it) } + ") مش نازل"; say("⚠ " + m); return m }
        var lastLabel = ""
        try {
            for ((i, tag) in need.withIndex()) {
                lastLabel = OffLang.nameOf(tag)
                say("⬇ جاري تحميل لغة: $lastLabel (" + (i + 1) + "/" + need.size + ") — ~30 ميجا، مرة واحدة")
                ModelDl.update(lastLabel, i, need.size)
                Tasks.await(mgr.download(TranslateRemoteModel.Builder(tag).build(), DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
            }
            ModelDl.finish(true, lastLabel)
            say("✅ لغة " + OffLang.nameOf(src) + " نزلت — الترجمة بتشتغل من غير نت من دلوقتي")
            return null
        } catch (e: Exception) {
            ModelDl.finish(false, lastLabel)
            val m = (e.message ?: e.javaClass.simpleName).take(100)
            say("⚠ تحميل لغة $lastLabel فشل: $m — اتصل بالنت مرة واحدة وجرّب تاني")
            return m
        }
    }

    fun translate(texts: List<String>, sl: String): List<String?> {
        val sample = texts.firstOrNull { it.isNotBlank() } ?: return texts.map { null }
        val src = srcTag(sl, sample) ?: throw IOException("اللغة دي مش مدعومة في ML Kit")
        if (src == TranslateLanguage.ARABIC) return texts.map { null }
        val t = translator(src)
        ensure(src)?.let { throw IOException("موديل اللغة مش نازل: $it") }
        return texts.map { s ->
            if (s.isBlank()) null
            else try { Tasks.await(t.translate(s), 60, TimeUnit.SECONDS).trim().takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
        }
    }
}
