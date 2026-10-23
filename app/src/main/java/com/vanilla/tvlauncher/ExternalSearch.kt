package com.vanilla.tvlauncher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import java.util.Locale

/**
 * نقطة التكامل الوحيدة مع «الخارج» للبحث (INTEGRATION_CONTRACTS): الواجهة بتنادي هنا بس،
 * ومابتعرفش أي تفاصيل داخلية لتطبيق يوتيوب. المرحلة 7 هي اللي هتحدّد المسار النهائي وتبدّل التنفيذ هنا لو لزم.
 * ⚠ كله لسه ما اتجرّبش على جهاز: بنجرّب تطبيق يوتيوب للتلفزيون الأول ثم أي تطبيق بيفتح الرابط، ولو مفيش بيرجّع false.
 */
object ExternalSearch {
    const val YT_TV_PKG = "com.google.android.youtube.tv"

    /** true = اتفتح تطبيق. بيتنادى بس من اختيار صريح (OK على زرار «البحث على يوتيوب»). */
    fun openYoutubeSearch(act: Activity, q: String): Boolean {
        val uri = Uri.parse(SearchLogic.youtubeSearchUrl(q))
        for (pkg in listOf<String?>(YT_TV_PKG, null)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, uri)
                if (pkg != null) i.setPackage(pkg)
                act.startActivity(i); return true
            } catch (_: Throwable) { }
        }
        return false
    }

    /** البحث الصوتي عبر خدمة التعرف بتاعة الجهاز (مش بنطلب RECORD_AUDIO — الخدمة هي اللي بتسجّل). false = مفيش خدمة. */
    @Suppress("DEPRECATION")
    fun startVoice(act: Activity, requestCode: Int): Boolean = try {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "قول اسم الفيديو")
        act.startActivityForResult(i, requestCode); true
    } catch (_: Throwable) { false }

    fun voiceText(data: Intent?): String? = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
