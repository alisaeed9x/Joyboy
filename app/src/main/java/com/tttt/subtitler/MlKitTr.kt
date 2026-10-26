package com.tttt.subtitler

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * (v569) ML Kit اتشالت من التطبيق (مكتبة تقيلة). الكائن فاضل كواجهة فاضية عشان باقي الكود يفضل يتبني،
 * ومفيش مزوّد «mlkit» في قايمة المترجمين بعد كده (اتشال من ExtTranslate.builtin).
 */
object MlKitTr {
    private const val GONE = "ترجمة ML Kit اتشالت من التطبيق"
    val approved: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var say: (String) -> Unit = {}
    @Volatile var ask: (String) -> Unit = {}

    fun srcTag(sl0: String, sample: String): String? = null
    fun ready(sl: String): Boolean = false
    fun prepare(sl: String): String? = GONE
    fun ensure(src: String, approve: Boolean = false): String? = GONE
    fun downloadAsync(act: android.app.Activity, lang: String) { Notice.show(act, "ℹ $GONE — الترجمة بتتم بجيميناي أو جوجل", 3000L) }
    fun translate(texts: List<String>, sl: String): List<String?> = throw IOException(GONE)
}
